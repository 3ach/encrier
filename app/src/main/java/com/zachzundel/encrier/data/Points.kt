package com.zachzundel.encrier.data

import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class InkPoint(val x: Float, val y: Float, val t: Long)

fun encodePoints(points: List<InkPoint>): String {
    val arr = JSONArray()
    for (p in points) {
        arr.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()).put(p.t))
    }
    return arr.toString()
}

fun decodePoints(json: String): List<InkPoint> {
    val arr = JSONArray(json)
    return List(arr.length()) { i ->
        val p = arr.getJSONArray(i)
        InkPoint(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getLong(2))
    }
}

fun encodeCandidates(candidates: List<String>): String =
    JSONArray().also { arr -> candidates.forEach(arr::put) }.toString()

fun decodeCandidates(json: String): List<String> {
    val arr = JSONArray(json)
    return List(arr.length()) { i -> arr.getString(i) }
}

fun localDate(ts: Long): LocalDate =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

private val dayMarkerFmt = DateTimeFormatter.ofPattern("MMM d — EEE", Locale.US)
private val shortDateFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)

fun dayMarkerLabel(ts: Long): String = dayMarkerFmt.format(localDate(ts)).uppercase(Locale.US)
fun shortDate(ts: Long): String = shortDateFmt.format(localDate(ts))
