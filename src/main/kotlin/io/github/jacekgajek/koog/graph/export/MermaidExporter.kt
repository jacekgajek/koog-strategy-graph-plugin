package io.github.jacekgajek.koog.graph.export

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.PathUtil
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.system.measureTimeMillis

/**
 * Turns a `strategy(...) { }` PSI call into a Mermaid diagram by actually running
 * Koog's `MermaidDiagramGenerator` against it:
 *
 *  1. [prepare] (read action) lifts the snippet into a standalone runner and
 *     gathers the module's classpath + JDK.
 *  2. [run] (off-EDT) compiles that runner with the bundled Kotlin compiler and
 *     executes it, capturing the printed diagram.
 *
 * Logs verbosely under the `#io.github.jacekgajek.koog` category so the pipeline
 * can be traced in idea.log.
 */
object MermaidExporter {

    private val LOG = logger<MermaidExporter>()

    private const val K2_COMPILER = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
    // Fallback JVM target when the module SDK version can't be determined (see jvmTargetFor).
    // 17 is Koog's floor: its inline DSL is compiled to 17, and kotlinc's own default of 1.8
    // refuses to inline that newer bytecode. The real target is usually the module SDK's, so
    // we can also inline dependencies built for a newer JVM.
    private const val JVM_TARGET = "17"
    private const val COMPILE_TIMEOUT_MS = 120_000
    private const val RUN_TIMEOUT_MS = 60_000

    /** Cap on referenced same-module files pulled into a single compile (see [collectReferencedSources]). */
    private const val MAX_EXTRA_SOURCES = 64

    /** Everything needed to compile + run, captured off the PSI under a read action. */
    class Prepared(
        val name: String,
        val source: String,
        val moduleClasspath: List<String>,
        val javaExe: String,
        /** FQN of the generated runner's main class (depends on the file's package). */
        val mainClass: String,
        /** Module output dir(s) passed via -Xfriend-paths so `internal` symbols resolve. */
        val friendPaths: List<String>,
        /** JVM bytecode target for the compile, derived from the module SDK (floored at 17). */
        val jvmTarget: String,
        /**
         * Lazily resolves same-module source files the strategy references from *other* files
         * (e.g. node factories in a separate object). Invoked only as a fallback, when the
         * snippet-only compile fails on unresolved references — i.e. the module isn't built —
         * so a built module never pays the resolution or extra-compile cost. Runs its own
         * read action.
         */
        val extraSources: () -> List<ExtraSource>,
    )

    /** An extra source file pulled into the compile: a unique runner-local name + its text. */
    class ExtraSource(val fileName: String, val content: String)

    /** A single row for the Problems-style table. */
    data class Problem(val message: String, val detail: String? = null)

    /**
     * Result of an export. [mermaid] is non-null only when a diagram was produced;
     * otherwise [problems] explains why (graph validation error, compile error, …)
     * and the caller keeps the previously shown diagram. [cacheable] is false for
     * transient/infrastructure outcomes (timeouts, missing compiler) that shouldn't
     * be memoized. [compileError] flags the specific case where the strategy snippet
     * didn't compile *because of another file* (a stale/unbuilt dependency, not the
     * strategy itself): the caller surfaces a "rebuild & refresh" hint rather than the
     * raw kotlinc diagnostics, whose temp-dir paths look like a plugin bug. [inFileCompileError]
     * flags the other compile case — the errors are in the strategy's own file. The user sees
     * those in their editor, so the caller keeps the current diagram untouched if there is one;
     * with no diagram to keep it shows a brief notice rather than leaving a stuck spinner.
     */
    class ExportOutcome(
        val name: String,
        val mermaid: String?,
        val problems: List<Problem>,
        val cacheable: Boolean = true,
        val compileError: Boolean = false,
        val inFileCompileError: Boolean = false,
    )

