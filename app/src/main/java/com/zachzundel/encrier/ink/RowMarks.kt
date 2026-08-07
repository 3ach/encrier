package com.zachzundel.encrier.ink

import com.zachzundel.encrier.Tunables
import com.zachzundel.encrier.data.InkPoint
import com.zachzundel.encrier.data.bounds
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
        val b = points.bounds()!! // non-null: at least two points
        val textW = max(1f, textX1 - textX0)
        val cover = max(0f, min(b.maxX, textX1) - max(b.minX, textX0)) / textW
        val heightFrac = (b.maxY - b.minY) / writeLh
        // A scribble can zigzag horizontally OR be a sawtooth: rapid vertical
        // reversals while sweeping rightward. Count both axes. Hysteresis-based
        // so ~550Hz sampling (sub-pixel per-sample deltas) can't hide reversals.
        val hys = 0.07f * writeLh
        val xRev = reversals(points, hys) { it.x }
        val yRev = reversals(points, hys) { it.y }

        // Must stay near the row band; wobble tolerance scales with the
        // WRITING line height, not the (possibly tight) row height.
        val inBand = b.minY >= rowTop - 0.35f * writeLh &&
            b.maxY <= rowTop + rowHeight + 0.35f * writeLh
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

    /** Direction changes along one axis, with hysteresis: a reversal counts only
     *  when the trajectory retreats ≥ [hys] from its running extreme. */
    private inline fun reversals(
        points: List<InkPoint>,
        hys: Float,
        axis: (InkPoint) -> Float,
    ): Int {
        if (points.size < 2) return 0
        var count = 0
        var dir = 0
        var extreme = axis(points[0])
        for (i in 1 until points.size) {
            val v = axis(points[i])
            when (dir) {
                0 -> if (abs(v - extreme) >= hys) {
                    dir = if (v > extreme) 1 else -1
                    extreme = v
                }
                1 -> if (v > extreme) extreme = v
                else if (extreme - v >= hys) {
                    count++
                    dir = -1
                    extreme = v
                }
                else -> if (v < extreme) extreme = v
                else if (v - extreme >= hys) {
                    count++
                    dir = 1
                    extreme = v
                }
            }
        }
        return count
    }
}
