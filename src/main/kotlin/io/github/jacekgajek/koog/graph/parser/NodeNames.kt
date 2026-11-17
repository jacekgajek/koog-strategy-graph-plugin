package io.github.jacekgajek.koog.graph.parser

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Maps a `val x by node…(…)` declaration to the display name Koog gives the node at runtime,
 * which is what the rendered diagram labels it with (and what a click on it reports back).
 *
 * Must be called under a read action: it resolves references, possibly across files.
 */
/**
 * The compile-time values of a helper function's parameters at one call site, so a
 * `node(name)` inside the helper can be read as `node("<what the caller passed>")`.
 * Keyed by the parameter element (not its name) so lambda parameters never collide.
 * A null value means the caller passed something we couldn't fold to a constant.
 */
private typealias Bindings = Map<KtParameter, String?>

object NodeNames {

    /** How many constant references we follow (`A = B`, `B = C`, …) before giving up. */
    private const val MAX_CONSTANT_DEPTH = 8

    /** How many helper functions we descend through (`gate() = pauseNode(…) = node(…)`). */
    private const val MAX_HELPER_DEPTH = 4

    /**
     * The display name Koog gives a node: the explicit `name`/string argument of its
     * delegate, or — when the delegate calls a factory function — the name of the
     * `node(…)` ultimately built by that helper; otherwise the property name. Helpers
     * are resolved by reference and followed through several hops, with each call's
     * arguments bound to the callee's parameters — so `val gate by gate(emit)` with
     * `gate(emit) = pauseNode(GATE_NAME, emit, …)` and `pauseNode(name, …) = node(name) {…}`
     * resolves to the value of `GATE_NAME`.
     */
    fun displayName(prop: KtProperty): String {
        val name = prop.name ?: ""
        val delegateCall = delegateCallOf(prop) ?: return name
        return nodeNameBuiltBy(delegateCall, emptyMap(), 0) ?: name
    }

    /** The call a delegate ultimately makes: `foo()` directly, or the `foo()` in `Receiver.foo()`. */
    fun delegateCallOf(prop: KtProperty): KtCallExpression? = callOf(prop.delegateExpression)

