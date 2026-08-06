package com.zachzundel.encrier

import com.zachzundel.encrier.data.InkPoint
import com.zachzundel.encrier.data.decodeCandidates
import com.zachzundel.encrier.data.decodePoints
import com.zachzundel.encrier.data.decodeStrokes
import com.zachzundel.encrier.data.encodeCandidates
import com.zachzundel.encrier.data.encodePoints
import com.zachzundel.encrier.data.encodeStrokes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointsCodecTest {
    private val stroke = listOf(
        InkPoint(0f, 0f, 0L),
        InkPoint(1.5f, -2.25f, 10L),
        InkPoint(320.75f, 71.5f, 12345678901L),
    )

    @Test
    fun `points round-trip`() {
        assertEquals(stroke, decodePoints(encodePoints(stroke)))
    }

    @Test
    fun `empty points round-trip`() {
        assertTrue(decodePoints(encodePoints(emptyList())).isEmpty())
    }

    @Test
    fun `strokes round-trip`() {
        val strokes = listOf(stroke, listOf(InkPoint(5f, 6f, 7L)))
        assertEquals(strokes, decodeStrokes(encodeStrokes(strokes)))
    }

    @Test
    fun `empty strokes round-trip`() {
        assertTrue(decodeStrokes(encodeStrokes(emptyList())).isEmpty())
    }

    @Test
    fun `candidates round-trip`() {
        val candidates = listOf("buy milk", "buy müesli", "6uy milk")
        assertEquals(candidates, decodeCandidates(encodeCandidates(candidates)))
    }

    @Test
    fun `empty candidates round-trip`() {
        assertTrue(decodeCandidates(encodeCandidates(emptyList())).isEmpty())
    }
}
