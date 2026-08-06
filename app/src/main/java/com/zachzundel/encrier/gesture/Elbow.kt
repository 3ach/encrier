package com.zachzundel.encrier.gesture

import com.zachzundel.encrier.Tunables
import com.zachzundel.encrier.data.InkPoint
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Spec §5 child-gesture detection. Pure geometry over a finished stroke in
 * CONTENT coordinates — unit-testable without Android.
 */
object Elbow {
    data class Metrics(
        val matched: Boolean,
        val reason: String,
        val dropPastRulePx: Float,
        val turnDeg: Float,
        val runPx: Float,
    )

    fun detect(
        points: List<InkPoint>,
        anchorBottomY: Float,
        lineHeightPx: Float,
        minRunPx: Float,
    ): Metrics {
        if (points.size < 4) return Metrics(false, "too few points", 0f, 0f, 0f)
        val first = points.first()
        val last = points.last()
        val chordX = last.x - first.x
        val chordY = last.y - first.y
        val chordLen = hypot(chordX, chordY)
        if (chordLen < 1f) return Metrics(false, "degenerate chord", 0f, 0f, 0f)

        // Split into two dominant segments at max perpendicular distance from the chord.
        var cornerIdx = -1
        var bestDist = -1f
        for (i in 1 until points.size - 1) {
            val d = abs((points[i].x - first.x) * chordY - (points[i].y - first.y) * chordX) / chordLen
            if (d > bestDist) {
                bestDist = d
                cornerIdx = i
            }
        }
        val turn = points[cornerIdx]
        val ax = turn.x - first.x
        val ay = turn.y - first.y // positive = downward
        val bx = last.x - turn.x
        val by = last.y - turn.y
        val lenA = hypot(ax, ay)
        val lenB = hypot(bx, by)
        val dropPastRule = turn.y - anchorBottomY
        val run = bx
        if (lenA < 1f || lenB < 1f) return Metrics(false, "segment too short", dropPastRule, 0f, run)
        val turnDeg = Math.toDegrees(
            acos(((ax * bx + ay * by) / (lenA * lenB)).toDouble().coerceIn(-1.0, 1.0))
        ).toFloat()

        val reason = when {
            ay <= abs(ax) ->
                "segment A not predominantly downward"
            dropPastRule < Tunables.GESTURE_MIN_DROP_FRAC * lineHeightPx ->
                "turn point too shallow past rule"
            bx <= abs(by) ->
                "segment B not predominantly rightward"
            run < max(minRunPx, Tunables.GESTURE_RUN_VS_DROP * ay) ->
                "rightward run too short"
            turnDeg < Tunables.GESTURE_TURN_MIN_DEG || turnDeg > Tunables.GESTURE_TURN_MAX_DEG ->
                "turn angle out of range"
            else -> return Metrics(true, "matched", dropPastRule, turnDeg, run)
        }
        return Metrics(false, reason, dropPastRule, turnDeg, run)
    }
}
