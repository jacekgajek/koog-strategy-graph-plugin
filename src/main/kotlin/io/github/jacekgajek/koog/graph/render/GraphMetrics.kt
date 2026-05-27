package io.github.jacekgajek.koog.graph.render

import java.awt.Font
import java.awt.font.FontRenderContext

/**
 * Shared font + sizing between [ElkLayout] (where nodes get their box sizes
 * before layout) and [GraphPanel] (where the same boxes are painted). They
 * must agree, or text overflows the box.
 */
object GraphMetrics {

    val mainFont: Font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
    val subFont: Font = Font(Font.SANS_SERIF, Font.PLAIN, 12)

    private val frc = FontRenderContext(null, true, true)

    /** Horizontal padding from text to box edge, per side. */
    const val PAD_X = 18.0
    /** Vertical padding from text to box edge, per side. */
    const val PAD_Y = 10.0
    /** Gap between the id line and the factory line. */
    const val LINE_GAP = 4.0
    /** Minimum box width regardless of label length. */
    const val MIN_WIDTH = 150.0

    fun textWidth(font: Font, text: String): Double =
        font.getStringBounds(text, frc).width

    fun lineHeight(font: Font): Double =
        font.getLineMetrics("Ag", frc).let { (it.ascent + it.descent + it.leading).toDouble() }

    fun nodeSize(label: String, factory: String?): Pair<Double, Double> {
        val mainW = textWidth(mainFont, label)
        val subText = factory?.let { "by $it()" }
        val subW = subText?.let { textWidth(subFont, it) } ?: 0.0
        val width = maxOf(mainW, subW) + 2 * PAD_X
        val mainH = lineHeight(mainFont)
        val subH = if (factory != null) lineHeight(subFont) + LINE_GAP else 0.0
        val height = mainH + subH + 2 * PAD_Y
        return width.coerceAtLeast(MIN_WIDTH) to height
    }
}
