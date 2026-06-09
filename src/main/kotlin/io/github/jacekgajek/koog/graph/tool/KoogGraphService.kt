package io.github.jacekgajek.koog.graph.tool

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import io.github.jacekgajek.koog.graph.export.MermaidExporter
import io.github.jacekgajek.koog.graph.ui.MermaidView
import org.jetbrains.kotlin.psi.KtCallExpression
import javax.swing.SwingConstants

@Service(Service.Level.PROJECT)
class KoogGraphService(private val project: Project) : Disposable {

    private val tabs = mutableListOf<GraphTab>()
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** The "click a gutter icon…" hint shown while no graph is open. */
    private var placeholder: Content? = null
    private var contentListenerInstalled = false

    fun showGraph(call: KtCallExpression) {
        val ctx = runReadAction {
            val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(call)
            val file = call.containingFile?.virtualFile
            pointer to file
        }
        val (pointer, file) = ctx

        val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        val cm = tw.contentManager
        ensureContentListener(cm)

        // Reuse the selected tab only if it isn't pinned — pinned tabs (and any
        // other strategy) get their own tab, like search-results windows. Pin/close
        // are the platform's standard tab affordances (canCloseContents in plugin.xml).
        val reuse = reusableTab(cm)
        val target = if (reuse != null) {
            reuse.retarget(pointer, file)
            reuse.content
        } else {
            removePlaceholder(cm)
            val tab = createTab(pointer, file)
            cm.addContent(tab.content)
            tab.render()
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
        pointer: SmartPsiElementPointer<KtCallExpression>,
        file: VirtualFile?,
    ): GraphTab {
        val view = MermaidView()
        val content = ContentFactory.getInstance().createContent(view, "Koog graph", false)
        content.isCloseable = true
        content.isPinnable = true
        val tab = GraphTab(pointer, view, content)
        tab.listenTo(file)
        tabs += tab
        return tab
    }

    /** Coalesce bursts of edits into a single recompile once typing pauses. */
    private fun scheduleRefresh() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest(::refreshAll, REFRESH_DELAY_MS)
    }

    private fun refreshAll() {
        if (project.isDisposed) return
        tabs.toList().forEach { it.render() }
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

    /** Per-tab state: the displayed strategy, its Mermaid view, and an edit listener. */
    private inner class GraphTab(
        private var pointer: SmartPsiElementPointer<KtCallExpression>,
        private val view: MermaidView,
        val content: Content,
    ) {
        private var document: Document? = null
        private val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = scheduleRefresh()
        }

        /** Discards stale async results when a newer render starts. */
        private var generation = 0
        private var hasDiagram = false

        /** Re-target this tab at a different strategy (tab reuse). */
        fun retarget(newPointer: SmartPsiElementPointer<KtCallExpression>, file: VirtualFile?) {
            pointer = newPointer
            hasDiagram = false
            listenTo(file)
            render()
        }

        /**
         * Recompile + run the strategy and show its diagram. The compile/run happens
         * off the EDT. While the source is mid-edit and doesn't compile we keep the
         * last good diagram (only the very first failure, with nothing to preserve,
         * is shown as an error) — so the graph doesn't blank out between valid states.
         */
        fun render() {
            val gen = ++generation
            LOG.info("render: starting generation #$gen (hasDiagram=$hasDiagram)")
            if (!hasDiagram) view.showMessage("Generating diagram…", "")

            // Make PSI reflect the latest keystrokes before we lift the snippet.
            document?.let { doc ->
                val pdm = PsiDocumentManager.getInstance(project)
                if (!pdm.isCommitted(doc)) pdm.commitDocument(doc)
            }

            val prepared = runReadAction {
                val call = pointer.element ?: return@runReadAction null
                if (!call.isValid) return@runReadAction null
                MermaidExporter.prepare(call)
            }
            if (prepared == null) {
                LOG.info("render #$gen: prepare returned null (strategy gone / not in a module) — keeping current view")
                if (!hasDiagram) view.showMessage("Cannot generate diagram", "The strategy is not inside a resolvable module, or the module isn't built yet.")
                return
            }

            ApplicationManager.getApplication().executeOnPooledThread {
                // Guard everything: an exception here must never leave the view stuck on "Generating…".
                val result = try {
                    MermaidExporter.run(prepared)
                } catch (t: Throwable) {
                    LOG.warn("render #$gen: export threw", t)
                    MermaidExporter.ExportResult.Failure("Diagram generation failed", t.toString())
                }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || gen != generation) {
                        LOG.info("render #$gen: result discarded (stale=${gen != generation}, disposed=${project.isDisposed})")
                        return@invokeLater
                    }
                    when (result) {
                        is MermaidExporter.ExportResult.Success -> {
                            LOG.info("render #$gen: success, showing diagram for '${result.name}'")
                            view.showDiagram(result.mermaid)
                            content.displayName = result.name
                            hasDiagram = true
                        }
                        is MermaidExporter.ExportResult.Failure -> {
                            LOG.info("render #$gen: failure '${result.message}' (hasDiagram=$hasDiagram)")
                            if (!hasDiagram) view.showMessage(result.message, result.detail)
                        }
                    }
                }
            }
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
            Disposer.dispose(view)
        }
    }

    companion object {
        private val LOG = logger<KoogGraphService>()
        const val TOOL_WINDOW_ID = "Koog Strategy"
        private const val REFRESH_DELAY_MS = 600
    }
}
