package com.zachzundel.encrier

import com.zachzundel.encrier.data.InkPoint
import com.zachzundel.encrier.gesture.RowMarks
import org.junit.Assert.assertEquals
import org.junit.Test

class RowMarksTest {
    private val lh = 90f
    private val rowTop = 0f
    private val textX0 = 20f
    private val textX1 = 420f // 400px of text

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 20): List<InkPoint> =
        List(n) { i ->
            val f = i / (n - 1f)
            InkPoint(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, i * 10L)
        }

    @Test
    fun `horizontal line across text is a strike`() {
        val stroke = line(10f, 45f, 430f, 50f)
        assertEquals(RowMarks.Kind.STRIKE, RowMarks.classify(stroke, rowTop, lh, lh, textX0, textX1).kind)
    }

    @Test
    fun `short dash is not a strike`() {
        val stroke = line(430f, 45f, 500f, 47f) // beyond text end — an amendment
        assertEquals(RowMarks.Kind.NONE, RowMarks.classify(stroke, rowTop, lh, lh, textX0, textX1).kind)
    }

    @Test
    fun `zigzag across text is a scribble`() {
        val pts = mutableListOf<InkPoint>()
        var t = 0L
        for (sweep in 0 until 6) {
            val fromX = if (sweep % 2 == 0) 30f else 400f
            val toX = if (sweep % 2 == 0) 400f else 30f
            for (i in 0 until 10) {
                val f = i / 9f
                pts.add(InkPoint(fromX + (toX - fromX) * f, 30f + sweep * 5f, t))
                t += 10
            }
        }
        assertEquals(RowMarks.Kind.SCRIBBLE, RowMarks.classify(pts, rowTop, lh, lh, textX0, textX1).kind)
    }

    @Test
    fun `diagonal stroke is neither`() {
        val stroke = line(30f, 10f, 400f, 85f)
        assertEquals(RowMarks.Kind.NONE, RowMarks.classify(stroke, rowTop, lh, lh, textX0, textX1).kind)
    }

    @Test
    fun `stroke leaving the row band is neither`() {
        val stroke = line(30f, 45f, 400f, 200f)
        assertEquals(RowMarks.Kind.NONE, RowMarks.classify(stroke, rowTop, lh, lh, textX0, textX1).kind)
    }
}