    /**
     * The name of the node [call] builds, evaluated with the caller's [bindings]:
     * the constant name argument of a library builder (`node`, `nodeLLMRequest`, `subgraph`…),
     * or, for a call to a source helper, the name of the node that helper's result
     * expression builds — with the helper's parameters bound to this call's arguments.
     *
     * Only *source* helpers are descended. Descending into a decompiled library function
     * would force its stub/AST to load — a slow, index-touching operation — and library
     * factories carry no user `node("…")` name anyway.
     */
    private fun nodeNameBuiltBy(call: KtCallExpression, bindings: Bindings, depth: Int): String? {
        val fn = (call.calleeExpression as? KtNameReferenceExpression)
            ?.reference?.resolve() as? KtNamedFunction
        if (fn == null || fn.containingKtFile.isCompiled) return explicitNodeName(call, bindings)
        if (depth >= MAX_HELPER_DEPTH) return explicitNodeName(call, bindings)

        val inner = bindParameters(fn, call, bindings)
        resultCallsOf(fn)
            .firstNotNullOfOrNull { nodeNameBuiltBy(it, inner, depth + 1) }
            ?.let { return it }
        // No named result call: the helper may build the node into a local first
        // (`val n = node("…") {…}; return n`). Take any `node…(…)` with a constant name.
        PsiTreeUtil.findChildrenOfType(fn, KtCallExpression::class.java)
            .firstNotNullOfOrNull { c ->
                val callee = (c.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                if (callee?.startsWith("node") == true) explicitNodeName(c, inner) else null
            }
            ?.let { return it }
        return explicitNodeName(call, bindings)
    }

    /** The calls a helper returns: its expression body, or the operand of each `return`. */
    private fun resultCallsOf(fn: KtNamedFunction): List<KtCallExpression> {
        val body = fn.bodyExpression ?: return emptyList()
        if (!fn.hasBlockBody()) return listOfNotNull(callOf(body))
        return PsiTreeUtil.findChildrenOfType(body, KtReturnExpression::class.java)
            .mapNotNull { callOf(it.returnedExpression) }
    }

    /** The call at the heart of [expr]: `foo()`, `a.foo()`, `(foo())`. */
    private fun callOf(expr: KtExpression?): KtCallExpression? = when (expr) {
        is KtCallExpression -> expr
        is KtDotQualifiedExpression -> callOf(expr.selectorExpression)
        is KtParenthesizedExpression -> callOf(expr.expression)
        else -> null
    }

    /**
     * The constant value of each of [fn]'s parameters as [call] passes it: named
     * arguments by name, positional ones in order, defaults for the rest. Argument
     * expressions live in the caller, so they fold under the caller's [outer] bindings.
     */
    private fun bindParameters(fn: KtNamedFunction, call: KtCallExpression, outer: Bindings): Bindings {
        val params = fn.valueParameters
        val byName = params.associateBy { it.name }
        val named = call.valueArguments.mapNotNull { it.getArgumentName()?.asName?.asString() }.toSet()
        val positional = params.filter { it.name !in named }.iterator()
        val bound = HashMap<KtParameter, String?>()
        call.valueArguments.forEach { arg ->
            val argName = arg.getArgumentName()?.asName?.asString()
            val param = if (argName != null) byName[argName] else positional.takeIf { it.hasNext() }?.next()
            if (param != null) bound[param] = arg.getArgumentExpression()?.let { constantString(it, outer, 0) }
        }
        params.forEach { p ->
            if (p !in bound) bound[p] = p.defaultValue?.let { constantString(it, emptyMap(), 0) }
        }
        return bound
    }

    /**
     * The string passed as `name = …` or as the first positional argument, if it's a
     * compile-time string: a literal, a `const val`/`val` holding one (possibly in a
     * companion or another file), a template over such constants, a `+` of them, or a
     * parameter of the enclosing helper that the caller passed such a value for.
     */
    private fun explicitNodeName(call: KtCallExpression, bindings: Bindings): String? {
        call.valueArguments.forEach { arg ->
            if (arg.getArgumentName()?.asName?.asString() == "name") {
                return arg.getArgumentExpression()?.let { constantString(it, bindings, 0) }
            }
        }
        return call.valueArguments
            .firstOrNull { it.getArgumentName() == null }
            ?.getArgumentExpression()
            ?.let { constantString(it, bindings, 0) }
    }

    /**
     * Folds [expr] to the string it evaluates to at compile time, or null when any part
     * of it isn't a constant we can read (a function call, an unbound parameter, a `var`, …).
     */
    private fun constantString(expr: KtExpression, bindings: Bindings, depth: Int): String? {
        if (depth > MAX_CONSTANT_DEPTH) return null
        return when (expr) {
            is KtStringTemplateExpression -> templateText(expr, bindings, depth)
            is KtParenthesizedExpression -> expr.expression?.let { constantString(it, bindings, depth) }
            is KtNameReferenceExpression -> referencedConstant(expr, bindings, depth)
            // `Companion.NAME`, `Outer.NAME`: fold whatever the selector resolves to.
            is KtDotQualifiedExpression -> expr.selectorExpression?.let { constantString(it, bindings, depth) }
            is KtBinaryExpression ->
                if (expr.operationToken == KtTokens.PLUS) {
                    val l = expr.left?.let { constantString(it, bindings, depth) } ?: return null
                    val r = expr.right?.let { constantString(it, bindings, depth) } ?: return null
                    l + r
                } else null
            else -> null
        }
    }

    private fun templateText(template: KtStringTemplateExpression, bindings: Bindings, depth: Int): String? {
        val sb = StringBuilder()
        template.entries.forEach { entry ->
            when (entry) {
                is KtLiteralStringTemplateEntry -> sb.append(entry.text)
                is KtEscapeStringTemplateEntry -> sb.append(entry.unescapedValue)
                is KtSimpleNameStringTemplateEntry, is KtBlockStringTemplateEntry -> {
                    val inner = entry.expression ?: return null
                    sb.append(constantString(inner, bindings, depth + 1) ?: return null)
                }
                else -> return null
            }
        }
        return sb.toString()
    }

    /**
     * The value of what [ref] names: a helper parameter the caller bound to a constant, or
     * a read-only property initialised with one. Properties in compiled (library) files are
     * skipped: loading their AST is slow, and a node named after a library constant is not
     * a case we need to support.
     */
    private fun referencedConstant(ref: KtNameReferenceExpression, bindings: Bindings, depth: Int): String? {
        return when (val target = ref.reference?.resolve()) {
            is KtParameter -> bindings[target]
            is KtProperty -> {
                if (target.isVar || target.hasDelegateExpression() || target.getter != null) return null
                if (target.containingKtFile.isCompiled) return null
                // A local `val` inside the strategy lambda is fine too, as long as it's constant.
                val init = target.initializer ?: return null
                constantString(init, bindings, depth + 1)
            }
            else -> null
        }
    }
}
