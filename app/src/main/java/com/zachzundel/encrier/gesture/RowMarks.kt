package com.zachzundel.encrier.gesture

import com.zachzundel.encrier.Tunables
import com.zachzundel.encrier.data.InkPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Classifies a stroke drawn over a committed row's TEXT as a strike-out
 * (→ DONE) or scribble-out (→ DELETE). Pure geometry, content coordinates.
 */
object RowMarks {
    enum class Kind { NONE, STRIKE, SCRIBBLE }

    fun classify(
        points: List<InkPoint>,
        rowTop: Float,
        lineHeightPx: Float,
        textX0: Float,
        textX1: Float,
    ): Kind {
        if (points.size < 2) return Kind.NONE
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        // Must stay within the row band (small tolerance for slop).
        if (minY < rowTop - 0.2f * lineHeightPx) return Kind.NONE
        if (maxY > rowTop + 1.2f * lineHeightPx) return Kind.NONE

        val textW = max(1f, textX1 - textX0)
        val cover = max(0f, min(maxX, textX1) - max(minX, textX0)) / textW
        val heightFrac = (maxY - minY) / lineHeightPx
        val reversals = horizontalReversals(points)

        if (cover >= Tunables.SCRIBBLE_MIN_COVER &&
            reversals >= Tunables.SCRIBBLE_MIN_REVERSALS
        ) return Kind.SCRIBBLE

        if (cover >= Tunables.STRIKE_MIN_COVER &&
            heightFrac <= Tunables.STRIKE_MAX_HEIGHT_FRAC &&
            reversals <= 1
        ) return Kind.STRIKE

        return Kind.NONE
    }

    private fun horizontalReversals(points: List<InkPoint>): Int {
        var reversals = 0
        var lastSign = 0
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            if (abs(dx) < 2f) continue
            val sign = if (dx > 0) 1 else -1
            if (lastSign != 0 && sign != lastSign) reversals++
            lastSign = sign
        }
        return reversals
    }
}
