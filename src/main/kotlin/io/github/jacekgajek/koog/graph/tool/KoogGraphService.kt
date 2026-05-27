package io.github.jacekgajek.koog.graph.tool

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import io.github.jacekgajek.koog.graph.parser.StrategyParser
import io.github.jacekgajek.koog.graph.render.ElkLayout
import io.github.jacekgajek.koog.graph.render.GraphPanel
import org.jetbrains.kotlin.psi.KtCallExpression
import javax.swing.SwingConstants

@Service(Service.Level.PROJECT)
class KoogGraphService(private val project: Project) {

    fun showGraph(call: KtCallExpression) {
        val graph = runReadAction { StrategyParser().parse(call) } ?: return
        val laidOut = ElkLayout.layout(graph)
        val panel = GraphPanel(laidOut, project)

        val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        val cm = tw.contentManager
        cm.removeAllContents(true)
        val content = ContentFactory.getInstance().createContent(panel, graph.name, false)
        content.isCloseable = false
        cm.addContent(content)
        cm.setSelectedContent(content)

        // show() makes the tool window visible without stealing focus from the editor.
        if (!tw.isVisible) tw.show(null)
    }

    fun installInitialContent(tw: ToolWindow) {
        val empty = JBLabel(
            "<html><i>Click a <code>strategy { }</code> gutter icon to load its graph.</i></html>",
            SwingConstants.CENTER,
        )
        val content = ContentFactory.getInstance().createContent(empty, "Koog Strategy", false)
        content.isCloseable = false
        tw.contentManager.addContent(content)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Koog Strategy"
    }
}
