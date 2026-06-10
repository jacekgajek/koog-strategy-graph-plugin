package io.github.jacekgajek.koog.graph.export

import com.intellij.openapi.diagnostic.logger
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/**
 * A self-contained Kotlin program that, compiled and run against the user's module,
 * prints the strategy's Mermaid diagram via Koog's
 * [ai.koog.agents.core.agent.MermaidDiagramGenerator].
 *
 * Rather than lifting only the `strategy(...) { }` expression — which would drop the
 * enclosing function's parameters, sibling `private` helpers and `internal` types it
 * relies on — we copy the **entire source file verbatim** and append a `main()` that
 * obtains the strategy:
 *  - enclosing top-level `fun` → call it, passing `mockk(relaxed = true)` per parameter;
 *  - enclosing top-level `val` → reference it;
 *  - otherwise (inline) → evaluate the expression text in place (best effort).
 *
 * The compile is given `-Xfriend-paths=<module output>` so `internal` declarations of
 * the module resolve (see [MermaidExporter]).
 */
data class StrategySnippet(
    /** Strategy display name, for the tab title. */
    val name: String,
    /** The full generated `.kt` source. */
    val source: String,
    /** Fully-qualified name of the generated file's `main` class. */
    val mainClass: String,
) {
    companion object {
        private val LOG = logger<StrategySnippet>()

        const val BEGIN_MARKER = "<<<KOOG-MERMAID-BEGIN>>>"
        const val END_MARKER = "<<<KOOG-MERMAID-END>>>"
        const val ERROR_BEGIN_MARKER = "<<<KOOG-MERMAID-ERROR-BEGIN>>>"
        const val ERROR_END_MARKER = "<<<KOOG-MERMAID-ERROR-END>>>"

        /** Generated runner file name; its facade class is `<pkg>.KoogStrategyMermaidMainKt`. */
        const val FILE_NAME = "KoogStrategyMermaidMain.kt"
        private const val FACADE = "KoogStrategyMermaidMainKt"

        /**
         * Build the runner source. Must be called inside a read action — it walks PSI.
         * Returns null if this isn't a usable `strategy(...) { }` call.
         */
        fun from(call: KtCallExpression): StrategySnippet? {
            val file = call.containingFile as? KtFile ?: return null
            val name = strategyName(call) ?: "strategy"

            val caller = callerFor(call)
            val pkg = file.packageFqName.asString()
            val mainClass = if (pkg.isEmpty()) FACADE else "$pkg.$FACADE"

            val sb = StringBuilder(file.text)
            sb.append("\n\n")
            sb.appendLine("fun main() {")
            sb.appendLine("    val __diagram = try {")
            sb.appendLine("        val __koogStrategy: ai.koog.agents.core.agent.entity.AIAgentGraphStrategy<*, *> = $caller")
            sb.appendLine("        ai.koog.agents.core.agent.MermaidDiagramGenerator.generate(__koogStrategy)")
            sb.appendLine("    } catch (t: Throwable) {")
            sb.appendLine("        println(\"$ERROR_BEGIN_MARKER\")")
            sb.appendLine("        println((t.message ?: t.cause?.message)?.takeIf { it.isNotBlank() } ?: (t::class.qualifiedName ?: t::class.java.name))")
            sb.appendLine("        println(\"$ERROR_END_MARKER\")")
            sb.appendLine("        return")
            sb.appendLine("    }")
            sb.appendLine("    println(\"$BEGIN_MARKER\")")
            sb.appendLine("    print(__diagram)")
            sb.appendLine("    println()")
            sb.appendLine("    println(\"$END_MARKER\")")
            sb.appendLine("}")

            LOG.info("snippet: strategy='$name', pkg='$pkg', caller='$caller'")
            return StrategySnippet(name, sb.toString(), mainClass)
        }

        /**
         * How `main()` should obtain the strategy. Prefers calling the enclosing
         * top-level function/property (so captured params/helpers come for free);
         * falls back to the expression text for inline strategies.
         */
        private fun callerFor(call: KtCallExpression): String {
            val decl = enclosingTopLevelDeclaration(call)
            return when (decl) {
                is KtNamedFunction -> {
                    val fnName = decl.name
                    if (fnName == null || decl.receiverTypeReference != null) {
                        call.text // extension/anonymous — can't call simply
                    } else {
                        val args = decl.valueParameters.joinToString(", ") { "io.mockk.mockk(relaxed = true)" }
                        "$fnName($args)"
                    }
                }
                is KtProperty -> decl.name ?: call.text
                else -> call.text
            }
        }

        /** The top-level (direct child of the file) fun/property that contains [call], if any. */
        private fun enclosingTopLevelDeclaration(call: KtCallExpression): KtDeclaration? {
            var el = call.parent
            var candidate: KtDeclaration? = null
            while (el != null && el !is KtFile) {
                if (el is KtNamedFunction || el is KtProperty) candidate = el as KtDeclaration
                el = el.parent
            }
            // candidate is the outermost fun/property on the path to the file root.
            return candidate?.takeIf { it.parent is KtFile }
        }

        private fun strategyName(call: KtCallExpression): String? {
            val firstArg = call.valueArguments.firstOrNull()?.getArgumentExpression()?.text ?: return null
            return firstArg.trim().trim('"').ifBlank { null }
        }
    }
}
