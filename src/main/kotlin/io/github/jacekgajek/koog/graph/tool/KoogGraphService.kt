package io.github.jacekgajek.koog.graph.tool

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerTopics
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.FrameWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import com.intellij.ui.JBSplitter
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.jacekgajek.koog.graph.export.MermaidExporter
import io.github.jacekgajek.koog.graph.index.StrategyIndex
import io.github.jacekgajek.koog.graph.ui.StrategyDiagramPanel
import io.github.jacekgajek.koog.graph.ui.StrategyListPanel
import org.jetbrains.kotlin.psi.KtCallExpression

@Service(Service.Level.PROJECT)
class KoogGraphService(private val project: Project) : Disposable {

    private val tabs = mutableListOf<GraphTab>()
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /**
     * The permanent overview content: the strategy list docked on top, a shared preview
     * graph beneath. Selecting a row previews it here; double-click opens a dedicated tab.
     */
    private var overview: Content? = null
    private val overviewPanel = StrategyListPanel().apply {
        onSelect = ::previewStrategyAt
        onActivate = ::navigateToStrategyAt
        onOpenInNewTab = ::openStrategyAt
        onOpenInNewWindow = ::openStrategyInWindowAt
    }

    /** Graphs detached into their own floating windows; refreshed alongside the tabs. */
    private val detached = mutableListOf<GraphTab>()

    /** The graph shown beneath the list; retargeted as the selection changes. */
    private val previewPanel = StrategyDiagramPanel()
    private var previewTab: GraphTab? = null

    /** Backs [overviewPanel]'s rows; parallel to its list indices. EDT only. */
    private var overviewStrategies: List<StrategyIndex.FoundStrategy> = emptyList()

    /** Discards stale overview scans when a newer one starts. */
    private var overviewGeneration = 0
    private var contentListenerInstalled = false

    init {
        // A finished build re-creates the module output the diagrams are compiled against,
        // so any open graph (and the overview) may now render differently — refresh.
        project.messageBus.connect(this).subscribe(
            CompilerTopics.COMPILATION_STATUS,
            object : CompilationStatusListener {
                override fun compilationFinished(aborted: Boolean, errors: Int, warnings: Int, context: CompileContext) {
                    if (aborted || project.isDisposed) return
                    ApplicationManager.getApplication().invokeLater({
                        if (!project.isDisposed) refresh()
                    }, ModalityState.any()) { project.isDisposed }
                }
            },
        )
    }

