package com.zachzundel.encrier

import com.zachzundel.encrier.ui.RowLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class RowLayoutTest {
    private val lh = 72f

    /** Rows stacked from y=0; lineIds default to 1-based row numbers. */
    private fun layout(vararg heights: Float, lineIds: LongArray? = null): RowLayout {
        val tops = FloatArray(heights.size)
        var y = 0f
        for (i in heights.indices) {
            tops[i] = y
            y += heights[i]
        }
        return RowLayout(tops, heights, lh, lineIds ?: LongArray(heights.size) { it + 1L })
    }

    @Test
    fun `empty layout maps everything to virtual slots`() {
        val l = layout()
        assertEquals(0f, l.end, 0f)
        assertEquals(0, l.slotAt(0f))
        assertEquals(0, l.slotAt(71.9f))
        assertEquals(1, l.slotAt(72f))
        assertEquals(3, l.slotAt(3 * 72f))
        assertEquals(2 * 72f, l.topOf(2), 0f)
        assertEquals(lh, l.heightOf(0), 0f)
    }

    @Test
    fun `negative y clamps to slot zero`() {
        assertEquals(0, layout().slotAt(-5f))
        assertEquals(0, layout(72f, 34f).slotAt(-0.1f))
    }

    @Test
    fun `exact top boundaries land on their own slot`() {
        val l = layout(72f, 34f, 48f, 72f)
        for (i in l.tops.indices) {
            assertEquals(i, l.slotAt(l.tops[i]))
        }
    }

    @Test
    fun `mixed heights route interior points`() {
        val l = layout(72f, 34f, 48f) // tops 0, 72, 106; end 154
        assertEquals(0, l.slotAt(71.9f))
        assertEquals(1, l.slotAt(80f))
        assertEquals(1, l.slotAt(105.9f))
        assertEquals(2, l.slotAt(120f))
    }

    @Test
    fun `virtual slots beyond the end step by writing height`() {
        val l = layout(72f, 34f) // end 106
        assertEquals(2, l.slotAt(106f))
        assertEquals(2, l.slotAt(106f + 71.9f))
        assertEquals(3, l.slotAt(106f + 72f))
        assertEquals(106f, l.topOf(2), 0f)
        assertEquals(106f + 72f, l.topOf(3), 0f)
    }

    @Test
    fun `heightOf beyond the end is writing height`() {
        val l = layout(72f, 34f)
        assertEquals(34f, l.heightOf(1), 0f)
        assertEquals(lh, l.heightOf(2), 0f)
        assertEquals(lh, l.heightOf(99), 0f)
    }

    @Test
    fun `lineIds bind slots to capture-time lines`() {
        val l = layout(72f, 34f, 48f, lineIds = longArrayOf(10L, 20L, 30L))
        assertEquals(10L, l.lineIds[l.slotAt(0f)])
        assertEquals(20L, l.lineIds[l.slotAt(80f)])
        assertEquals(30L, l.lineIds[l.slotAt(120f)])
        assertEquals(3, l.lineIds.size)
    }
}