    /** Build the runner + collect the module classpath. Call inside a read action. */
    fun prepare(call: KtCallExpression): Prepared? {
        val snippet = StrategySnippet.from(call)
        if (snippet == null) {
            LOG.info("prepare: not a usable strategy call; skipping")
            return null
        }
        val module = ModuleUtilCore.findModuleForPsiElement(call)
        if (module == null) {
            LOG.warn("prepare: no module found for strategy '${snippet.name}'; cannot resolve classpath")
            return null
        }
        // In a Kotlin Multiplatform project the strategy usually lives in a *common* source set
        // (e.g. `…commonMain`), whose IDE module carries only Kotlin metadata klibs and no JVM
        // classpath — kotlinc can't resolve `ai.koog.*` from those, so the snippet fails to compile
        // with everything unresolved. The compilable JVM view (real dependency jars + the
        // `build/classes/kotlin/jvm/main` output, which also holds the compiled common sources)
        // lives on the JVM source-set module. Resolve classpath/outputs/SDK/target against that
        // counterpart when there is one; keep `module` (the PSI's own) for scoping same-module
        // source collection, since the referenced helpers sit in the common source set.
        val cpModule = jvmCounterpart(module) ?: module
        val orderClasspath = OrderEnumerator.orderEntries(cpModule)
            .recursively()
            .withoutSdk()
            .classes()
            .pathsList
            .pathList

        val moduleSdkHome = ModuleRootManager.getInstance(cpModule).sdk?.homePath
        val javaHome = moduleSdkHome ?: System.getProperty("java.home")
        val javaExe = File(File(javaHome, "bin"), if (isWindows()) "java.exe" else "java").absolutePath

        // The module's own compiled output must be on the classpath so same-package
        // siblings (referenced without an import) resolve, and a "friend" so the
        // runner can read its `internal` declarations. IntelliJ's CompilerModuleExtension
        // gives us these for JPS builds — but for Gradle-delegated builds it's often
        // empty (the editor resolves siblings from source, not output). So we also
        // derive the on-disk Gradle/IDEA output dirs and use whatever actually exists.
        val ext = CompilerModuleExtension.getInstance(cpModule)
        val outputs = (listOfNotNull(ext?.compilerOutputPath?.path, ext?.compilerOutputPathForTests?.path) +
            deriveModuleOutputs(cpModule)).distinct()

        val classpath = (orderClasspath + outputs).distinct()
        val friendPaths = outputs

        // The snippet copies only the strategy's own file verbatim; helpers it references
        // from *other* same-module files (e.g. a `node("…")` factory object) only resolve
        // from the module's compiled output — often absent for Gradle-delegated builds. When
        // that happens the snippet-only compile fails on unresolved references and we fall
        // back to pulling those files in as source (resolved lazily, only then).
        val strategyFile = call.containingFile as? KtFile
        val extraSources: () -> List<ExtraSource> = provider@{
            val f = strategyFile ?: return@provider emptyList()
            runReadAction { if (f.isValid) collectReferencedSources(f, module) else emptyList() }
        }
        val jvmTarget = jvmTargetFor(cpModule)

        LOG.info(
            "prepare: strategy='${snippet.name}', module='${module.name}'" +
                (if (cpModule !== module) " (classpath via '${cpModule.name}')" else "") + ", " +
                "classpath=${classpath.size} entries (orderEntries=${orderClasspath.size}, outputs=${outputs.size}), " +
                "sdkHome=${moduleSdkHome ?: "(none, using IDE JRE)"}, " +
                "javaExe=$javaExe, mainClass=${snippet.mainClass}, friendPaths=$friendPaths, " +
                "jvmTarget=$jvmTarget, snippet=${snippet.source.length} chars",
        )
        if (outputs.isEmpty()) {
            LOG.warn("prepare: no module output dir found — same-package siblings may not resolve; build the module")
        }
        return Prepared(snippet.name, snippet.source, classpath, javaExe, snippet.mainClass, friendPaths, jvmTarget, extraSources)
    }

    /**
     * The JVM bytecode target to compile the snippet with. Derived from the module's SDK so
     * we can inline bytecode from dependencies built for that JDK (a hardcoded 17 fails when
     * a dependency targets a newer JVM). Floored at 17, which Koog's inline DSL requires.
     */
    private fun jvmTargetFor(module: Module): String {
        val sdk = ModuleRootManager.getInstance(module).sdk ?: return JVM_TARGET
        val feature = JavaSdk.getInstance().getVersion(sdk)?.maxLanguageLevel?.feature()
            ?: sdk.versionString?.let(::parseJavaFeature)
            ?: return JVM_TARGET
        return maxOf(feature, 17).toString()
    }

