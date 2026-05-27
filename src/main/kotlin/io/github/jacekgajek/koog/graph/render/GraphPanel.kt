package io.github.jacekgajek.koog.graph.render

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import io.github.jacekgajek.koog.graph.parser.SourceAnchor
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import io.github.jacekgajek.koog.graph.parser.NodeKind
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
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
    private var fitMode: Boolean = true
    private var panX: Double = 0.0
    private var panY: Double = 0.0
    private var hovered: LaidOutNode? = null
    private var hoveredEdge: LaidOutEdge? = null

    private var dragPrev: java.awt.Point? = null
    private var didDrag: Boolean = false
    private val dragThresholdPx: Int = JBUIScale.scale(4)

    private data class TooltipLine(val text: String, val bold: Boolean)
    private var tooltipLines: List<TooltipLine> = emptyList()
    private var tooltipAnchor: java.awt.Point? = null

    init {
        background = JBColor.background()
        isFocusable = true
        val margin = JBUIScale.scale(40)
        preferredSize = Dimension(
            (graph.width * scale + margin).toInt().coerceAtLeast(JBUIScale.scale(480)),
            (graph.height * scale + margin).toInt().coerceAtLeast(JBUIScale.scale(360)),
        )
        // We render our own tooltip in paintComponent — Swing's ToolTipText is
        // intercepted by IntelliJ's IdeTooltipManager, which has its own delay
        // we can't reliably override per-component, so we draw it ourselves.

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (fitMode) repaint()
            }
        })

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragPrev = e.point
                didDrag = false
            }

            override fun mouseReleased(e: MouseEvent) {
                dragPrev = null
                if (!didDrag) {
                    cursor = if (nodeAt(e.point) != null || edgeAt(e.point) != null) {
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    } else Cursor.getDefaultCursor()
                }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 1 || didDrag) return
                val node = nodeAt(e.point)
                if (node != null) {
                    navigateTo(node.model.anchor)
                    return
                }
                val edge = edgeAt(e.point)
                if (edge != null) {
                    navigateTo(edge.anchor)
                }
            }

            override fun mouseExited(e: MouseEvent) {
                if (hovered != null || hoveredEdge != null || tooltipLines.isNotEmpty()) {
                    hovered = null
                    hoveredEdge = null
                    tooltipLines = emptyList()
                    tooltipAnchor = null
                    cursor = Cursor.getDefaultCursor()
                    repaint()
                }
            }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val prev = dragPrev ?: return
                val dx = e.x - prev.x
                val dy = e.y - prev.y
                if (!didDrag && Math.abs(dx) + Math.abs(dy) < dragThresholdPx) return
                didDrag = true
                fitMode = false
                panX += dx
                panY += dy
                dragPrev = e.point
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                // Drop hover state during pan so we don't flicker tooltips/highlights.
                hovered = null
                hoveredEdge = null
                tooltipLines = emptyList()
                tooltipAnchor = null
                repaint()
            }

            override fun mouseMoved(e: MouseEvent) {
                val n = nodeAt(e.point)
                val edge = if (n == null) edgeAt(e.point) else null
                val newLines = buildTooltip(n, edge)
                val changed = n !== hovered ||
                    edge !== hoveredEdge ||
                    newLines != tooltipLines ||
                    (newLines.isNotEmpty() && tooltipAnchor != e.point)
                hovered = n
                hoveredEdge = edge
                tooltipLines = newLines
                tooltipAnchor = if (newLines.isNotEmpty()) e.point else null
                cursor = if (n != null || edge != null) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
                if (changed) repaint()
            }
        })
        addMouseWheelListener { e: MouseWheelEvent ->
            val factor = if (e.preciseWheelRotation < 0) 1.1 else 1 / 1.1
            fitMode = false
            scale = (scale * factor).coerceIn(0.2, 4.0)
            revalidate()
            repaint()
        }
    }

    /** Re-enable auto-fit and re-center; the next paint recomputes scale from current size. */
    fun fitToWindow() {
        fitMode = true
        panX = 0.0
        panY = 0.0
        repaint()
    }

    private fun computeFitScale() {
        if (graph.width <= 0 || graph.height <= 0) return
        val pad = JBUIScale.scale(40).toDouble() * 2
        val availW = (width - pad).coerceAtLeast(1.0)
        val availH = (height - pad).coerceAtLeast(1.0)
        scale = minOf(availW / graph.width, availH / graph.height).coerceIn(0.2, 4.0)
    }

    private fun buildTooltip(@Suppress("UNUSED_PARAMETER") node: LaidOutNode?, edge: LaidOutEdge?): List<TooltipLine> {
        // Node labels already show id + factory; a tooltip would just repeat them.
        if (edge != null && edge.condition != null) {
            val out = mutableListOf(TooltipLine(edge.condition, bold = true))
            edge.conditionExpr?.takeIf { it.isNotBlank() }?.let {
                out += TooltipLine(it, bold = false)
            }
            return out
        }
        return emptyList()
    }

    private fun nodeAt(p: java.awt.Point): LaidOutNode? {
        val gp = toGraph(p)
        return graph.nodes.firstOrNull { it.bounds.contains(gp) }
    }

    /** Edge hit test: distance to any segment under ~7 device px, in graph coords. */
    private fun edgeAt(p: java.awt.Point): LaidOutEdge? {
        val gp = toGraph(p)
        val tol = (JBUIScale.scale(7f).toDouble() / scale).coerceAtLeast(4.0)
        return graph.edges.firstOrNull { edge ->
            edge.points.zipWithNext().any { (a, b) -> distanceToSegment(gp, a, b) < tol }
        }
    }

    private fun toGraph(p: java.awt.Point): Point2D.Double {
        val (ox, oy) = origin()
        return Point2D.Double((p.x - ox) / scale, (p.y - oy) / scale)
    }

    private fun origin(): Pair<Double, Double> {
        val ox = (width - graph.width * scale) / 2 + panX
        val oy = (height - graph.height * scale) / 2 + panY
        return ox to oy
    }

    override fun paintComponent(g: Graphics) {
        if (fitMode) computeFitScale()
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
        paintTooltip(g2)
    }

    private fun paintTooltip(g2: Graphics2D) {
        val anchor = tooltipAnchor ?: return
        if (tooltipLines.isEmpty()) return

        val mainFm = g2.getFontMetrics(GraphMetrics.mainFont.deriveFont(java.awt.Font.BOLD))
        val subFm = g2.getFontMetrics(GraphMetrics.mainFont)
        val padX = JBUIScale.scale(8)
        val padY = JBUIScale.scale(6)
        val lineGap = JBUIScale.scale(2)

        val widths = tooltipLines.map { line ->
            (if (line.bold) mainFm else subFm).stringWidth(line.text)
        }
        val tw = (widths.maxOrNull() ?: 0) + 2 * padX
        val lineH = tooltipLines.map { if (it.bold) mainFm.height else subFm.height }
        val th = lineH.sum() + (tooltipLines.size - 1) * lineGap + 2 * padY

        // Position to the bottom-right of cursor; nudge inside panel.
        val offset = JBUIScale.scale(16)
        var x = anchor.x + offset
        var y = anchor.y + offset
        if (x + tw > width) x = (anchor.x - tw - offset).coerceAtLeast(0)
        if (y + th > height) y = (anchor.y - th - offset).coerceAtLeast(0)

        val bg = JBColor(Color(0xFFFFE1), Color(0x3B3F45))
        val border = JBColor(Color(0xC0C0C0), Color(0x5A5F66))
        val fg = JBColor.foreground()

        g2.color = bg
        g2.fillRoundRect(x, y, tw, th, 8, 8)
        g2.color = border
        g2.stroke = BasicStroke(1f)
        g2.drawRoundRect(x, y, tw, th, 8, 8)

        var lineTop = y + padY
        g2.color = fg
        tooltipLines.forEachIndexed { i, line ->
            val fm = if (line.bold) mainFm else subFm
            g2.font = if (line.bold) GraphMetrics.mainFont.deriveFont(java.awt.Font.BOLD)
                      else GraphMetrics.mainFont
            g2.drawString(line.text, x + padX, lineTop + fm.ascent)
            lineTop += fm.height + lineGap
        }
    }

    private fun paintEdges(g2: Graphics2D) {
        val edgeColor = JBColor(Color(0x6B6B6B), Color(0xB3B3B3))
        val edgeColorHover = JBColor(Color(0x2E5BBA), Color(0x6FA8FF))
        val labelBg = JBColor.background()
        g2.font = GraphMetrics.subFont
        val fm = g2.getFontMetrics(GraphMetrics.subFont)

        val baseStrokeW = (JBUIScale.scale(2.5f) / scale.toFloat()).coerceAtLeast(1.0f)
        val hoverStrokeW = (JBUIScale.scale(3.6f) / scale.toFloat()).coerceAtLeast(1.4f)
        val arrowSize = (JBUIScale.scale(12f).toDouble() / scale).coerceAtLeast(7.0)

        graph.edges.forEach { edge ->
            if (edge.points.size < 2) return@forEach
            val isHover = edge === hoveredEdge
            g2.color = if (isHover) edgeColorHover else edgeColor
            g2.stroke = BasicStroke(
                if (isHover) hoverStrokeW else baseStrokeW,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
            )

            val path = Path2D.Double().apply {
                val first = edge.points.first()
                moveTo(first.x, first.y)
                edge.points.drop(1).forEach { p -> lineTo(p.x, p.y) }
            }
            g2.draw(path)

            val last = edge.points.last()
            val prev = edge.points[edge.points.size - 2]
            drawArrowHead(g2, prev, last, arrowSize)

            edge.condition?.let { cond ->
                val mid = midpoint(edge.points)
                val tw = fm.stringWidth(cond).toDouble()
                val th = fm.height.toDouble()
                val padX = 6.0
                val padY = 3.0
                val rx = mid.x - tw / 2 - padX
                val ry = mid.y - th / 2 - padY
                g2.color = labelBg
                g2.fillRoundRect(rx.toInt(), ry.toInt(), (tw + 2 * padX).toInt(), (th + 2 * padY).toInt(), 8, 8)
                g2.color = if (isHover) edgeColorHover else edgeColor
                g2.drawString(cond, (mid.x - tw / 2).toFloat(), (mid.y + fm.ascent / 2.0 - 2).toFloat())
            }
        }
    }

    private fun paintNodes(g2: Graphics2D) {
        val mainFm = g2.getFontMetrics(GraphMetrics.mainFont)
        val subFm = g2.getFontMetrics(GraphMetrics.subFont)

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
            val xCenter = b.x + b.width / 2.0

            g2.color = JBColor.foreground()
            if (factory != null) {
                val mainH = mainFm.ascent + mainFm.descent.toDouble()
                val subH = subFm.ascent + subFm.descent.toDouble()
                val totalH = mainH + GraphMetrics.lineGap + subH
                val topY = b.y + (b.height - totalH) / 2.0

                g2.font = GraphMetrics.mainFont
                val idW = mainFm.stringWidth(id)
                val idBaseline = topY + mainFm.ascent
                g2.drawString(id, (xCenter - idW / 2).toFloat(), idBaseline.toFloat())

                val facText = "by $factory()"
                val facW = subFm.stringWidth(facText)
                val facBaseline = idBaseline + mainFm.descent + GraphMetrics.lineGap + subFm.ascent
                g2.font = GraphMetrics.subFont
                g2.color = JBColor(Color(0x7A7A7A), Color(0x9E9E9E))
                g2.drawString(facText, (xCenter - facW / 2).toFloat(), facBaseline.toFloat())
            } else {
                g2.font = GraphMetrics.mainFont
                val idW = mainFm.stringWidth(id)
                val baseline = b.y + (b.height + mainFm.ascent - mainFm.descent) / 2.0
                g2.drawString(id, (xCenter - idW / 2).toFloat(), baseline.toFloat())
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

    private fun navigateTo(anchor: SourceAnchor?) {
        anchor ?: return
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

        private fun drawArrowHead(g2: Graphics2D, from: Point2D.Double, to: Point2D.Double, size: Double) {
            val angle = Math.atan2(to.y - from.y, to.x - from.x)
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
