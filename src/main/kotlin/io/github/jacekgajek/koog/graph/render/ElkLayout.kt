package io.github.jacekgajek.koog.graph.render

import io.github.jacekgajek.koog.graph.parser.StrategyGraph
import com.intellij.ui.scale.JBUIScale
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.util.ElkGraphUtil
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.util.concurrent.atomic.AtomicBoolean

object ElkLayout {

    private val initialized = AtomicBoolean(false)

    private fun ensureRegistered() {
        if (initialized.compareAndSet(false, true)) {
            LayoutMetaDataService.getInstance()
                .registerLayoutMetaDataProviders(LayeredOptions())
        }
    }

    fun layout(graph: StrategyGraph): LaidOutGraph {
        ensureRegistered()

        val root = ElkGraphUtil.createGraph()
        root.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
        root.setProperty(CoreOptions.DIRECTION, Direction.DOWN)
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
        // SPACING_NODE_NODE is the in-layer (horizontal) gap. The between-layer
        // (vertical) gap is set separately and is what determines edge length —
        // it's what carries condition labels like "onToolCalls" without overlap.
        root.setProperty(CoreOptions.SPACING_NODE_NODE, JBUIScale.scale(80f).toDouble())
        root.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, JBUIScale.scale(90f).toDouble())
        root.setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, JBUIScale.scale(40f).toDouble())
        root.setProperty(LayeredOptions.SPACING_EDGE_EDGE_BETWEEN_LAYERS, JBUIScale.scale(25f).toDouble())
        root.setProperty(CoreOptions.SPACING_EDGE_NODE, JBUIScale.scale(40f).toDouble())
        root.setProperty(CoreOptions.SPACING_EDGE_EDGE, JBUIScale.scale(25f).toDouble())
        root.setProperty(CoreOptions.SPACING_EDGE_LABEL, JBUIScale.scale(10f).toDouble())
        root.setProperty(CoreOptions.PADDING, org.eclipse.elk.core.math.ElkPadding(JBUIScale.scale(36f).toDouble()))

        val elkNodes = graph.nodes.associate { node ->
            val n = ElkGraphUtil.createNode(root)
            n.identifier = node.id
            val (w, h) = GraphMetrics.nodeSize(node.id, node.factory)
            n.width = w
            n.height = h
            node.id to n
        }

        graph.edges.forEach { edge ->
            val source = elkNodes[edge.from] ?: return@forEach
            val target = elkNodes[edge.to] ?: return@forEach
            ElkGraphUtil.createSimpleEdge(source, target)
        }

        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())

        val laidOutNodes = graph.nodes.mapNotNull { model ->
            val n = elkNodes[model.id] ?: return@mapNotNull null
            LaidOutNode(
                model = model,
                bounds = Rectangle2D.Double(n.x, n.y, n.width, n.height),
            )
        }

        val laidOutEdges = graph.edges.mapNotNull { edge ->
            val source = elkNodes[edge.from] ?: return@mapNotNull null
            val elkEdge = source.outgoingEdges.firstOrNull { it.targets.firstOrNull()?.identifier == edge.to }
                ?: return@mapNotNull null
            val points = mutableListOf<Point2D.Double>()
            elkEdge.sections.firstOrNull()?.let { section ->
                points += Point2D.Double(section.startX, section.startY)
                section.bendPoints.forEach { bp -> points += Point2D.Double(bp.x, bp.y) }
                points += Point2D.Double(section.endX, section.endY)
            }
            LaidOutEdge(
                from = edge.from,
                to = edge.to,
                condition = edge.condition,
                conditionExpr = edge.conditionExpr,
                points = points,
            )
        }

        return LaidOutGraph(
            source = graph,
            nodes = laidOutNodes,
            edges = laidOutEdges,
            width = root.width,
            height = root.height,
        )
    }
}
