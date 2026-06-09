package io.github.jacekgajek.koog.graph.export

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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

    private const val KOTLIN_PLUGIN_ID = "org.jetbrains.kotlin"
    private const val K2_COMPILER = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
    // Koog's inline DSL functions are compiled to JVM target 17; kotlinc defaults to
    // 1.8, which refuses to inline newer bytecode. 17 also runs on any JDK >= 17,
    // which every Koog project has.
    private const val JVM_TARGET = "17"
    private const val COMPILE_TIMEOUT_MS = 120_000
    private const val RUN_TIMEOUT_MS = 60_000

    /** Everything needed to compile + run, captured off the PSI under a read action. */
    class Prepared(
        val name: String,
        val source: String,
        val moduleClasspath: List<String>,
        val javaExe: String,
    )

    sealed interface ExportResult {
        data class Success(val name: String, val mermaid: String) : ExportResult
        data class Failure(val message: String, val detail: String) : ExportResult
    }

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
        val classpath = OrderEnumerator.orderEntries(module)
            .recursively()
            .withoutSdk()
            .classes()
            .pathsList
            .pathList

        val moduleSdkHome = ModuleRootManager.getInstance(module).sdk?.homePath
        val javaHome = moduleSdkHome ?: System.getProperty("java.home")
        val javaExe = File(File(javaHome, "bin"), if (isWindows()) "java.exe" else "java").absolutePath

        LOG.info(
            "prepare: strategy='${snippet.name}', module='${module.name}', " +
                "classpath=${classpath.size} entries, sdkHome=${moduleSdkHome ?: "(none, using IDE JRE)"}, " +
                "javaExe=$javaExe, snippet=${snippet.source.length} chars",
        )
        if (classpath.isEmpty()) {
            LOG.warn("prepare: module classpath is EMPTY — the module is probably not built yet")
        }
        return Prepared(snippet.name, snippet.source, classpath, javaExe)
    }

    /** Compile and run the prepared snippet. Safe to call off the EDT. Never throws. */
    fun run(prepared: Prepared): ExportResult = try {
        doRun(prepared)
    } catch (t: Throwable) {
        LOG.warn("run: unexpected failure for strategy '${prepared.name}'", t)
        ExportResult.Failure("Diagram generation failed", t.toString())
    }

    private fun doRun(prepared: Prepared): ExportResult {
        val compilerJars = kotlinCompilerJars()
        LOG.info("run: located ${compilerJars.size} Kotlin compiler jars")
        if (compilerJars.isEmpty()) {
            return ExportResult.Failure(
                "Kotlin compiler not found",
                "Could not locate the Kotlin compiler bundled with the IDE's Kotlin plugin.",
            )
        }

        // Keep the work dir around (no delete-on-exit) so generated sources can be inspected.
        val workDir = FileUtil.createTempDirectory("koog-mermaid", null, false)
        val srcFile = File(workDir, StrategySnippet.FILE_NAME).apply {
            writeText(prepared.source, StandardCharsets.UTF_8)
        }
        val outDir = File(workDir, "out").apply { mkdirs() }
        LOG.info("run: workDir=${workDir.absolutePath}, wrote ${srcFile.name} (${prepared.source.length} chars)")

        // The snippet links against the user module (Koog + their node factories);
        // stdlib comes from the bundled compiler jars (-no-stdlib uses what's on -classpath).
        val compileCp = (prepared.moduleClasspath + compilerJars).joinToString(File.pathSeparator)
        val compile = GeneralCommandLine(prepared.javaExe).apply {
            addParameters("-cp", compilerJars.joinToString(File.pathSeparator))
            addParameter(K2_COMPILER)
            addParameters("-classpath", compileCp)
            addParameters("-d", outDir.absolutePath)
            addParameters("-jvm-target", JVM_TARGET)
            addParameters("-no-stdlib", "-no-reflect")
            addParameter(srcFile.absolutePath)
            charset = StandardCharsets.UTF_8
        }
        // Full command is huge (classpath); dump it next to the sources for inspection.
        File(workDir, "compile-cmd.txt").writeText(compile.commandLineString)

        LOG.info("run: compiling (timeout ${COMPILE_TIMEOUT_MS}ms, compile classpath=${prepared.moduleClasspath.size + compilerJars.size} entries)…")
        var compileOut: com.intellij.execution.process.ProcessOutput? = null
        val compileMs = measureTimeMillis { compileOut = exec(compile, COMPILE_TIMEOUT_MS) }
        val co = compileOut
            ?: return ExportResult.Failure("Compilation timed out", "kotlinc took longer than ${COMPILE_TIMEOUT_MS}ms.")
                .also { LOG.warn("run: compile timed out after ${compileMs}ms") }
        LOG.info("run: compile finished in ${compileMs}ms, exit=${co.exitCode}, stdout=${co.stdout.length} chars, stderr=${co.stderr.length} chars")
        if (co.exitCode != 0) {
            val detail = co.stderr.ifBlank { co.stdout }.trim()
            LOG.warn("run: compilation FAILED:\n$detail")
            return ExportResult.Failure("Strategy snippet does not compile", detail)
        }

        val runCp = (listOf(outDir.absolutePath) + prepared.moduleClasspath + compilerJars)
            .joinToString(File.pathSeparator)
        val runCmd = GeneralCommandLine(prepared.javaExe).apply {
            addParameters("-cp", runCp)
            addParameter(StrategySnippet.MAIN_CLASS)
            workDirectory = workDir
            charset = StandardCharsets.UTF_8
        }

        LOG.info("run: executing ${StrategySnippet.MAIN_CLASS} (timeout ${RUN_TIMEOUT_MS}ms)…")
        var runOutput: com.intellij.execution.process.ProcessOutput? = null
        val runMs = measureTimeMillis { runOutput = exec(runCmd, RUN_TIMEOUT_MS) }
        val ro = runOutput
            ?: return ExportResult.Failure("Strategy run timed out", "Execution took longer than ${RUN_TIMEOUT_MS}ms.")
                .also { LOG.warn("run: execution timed out after ${runMs}ms") }
        LOG.info("run: execution finished in ${runMs}ms, exit=${ro.exitCode}, stdout=${ro.stdout.length} chars, stderr=${ro.stderr.length} chars")

        val mermaid = between(ro.stdout, StrategySnippet.BEGIN_MARKER, StrategySnippet.END_MARKER)
        if (mermaid != null && mermaid.isNotBlank()) {
            LOG.info("run: diagram extracted (${mermaid.trim().length} chars) for '${prepared.name}'")
            return ExportResult.Success(prepared.name, mermaid.trim())
        }
        val detail = buildString {
            appendLine("exit=${ro.exitCode}")
            if (ro.stderr.isNotBlank()) appendLine("stderr:\n${ro.stderr.trim()}")
            if (ro.stdout.isNotBlank()) appendLine("stdout:\n${ro.stdout.trim()}")
        }.trim()
        LOG.warn("run: no diagram markers in output:\n$detail")
        return ExportResult.Failure("Ran, but no diagram was produced", detail)
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
     */
    private fun kotlinCompilerJars(): List<String> {
        val root = PluginManagerCore.getPlugin(PluginId.getId(KOTLIN_PLUGIN_ID))?.pluginPath
        if (root == null) {
            LOG.warn("kotlinCompilerJars: Kotlin plugin path not found")
            return emptyList()
        }
        LOG.info("kotlinCompilerJars: Kotlin plugin at $root")

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
