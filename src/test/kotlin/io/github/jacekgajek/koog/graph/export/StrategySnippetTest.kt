package io.github.jacekgajek.koog.graph.export

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * [StrategySnippet.from] builds a runnable copy of the strategy's file; these tests check the
 * *shape* of the generated source for cases that previously produced code which wouldn't
 * compile (see `context(...)` handling below) — they don't compile it (that needs a real
 * module classpath, exercised instead via [io.github.jacekgajek.koog.graph.export.MermaidExporter]).
 */
class StrategySnippetTest : BasePlatformTestCase() {

    fun testMemberFunctionWithContextParameterIsBridged() {
        val snippet = snippetOf(
            """
            class Accessor
            class Facade {
                context(accessor: Accessor)
                fun koogStreamingStrategy(): Any = strategy<String, String>("koog_streaming") {
                    edge(nodeStart forwardTo nodeFinish)
                }
            }
            """.trimIndent(),
        )
        // The injected export member must itself supply an `Accessor` receiver (via a nested
        // local extension function) before calling into the context-requiring strategy method —
        // otherwise the copied file fails with "no context argument for 'accessor: Accessor' found".
        assertTrue(snippet.source.contains("fun Accessor.`__koogCtx0`()"))
        assertTrue(snippet.source.contains("io.mockk.mockk<Accessor>(relaxed = true)"))
        assertTrue(snippet.source.contains("koogStreamingStrategy()"))
    }

    fun testTopLevelFunctionWithContextParameterIsBridged() {
        val snippet = snippetOf(
            """
            class Accessor
            context(accessor: Accessor)
            fun koogStreamingStrategy(): Any = strategy<String, String>("koog_streaming") {
                edge(nodeStart forwardTo nodeFinish)
            }
            """.trimIndent(),
        )
        assertTrue(snippet.source.contains("__koogExportStrategyTopLevel"))
        assertTrue(snippet.source.contains("fun Accessor.`__koogCtx0`()"))
    }

    fun testMemberFunctionWithoutContextParameterIsUnchanged() {
        val snippet = snippetOf(
            """
            class Facade {
                fun koogStreamingStrategy(): Any = strategy<String, String>("koog_streaming") {
                    edge(nodeStart forwardTo nodeFinish)
                }
            }
            """.trimIndent(),
        )
        assertFalse(snippet.source.contains("__koogCtx"))
        assertTrue(snippet.source.contains("koogStreamingStrategy()"))
    }

    private fun snippetOf(source: String): StrategySnippet {
        val file = myFixture.configureByText("Test.kt", source) as KtFile
        val call = findCall(file, "strategy")
        return StrategySnippet.from(call) ?: error("StrategySnippet.from returned null")
    }

    private fun findCall(file: KtFile, calleeName: String): KtCallExpression =
        PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java).first { call ->
            (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == calleeName
        }
}
