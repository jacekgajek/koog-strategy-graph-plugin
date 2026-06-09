package io.github.jacekgajek.koog.graph.export

import com.intellij.openapi.diagnostic.logger
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

/**
 * A self-contained Kotlin program text that, when compiled and run against the
 * user's module classpath, prints the Mermaid diagram of the strategy via
 * Koog's [ai.koog.agents.core.agent.MermaidDiagramGenerator].
 *
 * We lift the verbatim `strategy(...) { }` expression out of the source file,
 * carry over that file's imports so its node/edge factories resolve, and stub
 * any captured outer references with `mockk(relaxed = true)` so the snippet
 * compiles in isolation.
 */
data class StrategySnippet(
    /** Strategy display name, for the tab title. */
    val name: String,
    /** The full generated `.kt` source. */
    val source: String,
) {
    companion object {
        private val LOG = logger<StrategySnippet>()

        const val BEGIN_MARKER = "<<<KOOG-MERMAID-BEGIN>>>"
        const val END_MARKER = "<<<KOOG-MERMAID-END>>>"

        /** Stable name of the generated file / main class (MainKt). */
        const val FILE_NAME = "KoogStrategyMermaidMain.kt"
        const val MAIN_CLASS = "KoogStrategyMermaidMainKt"

        /**
         * Build the runner source. Must be called inside a read action — it walks PSI.
         * Returns null if this isn't a usable `strategy(...) { }` call.
         */
        fun from(call: KtCallExpression): StrategySnippet? {
            val file = call.containingFile as? KtFile ?: return null
            val name = strategyName(call) ?: "strategy"

            val imports = file.importList?.imports.orEmpty()
                .mapNotNull { it.text?.trim() }
                .filter { it.isNotEmpty() }

            val captures = runCatching { Captures.collect(call) }
                .onFailure { LOG.debug("capture detection failed; compiling without mocks", it) }
                .getOrDefault(emptyList())

            val strategyExpr = call.text

            val sb = StringBuilder()
            sb.appendLine("@file:Suppress(\"UNUSED_VARIABLE\", \"UNUSED_PARAMETER\", \"unused\", \"UNUSED_EXPRESSION\")")
            sb.appendLine()
            imports.forEach { sb.appendLine(it) }
            sb.appendLine("import ai.koog.agents.core.agent.MermaidDiagramGenerator")
            if (captures.isNotEmpty()) sb.appendLine("import io.mockk.mockk")
            sb.appendLine()
            captures.forEach { (id, type) ->
                sb.appendLine("private val $id: $type = mockk(relaxed = true)")
            }
            if (captures.isNotEmpty()) sb.appendLine()
            sb.appendLine("private val __koogStrategy = $strategyExpr")
            sb.appendLine()
            sb.appendLine("fun main() {")
            sb.appendLine("    println(\"$BEGIN_MARKER\")")
            sb.appendLine("    print(MermaidDiagramGenerator.generate(__koogStrategy))")
            sb.appendLine("    println()")
            sb.appendLine("    println(\"$END_MARKER\")")
            sb.appendLine("}")

            return StrategySnippet(name, sb.toString())
        }

        private fun strategyName(call: KtCallExpression): String? {
            val firstArg = call.valueArguments.firstOrNull()?.getArgumentExpression()?.text ?: return null
            return firstArg.trim().trim('"').ifBlank { null }
        }
    }
}
