package io.github.jacekgajek.koog.graph.tool

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import io.github.jacekgajek.koog.graph.parser.StrategyParser
import io.github.jacekgajek.koog.graph.render.ElkLayout
import io.github.jacekgajek.koog.graph.render.GraphPanel
import io.github.jacekgajek.koog.graph.render.LaidOutGraph
import org.jetbrains.kotlin.psi.KtCallExpression
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

@Service(Service.Level.PROJECT)
class KoogGraphService(private val project: Project) : Disposable {

    private val tabs = mutableListOf<GraphTab>()
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** The "click a gutter icon…" hint shown while no graph is open. */
    private var placeholder: Content? = null
    private var contentListenerInstalled = false

    fun showGraph(call: KtCallExpression) {
        val resolved = runReadAction {
            val graph = StrategyParser().parse(call) ?: return@runReadAction null
            val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(call)
            val file = call.containingFile?.virtualFile
            Triple(graph, pointer, file)
        } ?: return
        val (graph, pointer, file) = resolved
        val laidOut = ElkLayout.layout(graph)

        val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        val cm = tw.contentManager
        ensureContentListener(cm)

        // Reuse the selected tab only if it isn't pinned — pinned tabs (and any
        // other strategy) get their own tab, like search-results windows. Pin/close
        // are the platform's standard tab affordances (canCloseContents in plugin.xml).
        val reuse = reusableTab(cm)
        val target = if (reuse != null) {
            reuse.repoint(graph.name, laidOut, pointer, file)
            reuse.content
        } else {
            removePlaceholder(cm)
            val tab = createTab(graph.name, laidOut, pointer, file)
            cm.addContent(tab.content)
            tab.content
        }
        cm.setSelectedContent(target)

        // show() makes the tool window visible without stealing focus from the editor.
        if (!tw.isVisible) tw.show(null)
    }

    /** The currently selected graph tab, unless it's pinned. */
    private fun reusableTab(cm: ContentManager): GraphTab? {
        val selected = cm.selectedContent ?: return null
        val tab = tabs.firstOrNull { it.content === selected } ?: return null
        return tab.takeUnless { it.content.isPinned }
    }

    private fun createTab(
        name: String,
        laidOut: LaidOutGraph,
        pointer: SmartPsiElementPointer<KtCallExpression>,
        file: VirtualFile?,
    ): GraphTab {
        val wrapper = JPanel(BorderLayout())
        val panel = GraphPanel(laidOut, project)
        wrapper.add(panel, BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(wrapper, name, false)
        content.isCloseable = true
        content.isPinnable = true

        val tab = GraphTab(pointer, panel, wrapper, content)
        tab.listenTo(file)
        tabs += tab
        return tab
    }

    /** Coalesce bursts of edits into a single re-parse once typing pauses. */
    private fun scheduleRefresh() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest(::refreshAll, REFRESH_DELAY_MS)
    }

    private fun refreshAll() {
        if (project.isDisposed) return
        tabs.toList().forEach { it.refresh() }
    }

    private fun ensureContentListener(cm: ContentManager) {
        if (contentListenerInstalled) return
        contentListenerInstalled = true
        cm.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                val tab = tabs.firstOrNull { it.content === event.content } ?: return
                tab.dispose()
                tabs.remove(tab)
                if (tabs.isEmpty() && !project.isDisposed) restorePlaceholder(cm)
            }
        })
    }

    fun installInitialContent(tw: ToolWindow) {
        val cm = tw.contentManager
        ensureContentListener(cm)
        restorePlaceholder(cm)
    }

    private fun restorePlaceholder(cm: ContentManager) {
        if (placeholder != null) return
        val empty = JBLabel(
            "<html><i>Click a <code>strategy { }</code> gutter icon to load its graph.</i></html>",
            SwingConstants.CENTER,
        )
        val content = ContentFactory.getInstance().createContent(empty, "Koog Strategy", false)
        content.isCloseable = false
        placeholder = content
        cm.addContent(content)
    }

    private fun removePlaceholder(cm: ContentManager) {
        val p = placeholder ?: return
        placeholder = null
        cm.removeContent(p, true)
    }

    override fun dispose() {
        tabs.forEach { it.dispose() }
        tabs.clear()
    }

    /** Per-tab state: the displayed strategy, its panel, and an edit listener. */
    private inner class GraphTab(
        private var pointer: SmartPsiElementPointer<KtCallExpression>,
        private var panel: GraphPanel,
        private val wrapper: JPanel,
        val content: Content,
    ) {
        private var document: Document? = null
        private val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = scheduleRefresh()
        }

        /** Re-target this tab at a different strategy (tab reuse). */
        fun repoint(
            name: String,
            laidOut: LaidOutGraph,
            newPointer: SmartPsiElementPointer<KtCallExpression>,
            file: VirtualFile?,
        ) {
            pointer = newPointer
            panel = GraphPanel(laidOut, project)
            wrapper.removeAll()
            wrapper.add(panel, BorderLayout.CENTER)
            wrapper.revalidate()
            wrapper.repaint()
            content.displayName = name
            listenTo(file)
        }

        /**
         * Re-parse and swap in the new graph. While the source is mid-edit and
         * doesn't parse (syntax errors in the call, the call was deleted, or the
         * parser bails) we keep the previous graph so it doesn't flicker or blank
         * out between valid states.
         */
        fun refresh() {
            document?.let { doc ->
                val pdm = PsiDocumentManager.getInstance(project)
                if (!pdm.isCommitted(doc)) pdm.commitDocument(doc)
            }
            val graph = runReadAction {
                val call = pointer.element ?: return@runReadAction null
                if (!call.isValid) return@runReadAction null
                if (PsiTreeUtil.hasErrorElements(call)) return@runReadAction null
                StrategyParser().parse(call)
            } ?: return
            panel.updateGraph(ElkLayout.layout(graph))
            if (content.displayName != graph.name) content.displayName = graph.name
        }

        fun listenTo(file: VirtualFile?) {
            val newDoc = file?.let { FileDocumentManager.getInstance().getDocument(it) }
            if (newDoc === document) return
            document?.removeDocumentListener(listener)
            document = newDoc
            newDoc?.addDocumentListener(listener)
        }

        fun dispose() {
            document?.removeDocumentListener(listener)
            document = null
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "Koog Strategy"
        private const val REFRESH_DELAY_MS = 300
    }
}