    /** Best-effort Java feature version from an SDK version string ("1.8" → 8, "25.0.1" → 25). */
    private fun parseJavaFeature(versionString: String): Int? {
        val m = Regex("""(?<!\d)(1\.)?(\d{1,2})(?!\d)""").find(versionString) ?: return null
        return m.groupValues[2].toIntOrNull()
    }

    /**
     * Transitively collect the same-module Kotlin source files that [start] (the strategy's
     * file) references — node factories, helper objects, types, etc. defined in other files.
     * Resolution is syntactic-best-effort via PSI references; anything that resolves into a
     * library (no module) or back into [start] is ignored. Bounded by [MAX_EXTRA_SOURCES] so
     * a densely-connected module can't drag the whole source tree into one compile.
     * Must be called under a read action.
     */
    private fun collectReferencedSources(start: KtFile, module: Module): List<ExtraSource> {
        val fileIndex = ProjectFileIndex.getInstance(module.project)
        val visited = hashSetOf(start)
        val collected = ArrayList<KtFile>()
        val queue = ArrayDeque<KtFile>().apply { add(start) }
        while (queue.isNotEmpty() && collected.size < MAX_EXTRA_SOURCES) {
            val file = queue.removeFirst()
            for (ref in PsiTreeUtil.collectElementsOfType(file, KtNameReferenceExpression::class.java)) {
                val target = ref.reference?.resolve()?.containingFile as? KtFile ?: continue
                if (!visited.add(target)) continue
                val vf = target.virtualFile ?: continue
                // Editable project source only: never a library or a decompiled `.class`
                // stub (whose `/* compiled code */` body would crash the compiler), and
                // scoped to this module so we don't drag in unrelated modules.
                if (vf.extension != "kt" || !fileIndex.isInSourceContent(vf)) continue
                if (ModuleUtilCore.findModuleForPsiElement(target) !== module) continue
                collected.add(target)
                queue.add(target)
                if (collected.size >= MAX_EXTRA_SOURCES) break
            }
        }
        return collected.mapIndexed { i, f ->
            val base = f.virtualFile?.name?.takeIf { it.endsWith(".kt") } ?: "Ref$i.kt"
            // Prefix with the index so two files of the same simple name never collide on disk.
            ExtraSource("extra${i}_$base", f.text)
        }
    }

    /**
     * For a Kotlin Multiplatform *common* source-set module (e.g. `…commonMain`), the matching
     * JVM source-set module (`…jvmMain`). Gradle's KMP import names source-set modules
     * `<base>.<sourceSet>`, and a common source set's module carries only Kotlin metadata klibs —
     * no JVM classpath — whereas its JVM counterpart has the real dependency jars and compiled
     * output. Returns null when [module] is already a concrete (non-common) source set or no JVM
     * counterpart module exists, in which case the caller falls back to [module] itself.
     */
    private fun jvmCounterpart(module: Module): Module? {
        val base = module.name.substringBeforeLast('.', "")
        if (base.isEmpty()) return null
        val candidates = when (val sourceSet = module.name.substringAfterLast('.')) {
            "commonMain" -> listOf("jvmMain", "main")
            "commonTest" -> listOf("jvmTest", "test")
            // Other intermediate common source sets (e.g. `nonJvmCommonMain` won't apply, but
            // `commonMainFoo`-style names map by swapping the `common` prefix for `jvm`).
            else -> if (sourceSet.startsWith("common")) listOf("jvm" + sourceSet.removePrefix("common")) else return null
        }
        val mm = ModuleManager.getInstance(module.project)
        return candidates.firstNotNullOfOrNull { mm.findModuleByName("$base.$it") }
    }

