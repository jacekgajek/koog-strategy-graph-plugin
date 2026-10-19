package io.github.jacekgajek.koog.graph.export

import io.github.jacekgajek.koog.graph.export.MermaidExporter.CompileErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-function tests for how [MermaidExporter] reads kotlinc's diagnostics. */
class MermaidExporterDiagnosticsTest {

    private val unknownTarget = """
        error: unknown JVM target version: 27
        Supported versions: 1.8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26
        warning: the argument '-Xcontext-parameters' is redundant for the current language version 2.4.
    """.trimIndent()

    @Test
    fun `unknown jvm target yields the newest supported version`() {
        assertEquals(26, MermaidExporter.maxSupportedJvmTarget(unknownTarget))
    }

    @Test
    fun `other diagnostics are not mistaken for an unknown jvm target`() {
        assertNull(MermaidExporter.maxSupportedJvmTarget("/tmp/x/${StrategySnippet.FILE_NAME}:3:5: error: unresolved reference 'foo'."))
        assertNull(MermaidExporter.maxSupportedJvmTarget(""))
    }

    @Test
    fun `file-less errors are a toolchain problem, not the strategy file's`() {
        assertEquals(CompileErrorKind.TOOLCHAIN, MermaidExporter.classifyCompileErrors(unknownTarget))
        assertEquals(CompileErrorKind.TOOLCHAIN, MermaidExporter.classifyCompileErrors("error: source file or directory not found: /tmp/nope.kt"))
    }

    @Test
    fun `errors confined to the snippet are in-file`() {
        val diag = "/tmp/koog-mermaid1/${StrategySnippet.FILE_NAME}:12:9: error: unresolved reference 'nodeFoo'.\n" +
            "/tmp/koog-mermaid1/${StrategySnippet.FILE_NAME}:14:1: warning: unused variable."
        assertEquals(CompileErrorKind.IN_FILE, MermaidExporter.classifyCompileErrors(diag))
    }

    @Test
    fun `an error in a referenced sibling file is external`() {
        val diag = "/tmp/koog-mermaid1/${StrategySnippet.FILE_NAME}:12:9: error: unresolved reference 'nodeFoo'.\n" +
            "/tmp/koog-mermaid1/extra0_Helpers.kt:3:1: error: expecting a top level declaration."
        assertEquals(CompileErrorKind.EXTERNAL, MermaidExporter.classifyCompileErrors(diag))
    }
}