    /**
     * Memoizes outcomes by the strategy expression's own text. The strategy fully
     * determines the diagram, so an unchanged strategy re-renders instantly (no
     * compile/run) even after the per-tab key is evicted. LRU-bounded; EDT only.
     */
    private val cache = object : LinkedHashMap<String, MermaidExporter.ExportOutcome>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, MermaidExporter.ExportOutcome>) = size > CACHE_SIZE
    }

    fun showGraph(call: KtCallExpression) {
        val ctx = runReadActionBlocking {
            val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(call)
            val file = call.containingFile.virtualFile
            pointer to file
        }
        showGraph(ctx.first, ctx.second)
    }

    private fun showGraph(pointer: SmartPsiElementPointer<KtCallExpression>, file: VirtualFile?) {
        val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        val cm = tw.contentManager
        ensureContentListener(cm)
        ensureOverview(cm)

        // Each strategy gets its own tab; the overview list stays open alongside.
        // Opening a strategy that already has a tab re-selects it rather than duplicating.
        val existing = runReadActionBlocking {
            val target = pointer.element ?: return@runReadActionBlocking null
            tabs.firstOrNull { it.pointsTo(target) }
        }
        val target = if (existing != null) {
            existing.content
        } else {
            val tab = createTab(pointer, file)
            tab.content?.let { cm.addContent(it) }
            tab.render()
            tab.content
        }
        target?.let { cm.setSelectedContent(it) }

        // show() makes the tool window visible without stealing focus from the editor.
        if (!tw.isVisible) tw.show(null)
    }

    private fun createTab(
        pointer: SmartPsiElementPointer<KtCallExpression>,
        file: VirtualFile?,
    ): GraphTab {
        val view = StrategyDiagramPanel()
        val content = ContentFactory.getInstance().createContent(view, "Koog Graph", false)
        content.isCloseable = true
        val tab = GraphTab(pointer, view, content)
        view.onNodeClick = tab::navigateToNode
        view.onEdgeClick = tab::navigateToEdge
        view.onDetach = { detachTab(pointer, file, content) }
        tab.listenTo(file)
        tabs += tab
        return tab
    }

    /** Move a pinned tab into its own floating window, then close the tab. */
    private fun detachTab(
        pointer: SmartPsiElementPointer<KtCallExpression>,
        file: VirtualFile?,
        content: Content,
    ) {
        openDetachedWindow(pointer, file)
        content.manager?.removeContent(content, true)
    }

    /** Open a standalone, floating window rendering the given strategy. */
    private fun openDetachedWindow(pointer: SmartPsiElementPointer<KtCallExpression>, file: VirtualFile?) {
        val view = StrategyDiagramPanel()
        val tab = GraphTab(pointer, view, null)
        view.onNodeClick = tab::navigateToNode
        view.onEdgeClick = tab::navigateToEdge
        detached += tab

        val frame = FrameWrapper(project, "koog.graph.detached.window")
        frame.title = "Koog Graph"
        frame.component = view
        tab.onName = { frame.title = it }
        // Tie the window's lifetime to the service, and clean up our state when it closes.
        Disposer.register(this, frame)
        Disposer.register(frame, Disposable {
            detached.remove(tab)
            tab.dispose()
        })

        tab.listenTo(file)
        frame.show()
        tab.render()
    }

    /** Coalesce bursts of edits into a single recompile once typing pauses. */
    private fun scheduleRefresh() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest(::refreshAll, REFRESH_DELAY_MS)
    }

    private fun refreshAll() {
        if (project.isDisposed) return
        previewTab?.render()
        tabs.toList().forEach { it.render() }
        detached.toList().forEach { it.render() }
    }

    /**
     * Re-generate everything currently shown: drop the cache so open graphs recompile
     * from scratch, and re-scan the project. Backs the tool-window refresh button and
     * the post-build auto-refresh.
     */
    fun refresh() {
        if (project.isDisposed) return
        cache.clear()
        previewTab?.forceRender()
        tabs.toList().forEach { it.forceRender() }
        detached.toList().forEach { it.forceRender() }
        if (overview != null) scanOverview()
    }

    private fun ensureContentListener(cm: ContentManager) {
        if (contentListenerInstalled) return
        contentListenerInstalled = true
        cm.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                val tab = tabs.firstOrNull { it.content === event.content } ?: return
                tab.dispose()
                tabs.remove(tab)
            }
        })
    }

    fun installInitialContent(tw: ToolWindow) {
        val cm = tw.contentManager
        ensureContentListener(cm)
        tw.setTitleActions(listOf(RefreshAction()))
        ensureOverview(cm)
        scanOverview()
    }

    /** The strategy-list overview is permanent: created once, never removed. */
    private fun ensureOverview(cm: ContentManager) {
        if (overview != null) return

        val preview = GraphTab(null, previewPanel, null)
        previewPanel.onNodeClick = preview::navigateToNode
        previewPanel.onEdgeClick = preview::navigateToEdge
        previewPanel.showMessage("No strategy selected", "Pick a strategy from the list above to preview its graph.")
        previewTab = preview

        val splitter = JBSplitter(true, 0.3f).apply { splitterProportionKey = "koog.graph.overview.splitter" }
        splitter.firstComponent = overviewPanel
        splitter.secondComponent = previewPanel

        val content = ContentFactory.getInstance().createContent(splitter, "All Strategies", false)
        content.isCloseable = false
        overview = content
        cm.addContent(content)
    }

    /** Scan the project for strategies (index-backed, off the EDT) and fill the overview. */
    private fun scanOverview() {
        val gen = ++overviewGeneration
        val previouslySelected = overviewPanel.selectedItem
        overviewPanel.setStatus("Scanning project for strategies…")
        ReadAction.nonBlocking<List<StrategyIndex.FoundStrategy>> { StrategyIndex.findAll(project) }
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { found ->
                if (project.isDisposed || gen != overviewGeneration) return@finishOnUiThread
                overviewStrategies = found
                overviewPanel.setStrategies(found.map { StrategyListPanel.Item(it.name, it.location) }, previouslySelected)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** Preview the strategy at [index] in the shared graph beneath the list. */
    private fun previewStrategyAt(index: Int) {
        val found = overviewStrategies.getOrNull(index) ?: return
        val stillValid = runReadActionBlocking { found.pointer.element != null }
        if (!stillValid) {
            scanOverview()
            return
        }
        previewTab?.retarget(found.pointer, found.file)
    }

    /** Jump to the `strategy(...)` call at [index] in the editor. */
    private fun navigateToStrategyAt(index: Int) {
        val found = overviewStrategies.getOrNull(index) ?: return
        val target = runReadActionBlocking {
            val call = found.pointer.element ?: return@runReadActionBlocking null
            val vf = call.containingFile?.virtualFile ?: return@runReadActionBlocking null
            vf to call.textOffset
        }
        if (target == null) {
            // The strategy moved or the file changed since the scan — re-scan and bail.
            scanOverview()
            return
        }
        OpenFileDescriptor(project, target.first, target.second).navigate(true)
    }

    /** Open the graph for the strategy at [index] in the overview list. */
    private fun openStrategyAt(index: Int) {
        val found = overviewStrategies.getOrNull(index) ?: return
        // pointer.element walks PSI and needs a read action; never touch it bare on the EDT.
        val stillValid = runReadActionBlocking { found.pointer.element != null }
        if (!stillValid) {
            // The strategy moved or the file changed since the scan — re-scan and bail.
            scanOverview()
            return
        }
        showGraph(found.pointer, found.file)
    }

    /** Open the strategy at [index] directly in a detached floating window. */
    private fun openStrategyInWindowAt(index: Int) {
        val found = overviewStrategies.getOrNull(index) ?: return
        val stillValid = runReadActionBlocking { found.pointer.element != null }
        if (!stillValid) {
            scanOverview()
            return
        }
        openDetachedWindow(found.pointer, found.file)
    }

    private inner class RefreshAction : com.intellij.openapi.project.DumbAwareAction(
        "Refresh", "Re-generate the strategy graphs", com.intellij.icons.AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = refresh()
    }

    override fun dispose() {
        tabs.forEach { it.dispose() }
        tabs.clear()
        previewTab?.dispose()
        previewTab = null
    }

    /**
     * Renders one strategy into a [StrategyDiagramPanel] and tracks edits to its file.
     * Backs both a dedicated pinned tab (with its own [content]) and the shared preview
     * beneath the list (no content, retargeted as the selection changes).
     */
    private inner class GraphTab(
        private var pointer: SmartPsiElementPointer<KtCallExpression>?,
        private val view: StrategyDiagramPanel,
        val content: Content?,
    ) {
        private var document: Document? = null
        private var disposed = false
        private val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = scheduleRefresh()
        }

        /** Called with the strategy name once it renders; used to title a detached window. */
        var onName: ((String) -> Unit)? = null

        /** Discards stale async results when a newer render starts. */
        private var generation = 0
        private var hasDiagram = false

        /** Text of the strategy expression behind the current diagram; null until one renders. */
        private var lastKey: String? = null

        /** Point this (preview) tab at a different strategy and re-render from scratch. */
        fun retarget(newPointer: SmartPsiElementPointer<KtCallExpression>, file: VirtualFile?) {
            pointer = newPointer
            hasDiagram = false
            lastKey = null
            listenTo(file)
            render()
        }

        /**
         * Recompile unconditionally, even if the strategy text is unchanged. The cache is
         * cleared by the caller; clearing [lastKey] defeats the unchanged-strategy
         * short-circuit so a refresh (e.g. after a build) always re-runs the generator.
         */
        fun forceRender() {
            lastKey = null
            render()
        }

        /** Whether this tab's strategy is [element]. Caller must hold a read action. */
        fun pointsTo(element: PsiElement): Boolean = pointer?.element === element

        /**
         * Recompile + run the strategy and show its diagram. The diagram is keyed on the
         * strategy expression's own text, so edits elsewhere in the file are ignored: an
         * unchanged strategy short-circuits before any compile. Unchanged-but-evicted
         * strategies are served from [cache]. The compile/run happens off the EDT. When
         * the code doesn't compile or the graph is invalid we keep the last good diagram
         * and surface the problems in the table — so the graph never blanks out between
         * valid states.
         */
        fun render() {
            val ptr = pointer ?: return // preview before any selection
            val gen = ++generation
            LOG.info("render: starting generation #$gen (hasDiagram=$hasDiagram)")

            // Make PSI reflect the latest keystrokes before we read the strategy text.
            document?.let { doc ->
                val pdm = PsiDocumentManager.getInstance(project)
                if (!pdm.isCommitted(doc)) pdm.commitDocument(doc)
            }

            val key = runReadActionBlocking {
                val call = ptr.element ?: return@runReadActionBlocking null
                if (!call.isValid) return@runReadActionBlocking null
                call.text
            }
            if (key == null) {
                LOG.info("render #$gen: strategy gone / invalid — keeping current view")
                if (!hasDiagram) view.showMessage("Cannot generate diagram", "The strategy is not inside a resolvable module, or the module isn't built yet.")
                return
            }

            // The strategy itself hasn't changed (only the rest of the file) — nothing to do.
            if (key == lastKey && hasDiagram) {
                LOG.info("render #$gen: strategy unchanged — skipping")
                return
            }

            cache[key]?.let { cached ->
                LOG.info("render #$gen: cache hit for '${cached.name}'")
                apply(cached, key)
                return
            }

            val prepared = runReadActionBlocking {
                val call = ptr.element ?: return@runReadActionBlocking null
                if (!call.isValid) return@runReadActionBlocking null
                MermaidExporter.prepare(call)
            }
            if (prepared == null) {
                LOG.info("render #$gen: prepare returned null (strategy gone / not in a module) — keeping current view")
                if (!hasDiagram) view.showMessage("Cannot generate diagram", "The strategy is not inside a resolvable module, or the module isn't built yet.")
                return
            }

            if (!hasDiagram) view.showMessage("Generating diagram…", "")
            ApplicationManager.getApplication().executeOnPooledThread {
                // Guard everything: an exception here must never leave the view stuck on "Generating…".
                val outcome = try {
                    MermaidExporter.run(prepared)
                } catch (t: Throwable) {
                    LOG.warn("render #$gen: export threw", t)
                    MermaidExporter.ExportOutcome(prepared.name, null, listOf(MermaidExporter.Problem("Diagram generation failed", t.toString())), cacheable = false)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || gen != generation) {
                        LOG.info("render #$gen: result discarded (stale=${gen != generation}, disposed=${project.isDisposed})")
                        return@invokeLater
                    }
                    if (outcome.cacheable) cache[key] = outcome
                    apply(outcome, key)
                }
            }
        }

        /** Apply an outcome to the view: update the diagram if present, always set problems. */
        private fun apply(outcome: MermaidExporter.ExportOutcome, key: String) {
            if (outcome.mermaid != null) {
                view.showDiagram(outcome.mermaid)
                content?.displayName = outcome.name
                onName?.invoke(outcome.name)
                hasDiagram = true
                lastKey = key
                view.setProblems(emptyList())
            } else {
                if (!hasDiagram) {
                    val first = outcome.problems.firstOrNull()
                    view.showMessage(first?.message ?: "No diagram", first?.detail ?: "")
                }
                view.setProblems(outcome.problems)
            }
        }

        /**
         * Navigate from a clicked diagram node to its `val <id> by …` declaration.
         * The Mermaid node id equals the property name, so we find the matching
         * KtProperty inside the strategy call and jump to it.
         */
        fun navigateToNode(id: String) {
            val target = runReadActionBlocking {
                val call = pointer?.element ?: return@runReadActionBlocking null
                val prop = PsiTreeUtil.findChildrenOfType(call, KtProperty::class.java)
                    .firstOrNull { it.name == id } ?: return@runReadActionBlocking null
                val vf = prop.containingFile.virtualFile ?: return@runReadActionBlocking null
                vf to prop.textOffset
            }
            if (target == null) {
                LOG.info("navigateToNode: no declaration named '$id' in the strategy")
                return
            }
            OpenFileDescriptor(project, target.first, target.second).navigate(true)
        }

        /**
         * Navigate from a clicked edge to its `edge(from forwardTo to …)` definition.
         * Koog edges are written with the `forwardTo` infix, so we look for the
         * `from forwardTo to` expression whose operands name the clicked endpoints.
         */
        fun navigateToEdge(from: String, to: String) {
            val target = runReadActionBlocking {
                val call = pointer?.element ?: return@runReadActionBlocking null
                val binary = PsiTreeUtil.findChildrenOfType(call, KtBinaryExpression::class.java)
                    .firstOrNull {
                        it.operationReference.getReferencedName() == "forwardTo" &&
                            leadingName(it.left) == from && leadingName(it.right) == to
                    } ?: return@runReadActionBlocking null
                val vf = binary.containingFile?.virtualFile ?: return@runReadActionBlocking null
                vf to binary.textOffset
            }
            if (target == null) {
                LOG.info("navigateToEdge: no 'edge($from forwardTo $to)' found in the strategy")
                return
            }
            OpenFileDescriptor(project, target.first, target.second).navigate(true)
        }

        /** The first (receiver-most) referenced name within an expression, or null. */
        private fun leadingName(expr: KtExpression?): String? = when (expr) {
            null -> null
            is KtNameReferenceExpression -> expr.getReferencedName()
            else -> PsiTreeUtil.findChildOfType(expr, KtNameReferenceExpression::class.java)?.getReferencedName()
        }

        fun listenTo(file: VirtualFile?) {
            // getDocument walks the file model and needs a read action; we're on the EDT.
            val newDoc = file?.let { f -> runReadActionBlocking { FileDocumentManager.getInstance().getDocument(f) } }
            if (newDoc === document) return
            document?.removeDocumentListener(listener)
            document = newDoc
            newDoc?.addDocumentListener(listener)
        }

        fun dispose() {
            if (disposed) return
            disposed = true
            document?.removeDocumentListener(listener)
            document = null
            Disposer.dispose(view)
        }
    }

    companion object {
        private val LOG = logger<KoogGraphService>()
        const val TOOL_WINDOW_ID = "Koog Strategy"

        // Each edit triggers a recompile; wait longer than a single keystroke burst.
        private const val REFRESH_DELAY_MS = 1500
        private const val CACHE_SIZE = 64
    }
}