    /**
     * Best-effort discovery of a module's on-disk compiled output directories for
     * Gradle/IDEA layouts, used when IntelliJ's compiler-output model is empty
     * (typical for Gradle-delegated builds). Returns only directories that exist.
     */
    private fun deriveModuleOutputs(module: Module): List<String> {
        val rm = ModuleRootManager.getInstance(module)
        val roots = (rm.sourceRoots.toList() + rm.contentRoots.toList()).map { File(it.path) }
        val subPaths = listOf(
            "build/classes/kotlin/main", "build/classes/java/main",
            "build/classes/kotlin/test", "build/classes/java/test",
            // Kotlin Multiplatform JVM target output: the compiled common + jvm sources
            // land here, not under the plain `kotlin/main` path used for JVM-only modules.
            "build/classes/kotlin/jvm/main", "build/classes/kotlin/jvm/test",
            "build/resources/main",
            "out/production/classes", "out/test/classes",
        )
        val out = LinkedHashSet<String>()
        for (root in roots) {
            // Ascend to the module base: the nearest ancestor holding a build/out tree.
            var dir: File? = root
            var hops = 0
            while (dir != null && hops++ < 6) {
                if (File(dir, "build/classes").isDirectory || File(dir, "out/production").isDirectory) {
                    subPaths.map { File(dir, it) }.filter { it.isDirectory }.forEach { out += it.absolutePath }
                    break
                }
                dir = dir.parentFile
            }
        }
        return out.toList()
    }

    /** Compile and run the prepared snippet. Safe to call off the EDT. Never throws. */
    fun run(prepared: Prepared): ExportOutcome = try {
        doRun(prepared)
    } catch (t: Throwable) {
        LOG.warn("run: unexpected failure for strategy '${prepared.name}'", t)
        ExportOutcome(prepared.name, null, listOf(Problem("Diagram generation failed", t.toString())), cacheable = false)
    }

