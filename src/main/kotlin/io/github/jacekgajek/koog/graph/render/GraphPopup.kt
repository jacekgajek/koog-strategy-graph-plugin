package io.github.jacekgajek.koog.graph.render

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.awt.RelativePoint
import io.github.jacekgajek.koog.graph.parser.StrategyParser
import org.jetbrains.kotlin.psi.KtCallExpression
import java.awt.Dimension
import java.awt.event.MouseEvent

object GraphPopup {

    fun show(project: Project, call: KtCallExpression, event: MouseEvent) {
        val graph = ReadAction.compute<_, RuntimeException> { StrategyParser().parse(call) }
            ?: return
        val laidOut = ElkLayout.layout(graph)
        val panel = GraphPanel(laidOut, project)

        val popup: JBPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel)
            .setTitle("Koog strategy: ${graph.name}")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setMinSize(Dimension(420, 320))
            .createPopup()

        popup.show(RelativePoint(event))
    }
}
