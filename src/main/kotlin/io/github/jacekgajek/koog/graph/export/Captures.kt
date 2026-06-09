package io.github.jacekgajek.koog.graph.export

import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Detects references inside a `strategy(...) { }` block that are captured from
 * the surrounding scope (outer `val`s, function parameters, enclosing-class
 * members). Those don't exist once the block is lifted into a standalone file,
 * so to make the snippet compile we want to declare each as
 * `val name: ItsType = mockk(relaxed = true)`.
 *
 * Emitting that line needs the *type* of each reference, which requires real
 * symbol resolution (the Kotlin Analysis API). The Analysis API surface differs
 * between platform versions, so wiring it correctly has to be validated against
 * a live IDE + real Koog project rather than guessed blind. Until then this
 * returns no captures: the snippet compiles as-is for self-contained strategies,
 * and for strategies that capture outer state the compiler error is surfaced in
 * the panel (so it's obvious what to mock).
 *
 * Next iteration: resolve each candidate's type via `analyze(ref) { ... }` and
 * return `id to renderedType`.
 */
object Captures {
    data class Capture(val id: String, val type: String)

    fun collect(@Suppress("UNUSED_PARAMETER") call: KtCallExpression): List<Capture> = emptyList()
}
