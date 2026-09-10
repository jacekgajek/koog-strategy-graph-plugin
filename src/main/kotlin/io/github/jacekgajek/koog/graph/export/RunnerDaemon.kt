package io.github.jacekgajek.koog.graph.export

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.PathUtil
import java.io.BufferedReader
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

/**
 * Manages warm worker processes (see [RunnerWorkerMain]) that execute a compiled strategy
 * runner's `main()`, so a render doesn't pay a fresh JVM's startup + classpath-loading cost every
 * time — measured at a consistent 1.3-1.8s per render before this, dwarfing the ~0.3-0.9s the
 * warm compiler daemon already got the *compile* step down to.
 *
 * One worker per distinct SDK java executable, not a single global one like [CompilerDaemon]:
 * the compiler only ever needs *a* modern-enough JVM to run kotlinc in — the bytecode it
 * produces targets whatever `-jvm-target` is passed, independent of the compiler's own JVM — but
 * the runner actually *executes* that bytecode, so the worker's own JVM version must support the
 * module's class-file version. A project spanning modules on different JDKs would otherwise risk
 * `UnsupportedClassVersionError` from a worker that happened to start on an older SDK.
 *
 * Thread-safe and best-effort, mirroring [CompilerDaemon]: on any trouble (including an
 * unresponsive worker — a hung strategy run must never hang a render) it tears the offending
 * worker down and returns null so the caller falls back to a one-shot subprocess. It therefore
 * can only ever speed things up — never change the result.
 */
object RunnerDaemon {
    private val LOG = logger<RunnerDaemon>()
    private const val WORKER_MAIN = "io.github.jacekgajek.koog.graph.export.RunnerWorkerMain"

    // The worker holds classloaders for one or more modules' full dependency classpaths — more
    // headroom than the compiler worker's snippet-only compiles need, but still bounded rather
    // than left to JVM ergonomics.
    private const val WORKER_HEAP_MB = 768
    private val WORKER_JVM_ARGS = listOf("-Xmx${WORKER_HEAP_MB}m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1")

    private val IDLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10)
    private val IDLE_CHECK_MS = TimeUnit.MINUTES.toMillis(1)

    // Mirrors MermaidExporter.RUN_TIMEOUT_MS, the cold-subprocess run timeout this replaces.
    private val CALL_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(60)

    class RunResult(val exitCode: Int, val stdout: String, val stderr: String)

    fun run(javaExe: String, classpath: List<String>, outDir: File, mainClass: String, workDir: File): RunResult? {
        startIdleWatchdogOnce()
        ensureShutdownHook()
        return workers.getOrPut(javaExe) { Worker(javaExe) }.run(classpath, outDir, mainClass, workDir)
    }

    fun shutdownAll() {
        workers.values.forEach { it.shutdown() }
    }

    private val workers = ConcurrentHashMap<String, Worker>()
    @Volatile private var watchdogStarted = false
    @Volatile private var shutdownHookAdded = false

    private fun ensureShutdownHook() {
        if (shutdownHookAdded) return
        shutdownHookAdded = true
        Runtime.getRuntime().addShutdownHook(Thread { shutdownAll() })
    }

    /** Recycles any worker idle for [IDLE_TIMEOUT_MS], same reasoning as [CompilerDaemon]'s. */
    private fun startIdleWatchdogOnce() {
        if (watchdogStarted) return
        watchdogStarted = true
        val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "koog-runner-worker-watchdog").apply { isDaemon = true }
        }
        watchdog.scheduleWithFixedDelay({
            val now = System.currentTimeMillis()
            workers.values.forEach { if (it.isIdleAt(now)) it.shutdown() }
        }, IDLE_CHECK_MS, IDLE_CHECK_MS, TimeUnit.MILLISECONDS)
    }

    /** One warm worker JVM, launched via [javaExe], reused across renders for that SDK. */
    private class Worker(private val javaExe: String) {
        private val lock = Any()
        private var process: Process? = null
        private var stdin: OutputStreamWriter? = null
        private var stdout: BufferedReader? = null
        @Volatile private var lastUsedAt: Long = System.currentTimeMillis()

        fun isIdleAt(now: Long): Boolean = process?.isAlive == true && (now - lastUsedAt) > IDLE_TIMEOUT_MS

        fun run(classpath: List<String>, outDir: File, mainClass: String, workDir: File): RunResult? = synchronized(lock) {
            try {
                ensureStarted()
                lastUsedAt = System.currentTimeMillis()
                val stdoutFile = File(workDir, "run-stdout.txt")
                val stderrFile = File(workDir, "run-stderr.txt")
                val reqFile = File(workDir, "run-req.txt")
                reqFile.writeText(
                    buildString {
                        appendLine(outDir.absolutePath)
                        appendLine(mainClass)
                        appendLine(stdoutFile.absolutePath)
                        appendLine(stderrFile.absolutePath)
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

                val line = readLineWithTimeout(r, CALL_TIMEOUT_MS)
                if (line == null) {
                    LOG.warn("RunnerDaemon: worker for $javaExe unresponsive or died mid-run — falling back")
                    shutdown()
                    return@synchronized null
                }
                val code = line.removePrefix("DONE ").trim().toIntOrNull() ?: error("bad worker response: $line")
                val out = stdoutFile.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8).orEmpty()
                val err = stderrFile.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8).orEmpty()
                RunResult(code, out, err)
            } catch (t: Throwable) {
                LOG.warn("RunnerDaemon: run failed for $javaExe; falling back to cold run", t)
                shutdown()
                null
            }
        }

        private fun ensureStarted() {
            process?.takeIf { it.isAlive }?.let { return }

            // The worker's own launch classpath is just the plugin's classes — it never needs
            // the target module's jars at launch; those are supplied per-request and loaded
            // dynamically (see RunnerWorkerMain), which is what lets one worker serve every
            // module that happens to share this SDK.
            val pluginClasses = PathUtil.getJarPathForClass(RunnerDaemon::class.java)
            val cmd = GeneralCommandLine(javaExe).apply {
                addParameters(WORKER_JVM_ARGS)
                addParameters("-cp", pluginClasses)
                addParameter(WORKER_MAIN)
                charset = StandardCharsets.UTF_8
            }
            LOG.info("RunnerDaemon: starting worker via $javaExe")
            val proc = cmd.createProcess()
            process = proc
            stdin = OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8)
            stdout = proc.inputStream.bufferedReader(StandardCharsets.UTF_8)

            Thread({
                runCatching { proc.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { LOG.debug("runner worker ($javaExe): $it") } }
            }, "koog-runner-worker-stderr").apply { isDaemon = true; start() }
        }

        fun shutdown() = synchronized(lock) {
            runCatching { stdin?.apply { write("EXIT\n"); flush() } }
            runCatching { process?.destroy() }
            process = null
            stdin = null
            stdout = null
        }
    }
}

/**
 * [BufferedReader.readLine] has no timeout, and a hung strategy run must never hang the render
 * that requested it. Reads on a throwaway daemon thread and rendezvous through a
 * [SynchronousQueue] instead: on timeout the caller moves on (and kills the worker, which
 * unblocks the reader thread once the pipe closes) rather than waiting indefinitely.
 */
private fun readLineWithTimeout(r: BufferedReader, timeoutMs: Long): String? {
    val result = SynchronousQueue<String?>()
    Thread({
        val line = runCatching { r.readLine() }.getOrNull()
        result.offer(line)
    }, "koog-runner-worker-read").apply { isDaemon = true; start() }
    return result.poll(timeoutMs, TimeUnit.MILLISECONDS)
}
