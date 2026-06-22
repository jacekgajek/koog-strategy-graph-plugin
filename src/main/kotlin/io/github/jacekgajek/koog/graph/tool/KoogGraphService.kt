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
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.FrameWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBSplitter
import com.intellij.ui.content.*
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.jacekgajek.koog.graph.export.MermaidExporter
import io.github.jacekgajek.koog.graph.index.StrategyIndex
import io.github.jacekgajek.koog.graph.tool.KoogGraphService.Companion.canonicalKey
import io.github.jacekgajek.koog.graph.ui.StrategyDiagramPanel
import io.github.jacekgajek.koog.graph.ui.StrategyListPanel
import org.jetbrains.kotlin.psi.*
import java.util.concurrent.Callable

/**
 * Mirrors Koog's `MermaidDiagramGenerator`: a node's diagram id is its display name with
 * every non-`[a-zA-Z0-9_]` character replaced by `_`. We sanitize the same way to match a
 * clicked edge endpoint back to its source declaration.
 */
private val MERMAID_ID_REGEX = Regex("[^a-zA-Z0-9_]")
private fun String.toMermaidId(): String = replace(MERMAID_ID_REGEX, "_")

/** A caret-driven highlight request: a node (by display name) or an edge (by from/to ids). */
private data class Highlight(val node: String?, val from: String?, val to: String?)

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

        // Reverse navigation: as the caret moves over a node/edge declaration, highlight
        // the matching part in every open graph showing that strategy.
        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = onCaretMoved(event)
        }, this)
    }

    private fun onCaretMoved(event: CaretEvent) {
        if (project.isDisposed) return
        val editor = event.editor
        if (editor.project != null && editor.project !== project) return
        val openTabs = listOfNotNull(previewTab) + tabs + detached
        if (openTabs.isEmpty()) return
        val vf = FileDocumentManager.getInstance().getFile(editor.document)
        val offset = editor.caretModel.offset
        openTabs.forEach { it.highlightAtCaret(vf, offset) }
    }

    /**
     * Memoizes outcomes by the [canonicalKey] of the strategy expression together with its
     * containing file (whitespace and comments stripped) — the file is included because the
     * diagram can depend on same-file helper functions the strategy references. An unchanged
     * strategy/file — or one that was only reformatted — re-renders instantly (no compile/run)
     * even after the per-tab key is evicted. LRU-bounded; EDT only.
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
        navigate(target.first, target.second)
    }

    /**
     * Open [vf] and put the caret at [offset], scrolling only enough to bring it into view.
     * Unlike [OpenFileDescriptor.navigate], this never re-centers an already-visible line, so
     * navigating to a node/edge whose declaration is already on screen doesn't jolt the editor.
     */
    private fun navigate(vf: VirtualFile, offset: Int) {
        val editor = FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, vf), true) ?: return
        editor.caretModel.moveToOffset(offset)
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
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

        /** The file this tab's strategy lives in; lets caret events skip unrelated files cheaply. */
        private var currentFile: VirtualFile? = null

        /** Last highlight pushed to the view; avoids re-sending JS on every caret tick. */
        private var lastHighlight: Highlight? = null

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
            // New strategy: drop any highlight from the previous one.
            lastHighlight = null
            view.highlight(null, null, null)
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
                // The diagram also depends on same-file helpers the strategy references
                // (e.g. `node("…")` factory functions), so key on the whole containing file,
                // not just the strategy expression — otherwise editing a helper's node name
                // wouldn't refresh. The expression text disambiguates sibling strategies in
                // the same file (their file text is identical) so they don't collide in the
                // shared cache. Both parts are canonicalized, so whitespace/comment-only
                // edits still short-circuit.
                canonicalKey(call) + " " + canonicalKey(call.containingFile)
            }
            if (key == null) {
                LOG.info("render #$gen: strategy gone / invalid — keeping current view")
                if (!hasDiagram) view.showMessage("Cannot generate diagram", "The strategy is not inside a resolvable module, or the module isn't built yet.")
                return
            }

            // Nothing meaningful changed in the strategy or its file — nothing to do.
            if (key == lastKey && hasDiagram) {
                LOG.info("render #$gen: strategy unchanged — skipping")
                return
            }

            cache[key]?.let { cached ->
                LOG.info("render #$gen: cache hit for '${cached.name}'")
                apply(cached, key)
                return
            }

            if (!hasDiagram) view.showMessage("Generating diagram…", "")
            view.setRefreshing(true)
            ApplicationManager.getApplication().executeOnPooledThread {
                // prepare() walks PSI and resolves cross-file references, which can be slow;
                // run it here under a background read action rather than on the EDT (where a
                // blocking read action would freeze the UI).
                val prepared = try {
                    runReadActionBlocking {
                        val call = ptr.element ?: return@runReadActionBlocking null
                        if (!call.isValid) return@runReadActionBlocking null
                        MermaidExporter.prepare(call)
                    }
                } catch (t: Throwable) {
                    LOG.warn("render #$gen: prepare threw", t)
                    null
                }
                // Guard everything: an exception here must never leave the view stuck on "Generating…".
                val outcome = when {
                    prepared == null -> {
                        LOG.info("render #$gen: prepare returned null (strategy gone / not in a module) — keeping current view")
                        null
                    }
                    else -> try {
                        MermaidExporter.run(prepared)
                    } catch (t: Throwable) {
                        LOG.warn("render #$gen: export threw", t)
                        MermaidExporter.ExportOutcome(prepared.name, null, listOf(MermaidExporter.Problem("Diagram generation failed", t.toString())), cacheable = false)
                    }
                }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || gen != generation) {
                        // A newer render is in flight (or we're disposed); it owns the
                        // indicator now, so leave it to clear when it finishes.
                        LOG.info("render #$gen: result discarded (stale=${gen != generation}, disposed=${project.isDisposed})")
                        return@invokeLater
                    }
                    view.setRefreshing(false)
                    if (outcome == null) {
                        if (!hasDiagram) view.showMessage("Cannot generate diagram", "The strategy is not inside a resolvable module, or the module isn't built yet.")
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
         * Navigate from a clicked diagram node to its `val <name> by …` declaration.
         * The clicked id is the node's *display name* (Koog labels nodes with the
         * `name = …` argument, falling back to the property name), which usually differs
         * from the Kotlin variable — so we map it back to the declaring property.
         */
        fun navigateToNode(id: String) {
            // Off the EDT: resolveNodeProperty resolves (possibly cross-file) helper
            // references, which can touch indexes — illegal on the EDT.
            ReadAction.nonBlocking(Callable {
                val call = pointer?.element ?: return@Callable null
                val prop = resolveNodeProperty(call, id) ?: return@Callable null
                val vf = prop.containingFile.virtualFile ?: return@Callable null
                vf to prop.textOffset
            })
                .expireWith(view)
                .finishOnUiThread(ModalityState.defaultModalityState()) { target ->
                    if (target == null) LOG.info("navigateToNode: no declaration for node '$id' in the strategy")
                    else navigate(target.first, target.second)
                }
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        /**
         * Navigate from a clicked edge to its `edge(from forwardTo to …)` definition.
         * The clicked endpoints are diagram ids (the sanitized node display names), so we
         * map each back to the Kotlin variable used in the `forwardTo` infix.
         */
        fun navigateToEdge(from: String, to: String) {
            ReadAction.nonBlocking(Callable {
                val call = pointer?.element ?: return@Callable null
                val fromVar = resolveVarName(call, from)
                val toVar = resolveVarName(call, to)
                val binary = PsiTreeUtil.findChildrenOfType(call, KtBinaryExpression::class.java)
                    .firstOrNull {
                        it.operationReference.getReferencedName() == "forwardTo" &&
                            leadingName(it.left) == fromVar && leadingName(it.right) == toVar
                    } ?: return@Callable null
                val vf = binary.containingFile?.virtualFile ?: return@Callable null
                vf to binary.textOffset
            })
                .expireWith(view)
                .finishOnUiThread(ModalityState.defaultModalityState()) { target ->
                    if (target == null) LOG.info("navigateToEdge: no 'edge($from forwardTo $to)' found in the strategy")
                    else navigate(target.first, target.second)
                }
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        /** The Kotlin variable behind a clicked edge endpoint id (or the id itself). */
        private fun resolveVarName(call: KtCallExpression, token: String): String {
            if (token == "nodeStart" || token == "nodeFinish") return token
            return resolveNodeProperty(call, token)?.name ?: token
        }

        /**
         * Find the delegated `val … by node…(…)` property whose node matches [token],
         * which may be the raw display name (node click) or its sanitized Mermaid id
         * (edge click), and falls back to the variable name.
         */
        private fun resolveNodeProperty(call: KtCallExpression, token: String): KtProperty? {
            return PsiTreeUtil.findChildrenOfType(call, KtProperty::class.java).firstOrNull { prop ->
                if (!prop.hasDelegateExpression()) return@firstOrNull false
                val display = nodeDisplayName(prop)
                val name = prop.name
                display == token || display.toMermaidId() == token ||
                    name == token || (name != null && name.toMermaidId() == token)
            }
        }

        /**
         * The display name Koog gives a node: the explicit `name`/string argument of its
         * delegate, or — when the delegate calls a factory function — the name of the
         * `node(…)` built inside that helper; otherwise the property name. The factory is
         * resolved by reference, so it works whether it's a same-file function or one in
         * another file (e.g. `Obj.makeNode()`).
         */
        private fun nodeDisplayName(prop: KtProperty): String {
            val name = prop.name ?: ""
            val delegateCall = delegateCallOf(prop) ?: return name
            explicitNodeName(delegateCall)?.let { return it }

            val fn = (delegateCall.calleeExpression as? KtNameReferenceExpression)
                ?.reference?.resolve() as? KtNamedFunction ?: return name
            // Only read the name out of a *source* helper. Descending into a decompiled
            // library function would force its stub/AST to load — a slow, index-touching
            // operation — and library factories carry no user `node("…")` name anyway.
            if (fn.containingKtFile.isCompiled) return name
            return PsiTreeUtil.findChildrenOfType(fn, KtCallExpression::class.java)
                .firstNotNullOfOrNull { c ->
                    val callee = (c.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                    if (callee?.startsWith("node") == true) explicitNodeName(c) else null
                } ?: name
        }

        /** The call a delegate ultimately makes: `foo()` directly, or the `foo()` in `Receiver.foo()`. */
        private fun delegateCallOf(prop: KtProperty): KtCallExpression? =
            when (val d = prop.delegateExpression) {
                is KtCallExpression -> d
                is KtDotQualifiedExpression -> d.selectorExpression as? KtCallExpression
                else -> null
            }

        /** The string passed as `name = "…"` or the first positional string literal, if any. */
        private fun explicitNodeName(call: KtCallExpression): String? {
            call.valueArguments.forEach { arg ->
                val literal = arg.getArgumentExpression() as? KtStringTemplateExpression ?: return@forEach
                if (arg.getArgumentName()?.asName?.asString() == "name") return literalText(literal)
            }
            return call.valueArguments
                .firstOrNull { it.getArgumentName() == null }
                ?.let { it.getArgumentExpression() as? KtStringTemplateExpression }
                ?.let { literalText(it) }
        }

        private fun literalText(template: KtStringTemplateExpression): String =
            template.entries.joinToString("") { it.text }

        /** The first (receiver-most) referenced name within an expression, or null. */
        private fun leadingName(expr: KtExpression?): String? = when (expr) {
            null -> null
            is KtNameReferenceExpression -> expr.getReferencedName()
            else -> PsiTreeUtil.findChildOfType(expr, KtNameReferenceExpression::class.java)?.getReferencedName()
        }

        fun listenTo(file: VirtualFile?) {
            currentFile = file
            // getDocument walks the file model and needs a read action; we're on the EDT.
            val newDoc = file?.let { f -> runReadActionBlocking { FileDocumentManager.getInstance().getDocument(f) } }
            if (newDoc === document) return
            document?.removeDocumentListener(listener)
            document = newDoc
            newDoc?.addDocumentListener(listener)
        }

        /**
         * Reverse navigation: given the editor caret at [offset] in [vf], highlight the
         * node/edge under it in this tab's graph (or clear when the caret isn't on one).
         * Cheap when the caret is in an unrelated file — no read action is taken. The PSI
         * walk (which may resolve cross-file references) runs in a coalesced background read
         * action, never on the EDT — caret moves are frequent and resolution can touch indexes.
         */
        fun highlightAtCaret(vf: VirtualFile?, offset: Int) {
            if (vf == null || currentFile != vf) {
                applyHighlight(Highlight(null, null, null))
                return
            }
            ReadAction.nonBlocking(Callable {
                val call = pointer?.element ?: return@Callable null // strategy gone — leave it be
                if (!call.isValid || !call.textRange.contains(offset)) Highlight(null, null, null)
                else highlightFor(call, offset)
            })
                .expireWith(view)
                .coalesceBy(this, vf)
                .finishOnUiThread(ModalityState.any(), ::applyHighlight)
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        private fun applyHighlight(spec: Highlight?) {
            if (spec == null || spec == lastHighlight) return
            lastHighlight = spec
            view.highlight(spec.node, spec.from, spec.to)
        }

        /**
         * The node/edge at [offset] within the strategy [call], as a highlight target.
         * We pick the *innermost* match: an `edge(…)` inside a subgraph is smaller than the
         * subgraph property that encloses it, so the edge wins — without this, a caret on an
         * inner edge would resolve to (and highlight) the surrounding subgraph node.
         */
        private fun highlightFor(call: KtCallExpression, offset: Int): Highlight {
            val prop = PsiTreeUtil.findChildrenOfType(call, KtProperty::class.java)
                .filter { it.hasDelegateExpression() && it.textRange.contains(offset) }
                .minByOrNull { it.textRange.length }
            val edgeCall = PsiTreeUtil.findChildrenOfType(call, KtCallExpression::class.java)
                .filter {
                    (it.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == "edge" &&
                        it.textRange.contains(offset)
                }
                .minByOrNull { it.textRange.length }

            val edgeIsMoreSpecific = edgeCall != null &&
                (prop == null || edgeCall.textRange.length < prop.textRange.length)
            if (edgeIsMoreSpecific) {
                val binary = PsiTreeUtil.findChildrenOfType(edgeCall, KtBinaryExpression::class.java)
                    .firstOrNull { it.operationReference.getReferencedName() == "forwardTo" }
                val from = leadingName(binary?.left)
                val to = leadingName(binary?.right)
                if (from != null && to != null) {
                    return Highlight(node = null, from = mermaidIdForVar(call, from), to = mermaidIdForVar(call, to))
                }
            }
            if (prop != null) return Highlight(node = nodeDisplayName(prop), from = null, to = null)
            return Highlight(null, null, null)
        }

        /** The Mermaid edge-endpoint id for a Kotlin variable used in a `forwardTo`. */
        private fun mermaidIdForVar(call: KtCallExpression, varName: String): String {
            if (varName == "nodeStart" || varName == "nodeFinish") return varName
            val prop = PsiTreeUtil.findChildrenOfType(call, KtProperty::class.java)
                .firstOrNull { it.name == varName }
            val display = prop?.let { nodeDisplayName(it) } ?: varName
            return display.toMermaidId()
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

        /**
         * A canonical form of a strategy expression used as the diagram cache key: every
         * run of insignificant whitespace and every comment is dropped, so edits that only
         * reformat the code (or touch comments) collapse to the same key and re-use the
         * cached diagram instead of triggering a recompile. Whitespace *inside* a string or
         * char literal is part of that literal's leaf token (not a [PsiWhiteSpace]), so it
         * is preserved — e.g. renaming a node label still invalidates the cache. Non-leaf
         * tokens are joined with a single space so distinct tokens never merge (`a  b` and
         * `a b` match, but `ab` stays distinct). Must be called under a read action.
         */
        private fun canonicalKey(element: PsiElement): String {
            val sb = StringBuilder()
            fun walk(e: PsiElement) {
                var child = e.firstChild
                if (child == null) { // leaf token
                    if (e is PsiWhiteSpace || e is PsiComment) return
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(e.text)
                    return
                }
                while (child != null) {
                    walk(child)
                    child = child.nextSibling
                }
            }
            walk(element)
            return sb.toString()
        }

        // Each edit triggers a recompile; wait longer than a single keystroke burst.
        private const val REFRESH_DELAY_MS = 1500
        private const val CACHE_SIZE = 64
    }
}
