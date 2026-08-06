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

    data class Verdict(
        val kind: Kind,
        val cover: Float,
        val heightFrac: Float,
        val xReversals: Int,
        val yReversals: Int,
        val reason: String,
    )

    fun classify(
        points: List<InkPoint>,
        rowTop: Float,
        rowHeight: Float,
        writeLh: Float,
        textX0: Float,
        textX1: Float,
    ): Verdict {
        if (points.size < 2) return Verdict(Kind.NONE, 0f, 0f, 0, 0, "too few points")
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
        val textW = max(1f, textX1 - textX0)
        val cover = max(0f, min(maxX, textX1) - max(minX, textX0)) / textW
        val heightFrac = (maxY - minY) / writeLh
        // A scribble can zigzag horizontally OR be a sawtooth: rapid vertical
        // reversals while sweeping rightward. Count both axes.
        val xRev = reversals(points) { it.x }
        val yRev = reversals(points) { it.y }

        // Must stay near the row band; wobble tolerance scales with the
        // WRITING line height, not the (possibly tight) row height.
        val inBand = minY >= rowTop - 0.35f * writeLh &&
            maxY <= rowTop + rowHeight + 0.35f * writeLh
        val kind = when {
            !inBand -> Kind.NONE
            cover >= Tunables.SCRIBBLE_MIN_COVER &&
                max(xRev, yRev) >= Tunables.SCRIBBLE_MIN_REVERSALS -> Kind.SCRIBBLE
            cover >= Tunables.STRIKE_MIN_COVER &&
                heightFrac <= Tunables.STRIKE_MAX_HEIGHT_FRAC &&
                xRev <= 1 -> Kind.STRIKE
            else -> Kind.NONE
        }
        val reason = if (!inBand) "outside row band" else "thresholds"
        return Verdict(kind, cover, heightFrac, xRev, yRev, reason)
    }

    private inline fun reversals(points: List<InkPoint>, axis: (InkPoint) -> Float): Int {
        var count = 0
        var lastSign = 0
        for (i in 1 until points.size) {
            val d = axis(points[i]) - axis(points[i - 1])
            if (abs(d) < 2f) continue
            val sign = if (d > 0) 1 else -1
            if (lastSign != 0 && sign != lastSign) count++
            lastSign = sign
        }
        return count
    }
}
