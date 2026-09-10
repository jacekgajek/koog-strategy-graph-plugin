package io.github.jacekgajek.koog.graph.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets

/**
 * Long-lived process that executes a compiled strategy runner's `main()` in-process instead of
 * launching a fresh JVM per render (see [RunnerDaemon]). A [URLClassLoader] for the (stable,
 * often large — a module's full dependency classpath) part is cached per distinct classpath;
 * only the freshly compiled runner class (small, different every request) is loaded fresh each
 * time as a child of that cached loader. Repeated renders of the same module then skip
 * re-opening and re-parsing hundreds of jars, which is what made every render pay a JVM-startup
 * tax even though the daemon compiler already made compiling itself fast.
 *
 * Line protocol, matching the shape of [CompilerWorkerMain]:
 *   stdin:  absolute path to a request file, or the literal `EXIT`
 *   request file (UTF-8 lines): outDir / mainClass / stdoutFile / stderrFile /
 *                               N / <N classpath entries>
 *   stdout: `DONE <exitCode>` once the run finishes. The executed code's own stdout/stderr are
 *           captured to the given files, never mixed onto this control channel.
 */
object RunnerWorkerMain {
    // Bounded so a session that touches many distinct modules doesn't grow this without limit;
    // an evicted loader is closed to release the (many) open jar file handles it holds.
    private const val MAX_CACHED_CLASSPATHS = 8
    private val baseLoaders = object : LinkedHashMap<List<String>, URLClassLoader>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<String>, URLClassLoader>): Boolean {
            if (size <= MAX_CACHED_CLASSPATHS) return false
            runCatching { eldest.value.close() }
            return true
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = System.out
        val reader = System.`in`.bufferedReader()
        while (true) {
            val cmd = (reader.readLine() ?: break).trim()
            if (cmd.isEmpty()) continue
            if (cmd == "EXIT") break
            val code = try {
                runOne(File(cmd))
            } catch (t: Throwable) {
                t.printStackTrace(System.err)
                2
            }
            out.println("DONE $code")
            out.flush()
        }
    }

    private fun runOne(requestFile: File): Int {
        val lines = requestFile.readLines()
        val outDir = lines[0]
        val mainClass = lines[1]
        val stdoutFile = File(lines[2])
        val stderrFile = File(lines[3])
        val n = lines[4].toInt()
        val classpath = lines.subList(5, 5 + n)

        val base = synchronized(baseLoaders) {
            baseLoaders.getOrPut(classpath) {
                URLClassLoader(classpath.map { File(it).toURI().toURL() }.toTypedArray(), ClassLoader.getSystemClassLoader())
            }
        }
        // The compiled runner is different every call (a fresh temp outDir each render) — never
        // cache it; load it fresh each time as a child of the cached, stable base loader.
        val runnerLoader = URLClassLoader(arrayOf(File(outDir).toURI().toURL()), base)

        val outCapture = ByteArrayOutputStream()
        val errCapture = ByteArrayOutputStream()
        val savedOut = System.out
        val savedErr = System.err
        var exitCode = 0
        System.setOut(PrintStream(outCapture, true, StandardCharsets.UTF_8))
        System.setErr(PrintStream(errCapture, true, StandardCharsets.UTF_8))
        try {
            val cls = Class.forName(mainClass, true, runnerLoader)
            val m = cls.getMethod("main", Array<String>::class.java)
            m.invoke(null, arrayOf<String>())
        } catch (e: InvocationTargetException) {
            (e.targetException ?: e).printStackTrace(System.err)
            exitCode = 1
        } catch (t: Throwable) {
            t.printStackTrace(System.err)
            exitCode = 1
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
            // Only the tiny per-call child loader — the cached base loader stays open.
            runCatching { runnerLoader.close() }
        }
        stdoutFile.writeBytes(outCapture.toByteArray())
        stderrFile.writeBytes(errCapture.toByteArray())
        return exitCode
    }
}
