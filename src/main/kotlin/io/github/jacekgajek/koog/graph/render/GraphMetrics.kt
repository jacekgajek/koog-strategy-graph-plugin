package io.github.jacekgajek.koog.graph.render

import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import java.awt.Font
import java.awt.font.FontRenderContext

/**
 * Shared font + sizing between [ElkLayout] (where nodes get their box sizes
 * before layout) and [GraphPanel] (where the same boxes are painted). They
 * must agree, or text overflows the box.
 *
 * All sizes go through [JBUIScale] so the graph keeps a consistent visual
 * footprint across IDE UI scales (HiDPI screens, "Presentation Mode", etc.).
 * Raw `java.awt.Font(..., size)` constants render at literal pixel sizes and
 * look microscopic on Linux/X11 HiDPI — use [JBFont] instead.
 */
object GraphMetrics {

    /** ID label font — follows the IDE's label font (scale-aware). */
    val mainFont: Font get() = JBFont.label()

    /** Subtitle ("by foo()") font — same size as main, differentiated by color. */
    val subFont: Font get() = JBFont.label()

    private val frc = FontRenderContext(null, true, true)

    val padX: Double get() = JBUIScale.scale(20f).toDouble()
    val padY: Double get() = JBUIScale.scale(12f).toDouble()
    val lineGap: Double get() = JBUIScale.scale(4f).toDouble()
    val minWidth: Double get() = JBUIScale.scale(160f).toDouble()

    fun textWidth(font: Font, text: String): Double =
        font.getStringBounds(text, frc).width

    fun lineHeight(font: Font): Double =
        font.getLineMetrics("Ag", frc).run { (ascent + descent + leading).toDouble() }

    fun nodeSize(label: String, factory: String?): Pair<Double, Double> {
        val mainW = textWidth(mainFont, label)
        val subText = factory?.let { "by $it()" }
        val subW = subText?.let { textWidth(subFont, it) } ?: 0.0
        val width = maxOf(mainW, subW) + 2 * padX
        val mainH = lineHeight(mainFont)
        val subH = if (factory != null) lineHeight(subFont) + lineGap else 0.0
        val height = mainH + subH + 2 * padY
        return width.coerceAtLeast(minWidth) to height
    }
}
