package io.github.jacekgajek.koog.graph.parser

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty

class NodeNamesTest : BasePlatformTestCase() {

    fun testLiteralName() {
        assertEquals("Emit citations", displayNameOf("""val emitCitations by node("Emit citations") { }"""))
    }

    fun testNamedLiteralArgument() {
        assertEquals("Execute tool", displayNameOf("""val executeTool by node(name = "Execute tool") { }"""))
    }

    fun testCompanionConstant() {
        val name = displayNameOf(
            decl = """val emitToolResults by node(EMIT_TOOL_RESULTS_NODE) { }""",
            companion = """private const val EMIT_TOOL_RESULTS_NODE = "Emit tool results"""",
        )
        assertEquals("Emit tool results", name)
    }

    fun testConstantThroughHelperFunction() {
        val name = displayNameOf(
            decl = """val clientToolGate by clientToolGate()""",
            members = """
                private fun clientToolGate() = node(CLIENT_TOOL_GATE_NODE) { }
            """.trimIndent(),
            companion = """private const val CLIENT_TOOL_GATE_NODE = "Client tool gate"""",
        )
        assertEquals("Client tool gate", name)
    }

    fun testConstantThroughTwoHelpersWithParameterBinding() {
        // The gate's delegate calls a helper that calls a generic `pauseNode(name, …)` helper,
        // which finally builds `node(name)`: the name must flow through both parameter lists.
        val name = displayNameOf(
            decl = """val toolConfirmationGate by toolConfirmationGate("emit")""",
            members = """
                private fun toolConfirmationGate(emit: String) = pauseNode(
                    TOOL_CONFIRMATION_GATE_NODE,
                    emit,
                    afterNode = ANONYMIZE_NODE,
                )

                private fun pauseNode(name: String, emit: String, afterNode: String) = node(name) { }
            """.trimIndent(),
            companion = """
                private const val ANONYMIZE_NODE = "Anonymize"
                private const val TOOL_CONFIRMATION_GATE_NODE = "Tool confirmation gate"
            """.trimIndent(),
        )
        assertEquals("Tool confirmation gate", name)
    }

    fun testNamedArgumentBindsHelperParameter() {
        val name = displayNameOf(
            decl = """val gate by gate(afterNode = "x", name = GATE)""",
            members = """private fun gate(name: String, afterNode: String) = node(name = name) { }""",
            companion = """private const val GATE = "Gate"""",
        )
        assertEquals("Gate", name)
    }

    fun testDefaultParameterValueNamesNode() {
        val name = displayNameOf(
            decl = """val gate by gate()""",
            members = """private fun gate(name: String = "Default gate") = node(name) { }""",
        )
        assertEquals("Default gate", name)
    }

    fun testHelperWithBlockBodyReturn() {
        val name = displayNameOf(
            decl = """val gate by gate()""",
            members = """
                private fun gate(): Any {
                    val n = node("Block gate") { }
                    return n
                }
            """.trimIndent(),
        )
        assertEquals("Block gate", name)
    }

    fun testNonConstantHelperArgumentFallsBackToPropertyName() {
        val name = displayNameOf(
            decl = """val gate by gate(computeName())""",
            members = """
                private fun gate(name: String) = node(name) { }
                private fun computeName(): String = "x"
            """.trimIndent(),
        )
        assertEquals("gate", name)
    }

    fun testTemplateOverConstantAndConcatenation() {
        val name = displayNameOf(
            decl = """val a by node("${'$'}PREFIX results" + SUFFIX) { }""",
            companion = """
                private const val PREFIX = "Emit"
                private const val SUFFIX = " (v2)"
            """.trimIndent(),
        )
        assertEquals("Emit results (v2)", name)
    }

    fun testChainedConstants() {
        val name = displayNameOf(
            decl = """val a by node(ALIAS) { }""",
            companion = """
                private const val ALIAS = BASE
                private const val BASE = "Base name"
            """.trimIndent(),
        )
        assertEquals("Base name", name)
    }

    fun testCrossFileConstant() {
        myFixture.addFileToProject(
            "Names.kt",
            """
            package names
            object Names { const val CHECKPOINT = "Checkpoint turn" }
            """.trimIndent(),
        )
        val name = displayNameOf(
            decl = """val checkpoint by node(names.Names.CHECKPOINT) { }""",
        )
        assertEquals("Checkpoint turn", name)
    }

    fun testNonConstantFallsBackToPropertyName() {
        val name = displayNameOf(
            decl = """val dynamic by node(computeName()) { }""",
            members = """private fun computeName(): String = "x"""",
        )
        assertEquals("dynamic", name)
    }

    fun testVarIsNotAConstant() {
        val name = displayNameOf(
            decl = """val a by node(MUTABLE) { }""",
            members = """private var MUTABLE = "x"""",
        )
        assertEquals("a", name)
    }

    private fun displayNameOf(decl: String, members: String = "", companion: String = ""): String {
        val file = myFixture.configureByText(
            "Facade.kt",
            """
            fun <T> node(name: String, block: () -> Unit): Any = Unit
            fun strategy(name: String, block: () -> Unit) {}

            class Facade {
                fun build() = strategy("s") {
                    $decl
                }

                $members

                companion object {
                    $companion
                }
            }
            """.trimIndent(),
        ) as KtFile
        val prop = PsiTreeUtil.findChildrenOfType(file, KtProperty::class.java)
            .first { it.hasDelegateExpression() }
        return NodeNames.displayName(prop)
    }
}