    private fun doRun(prepared: Prepared): ExportOutcome {
        val compilerJars = kotlinCompilerJars()
        LOG.info("run: located ${compilerJars.size} Kotlin compiler jars")
        if (compilerJars.isEmpty()) {
            return ExportOutcome(
                prepared.name, null,
                listOf(Problem("Kotlin compiler not found", "Could not locate the IDE's bundled Kotlin compiler.")),
                cacheable = false,
            )
        }

        val workDir = FileUtil.createTempDirectory("koog-mermaid", null, true)
        val srcFile = File(workDir, StrategySnippet.FILE_NAME).apply {
            writeText(prepared.source, StandardCharsets.UTF_8)
        }
        val outDir = File(workDir, "out").apply { mkdirs() }
        LOG.info("run: workDir=${workDir.absolutePath}, wrote ${srcFile.name} (${prepared.source.length} chars)")

        // The snippet links against the user module (Koog + their node factories);
        // stdlib comes from the bundled compiler jars (-no-stdlib uses what's on -classpath);
        // mockk (bundled with the plugin) lets the runner stub function parameters.
        val mockk = bundledMockkJars()
        LOG.info("run: ${mockk.size} bundled mockk jars")
        val compileClasspath = prepared.moduleClasspath + compilerJars + mockk

        // Pass 1: the snippet alone. For a built module everything it references is already
        // on the classpath, so this resolves without dragging in (and recompiling) other
        // files. Pass 2 (fallback): only when that fails on unresolved references — i.e. the
        // module isn't built — pull the referenced same-module sources in and try again.
        var cr: CompilerDaemon.CompileResult? = null
        val compileMs = measureTimeMillis {
            cr = compile(prepared, compilerJars, listOf(srcFile), outDir, compileClasspath, workDir)
            if (cr.let { it != null && it.exitCode != 0 && looksLikeMissingSymbols(it.diagnostics) }) {
                val extraFiles = prepared.extraSources().map { es ->
                    File(workDir, es.fileName).apply { writeText(es.content, StandardCharsets.UTF_8) }
                }
                if (extraFiles.isNotEmpty()) {
                    LOG.info("run: snippet-only compile had unresolved refs — retrying with ${extraFiles.size} referenced file(s)")
                    cr = compile(prepared, compilerJars, listOf(srcFile) + extraFiles, outDir, compileClasspath, workDir)
                }
            }
        }
        val compile = cr
            ?: return ExportOutcome(prepared.name, null, listOf(Problem("Compilation timed out", "kotlinc exceeded ${COMPILE_TIMEOUT_MS}ms.")), cacheable = false)
                .also { LOG.warn("run: compile timed out after ${compileMs}ms") }
        LOG.info("run: compile finished in ${compileMs}ms, exit=${compile.exitCode}, diagnostics=${compile.diagnostics.length} chars")
        if (compile.exitCode != 0) {
            LOG.warn("run: compilation FAILED:\n${compile.diagnostics.trim()}")
            // Don't cache: compile errors can depend on external state (an unbuilt
            // module, a stale classpath) that a rebuild fixes without the strategy
            // text changing — caching would pin the stale error.
            return if (compileErrorIsExternal(compile.diagnostics)) {
                // The failure is in another file (a stale/unbuilt dependency the user
                // can't see is the culprit). Surface a "rebuild & refresh" hint instead of
                // the raw diagnostics (whose temp-dir paths read like a plugin bug).
                ExportOutcome(prepared.name, null, parseCompileDiagnostics(compile.diagnostics), cacheable = false, compileError = true)
            } else {
                // The only errors are in the strategy's own file — the user already sees them
                // in their editor as they type. The UI keeps the current diagram if there is
                // one (no nagging), or shows a brief notice if there's nothing to keep.
                LOG.info("run: compile errors are confined to the strategy's own file")
                ExportOutcome(prepared.name, null, emptyList(), cacheable = false, inFileCompileError = true)
            }
        }

        val runCp = (listOf(outDir.absolutePath) + prepared.moduleClasspath + compilerJars + mockk)
            .joinToString(File.pathSeparator)
        val runCmd = GeneralCommandLine(prepared.javaExe).apply {
            addParameters("-cp", runCp)
            addParameter(prepared.mainClass)
            workDirectory = workDir
            charset = StandardCharsets.UTF_8
        }

        LOG.info("run: executing ${prepared.mainClass} (timeout ${RUN_TIMEOUT_MS}ms)…")
        var runOutput: com.intellij.execution.process.ProcessOutput? = null
        val runMs = measureTimeMillis { runOutput = exec(runCmd, RUN_TIMEOUT_MS) }
        val ro = runOutput
            ?: return ExportOutcome(prepared.name, null, listOf(Problem("Strategy run timed out", "Execution exceeded ${RUN_TIMEOUT_MS}ms.")), cacheable = false)
                .also { LOG.warn("run: execution timed out after ${runMs}ms") }
        LOG.info("run: execution finished in ${runMs}ms, exit=${ro.exitCode}, stdout=${ro.stdout.length} chars, stderr=${ro.stderr.length} chars")

        between(ro.stdout, StrategySnippet.BEGIN_MARKER, StrategySnippet.END_MARKER)?.takeIf { it.isNotBlank() }?.let {
            LOG.info("run: diagram extracted (${it.trim().length} chars) for '${prepared.name}'")
            return ExportOutcome(prepared.name, it.trim(), emptyList())
        }
        // generate() threw: a graph validation error. Keep the previous diagram; report it.
        between(ro.stdout, StrategySnippet.ERROR_BEGIN_MARKER, StrategySnippet.ERROR_END_MARKER)?.takeIf { it.isNotBlank() }?.let {
            val msg = it.trim()
            LOG.info("run: graph error for '${prepared.name}': $msg")
            return ExportOutcome(prepared.name, null, listOf(Problem(msg)))
        }
        val detail = buildString {
            appendLine("exit=${ro.exitCode}")
            if (ro.stderr.isNotBlank()) appendLine("stderr:\n${ro.stderr.trim()}")
            if (ro.stdout.isNotBlank()) appendLine("stdout:\n${ro.stdout.trim()}")
        }.trim()
        LOG.warn("run: no diagram markers in output:\n$detail")
        return ExportOutcome(prepared.name, null, listOf(Problem("Ran, but no diagram was produced", detail)))
    }

    /**
     * Compile [srcFiles] with the warm daemon, falling back to a cold one-shot. The daemon
     * runs with the IDE's JRE; the bytecode target is fixed by `prepared.jvmTarget`, so it's
     * independent of that JVM. Returns null only on timeout / failure to start.
     */
    private fun compile(
        prepared: Prepared,
        compilerJars: List<String>,
        srcFiles: List<File>,
        outDir: File,
        compileClasspath: List<String>,
        workDir: File,
    ): CompilerDaemon.CompileResult? {
        val fromDaemon = CompilerDaemon.compile(
            ideJavaExe(), compilerJars, srcFiles, outDir, compileClasspath, prepared.jvmTarget, prepared.friendPaths,
        )
        if (fromDaemon != null) return fromDaemon
        LOG.info("run: daemon unavailable — cold compile")
        return coldCompile(prepared.javaExe, compilerJars, srcFiles, outDir, compileClasspath, prepared.friendPaths, prepared.jvmTarget, workDir)
    }

