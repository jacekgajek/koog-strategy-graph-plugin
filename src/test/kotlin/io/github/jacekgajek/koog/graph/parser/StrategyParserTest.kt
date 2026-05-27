package io.github.jacekgajek.koog.graph.parser

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

class StrategyParserTest : BasePlatformTestCase() {

    fun testGoalExample() {
        val graph = parse(
            """
            fun streamConsolidator(): Any = Unit
            fun foo() {
                strategy<String, String>("koog_streaming") {
                    val callLLM by nodeLLMRequestStreaming()
                    val consolidateInitial by streamConsolidator()
                    val executeTool by nodeExecuteTools()
                    val sendToolResult by nodeLLMSendToolResultsStreaming()
                    val consolidateAfterTool by streamConsolidator()

                    edge(nodeStart forwardTo callLLM)
                    edge(callLLM forwardTo consolidateInitial)
                    edge(consolidateInitial forwardTo executeTool onToolCalls { true })
                    edge(consolidateInitial forwardTo nodeFinish onTextMessage { true })
                    edge(executeTool forwardTo sendToolResult)
                    edge(sendToolResult forwardTo consolidateAfterTool)
                    edge(consolidateAfterTool forwardTo executeTool onToolCalls { true })
                    edge(consolidateAfterTool forwardTo nodeFinish onTextMessage { true })
                }
            }
            """.trimIndent(),
        )
        assertEquals("koog_streaming", graph.name)
        assertEquals("String", graph.inputType)
        assertEquals("String", graph.outputType)

        // 5 declared + nodeStart + nodeFinish
        assertEquals(7, graph.nodes.size)
        val byName = graph.nodes.associateBy { it.id }
        assertEquals(NodeKind.Start, byName["nodeStart"]?.kind)
        assertEquals(NodeKind.Finish, byName["nodeFinish"]?.kind)
        assertEquals(NodeKind.Declared, byName["callLLM"]?.kind)
        assertEquals("nodeLLMRequestStreaming", byName["callLLM"]?.factory)

        assertEquals(8, graph.edges.size)
        val conditioned = graph.edges.filter { it.condition != null }
        assertEquals(4, conditioned.size)
        assertTrue(conditioned.all { it.condition == "onToolCalls" || it.condition == "onTextMessage" })
    }

    fun testUnknownReferenceIsRecorded() {
        val graph = parse(
            """
            fun foo() {
                strategy<Int, Int>("x") {
                    val a by nodeFoo()
                    edge(a forwardTo mysteryNode)
                }
            }
            """.trimIndent(),
        )
        val mystery = graph.nodes.first { it.id == "mysteryNode" }
        assertEquals(NodeKind.Unknown, mystery.kind)
    }

    fun testNonStrategyCallIsIgnored() {
        val file = myFixture.configureByText(
            "Foo.kt",
            """
            fun other(name: String, block: () -> Unit) {}
            fun foo() {
                other("not a strategy") { }
            }
            """.trimIndent(),
        ) as KtFile
        val call = findCall(file, "other")
        assertNull(StrategyParser().parse(call))
    }

    private fun parse(source: String): StrategyGraph {
        val file = myFixture.configureByText("Test.kt", source) as KtFile
        val call = findCall(file, "strategy")
        return StrategyParser().parse(call) ?: error("Parser returned null")
    }

    private fun findCall(file: KtFile, calleeName: String): KtCallExpression =
        PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java).first { call ->
            (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == calleeName
        }
}
