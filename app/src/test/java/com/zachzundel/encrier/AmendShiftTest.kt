package com.zachzundel.encrier

import com.zachzundel.encrier.ui.amendShift
import org.junit.Assert.assertEquals
import org.junit.Test

class AmendShiftTest {
    @Test
    fun `no existing ink means no shift`() {
        assertEquals(0f, amendShift(inkMaxX = null, gapPx = 24f, relMinX = 40f), 0f)
    }

    @Test
    fun `shift lands the stroke one gap past the ink`() {
        // Ink ends at x=100, stroke starts at x=40: shift so it starts at 124.
        assertEquals(84f, amendShift(inkMaxX = 100f, gapPx = 24f, relMinX = 40f), 1e-4f)
    }

    @Test
    fun `stroke already right of the ink shifts backward to close the gap`() {
        assertEquals(-76f, amendShift(inkMaxX = 100f, gapPx = 24f, relMinX = 200f), 1e-4f)
    }

    @Test
    fun `cached offset keeps later strokes in written order`() {
        // The offset is computed once per amendment and reused, so strokes keep
        // their relative positions: first at 40 -> 124, second at 70 -> 154.
        val offset = amendShift(inkMaxX = 100f, gapPx = 24f, relMinX = 40f)
        assertEquals(124f, 40f + offset, 1e-4f)
        assertEquals(154f, 70f + offset, 1e-4f)
    }
}
