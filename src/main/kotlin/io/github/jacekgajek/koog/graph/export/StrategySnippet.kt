package io.github.jacekgajek.koog.graph.export

import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression

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
 *  - strategy inside a **constructable class/object** (a member `fun`/`val`, or a local
 *    `val`/inline expression in one of its methods) → inject a synthetic member function
 *    that returns the strategy and call it on a constructed instance. Running in member
 *    scope means `private` members and `companion` constants resolve; any *local* variables
 *    the strategy captures are reproduced by copying their declarations into that function.
 *  - otherwise (top-level inline) → evaluate the expression in place, reproducing referenced
 *    locals in a `run { }` block (best effort).
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

        /** A plain (non-backtick-quoted) Kotlin identifier; anything else must be escaped. */
        private val PLAIN_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

        const val BEGIN_MARKER = "<<<KOOG-MERMAID-BEGIN>>>"
        const val END_MARKER = "<<<KOOG-MERMAID-END>>>"
        const val ERROR_BEGIN_MARKER = "<<<KOOG-MERMAID-ERROR-BEGIN>>>"
        const val ERROR_END_MARKER = "<<<KOOG-MERMAID-ERROR-END>>>"

        /** Generated runner file name; its facade class is `<pkg>.KoogStrategyMermaidMainKt`. */
        const val FILE_NAME = "KoogStrategyMermaidMain.kt"
        private const val FACADE = "KoogStrategyMermaidMainKt"

        /** Name of the synthetic member we inject to obtain the strategy from inside a class. */
        private const val EXPORT_FN = "__koogExportStrategy"
        private const val STRATEGY_TYPE = "ai.koog.agents.core.agent.entity.AIAgentGraphStrategy<*, *>"

        /** The (possibly rewritten) file text plus the `main()` expression that yields the strategy. */
        private class Plan(val fileText: String, val caller: String, val how: String)

        /**
         * Build the runner source. Must be called inside a read action — it walks PSI (and
         * resolves references), so run it off the EDT. Returns null if this isn't a usable
         * `strategy(...) { }` call.
         */
        fun from(call: KtCallExpression): StrategySnippet? {
            val file = call.containingFile as? KtFile ?: return null
            val name = strategyName(call) ?: "strategy"
            val pkg = file.packageFqName.asString()
            val mainClass = if (pkg.isEmpty()) FACADE else "$pkg.$FACADE"

            val plan = planExport(call, file)
            // The strategy's enclosing function/class may carry `@OptIn(Marker::class)` that the
            // experimental APIs in its body require. Lifting the strategy into our `main()`/injected
            // member would drop it, so re-assert those opt-ins file-wide on the generated source.
            val optIns = collectOptIns(call)
            val fileText = if (optIns.isEmpty()) plan.fileText else "@file:OptIn($optIns)\n${plan.fileText}"
            LOG.info("snippet: strategy='$name', pkg='$pkg', how=${plan.how}, caller='${plan.caller}', optIns='$optIns'")
            return StrategySnippet(name, runner(fileText, plan.caller), mainClass)
        }

        /**
         * The combined `@OptIn(...)` marker arguments declared on the strategy's enclosing function
         * and enclosing classes/objects (e.g. `DetachedPromptExecutorAPI::class`), de-duplicated.
         * Re-applied as a `@file:OptIn(...)` so experimental calls in the lifted strategy still
         * compile. The markers resolve via the file's own imports (copied verbatim).
         */
        private fun collectOptIns(call: KtCallExpression): String {
            val markers = LinkedHashSet<String>()
            var owner: KtModifierListOwner? =
                PsiTreeUtil.getParentOfType(call, KtNamedFunction::class.java, KtClassOrObject::class.java)
            while (owner != null) {
                owner.annotationEntries
                    .filter { it.shortName?.asString() == "OptIn" }
                    .forEach { ann -> ann.valueArguments.forEach { a -> a.getArgumentExpression()?.text?.let(markers::add) } }
                owner = PsiTreeUtil.getParentOfType(owner, KtNamedFunction::class.java, KtClassOrObject::class.java)
            }
            return markers.joinToString(", ")
        }

        /** The full runner source: [fileText] (the strategy's file, possibly with an injected member) plus a `main()`. */
        private fun runner(fileText: String, caller: String): String {
            val sb = StringBuilder(stripFacadeRenames(fileText))
            sb.append("\n\n")
            sb.appendLine("fun main() {")
            sb.appendLine("    val __diagram = try {")
            sb.appendLine("        val __koogStrategy: $STRATEGY_TYPE = $caller")
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
            return sb.toString()
        }

        /**
         * Strip file-facade-renaming annotations (`@file:JvmName(...)`, `@file:JvmMultifileClass`)
         * from the copied source. They'd route our appended `main()` into a differently-named (or
         * multi-file) facade class, so the runner's `<pkg>.KoogStrategyMermaidMainKt` entry point
         * wouldn't exist at run time (ClassNotFoundException). Other file annotations (e.g.
         * `@file:OptIn`, `@file:Suppress`) are kept — they affect compilation.
         */
        private fun stripFacadeRenames(text: String): String =
            text.replace(Regex("""@file\s*:\s*JvmName\s*\([^)]*\)"""), "")
                .replace(Regex("""@file\s*:\s*JvmMultifileClass\b"""), "")

        /**
         * Decide how `main()` obtains the strategy:
         *  1. a **top-level** `fun`/`val` whose value is the strategy → reference it directly;
         *  2. a strategy inside a **constructable class/object** → inject a synthetic member that
         *     returns it (member scope resolves `private` members and `companion` constants) and
         *     invoke it on a constructed instance, reproducing any captured locals inside it;
         *  3. otherwise → inline the expression at top level, reproducing captured locals.
         */
        private fun planExport(call: KtCallExpression, file: KtFile): Plan {
            val owner = valueOwnerOf(call)

            // 1) Top-level declaration — reference it directly, no rewrite needed.
            if (owner != null && owner.parent is KtFile) {
                topLevelRef(owner)?.let { return Plan(file.text, it, "top-level-ref") }
            }

            // 2) Inside a reachable class/object — inject a member and call it.
            val cls = enclosingClassOrObject(call)
            val receiver = cls?.let { receiverFor(it) }
            if (cls != null && receiver != null) {
                injectExportMember(file, cls, exportBody(call, owner, cls))?.let {
                    return Plan(it, "$receiver.`$EXPORT_FN`()", "injected-member")
                }
            }

            // 3) Inline at top level, reproducing referenced locals.
            return Plan(file.text, inlineWithLocals(call), "inline")
        }

        /** A reference to a top-level declaration whose value is the strategy, or null if not simply callable. */
        private fun topLevelRef(owner: KtDeclaration): String? = when (owner) {
            is KtNamedFunction -> owner.name?.takeIf { owner.receiverTypeReference == null }
                ?.let { factoryCall(escapeName(it), owner) }
            is KtProperty -> owner.name?.let(::escapeName)
            else -> null
        }

        /**
         * A call to factory function [escapedName]/[fn] with synthesized arguments. When the
         * function is generic (e.g. `inline fun <reified Input, reified Output> …`), its type
         * arguments can't be inferred from mock values, so we supply them explicitly — each
         * type parameter's declared upper bound, or `String` (a serializable, widely-valid
         * stand-in) when unbounded. The concrete types don't change the graph shape.
         */
        private fun factoryCall(escapedName: String, fn: KtNamedFunction): String {
            val typeArgs = fn.typeParameters
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ", "<", ">") { it.extendsBound?.text ?: "String" }
                .orEmpty()
            val args = fn.valueParameters.joinToString(", ") { mockArg(it) }
            return "$escapedName$typeArgs($args)"
        }

        /** Body of the injected member: `return <member ref>` when the strategy is a class member, else copied locals + the inlined expression. */
        private fun exportBody(call: KtCallExpression, owner: KtDeclaration?, cls: KtClassOrObject): String {
            val isMemberOfCls = owner != null && (owner.parent as? KtClassBody)?.parent === cls
            if (isMemberOfCls) {
                when (owner) {
                    is KtNamedFunction -> owner.name?.takeIf { owner.receiverTypeReference == null }
                        ?.let { return "return ${factoryCall(escapeName(it), owner)}" }
                    is KtProperty -> owner.name?.let { return "return ${escapeName(it)}" }
                    else -> {}
                }
            }
            // Local `val` / inline expression: reproduce what it captures, then return it.
            return "${capturedPrelude(call)}return ${call.text}"
        }

        /** Inline the strategy expression at the top level, wrapping it in a `run { }` that reproduces captured state. */
        private fun inlineWithLocals(call: KtCallExpression): String {
            val prelude = capturedPrelude(call)
            if (prelude.isEmpty()) return call.text
            return "run {\n        ${prelude}${call.text}\n    }"
        }

        /** Insert a synthetic `EXPORT_FN` member (returning [body]'s value) just before [cls]'s closing brace. */
        private fun injectExportMember(file: KtFile, cls: KtClassOrObject, body: String): String? {
            val at = cls.body?.rBrace?.textRange?.startOffset ?: return null
            val member = "\n    public fun `$EXPORT_FN`(): $STRATEGY_TYPE {\n        $body\n    }\n"
            val text = file.text
            return text.substring(0, at) + member + text.substring(at)
        }

        /** The innermost class/object enclosing [call], or null if the strategy is at file top level. */
        private fun enclosingClassOrObject(call: KtCallExpression): KtClassOrObject? =
            PsiTreeUtil.getParentOfType(call, KtClassOrObject::class.java)

        /**
         * The declarations the strategy expression captures from its enclosing function, rendered
         * as a prelude to emit before it so it compiles in isolation. Resolves PSI references
         * (transitively), collecting three kinds local to the enclosing function:
         *  - **local `val`/`var`s** declared before the strategy → copied verbatim;
         *  - **local functions** it calls (e.g. a `node(...)` factory extension) → copied verbatim;
         *  - **local classes/objects** it references (e.g. a `@Serializable` type used as a
         *    `nodeLLMRequestStructured<T>` type argument) → copied verbatim;
         *  - the enclosing function's **parameters** it reads → reproduced as typed stubs
         *    (`val p: T = mockk(relaxed = true)`), since their bodies aren't run while the graph
         *    is built.
         *
         * Class members and companion constants are intentionally excluded — they resolve in the
         * injected member's scope (and an abstract enclosing class is still reached via an
         * anonymous subclass, see [receiverFor]). Must run under a read action, off the EDT.
         * Returns "" when nothing is captured; otherwise ends with a newline + indent.
         */
        private fun capturedPrelude(call: KtCallExpression): String {
            val fn = PsiTreeUtil.getParentOfType(call, KtNamedFunction::class.java) ?: return ""
            val decls = LinkedHashSet<KtDeclaration>() // local vals + local functions + local classes
            val params = LinkedHashSet<KtParameter>()
            val scanned = HashSet<PsiElement>()
            val queue = ArrayDeque<PsiElement>().apply { add(call) }
            while (queue.isNotEmpty()) {
                val scope = queue.removeFirst()
                if (!scanned.add(scope)) continue
                for (ref in PsiTreeUtil.collectElementsOfType(scope, KtNameReferenceExpression::class.java)) {
                    when (val target = ref.reference?.resolve()) {
                        is KtProperty ->
                            if (target.isLocal && PsiTreeUtil.isAncestor(fn, target, true) &&
                                target.textRange.startOffset < call.textRange.startOffset && decls.add(target)
                            ) queue.add(target)
                        is KtNamedFunction ->
                            if (isLocalTo(target, fn) && decls.add(target)) queue.add(target)
                        is KtClassOrObject ->
                            if (PsiTreeUtil.isAncestor(fn, target, true) && target.name != null && decls.add(target)) queue.add(target)
                        is KtParameter ->
                            if (PsiTreeUtil.getParentOfType(target, KtNamedFunction::class.java) === fn) params.add(target)
                        else -> {}
                    }
                }
            }
            val sb = StringBuilder()
            params.forEach { p ->
                val n = p.name ?: return@forEach
                sb.append("val ${escapeName(n)}: ${p.typeReference?.text ?: "Any?"} = ${mockArg(p)}\n        ")
            }
            decls.sortedBy { it.textRange.startOffset }.forEach { sb.append(it.text).append("\n        ") }
            return sb.toString()
        }

        /** Whether [fn0] is a local function declared inside [fn] (not a class/file member). */
        private fun isLocalTo(fn0: KtNamedFunction, fn: KtNamedFunction): Boolean =
            PsiTreeUtil.isAncestor(fn, fn0, true) && fn0.parent !is KtClassBody && fn0.parent !is KtFile

        /**
         * Re-wrap a declaration name in backticks when it isn't a plain Kotlin identifier.
         * PSI returns names unescaped, so a backtick-quoted member — common for test methods
         * like `` `does not capture event` `` — would otherwise emit invalid code in the caller.
         */
        private fun escapeName(name: String): String =
            if (PLAIN_IDENTIFIER.matches(name)) name else "`$name`"

        /**
         * An expression that yields an instance [owner]'s members can be accessed on, or null when
         * one can't be reached statically. Handles arbitrary nesting in **objects**:
         *  - `object` (possibly nested in objects) → its dotted qualified name (`Outer.Inner`);
         *  - `companion object` → the enclosing class's qualified name (companion members are
         *    reached through it);
         *  - a constructable `class` (top level, or nested in objects) → `Outer.Name(mockk(), …)`;
         *  - an **abstract** class → an anonymous subclass `(object : Name(…) { <stub overrides> })`,
         *    so its concrete members (and an injected export) stay reachable as real members
         *    (`::member` references then reflect correctly, unlike a copied local function).
         *
         * Returns null for anything reachable only through a *plain-class instance*, or that we
         * can't safely instantiate (interface, sealed, enum, annotation, generic, inner, private
         * ctor, or abstract members we can't stub). The whole source file is copied in, so the
         * names resolve.
         */
        private fun receiverFor(owner: KtClassOrObject): String? = when (owner) {
            is KtObjectDeclaration -> {
                if (owner.isCompanion()) {
                    // Companion members are reached through the enclosing class's static path.
                    val outer = (owner.parent as? KtClassBody)?.parent as? KtClass ?: return null
                    (enclosingStaticPrefix(outer.parent) ?: return null) + escapeName(outer.name ?: return null)
                } else {
                    (enclosingStaticPrefix(owner.parent) ?: return null) + escapeName(owner.name ?: return null)
                }
            }
            is KtClass -> {
                if (owner.isInterface() || owner.isEnum() || owner.isAnnotation() || owner.isSealed()) return null
                if (owner.hasModifier(KtTokens.INNER_KEYWORD)) return null
                if (owner.typeParameters.isNotEmpty()) return null
                val ctor = owner.primaryConstructor
                if (ctor != null && ctor.hasModifier(KtTokens.PRIVATE_KEYWORD)) return null
                val prefix = enclosingStaticPrefix(owner.parent) ?: return null
                val args = (ctor?.valueParameters ?: emptyList()).joinToString(", ") { mockArg(it) }
                val construct = "$prefix${escapeName(owner.name ?: return null)}($args)"
                if (owner.hasModifier(KtTokens.ABSTRACT_KEYWORD)) {
                    val stubs = abstractMemberStubs(owner) ?: return null
                    "(object : $construct {$stubs})"
                } else construct
            }
            else -> null
        }

        /**
         * Stub overrides for [cls]'s own abstract members, so an anonymous subclass of it compiles.
         * Returns null (so the caller gives up on construction) for anything we can't reproduce
         * confidently: a generic abstract member, or a member with no return type / name. Inherited
         * abstracts (from a superclass/interface) aren't visible here — best-effort.
         */
        private fun abstractMemberStubs(cls: KtClass): String? {
            val abstracts = cls.body?.declarations.orEmpty().filter { it.hasModifier(KtTokens.ABSTRACT_KEYWORD) }
            if (abstracts.isEmpty()) return ""
            val sb = StringBuilder("\n")
            for (m in abstracts) when (m) {
                is KtNamedFunction -> {
                    if (m.typeParameters.isNotEmpty()) return null
                    val name = m.name ?: return null
                    val suspend = if (m.hasModifier(KtTokens.SUSPEND_KEYWORD)) "suspend " else ""
                    val params = m.valueParameters.joinToString(", ") { p ->
                        // Abstract params always have a declared type; "Any?" is a defensive fallback.
                        (if (p.isVarArg) "vararg " else "") + "${p.name}: ${p.typeReference?.text ?: "Any?"}"
                    }
                    val ret = m.typeReference?.text ?: "Unit"
                    sb.append("    override ${suspend}fun ${escapeName(name)}($params): $ret = TODO()\n")
                }
                is KtProperty -> {
                    val name = m.name ?: return null
                    val type = m.typeReference?.text ?: return null
                    sb.append("    override val ${escapeName(name)}: $type get() = TODO()\n")
                }
                else -> return null
            }
            return sb.toString()
        }

        /**
         * The dotted prefix (e.g. `"Outer.Inner."`, or `""` at top level) to reach declarations in
         * [scope] — a [KtClassBody] or [KtFile] — when every enclosing scope is an `object` (so it's
         * reachable statically). Returns null if any enclosing scope is a plain class needing an
         * instance.
         */
        private fun enclosingStaticPrefix(scope: PsiElement?): String? {
            val parts = ArrayList<String>()
            var ctx = scope
            while (ctx != null && ctx !is KtFile) {
                val owner = (ctx as? KtClassBody)?.parent ?: return null
                when {
                    owner is KtObjectDeclaration && !owner.isCompanion() -> {
                        parts.add(escapeName(owner.name ?: return null))
                        ctx = owner.parent
                    }
                    owner is KtObjectDeclaration && owner.isCompanion() -> {
                        // Reach a member of a companion via the enclosing class's name.
                        val outer = (owner.parent as? KtClassBody)?.parent as? KtClass ?: return null
                        parts.add(escapeName(outer.name ?: return null))
                        ctx = outer.parent
                    }
                    else -> return null // a plain class scope — needs an instance we can't synthesize
                }
            }
            return parts.asReversed().joinToString("") { "$it." }
        }

        /**
         * The declaration whose evaluated value *is* [call]: the property it initializes, or the
         * function it is the expression body / return value of. Only such a declaration can be
         * referenced from `main()` to obtain the strategy.
         *
         * Returns null when the call sits elsewhere — a **local** `val`, a lambda, an argument —
         * in which case [callerFor] inlines the expression text instead. (The earlier approach
         * walked up to the *enclosing* member and called it; for a strategy stored in a local
         * `val` of a `@Test fun … = runTest { val s = strategy { … } }` that calls the test
         * method, whose return is `Unit`, and the snippet fails to compile.)
         */
        private fun valueOwnerOf(call: KtCallExpression): KtDeclaration? = when (val parent = call.parent) {
            is KtProperty -> parent.takeIf { it.initializer === call }
            is KtNamedFunction -> parent.takeIf { it.bodyExpression === call }
            // `return strategy(...)` — the value of the function it returns from.
            is KtReturnExpression -> PsiTreeUtil.getParentOfType(parent, KtNamedFunction::class.java)
            else -> null
        }

        /**
         * A value to pass for a synthesized constructor/function argument. mockk can't
         * proxy `String` or the primitive types (it fails with "Can't instantiate proxy
         * for class kotlin.String"), so we hand those a literal default; any nullable
         * parameter just gets `null`. Everything else gets a relaxed mock.
         *
         * The values are never read while building the graph — node bodies don't run — but the
         * strategy *factory* may validate them eagerly (e.g. `require(reasoningInterval > 0)`).
         * We can't satisfy arbitrary preconditions, but `> 0` on counts/intervals/sizes is the
         * common one, so numbers default to `1` (the smallest positive) rather than `0`.
         */
        private fun mockArg(param: KtParameter): String {
            val type = param.typeReference?.text?.trim()
            if (type != null && type.endsWith("?")) return "null"
            // A function-typed parameter (`(T) -> R`, `suspend (T) -> R`): a `{ TODO() }` lambda
            // conforms to any function type (its body is `Nothing`) and, unlike a mockk, is a real
            // callable. It's never invoked while the graph is built.
            if (type != null && type.contains("->")) return "{ TODO() }"
            return when (type?.substringAfterLast('.')?.substringBefore('<')?.trim()) {
                "String", "CharSequence" -> "\"\""
                "Int", "Short", "Byte" -> "1"
                "Long" -> "1L"
                "Double" -> "1.0"
                "Float" -> "1.0f"
                "Boolean" -> "false"
                "Char" -> "' '"
                else -> "io.mockk.mockk(relaxed = true)"
            }
        }

        private fun strategyName(call: KtCallExpression): String? {
            val firstArg = call.valueArguments.firstOrNull()?.getArgumentExpression()?.text ?: return null
            return firstArg.trim().trim('"').ifBlank { null }
        }
    }
}
