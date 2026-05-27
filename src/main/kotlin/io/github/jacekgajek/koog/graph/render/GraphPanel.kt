package io.github.jacekgajek.koog.graph.render

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import io.github.jacekgajek.koog.graph.parser.NodeKind
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

class GraphPanel(
    private val graph: LaidOutGraph,
    private val project: Project,
) : JPanel() {

    private var scale: Double = 1.0
    private var hovered: LaidOutNode? = null

    init {
        background = JBColor.background()
        isFocusable = true
        preferredSize = Dimension(
            (graph.width * scale + 40).toInt().coerceAtLeast(420),
            (graph.height * scale + 40).toInt().coerceAtLeast(320),
        )
        toolTipText = ""

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount < 2) return
                val node = nodeAt(e.point) ?: return
                navigateTo(node)
            }

            override fun mouseExited(e: MouseEvent) {
                if (hovered != null) {
                    hovered = null
                    repaint()
                }
            }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val n = nodeAt(e.point)
                if (n !== hovered) {
                    hovered = n
                    repaint()
                }
            }
        })
        addMouseWheelListener { e: MouseWheelEvent ->
            val factor = if (e.preciseWheelRotation < 0) 1.1 else 1 / 1.1
            scale = (scale * factor).coerceIn(0.4, 3.0)
            revalidate()
            repaint()
        }
    }

    override fun getToolTipText(e: MouseEvent): String? {
        val node = nodeAt(e.point)
        if (node != null) {
            val parts = mutableListOf("<b>${node.model.id}</b>")
            node.model.factory?.let { parts += "by $it()" }
            return "<html>${parts.joinToString("<br/>")}</html>"
        }
        val edge = edgeAt(e.point)
        if (edge != null && edge.condition != null) {
            return "<html><b>${edge.condition}</b> ${edge.conditionExpr.orEmpty()}</html>"
        }
        return null
    }

    private fun nodeAt(p: java.awt.Point): LaidOutNode? {
        val gp = toGraph(p)
        return graph.nodes.firstOrNull { it.bounds.contains(gp) }
    }

    /** Crude edge hit test: distance to any segment under 4px (in graph coords). */
    private fun edgeAt(p: java.awt.Point): LaidOutEdge? {
        val gp = toGraph(p)
        val tol = 4.0
        return graph.edges.firstOrNull { edge ->
            edge.points.zipWithNext().any { (a, b) -> distanceToSegment(gp, a, b) < tol }
        }
    }

    private fun toGraph(p: java.awt.Point): Point2D.Double {
        val (ox, oy) = origin()
        return Point2D.Double((p.x - ox) / scale, (p.y - oy) / scale)
    }

    private fun origin(): Pair<Double, Double> {
        val ox = ((width - graph.width * scale) / 2).coerceAtLeast(20.0)
        val oy = 20.0
        return ox to oy
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = (g as Graphics2D)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val (ox, oy) = origin()
        val original = g2.transform
        g2.transform = AffineTransform(original).also { it.translate(ox, oy); it.scale(scale, scale) }

        paintEdges(g2)
        paintNodes(g2)

        g2.transform = original
    }

    private fun paintEdges(g2: Graphics2D) {
        val edgeColor = JBColor(Color(0x6B6B6B), Color(0xB3B3B3))
        val labelBg = JBColor.background()
        val fm = g2.getFontMetrics(g2.font)

        g2.color = edgeColor
        g2.stroke = BasicStroke(1.4f)

        graph.edges.forEach { edge ->
            if (edge.points.size < 2) return@forEach
            val path = Path2D.Double().apply {
                val first = edge.points.first()
                moveTo(first.x, first.y)
                edge.points.drop(1).forEach { p -> lineTo(p.x, p.y) }
            }
            g2.draw(path)

            val last = edge.points.last()
            val prev = edge.points[edge.points.size - 2]
            drawArrowHead(g2, prev, last)

            edge.condition?.let { cond ->
                val mid = midpoint(edge.points)
                val text = cond
                val tw = fm.stringWidth(text).toDouble()
                val th = fm.height.toDouble()
                val padX = 4.0
                val padY = 2.0
                val rx = mid.x - tw / 2 - padX
                val ry = mid.y - th / 2 - padY
                g2.color = labelBg
                g2.fillRoundRect(rx.toInt(), ry.toInt(), (tw + 2 * padX).toInt(), (th + 2 * padY).toInt(), 6, 6)
                g2.color = edgeColor
                g2.drawString(text, (mid.x - tw / 2).toFloat(), (mid.y + fm.ascent / 2.0 - 2).toFloat())
            }
        }
    }

    private fun paintNodes(g2: Graphics2D) {
        val fm = g2.getFontMetrics(g2.font)
        graph.nodes.forEach { node ->
            val b = node.bounds
            val shape = RoundRectangle2D.Double(b.x, b.y, b.width, b.height, 14.0, 14.0)
            val (fill, border) = colorsFor(node.model.kind, node === hovered)
            g2.color = fill
            g2.fill(shape)
            g2.color = border
            g2.stroke = if (node.model.kind == NodeKind.Unknown) DASHED else BasicStroke(1.6f)
            g2.draw(shape)

            val id = node.model.id
            val factory = node.model.factory
            val idW = fm.stringWidth(id)
            val xCenter = b.x + b.width / 2.0

            g2.color = JBColor.foreground()
            if (factory != null) {
                val idY = b.y + b.height / 2.0 - 2.0
                val facY = b.y + b.height / 2.0 + fm.height - 4.0
                g2.drawString(id, (xCenter - idW / 2).toFloat(), idY.toFloat())
                val facText = "by $factory()"
                val facW = fm.stringWidth(facText)
                g2.color = JBColor(Color(0x7A7A7A), Color(0x9E9E9E))
                g2.drawString(facText, (xCenter - facW / 2).toFloat(), facY.toFloat())
            } else {
                val y = b.y + b.height / 2.0 + fm.ascent / 2.0 - 2.0
                g2.drawString(id, (xCenter - idW / 2).toFloat(), y.toFloat())
            }
        }
    }

    private fun colorsFor(kind: NodeKind, hovered: Boolean): Pair<Color, Color> {
        val base = when (kind) {
            NodeKind.Start -> JBColor(Color(0xDFF3E2), Color(0x2E4A33)) to JBColor(Color(0x2E8B57), Color(0x6BCB89))
            NodeKind.Finish -> JBColor(Color(0xF8E2E2), Color(0x4A2E2E)) to JBColor(Color(0xB94545), Color(0xE08A8A))
            NodeKind.Unknown -> JBColor(Color(0xFFF5DC), Color(0x4A3F2A)) to JBColor(Color(0xC58900), Color(0xE0B85A))
            NodeKind.Declared -> JBColor(Color(0xE8EEF8), Color(0x2E384A)) to JBColor(Color(0x4A6FA5), Color(0x7FA7DC))
        }
        return if (hovered) base.first.brighter() to base.second.brighter() else base
    }

    private fun navigateTo(node: LaidOutNode) {
        val anchor = node.model.anchor ?: return
        OpenFileDescriptor(project, anchor.file, anchor.offset).navigate(true)
    }

    companion object {
        private val DASHED = BasicStroke(
            1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
            floatArrayOf(6f, 4f), 0f,
        )

        private fun midpoint(points: List<Point2D.Double>): Point2D.Double {
            // Walk to halfway along total length for a stable label position.
            val total = points.zipWithNext().sumOf { (a, b) -> a.distance(b) }
            var remaining = total / 2.0
            points.zipWithNext().forEach { (a, b) ->
                val d = a.distance(b)
                if (remaining <= d) {
                    val t = if (d == 0.0) 0.0 else remaining / d
                    return Point2D.Double(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                }
                remaining -= d
            }
            return points.last()
        }

        private fun drawArrowHead(g2: Graphics2D, from: Point2D.Double, to: Point2D.Double) {
            val angle = Math.atan2(to.y - from.y, to.x - from.x)
            val size = 8.0
            val x1 = to.x - size * Math.cos(angle - Math.PI / 7)
            val y1 = to.y - size * Math.sin(angle - Math.PI / 7)
            val x2 = to.x - size * Math.cos(angle + Math.PI / 7)
            val y2 = to.y - size * Math.sin(angle + Math.PI / 7)
            val path = Path2D.Double().apply {
                moveTo(to.x, to.y)
                lineTo(x1, y1)
                lineTo(x2, y2)
                closePath()
            }
            g2.fill(path)
        }

        private fun distanceToSegment(p: Point2D.Double, a: Point2D.Double, b: Point2D.Double): Double {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            if (len2 == 0.0) return p.distance(a)
            val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
            return p.distance(Point2D.Double(a.x + t * dx, a.y + t * dy))
        }
    }
}
