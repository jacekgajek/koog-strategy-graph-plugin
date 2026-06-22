package io.github.jacekgajek.koog.graph.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListSelectionModel

/**
 * The always-visible list of every `strategy { }` found in the project, shown atop the
 * tool window. Selecting a row (single click / arrow keys) previews its graph beneath;
 * double-click, Enter, or the right-click menu opens it in its own tab. UI-only — the
 * model is supplied by [setStrategies].
 */
class StrategyListPanel : JPanel(BorderLayout()) {

    /** A single row: the display name and a project-relative `path:line`. */
    data class Item(val name: String, val location: String)

    private val listModel = DefaultListModel<Item>()
    private val list = JBList(listModel)
    private val header = JBLabel().apply {
        border = JBUI.Borders.empty(4, 8)
        foreground = JBColor.GRAY
    }

    /** Invoked (on the EDT) with the selected row index — drives the preview. */
    var onSelect: (Int) -> Unit = {}

    /** Invoked (on the EDT) with the activated row index — jumps to the source. */
    var onActivate: (Int) -> Unit = {}

    /** Invoked (on the EDT) with a row index from the right-click menu — opens a tab. */
    var onOpenInNewTab: (Int) -> Unit = {}

    /** Invoked (on the EDT) with a row index from the right-click menu — opens a window. */
    var onOpenInNewWindow: (Int) -> Unit = {}

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<Item>() {
            override fun customizeCellRenderer(
                list: JList<out Item>, value: Item, index: Int, selected: Boolean, hasFocus: Boolean,
            ) {
                icon = AllIcons.Toolwindows.ToolWindowHierarchy
                append(value.name)
                append("  ${value.location}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        list.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val index = list.selectedIndex
                if (index >= 0) onSelect(index)
            }
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                activateSelected()
                return true
            }
        }.installOn(list)
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) activateSelected()
            }
        })
        installContextMenu()

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
    }

    private fun installContextMenu() {
        val menu = JPopupMenu()
        menu.add(JMenuItem("Open in New Tab").apply {
            addActionListener { list.selectedIndex.takeIf { it >= 0 }?.let(onOpenInNewTab) }
        })
        menu.add(JMenuItem("Open in New Window").apply {
            addActionListener { list.selectedIndex.takeIf { it >= 0 }?.let(onOpenInNewWindow) }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShow(e)
            override fun mouseReleased(e: MouseEvent) = maybeShow(e)
            private fun maybeShow(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = list.locationToIndex(e.point)
                if (row >= 0) list.selectedIndex = row
                if (list.selectedIndex >= 0) menu.show(list, e.x, e.y)
            }
        })
    }

    private fun activateSelected() {
        val index = list.selectedIndex
        if (index >= 0) onActivate(index)
    }

    /** Show a status line and clear the list (e.g. "Scanning…"). */
    fun setStatus(message: String) {
        listModel.clear()
        header.text = message
    }

    /**
     * Populate the list. Shows a hint when the project has no strategies. Re-selects the
     * row matching [preselect] (a previously selected [Item]) so a re-scan doesn't yank
     * the preview away; falls back to the first row.
     */
    fun setStrategies(items: List<Item>, preselect: Item? = null) {
        listModel.clear()
        items.forEach { listModel.addElement(it) }
        header.text = when (items.size) {
            0 -> "No strategy { } calls found in the project."
            1 -> "1 strategy — click to preview, double-click to jump to source."
            else -> "${items.size} strategies — click to preview, double-click to jump to source."
        }
        if (items.isNotEmpty()) {
            list.selectedIndex = items.indexOf(preselect).takeIf { it >= 0 } ?: 0
        }
    }

    /** The currently selected row, or null. */
    val selectedItem: Item? get() = list.selectedValue
}
