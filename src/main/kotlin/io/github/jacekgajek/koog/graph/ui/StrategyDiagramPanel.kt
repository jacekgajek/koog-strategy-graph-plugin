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
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.jacekgajek.koog.graph.export.MermaidExporter.Problem
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * The tool-window content for one strategy: the Mermaid diagram on top and, when
 * generation reports problems, a Problems-style error table underneath. The last
 * good diagram stays visible while errors are shown.
 */
class StrategyDiagramPanel : JPanel(BorderLayout()), Disposable {

    private val view = MermaidView()
    private val model = ProblemsModel()
    private val table = JBTable(model)
    private val problemsComponent: JComponent
    private val splitter = JBSplitter(true, 0.7f).apply { splitterProportionKey = "koog.graph.diagram.splitter" }

    /** The Mermaid source currently displayed; backs the copy-to-clipboard action. */
    private var currentMermaid: String? = null

    init {
        table.setShowGrid(false)
        table.rowHeight = JBUI.scale(22)
        table.intercellSpacing = JBUI.size(0, 0)
        table.columnModel.getColumn(0).apply {
            maxWidth = JBUI.scale(26); minWidth = JBUI.scale(26)
            cellRenderer = SeverityIconRenderer()
        }

        val header = JBLabel(" Graph problems").apply {
            border = JBUI.Borders.empty(4, 6)
            foreground = JBColor.GRAY
        }
        problemsComponent = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
        }

        splitter.firstComponent = view
        splitter.secondComponent = null // hidden until there are problems
        add(splitter, BorderLayout.CENTER)
        add(buildToolbarStrip(), BorderLayout.NORTH)
    }

    /**
     * Invoked (on the EDT) when the user clicks the detach button. When null the button
     * is hidden — set it only on graphs that can be popped into their own window.
     */
    var onDetach: (() -> Unit)? = null

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
        view.showDiagram(mermaid)
    }

    fun showMessage(title: String, detail: String) = view.showMessage(title, detail)

    /** Show/hide the unobtrusive refresh indicator on the canvas while a render is in flight. */
    fun setRefreshing(refreshing: Boolean) = view.setRefreshing(refreshing)

    /** Highlight (or clear, when all-null) the node/edge matching the editor caret. */
    fun highlight(node: String?, from: String?, to: String?) = view.highlight(node, from, to)

    fun setProblems(problems: List<Problem>) {
        model.set(problems)
        val shouldShow = problems.isNotEmpty()
        val showing = splitter.secondComponent != null
        if (shouldShow && !showing) {
            splitter.secondComponent = problemsComponent
            splitter.proportion = 0.7f
        } else if (!shouldShow && showing) {
            splitter.secondComponent = null
        }
        splitter.revalidate()
        splitter.repaint()
    }

    override fun dispose() = Disposer.dispose(view)

    private class ProblemsModel : AbstractTableModel() {
        private var rows: List<Problem> = emptyList()
        fun set(problems: List<Problem>) {
            rows = problems
            fireTableDataChanged()
        }

        fun problemAt(row: Int): Problem? = rows.getOrNull(row)
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "" else "Description"
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
            if (columnIndex == 0) "" else rows[rowIndex].message
    }

    private class SeverityIconRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column)
            icon = AllIcons.General.Error
            horizontalAlignment = CENTER
            return this
        }
    }

    init {
        // Show each problem's detail as a tooltip on hover.
        table.setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
            ): Component {
                val c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column)
                toolTipText = model.problemAt(row)?.detail ?: model.problemAt(row)?.message
                return c
            }
        })
    }
}
