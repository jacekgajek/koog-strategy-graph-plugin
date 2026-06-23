package io.github.jacekgajek.koog.graph.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The tool-window content for one strategy: the Mermaid diagram in the canvas, plus error
 * reporting that never obstructs a good diagram. While a diagram is on screen, errors are
 * shown in a thin strip along the bottom (a compile-error "rebuild & refresh" hint, or a
 * thrown graph error's message); the last good diagram stays put, so live edits don't flip
 * the canvas back and forth between the graph and an error. Only when there is no diagram to
 * keep does the error take over the whole canvas.
 */
class StrategyDiagramPanel : JPanel(BorderLayout()), Disposable {

    private val view = MermaidView()

    /** The Mermaid source currently displayed; backs the copy-to-clipboard action. */
    private var currentMermaid: String? = null

    // --- Bottom error strip: shown only when a diagram is already on the canvas. ---
    private val bannerCards = CardLayout()
    private val bannerCenter = JPanel(bannerCards)
    private val bannerMessage = JBLabel()
    private val banner = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
            JBUI.Borders.empty(5, 8),
        )
        add(JBLabel(AllIcons.General.Error).apply { border = JBUI.Borders.emptyRight(6) }, BorderLayout.WEST)
        add(bannerCenter, BorderLayout.CENTER)
        isVisible = false
    }

    init {
        val compileRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(JBLabel("There are compile errors. Rebuild your project and "))
            add(ActionLink("refresh") { onRefresh() })
            add(JBLabel("."))
        }
        bannerCenter.add(compileRow, CARD_COMPILE)
        bannerCenter.add(bannerMessage, CARD_MESSAGE)

        add(view, BorderLayout.CENTER)
        add(buildToolbarStrip(), BorderLayout.NORTH)
        add(banner, BorderLayout.SOUTH)
    }

    /**
     * Invoked (on the EDT) when the user clicks the detach button. When null the button
     * is hidden — set it only on graphs that can be popped into their own window.
     */
    var onDetach: (() -> Unit)? = null

    /**
     * Invoked (on the EDT) when the user clicks a "refresh" link shown for a compile error
     * (in the bottom strip or the full-canvas notice). Wired by the owner to the same action
     * as the tool-window refresh button.
     */
    var onRefresh: () -> Unit
        get() = view.onRefresh
        set(value) { view.onRefresh = value }

    /** A thin top strip carrying the right-aligned detach + "copy Mermaid code" buttons. */
    private fun buildToolbarStrip(): JComponent {
        val group = DefaultActionGroup(DetachAction(), CopyMermaidAction())
        val toolbar = ActionManager.getInstance().createActionToolbar("KoogStrategyDiagram", group, true)
        toolbar.targetComponent = this
        return JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.EAST) }
    }

    private inner class DetachAction : AnAction(
        "Detach to Window", "Move this graph into a separate, floating window", AllIcons.Actions.MoveToWindow,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            onDetach?.invoke()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = onDetach != null
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class CopyMermaidAction : AnAction(
        "Copy Mermaid Code", "Copy the Mermaid diagram source to the clipboard", AllIcons.Actions.Copy,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            currentMermaid?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentMermaid != null
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** Called (on the EDT) with a node/subgraph id when the user clicks it. */
    var onNodeClick: (String) -> Unit
        get() = view.onNodeClick
        set(value) { view.onNodeClick = value }

    /** Called (on the EDT) with the from/to node ids when the user clicks an edge. */
    var onEdgeClick: (from: String, to: String) -> Unit
        get() = view.onEdgeClick
        set(value) { view.onEdgeClick = value }

    fun showDiagram(mermaid: String) {
        currentMermaid = mermaid
        hideBanner()
        view.showDiagram(mermaid)
    }

    fun showMessage(title: String, detail: String) {
        hideBanner()
        view.showMessage(title, detail)
    }

    /**
     * Take over the whole canvas with the "There are compile errors. Rebuild your project and
     * refresh." notice ("refresh" is a link). Used when there's no diagram to keep on screen;
     * shown instead of the raw kotlinc diagnostics, whose temp-dir paths look like a plugin bug.
     */
    fun showCompileError() {
        hideBanner()
        view.showCompileError()
    }

    /** Show the compile-error hint in the bottom strip, leaving the current diagram on screen. */
    fun showCompileErrorBanner() {
        bannerCards.show(bannerCenter, CARD_COMPILE)
        showBanner()
    }

    /** Show a graph/logical error's [message] in the bottom strip, keeping the diagram on screen. */
    fun showErrorBanner(message: String) {
        bannerMessage.text = "<html>${StringUtil.escapeXmlEntities(message)}</html>"
        bannerMessage.toolTipText = message
        bannerCards.show(bannerCenter, CARD_MESSAGE)
        showBanner()
    }

    private fun showBanner() {
        banner.isVisible = true
        banner.revalidate()
        banner.repaint()
    }

    private fun hideBanner() {
        if (!banner.isVisible) return
        banner.isVisible = false
        banner.revalidate()
        banner.repaint()
    }

    /** Show/hide the unobtrusive refresh indicator on the canvas while a render is in flight. */
    fun setRefreshing(refreshing: Boolean) = view.setRefreshing(refreshing)

    /** Highlight (or clear, when all-null) the node/edge matching the editor caret. */
    fun highlight(node: String?, from: String?, to: String?) = view.highlight(node, from, to)

    override fun dispose() = Disposer.dispose(view)

    private companion object {
        const val CARD_COMPILE = "compile"
        const val CARD_MESSAGE = "message"
    }
}
