package io.github.jacekgajek.koog.graph.marker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import io.github.jacekgajek.koog.graph.parser.StrategyParser
import io.github.jacekgajek.koog.graph.tool.KoogGraphService
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

class StrategyLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = "Koog strategy graph"
    override fun getIcon() = AllIcons.Toolwindows.ToolWindowHierarchy

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is LeafPsiElement) return null
        if (element.elementType != KtTokens.IDENTIFIER) return null
        if (element.text != "strategy") return null

        val nameRef = element.parent as? KtNameReferenceExpression ?: return null
        val call = nameRef.parent as? KtCallExpression ?: return null
        if (call.calleeExpression !== nameRef) return null
        if (!StrategyParser().isRenderableStrategyCall(call)) return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Toolwindows.ToolWindowHierarchy,
            { "Show Koog strategy graph" },
            { _, _ -> call.project.service<KoogGraphService>().showGraph(call) },
            GutterIconRenderer.Alignment.LEFT,
            { "Show Koog strategy graph" },
        )
    }
}