    /** Whether a failed compile looks like missing classpath symbols (an unbuilt module). */
    private fun looksLikeMissingSymbols(diagnostics: String): Boolean =
        diagnostics.contains("unresolved reference", ignoreCase = true)

    /** One-shot fallback compile. Returns null on timeout / failure to start. */
    private fun coldCompile(
        javaExe: String,
        compilerJars: List<String>,
        srcFiles: List<File>,
        outDir: File,
        compileClasspath: List<String>,
        friendPaths: List<String>,
        jvmTarget: String,
        workDir: File,
    ): CompilerDaemon.CompileResult? {
        val cmd = GeneralCommandLine(javaExe).apply {
            addParameters("-cp", compilerJars.joinToString(File.pathSeparator))
            addParameter(K2_COMPILER)
            addParameters("-classpath", compileClasspath.joinToString(File.pathSeparator))
            addParameters("-d", outDir.absolutePath)
            addParameters("-jvm-target", jvmTarget)
            if (friendPaths.isNotEmpty()) addParameter("-Xfriend-paths=${friendPaths.joinToString(",")}")
            // See CompilerWorkerMain: lets a copied KMP common/commonTest source compile as JVM
            // (allows @OptionalExpectation annotations like @JsName); a no-op for plain JVM sources.
            addParameter("-Xmulti-platform")
            addParameter("-Xcommon-sources=${srcFiles.joinToString(",") { it.absolutePath }}")
            // Enable kotlinx-serialization for copied `@Serializable` types (see CompilerWorkerMain).
            CompilerWorkerMain.serializationPlugin(compileClasspath)?.let { addParameter("-Xplugin=$it") }
            addParameters("-no-stdlib", "-no-reflect")
            srcFiles.forEach { addParameter(it.absolutePath) }
            charset = StandardCharsets.UTF_8
        }
        File(workDir, "compile-cmd.txt").writeText(cmd.commandLineString)
        val out = exec(cmd, COMPILE_TIMEOUT_MS) ?: return null
        return CompilerDaemon.CompileResult(out.exitCode, out.stderr.ifBlank { out.stdout })
    }

    /**
     * Whether the failed compile reports an error in a file *other than* the strategy's own.
     * The snippet is a verbatim copy of the strategy's file ([StrategySnippet.FILE_NAME]);
     * referenced sibling files are pulled in as `extra*` sources. An error outside the
     * snippet means a rebuild is needed for a reason the user can't see in the file they're
     * editing, so it's worth surfacing; an error confined to the snippet is already visible
     * to them as they type. Errors with no attributable file (rare) don't count as external.
     */
    private fun compileErrorIsExternal(diagnostics: String): Boolean =
        diagnostics.lineSequence()
            .filter { it.contains(": error:") }
            .mapNotNull { errorFileName(it) }
            .any { it != StrategySnippet.FILE_NAME }

    /** The source-file basename in a kotlinc diagnostic line ("/path/Foo.kt:1:2: error: …" → "Foo.kt"). */
    private fun errorFileName(line: String): String? {
        val end = line.indexOf(".kt:")
        if (end < 0) return null
        return line.substring(0, end + 3).substringAfterLast('/').substringAfterLast('\\')
    }

    /** kotlinc diagnostics → one Problem per error/warning line. */
    private fun parseCompileDiagnostics(diagnostics: String): List<Problem> {
        val rows = diagnostics.lineSequence()
            .map { it.trim() }
            .filter { it.contains(": error:") || it.contains(": warning:") }
            .map { Problem(it) }
            .toList()
        return rows.ifEmpty {
            listOf(Problem("Strategy snippet does not compile", diagnostics.trim().ifBlank { null }))
        }
    }

    private fun ideJavaExe(): String {
        val home = System.getProperty("java.home")
        return File(File(home, "bin"), if (isWindows()) "java.exe" else "java").absolutePath
    }

    private fun exec(cmd: GeneralCommandLine, timeoutMs: Int): com.intellij.execution.process.ProcessOutput? =
        runCatching { CapturingProcessHandler(cmd).runProcess(timeoutMs) }
            .onFailure { LOG.warn("exec: failed to start process: ${cmd.exePath}", it) }
            .getOrNull()
            ?.also { if (it.isTimeout) LOG.warn("exec: process timed out: ${cmd.exePath}") }
            ?.takeUnless { it.isTimeout }

