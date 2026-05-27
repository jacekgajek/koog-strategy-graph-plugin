package io.github.jacekgajek.koog.graph.parser

import com.intellij.openapi.vfs.VirtualFile

/**
 * Pure data extracted from a `strategy { ... }` block. Carries no live PSI;
 * just the data we need for layout and rendering. We hold a [SourceAnchor] per
 * node so the popup can jump back to source.
 */
data class StrategyGraph(
    val name: String,
    val inputType: String?,
    val outputType: String?,
    val nodes: List<Node>,
    val edges: List<Edge>,
)

enum class NodeKind {
    /** Built-in pseudo-start node. */
    Start,
    /** Built-in pseudo-finish node. */
    Finish,
    /** A regular `val x by nodeXxx()` declaration. */
    Declared,
    /** Referenced by an edge but not declared and not a known builtin. */
    Unknown,
}

data class Node(
    val id: String,
    val kind: NodeKind,
    /** Factory function name, e.g. "nodeLLMRequestStreaming". Null for builtins/unknowns. */
    val factory: String?,
    /** Verbatim source of the delegate call, for hover tooltips. */
    val sourceText: String?,
    /** Where in the source this node was declared. Null for builtins/unknowns. */
    val anchor: SourceAnchor?,
)

data class Edge(
    val from: String,
    val to: String,
    /** e.g. "onToolCalls", "onTextMessage". Null = unconditional. */
    val condition: String?,
    /** Verbatim source of the condition lambda, for tooltips. */
    val conditionExpr: String?,
    /** Where in the source the `edge(...)` call lives. */
    val anchor: SourceAnchor?,
)

/** Lightweight pointer back to source; safe to hold across PSI invalidations. */
data class SourceAnchor(
    val file: VirtualFile,
    val offset: Int,
)
