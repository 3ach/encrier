package com.zachzundel.encrier

import com.zachzundel.encrier.ui.DatedRow
import com.zachzundel.encrier.ui.availableDates
import com.zachzundel.encrier.ui.dayMarkerSlots
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapeDatesTest {
    private val la = ZoneId.of("America/Los_Angeles")

    private fun ts(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun `0530Z is the previous day in Los Angeles`() {
        val rows = listOf(DatedRow(1L, ts("2026-08-05T05:30:00Z")))
        assertEquals(
            listOf(LocalDate.of(2026, 8, 4) to 1L),
            availableDates(rows, la),
        )
        assertEquals(mapOf(0 to ts("2026-08-05T05:30:00Z")), dayMarkerSlots(rows, la))
    }

    @Test
    fun `interleaved child starts and ends runs`() {
        // Dates A, A, B (child inserted mid-tape), A.
        val rows = listOf(
            DatedRow(1L, ts("2026-08-04T18:00:00Z")), // A
            DatedRow(2L, ts("2026-08-04T19:00:00Z")), // A
            DatedRow(3L, ts("2026-08-03T18:00:00Z")), // B
            DatedRow(4L, ts("2026-08-04T20:00:00Z")), // A again — new run
        )
        assertEquals(setOf(0, 2, 3), dayMarkerSlots(rows, la).keys)
        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 4) to 4L, // A maps to its LAST run's start
                LocalDate.of(2026, 8, 3) to 3L, // B maps to its own row
            ),
            availableDates(rows, la),
        )
    }

    @Test
    fun `inkless rows are skipped without breaking runs`() {
        val rows = listOf(
            DatedRow(1L, ts("2026-08-04T18:00:00Z")),
            DatedRow(2L, null),
            DatedRow(3L, ts("2026-08-04T19:00:00Z")),
        )
        assertEquals(setOf(0), dayMarkerSlots(rows, la).keys)
        assertEquals(listOf(LocalDate.of(2026, 8, 4) to 1L), availableDates(rows, la))
    }

    @Test
    fun `empty tape yields nothing`() {
        assertTrue(availableDates(emptyList(), la).isEmpty())
        assertTrue(dayMarkerSlots(emptyList(), la).isEmpty())
    }
}
