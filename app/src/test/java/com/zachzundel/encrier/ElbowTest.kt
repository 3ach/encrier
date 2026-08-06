package com.zachzundel.encrier

import com.zachzundel.encrier.data.InkPoint
import com.zachzundel.encrier.gesture.Elbow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElbowTest {
    private val lh = 100f
    private val minRun = 76f

    private fun stroke(vararg xy: Pair<Float, Float>): List<InkPoint> =
        xy.mapIndexed { i, (x, y) -> InkPoint(x, y, i * 10L) }

    private fun interpolated(vararg corners: Pair<Float, Float>): List<InkPoint> {
        val pts = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until corners.size - 1) {
            val (x0, y0) = corners[i]
            val (x1, y1) = corners[i + 1]
            for (s in 0 until 10) {
                val f = s / 10f
                pts.add(x0 + (x1 - x0) * f to y0 + (y1 - y0) * f)
            }
        }
        pts.add(corners.last())
        return stroke(*pts.toTypedArray())
    }

    @Test
    fun `clean elbow matches`() {
        // Anchor line bottom rule at y=100; down to y=190 (90 past rule), right 150.
        val m = Elbow.detect(
            interpolated(50f to 80f, 50f to 190f, 200f to 190f),
            anchorBottomY = 100f, lineHeightPx = lh, minRunPx = minRun,
        )
        assertTrue(m.reason, m.matched)
    }

    @Test
    fun `shallow drop rejected`() {
        // Turn point only 20px past the rule (< 0.5 × lineHeight).
        val m = Elbow.detect(
            interpolated(50f to 80f, 50f to 120f, 200f to 120f),
            anchorBottomY = 100f, lineHeightPx = lh, minRunPx = minRun,
        )
        assertFalse(m.matched)
    }

    @Test
    fun `descender-like leftward curl rejected`() {
        // Deep descender of a lowercase q: down then small LEFT hook.
        val m = Elbow.detect(
            interpolated(50f to 80f, 50f to 200f, 20f to 210f),
            anchorBottomY = 100f, lineHeightPx = lh, minRunPx = minRun,
        )
        assertFalse(m.matched)
    }

    @Test
    fun `short rightward run rejected`() {
        // Rightward run of 40px < max(76, drop).
        val m = Elbow.detect(
            interpolated(50f to 80f, 50f to 190f, 90f to 190f),
            anchorBottomY = 100f, lineHeightPx = lh, minRunPx = minRun,
        )
        assertFalse(m.matched)
    }

    @Test
    fun `run must exceed vertical drop`() {
        // Run 100 > minRun but drop is 160 — run must be >= drop.
        val m = Elbow.detect(
            interpolated(50f to 40f, 50f to 200f, 150f to 200f),
            anchorBottomY = 100f, lineHeightPx = lh, minRunPx = minRun,
        )
        assertFalse(m.matched)
    }

    @Test
    fun `diagonal stroke without corner rejected`() {
        val m = Elbow.detect(
            interpolated(50f to 80f, 200f to 230f),
            anchorBottomY = 100f, lineHeightPx = lh, minRunPx = minRun,
        )
        assertFalse(m.matched)
    }
}
