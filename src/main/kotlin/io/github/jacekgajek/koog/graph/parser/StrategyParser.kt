package io.github.jacekgajek.koog.graph.parser

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

class StrategyParser {

    fun looksLikeStrategyCall(call: KtCallExpression): Boolean {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return false
        if (callee.getReferencedName() != "strategy") return false
        return call.lambdaArguments.isNotEmpty()
    }

    /**
     * Whether [call] is a strategy we surface for rendering (gutter marker + overview entry).
     * That's a `strategy { }` call we can lift into a standalone runner: bound to a declaration
     * or inlined in a function body. It excludes a strategy passed **directly as a call argument**
     * (e.g. `createAgent(strategy = strategy(...) { })`) — an anonymous inline strategy with no
     * declaration to reference, which we deliberately don't support.
     */
    fun isRenderableStrategyCall(call: KtCallExpression): Boolean =
        looksLikeStrategyCall(call) && !isInlineArgument(call)

    private fun isInlineArgument(call: KtCallExpression): Boolean {
        var node: PsiElement = call
        var parent = node.parent
        while (parent is KtParenthesizedExpression) { node = parent; parent = parent.parent }
        return parent is KtValueArgument
    }

    /**
     * The strategy's declared name (its first string-literal argument), or null if this
     * isn't a strategy call. Cheap: reads only the first argument, no body traversal.
     */
    fun strategyName(call: KtCallExpression): String? {
        if (!looksLikeStrategyCall(call)) return null
        return (call.valueArguments.firstOrNull()?.getArgumentExpression() as? KtStringTemplateExpression)
            ?.entries
            ?.joinToString("") { it.text }
    }

    fun parse(call: KtCallExpression): StrategyGraph? {
        if (!looksLikeStrategyCall(call)) return null

        val typeArgs = call.typeArguments
        val inputType = typeArgs.getOrNull(0)?.typeReference?.text
        val outputType = typeArgs.getOrNull(1)?.typeReference?.text

        val name = strategyName(call) ?: "<unnamed>"

        val body = call.lambdaArguments
            .firstOrNull()
            ?.getLambdaExpression()
            ?.bodyExpression
            ?: return null

        val declared = mutableMapOf<String, Node>()
        val edges = mutableListOf<Edge>()

        body.statements.forEach { stmt ->
            val node = extractNode(stmt)
            if (node != null) {
                declared[node.id] = node
                return@forEach
            }
            extractEdges(stmt, edges)
        }

        // Synthesize references from edges into the node list.
        val all = LinkedHashMap<String, Node>(declared)
        edges.forEach { edge ->
            ensureNode(all, edge.from)
            ensureNode(all, edge.to)
        }

        return StrategyGraph(
            name = name,
            inputType = inputType,
            outputType = outputType,
            nodes = all.values.toList(),
            edges = edges.toList(),
        )
    }

    private fun ensureNode(all: MutableMap<String, Node>, id: String) {
        if (all.containsKey(id)) return
        val kind = when (id) {
            "nodeStart" -> NodeKind.Start
            "nodeFinish" -> NodeKind.Finish
            else -> NodeKind.Unknown
        }
        all[id] = Node(id = id, kind = kind, factory = null, sourceText = null, anchor = null)
    }

    private fun extractNode(stmt: KtExpression): Node? {
        val prop = stmt as? KtProperty ?: return null
        if (!prop.hasDelegateExpression()) return null
        val name = prop.name ?: return null
        val delegate = prop.delegateExpression ?: return null

        val factory = when (delegate) {
            is KtCallExpression -> (delegate.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            is KtNameReferenceExpression -> delegate.getReferencedName()
            else -> null
        }
        val anchor = prop.containingFile.virtualFile?.let { vf ->
            SourceAnchor(vf, prop.textRange.startOffset)
        }
        return Node(
            id = name,
            kind = NodeKind.Declared,
            factory = factory,
            sourceText = delegate.text,
            anchor = anchor,
        )
    }

    private fun extractEdges(stmt: KtExpression, sink: MutableList<Edge>) {
        val call = stmt as? KtCallExpression ?: return
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return
        if (callee.getReferencedName() != "edge") return
        val arg = call.valueArguments.firstOrNull()?.getArgumentExpression() ?: return
        val anchor = call.containingFile.virtualFile?.let { vf ->
            SourceAnchor(vf, call.textRange.startOffset)
        }
        val edge = extractEdge(arg, anchor) ?: return
        sink += edge
    }

    /**
     * Walks a chain like
     *   from forwardTo to onCondition { ... } [onAnotherCondition { ... }]
     * collecting outer infix ops as the condition chain and digging into the
     * innermost `forwardTo` to get the endpoints. The grammar is left-associative,
     * so the structure is:
     *   ((from forwardTo to) onCondition { ... })
     */
    private fun extractEdge(expr: KtExpression, anchor: SourceAnchor?): Edge? {
        var current: KtExpression = expr
        var condition: String? = null
        var conditionExpr: String? = null
        while (current is KtBinaryExpression) {
            val op = current.operationReference.getReferencedName()
            if (op == "forwardTo") {
                val from = nameOf(current.left) ?: return null
                val to = nameOf(current.right) ?: return null
                return Edge(from, to, condition, conditionExpr, anchor)
            }
            // This is a condition wrapping (e.g. `onToolCalls { true }`).
            condition = op
            conditionExpr = (current.right as? KtLambdaExpression)?.text ?: current.right?.text
            current = current.left ?: return null
        }
        return null
    }

    private fun nameOf(expr: KtExpression?): String? =
        (expr as? KtNameReferenceExpression)?.getReferencedName()
}