    /**
     * Jars needed to launch [K2_COMPILER], taken from the IDE's bundled Kotlin
     * plugin. We reference them only by path (subprocess), never loading them into
     * our own classloader. Prefers a `kotlinc/lib` dist dir; otherwise falls back
     * to the directory containing `kotlin-compiler*.jar` found anywhere under the
     * plugin.
     *
     * The plugin is located via a class it owns ([KtCallExpression]) rather than the
     * `PluginManager` descriptor APIs, which are all `@ApiStatus.Internal`.
     */
    private fun kotlinCompilerJars(): List<String> {
        val markerJar = runCatching { Path.of(PathUtil.getJarPathForClass(KtCallExpression::class.java)) }
            .getOrNull()
        if (markerJar == null) {
            LOG.warn("kotlinCompilerJars: could not locate the Kotlin plugin jar via KtCallExpression")
            return emptyList()
        }
        LOG.info("kotlinCompilerJars: Kotlin PSI jar at $markerJar")

        // Ascend from the PSI jar (under <plugin>/lib) to the plugin root, which
        // holds the bundled compiler dist under kotlinc/lib.
        var root: Path? = markerJar.parent
        var hops = 0
        while (root != null && hops++ < 6 && !Files.isDirectory(root.resolve("kotlinc").resolve("lib"))) {
            root = root.parent
        }
        if (root == null) {
            LOG.warn("kotlinCompilerJars: no kotlinc/lib found in any parent of $markerJar")
            return emptyList()
        }
        LOG.info("kotlinCompilerJars: Kotlin plugin root at $root")

        val kotlincLib = root.resolve("kotlinc").resolve("lib")
        if (Files.isDirectory(kotlincLib)) {
            val jars = jarsIn(kotlincLib)
            if (jars.isNotEmpty()) {
                LOG.info("kotlinCompilerJars: using kotlinc/lib (${jars.size} jars)")
                return jars
            }
        }

        LOG.info("kotlinCompilerJars: no kotlinc/lib; scanning plugin dir for kotlin-compiler*.jar")
        val compilerJar = Files.walk(root).use { stream ->
            stream.filter { it.extension == "jar" && it.name.startsWith("kotlin-compiler") }
                .findFirst()
                .orElse(null)
        }
        if (compilerJar == null) {
            LOG.warn("kotlinCompilerJars: no kotlin-compiler*.jar found under $root")
            return emptyList()
        }
        LOG.info("kotlinCompilerJars: found $compilerJar; using its directory")
        return jarsIn(compilerJar.parent)
    }

    /**
     * mockk (+ byte-buddy/objenesis) ships as separate jars in the plugin's `lib/`
     * dir. We locate them by name among the siblings of the plugin jar and add them
     * to the runner's classpath. (They sit on our classloader but we never load them.)
     */
    private val mockkJarsCache: List<String> by lazy { locateMockkJars() }

    private fun bundledMockkJars(): List<String> = mockkJarsCache

    private fun locateMockkJars(): List<String> = try {
        val libDir = File(PathUtil.getJarPathForClass(MermaidExporter::class.java)).parentFile
        val names = listOf("mockk", "byte-buddy", "byte-buddy-agent", "objenesis")
        libDir?.listFiles { f -> f.extension == "jar" && names.any { f.name.startsWith(it) } }
            ?.map { it.absolutePath }
            .orEmpty()
            .also { if (it.isEmpty()) LOG.warn("locateMockkJars: no mockk jars found in $libDir") }
    } catch (t: Throwable) {
        LOG.warn("Failed to locate bundled mockk jars", t)
        emptyList()
    }

    private fun jarsIn(dir: java.nio.file.Path): List<String> =
        Files.list(dir).use { stream ->
            stream.filter { it.extension == "jar" }
                .map { it.toAbsolutePath().toString() }
                .toList()
        }

    private fun between(text: String, begin: String, end: String): String? {
        val start = text.indexOf(begin)
        if (start < 0) return null
        val from = start + begin.length
        val stop = text.indexOf(end, from)
        if (stop < 0) return null
        return text.substring(from, stop)
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")
}
