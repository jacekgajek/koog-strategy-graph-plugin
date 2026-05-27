package io.github.jacekgajek.koog.graph.render

import io.github.jacekgajek.koog.graph.parser.Node
import io.github.jacekgajek.koog.graph.parser.SourceAnchor
import io.github.jacekgajek.koog.graph.parser.StrategyGraph
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D

data class LaidOutNode(
    val model: Node,
    val bounds: Rectangle2D.Double,
)

data class LaidOutEdge(
    val from: String,
    val to: String,
    val condition: String?,
    val conditionExpr: String?,
    /** Polyline including endpoints, in graph coordinates. */
    val points: List<Point2D.Double>,
    val anchor: SourceAnchor?,
)

data class LaidOutGraph(
    val source: StrategyGraph,
    val nodes: List<LaidOutNode>,
    val edges: List<LaidOutEdge>,
    val width: Double,
    val height: Double,
)
