package io.github.jacekgajek.koog.graph.export

import java.io.File
import java.io.PrintStream

/**
 * Long-lived Kotlin-compiler worker started once per IDE session by
 * [CompilerDaemon]. Keeping the compiler classes + JIT warm turns the ~2s cold
 * start of launching `kotlinc` afresh into a few hundred ms per compile.
 *
 * It runs in its own JVM with only the Kotlin compiler jars + this plugin jar on
 * the classpath, and invokes `K2JVMCompiler` reflectively — the compiler is never
 * loaded into the IDE's own classloader.
 *
 * Line protocol over stdin/stdout:
 *   stdin:  absolute path to a request file, or the literal `EXIT`
 *   request file (UTF-8 lines): outDir / jvmTarget / friendPaths / diagFile /
 *                               N / <N classpath entries> / S / <S source files>
 *   stdout: `DONE <exitCode>` once a compile finishes (diagnostics go to diagFile)
 */
object CompilerWorkerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = System.out
        val reader = System.`in`.bufferedReader()
        while (true) {
            val cmd = (reader.readLine() ?: break).trim()
            if (cmd.isEmpty()) continue
            if (cmd == "EXIT") break
            val code = try {
                compile(File(cmd))
            } catch (t: Throwable) {
                t.printStackTrace(System.err)
                2
            }
            out.println("DONE $code")
            out.flush()
        }
    }

    private fun compile(requestFile: File): Int {
        val lines = requestFile.readLines()
        val outDir = lines[0]
        val jvmTarget = lines[1]
        val friendPaths = lines[2]
        val diagFile = lines[3]
        val n = lines[4].toInt()
        val classpath = lines.subList(5, 5 + n).joinToString(File.pathSeparator)
        val s = lines[5 + n].toInt()
        val srcFiles = lines.subList(6 + n, 6 + n + s)

        val args = buildList {
            add("-classpath"); add(classpath)
            add("-d"); add(outDir)
            add("-jvm-target"); add(jvmTarget)
            if (friendPaths.isNotBlank()) add("-Xfriend-paths=$friendPaths")
            add("-no-stdlib"); add("-no-reflect")
            addAll(srcFiles)
        }.toTypedArray()
        PrintStream(File(diagFile).outputStream(), true, "UTF-8").use { diag ->
            val cls = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
            val compiler = cls.getDeclaredConstructor().newInstance()
            val exec = cls.getMethod("exec", PrintStream::class.java, Array<String>::class.java)
            val exit = exec.invoke(compiler, diag, args)
            return exit.javaClass.getMethod("getCode").invoke(exit) as Int
        }
    }
}
