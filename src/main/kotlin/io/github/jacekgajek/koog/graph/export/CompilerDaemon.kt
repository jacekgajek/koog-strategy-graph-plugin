package io.github.jacekgajek.koog.graph.export

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.PathUtil
import java.io.BufferedReader
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    // The worker only ever compiles one strategy snippet (plus a handful of same-file
    // helpers) against an already-resolved classpath — a few hundred MB is generous. Cap
    // it explicitly rather than inherit the JBR's ergonomic default (up to 1/4 of physical
    // RAM), which on a well-provisioned dev box can reserve several GB for a JVM that's
    // idle almost all the time. UseSerialGC drops G1's background GC threads, which cost
    // nothing when the heap is this small but otherwise sit around consuming a core.
    private const val WORKER_HEAP_MB = 512
    private val WORKER_JVM_ARGS = listOf("-Xmx${WORKER_HEAP_MB}m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2")

    // A diagram is re-rendered on every settled edit (see KoogGraphService.REFRESH_DELAY_MS)
    // while a tab is open, but most of a session has none open. Recycling the worker after a
    // stretch of inactivity — mirroring the real Kotlin daemon's own idle-shutdown — trades a
    // ~1-2s cold start on the next render for not holding compiler classes + heap resident for
    // hours. Checked well below the timeout so the actual wait is close to IDLE_TIMEOUT_MS.
    private val IDLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10)
    private val IDLE_CHECK_MS = TimeUnit.MINUTES.toMillis(1)

    class CompileResult(val exitCode: Int, val diagnostics: String)

    private val lock = Any()
    private var process: Process? = null
    private var stdin: OutputStreamWriter? = null
    private var stdout: BufferedReader? = null
    @Volatile private var shutdownHookAdded = false
    @Volatile private var lastUsedAt: Long = 0L
    @Volatile private var watchdogStarted = false

    /**
     * Compile [srcFiles] into [outDir] using the warm worker. Returns null if the
     * daemon is unavailable (the caller should then fall back to a cold compile).
     * [javaExe] should be a JDK >= 17 (the IDE's own JRE is fine — the produced
     * bytecode target is controlled by [jvmTarget], independent of this JVM).
     */
    fun compile(
        javaExe: String,
        compilerJars: List<String>,
        srcFiles: List<File>,
        outDir: File,
        classpath: List<String>,
        jvmTarget: String,
        friendPaths: List<String>,
    ): CompileResult? = synchronized(lock) {
        try {
            ensureStarted(javaExe, compilerJars)
            lastUsedAt = System.currentTimeMillis()
            val diagFile = File(outDir.parentFile, "compile-diag.txt")
            val reqFile = File(outDir.parentFile, "compile-req.txt")
            reqFile.writeText(
                buildString {
                    appendLine(outDir.absolutePath)
                    appendLine(jvmTarget)
                    appendLine(friendPaths.joinToString(",")) // empty line if none
                    appendLine(diagFile.absolutePath)
                    appendLine(classpath.size.toString())
                    classpath.forEach { appendLine(it) }
                    appendLine(srcFiles.size.toString())
                    srcFiles.forEach { appendLine(it.absolutePath) }
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
            addParameters(WORKER_JVM_ARGS)
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
        startIdleWatchdogOnce()
    }

    /**
     * Recycles the worker after [IDLE_TIMEOUT_MS] of inactivity. Runs on a daemon thread so it
     * never keeps the IDE process alive on its own; [ensureStarted] restarts the worker lazily
     * on the next [compile] call, same as a first cold start.
     */
    private fun startIdleWatchdogOnce() {
        if (watchdogStarted) return
        watchdogStarted = true
        val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "koog-compiler-worker-watchdog").apply { isDaemon = true }
        }
        watchdog.scheduleWithFixedDelay({
            synchronized(lock) {
                val idleFor = System.currentTimeMillis() - lastUsedAt
                if (process?.isAlive == true && idleFor > IDLE_TIMEOUT_MS) {
                    LOG.info("CompilerDaemon: worker idle for ${idleFor}ms, shutting it down")
                    shutdown()
                }
            }
        }, IDLE_CHECK_MS, IDLE_CHECK_MS, TimeUnit.MILLISECONDS)
    }

    fun shutdown() = synchronized(lock) {
        runCatching { stdin?.apply { write("EXIT\n"); flush() } }
        runCatching { process?.destroy() }
        process = null
        stdin = null
        stdout = null
    }
}
