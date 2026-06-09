package io.github.jacekgajek.koog.graph.export

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.PathUtil
import java.io.BufferedReader
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Manages a warm Kotlin-compiler worker process (see [CompilerWorkerMain]) so the
 * compiler isn't cold-started on every diagram render.
 *
 * Thread-safe and best-effort: on any I/O trouble it tears the worker down and
 * returns null so the caller falls back to a one-shot compile. It therefore can
 * only ever speed things up — never change the result.
 */
object CompilerDaemon {
    private val LOG = logger<CompilerDaemon>()
    private const val WORKER_MAIN = "io.github.jacekgajek.koog.graph.export.CompilerWorkerMain"

    class CompileResult(val exitCode: Int, val diagnostics: String)

    private val lock = Any()
    private var process: Process? = null
    private var stdin: OutputStreamWriter? = null
    private var stdout: BufferedReader? = null
    @Volatile private var shutdownHookAdded = false

    /**
     * Compile [srcFile] into [outDir] using the warm worker. Returns null if the
     * daemon is unavailable (the caller should then fall back to a cold compile).
     * [javaExe] should be a JDK >= 17 (the IDE's own JRE is fine — the produced
     * bytecode target is controlled by [jvmTarget], independent of this JVM).
     */
    fun compile(
        javaExe: String,
        compilerJars: List<String>,
        srcFile: File,
        outDir: File,
        classpath: List<String>,
        jvmTarget: String,
    ): CompileResult? = synchronized(lock) {
        try {
            ensureStarted(javaExe, compilerJars)
            val diagFile = File(outDir.parentFile, "compile-diag.txt")
            val reqFile = File(outDir.parentFile, "compile-req.txt")
            reqFile.writeText(
                buildString {
                    appendLine(outDir.absolutePath)
                    appendLine(jvmTarget)
                    appendLine(diagFile.absolutePath)
                    appendLine(srcFile.absolutePath)
                    appendLine(classpath.size.toString())
                    classpath.forEach { appendLine(it) }
                },
                StandardCharsets.UTF_8,
            )

            val w = stdin ?: error("worker stdin missing")
            val r = stdout ?: error("worker stdout missing")
            w.write(reqFile.absolutePath)
            w.write("\n")
            w.flush()

            var code = 2
            while (true) {
                val line = r.readLine() ?: error("worker stdout closed")
                if (line.startsWith("DONE ")) {
                    code = line.removePrefix("DONE ").trim().toIntOrNull() ?: 2
                    break
                }
            }
            val diag = diagFile.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8).orEmpty()
            CompileResult(code, diag)
        } catch (t: Throwable) {
            LOG.warn("CompilerDaemon: compile failed; falling back to cold compile", t)
            shutdown()
            null
        }
    }

    private fun ensureStarted(javaExe: String, compilerJars: List<String>) {
        process?.takeIf { it.isAlive }?.let { return }

        val pluginClasses = PathUtil.getJarPathForClass(CompilerDaemon::class.java)
        val cp = (listOf(pluginClasses) + compilerJars).joinToString(File.pathSeparator)
        val cmd = GeneralCommandLine(javaExe).apply {
            addParameters("-cp", cp)
            addParameter(WORKER_MAIN)
            charset = StandardCharsets.UTF_8
        }
        LOG.info("CompilerDaemon: starting worker via $javaExe (${compilerJars.size} compiler jars + plugin classes)")
        val proc = cmd.createProcess()
        process = proc
        stdin = OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8)
        stdout = proc.inputStream.bufferedReader(StandardCharsets.UTF_8)

        // Drain stderr so a full pipe never blocks the worker.
        Thread({
            runCatching { proc.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { LOG.debug("worker: $it") } }
        }, "koog-compiler-worker-stderr").apply { isDaemon = true; start() }

        if (!shutdownHookAdded) {
            shutdownHookAdded = true
            Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
        }
    }

    fun shutdown() = synchronized(lock) {
        runCatching { stdin?.apply { write("EXIT\n"); flush() } }
        runCatching { process?.destroy() }
        process = null
        stdin = null
        stdout = null
    }
}
