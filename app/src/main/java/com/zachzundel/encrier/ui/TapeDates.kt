package com.zachzundel.encrier.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The date-relevant slice of a tape row, in tape order. */
data class DatedRow(val lineId: Long, val firstInkAt: Long?) // ts: item listed date if present, else first ink

private fun dateOf(ts: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

/**
 * Slot index → the timestamp opening that day's run. A run starts wherever a
 * row's date differs from the previous inked row's — children inserted
 * mid-tape start (and end) runs of their own. Drives day markers (spec §3).
 */
fun dayMarkerSlots(rows: List<DatedRow>, zone: ZoneId): Map<Int, Long> {
    val markers = mutableMapOf<Int, Long>()
    var lastDate: LocalDate? = null
    for ((i, row) in rows.withIndex()) {
        val ts = row.firstInkAt ?: continue
        val d = dateOf(ts, zone)
        if (d != lastDate) {
            markers[i] = ts
            lastDate = d
        }
    }
    return markers
}

/**
 * Distinct dates with any ink (most recent first), each mapped to the first
 * line of that date's LATEST run — children insert mid-tape, so the first
 * occurrence of a date can be a lone interleaved row far from today's writing.
 */
fun availableDates(rows: List<DatedRow>, zone: ZoneId): List<Pair<LocalDate, Long>> {
    val runStart = mutableMapOf<LocalDate, Long>()
    var lastDate: LocalDate? = null
    for (row in rows) {
        val ts = row.firstInkAt ?: continue
        val d = dateOf(ts, zone)
        if (d != lastDate) {
            runStart[d] = row.lineId // later runs overwrite earlier ones
            lastDate = d
        }
    }
    return runStart.map { (d, id) -> d to id }.sortedByDescending { it.first }
}
