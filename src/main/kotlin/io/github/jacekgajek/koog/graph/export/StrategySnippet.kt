package io.github.jacekgajek.koog.graph.export

import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
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
 *  - enclosing **class member** `fun`/`val` → construct the class with a
 *    `mockk(relaxed = true)` per constructor parameter, then call/reference the member;
 *  - enclosing **object / companion** member → reference it via the object (or
 *    enclosing class) name;
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
         * function/property declaration (so captured params/helpers come for free);
         * falls back to the expression text for inline strategies.
         *
         * The declaration may be top-level or a member of a class/object; for a
         * plain class member we synthesize the receiver by constructing the class
         * with `mockk(relaxed = true)` per constructor parameter.
         */
        private fun callerFor(call: KtCallExpression): String {
            val member = enclosingMemberDeclaration(call) ?: return call.text
            val container = member.parent

            // The receiver expression the member is accessed through. "" means
            // top-level (no receiver); null means we can't synthesize one.
            val receiver: String? = when {
                container is KtFile -> ""
                container is KtClassBody -> {
                    val owner = container.parent as? KtClassOrObject ?: return call.text
                    receiverForClassOrObject(owner) ?: return call.text
                }
                else -> return call.text // local declaration, etc. — not callable from main()
            }

            val prefix = if (receiver.isNullOrEmpty()) "" else "$receiver."
            return when (member) {
                is KtNamedFunction -> {
                    val fnName = member.name
                    if (fnName == null || member.receiverTypeReference != null) {
                        call.text // extension/anonymous — can't call simply
                    } else {
                        val args = member.valueParameters.joinToString(", ") { "io.mockk.mockk(relaxed = true)" }
                        "$prefix$fnName($args)"
                    }
                }
                is KtProperty -> member.name?.let { "$prefix$it" } ?: call.text
                else -> call.text
            }
        }

        /**
         * An expression that yields an instance the [owner]'s members can be accessed on.
         *  - `object` / `companion object` → the object (or enclosing class) name;
         *  - top-level constructable `class` → `Name(mockk(), …)` over its primary ctor;
         *  - anything we can't safely instantiate (interface, abstract, sealed, enum,
         *    annotation, generic, nested, private ctor) → null.
         *
         * The whole source file is copied verbatim into the snippet, so simple names
         * resolve without qualification.
         */
        private fun receiverForClassOrObject(owner: KtClassOrObject): String? {
            val name = owner.name ?: return null
            return when (owner) {
                is KtObjectDeclaration -> {
                    if (owner.isCompanion()) {
                        // companion members are reachable via the enclosing class name
                        val outer = (owner.parent as? KtClassBody)?.parent as? KtClass ?: return null
                        if (outer.parent !is KtFile) return null
                        outer.name
                    } else {
                        if (owner.parent !is KtFile) return null
                        name
                    }
                }
                is KtClass -> {
                    if (owner.parent !is KtFile) return null // only top-level classes
                    if (owner.isInterface() || owner.isEnum() || owner.isAnnotation() || owner.isSealed()) return null
                    if (owner.hasModifier(KtTokens.ABSTRACT_KEYWORD) || owner.hasModifier(KtTokens.INNER_KEYWORD)) return null
                    if (owner.typeParameters.isNotEmpty()) return null
                    val ctor = owner.primaryConstructor
                    if (ctor != null && ctor.hasModifier(KtTokens.PRIVATE_KEYWORD)) return null
                    val args = (ctor?.valueParameters ?: emptyList()).joinToString(", ") { "io.mockk.mockk(relaxed = true)" }
                    "$name($args)"
                }
                else -> null
            }
        }

        /**
         * The outermost fun/property that directly contains [call] and is itself a
         * direct member of a file or a class/object body (i.e. not a local declaration).
         */
        private fun enclosingMemberDeclaration(call: KtCallExpression): KtDeclaration? {
            var el: PsiElement? = call.parent
            var candidate: KtDeclaration? = null
            while (el != null && el !is KtFile) {
                if (el is KtNamedFunction || el is KtProperty) {
                    val p = el.parent
                    if (p is KtFile || p is KtClassBody) candidate = el as KtDeclaration
                }
                el = el.parent
            }
            return candidate
        }

        private fun strategyName(call: KtCallExpression): String? {
            val firstArg = call.valueArguments.firstOrNull()?.getArgumentExpression()?.text ?: return null
            return firstArg.trim().trim('"').ifBlank { null }
        }
    }
}
