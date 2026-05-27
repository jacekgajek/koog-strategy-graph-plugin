package io.github.jacekgajek.koog.graph.render

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.scale.JBUIScale
import io.github.jacekgajek.koog.graph.parser.StrategyParser
import org.jetbrains.kotlin.psi.KtCallExpression
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

object GraphPopup {

    fun show(project: Project, call: KtCallExpression, event: MouseEvent) {
        val graph = ReadAction.compute<_, RuntimeException> { StrategyParser().parse(call) }
            ?: return
        val laidOut = ElkLayout.layout(graph)
        val panel = GraphPanel(laidOut, project)

        // Holder so the Close button (built before the popup) can reference it later.
        val popupRef = arrayOfNulls<JBPopup>(1)

        val pad = JBUIScale.scale(4)
        val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT, pad, pad)).apply {
            background = JBColor.background()
            border = BorderFactory.createEmptyBorder(pad, pad, 0, pad)
            add(JButton("Fit").apply {
                toolTipText = "Fit graph to window"
                addActionListener { panel.fitToWindow() }
            })
            add(JButton("Close", AllIcons.Actions.Close).apply {
                toolTipText = "Close (Esc when popup is focused)"
                addActionListener { popupRef[0]?.cancel() }
            })
        }

        val wrapper = JPanel(BorderLayout()).apply {
            background = JBColor.background()
            add(toolbar, BorderLayout.NORTH)
            add(panel, BorderLayout.CENTER)
        }

        val popup: JBPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(wrapper, panel)
            .setTitle("Koog strategy: ${graph.name}")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(false)
            .setFocusable(true)
            .setCancelOnClickOutside(false)
            .setCancelOnOtherWindowOpen(false)
            .setCancelOnWindowDeactivation(false)
            .setCancelKeyEnabled(true)
            .setMinSize(Dimension(JBUIScale.scale(480), JBUIScale.scale(360)))
            .createPopup()
        popupRef[0] = popup

        popup.show(RelativePoint(event))
    }
}
