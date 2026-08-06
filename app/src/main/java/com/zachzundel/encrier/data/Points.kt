package com.zachzundel.encrier.data

import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class InkPoint(val x: Float, val y: Float, val t: Long)

/** Axis-aligned bounding box of one or more strokes. */
data class InkBounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

fun List<InkPoint>.bounds(): InkBounds? {
    if (isEmpty()) return null
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (p in this) {
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }
    return InkBounds(minX, minY, maxX, maxY)
}

@JvmName("strokesBounds")
fun List<List<InkPoint>>.bounds(): InkBounds? = flatten().bounds()

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

fun encodeStrokes(strokes: List<List<InkPoint>>): String =
    JSONArray().also { arr -> strokes.forEach { arr.put(JSONArray(encodePoints(it))) } }.toString()

fun decodeStrokes(json: String): List<List<InkPoint>> {
    val arr = JSONArray(json)
    return List(arr.length()) { i -> decodePoints(arr.getJSONArray(i).toString()) }
}

fun encodeCandidates(candidates: List<String>): String =
    JSONArray().also { arr -> candidates.forEach(arr::put) }.toString()

fun decodeCandidates(json: String): List<String> {
    val arr = JSONArray(json)
    return List(arr.length()) { i -> arr.getString(i) }
}

const val DAY_MS = 86_400_000L

/** The tape lives on the owner's wall clock, not the device clock (which runs UTC). */
val TAPE_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

fun localDate(ts: Long): LocalDate =
    Instant.ofEpochMilli(ts).atZone(TAPE_ZONE).toLocalDate()

private val dayMarkerFmt = DateTimeFormatter.ofPattern("MMM d — EEE", Locale.US)
private val shortDateFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)

fun dayMarkerLabel(ts: Long): String = dayMarkerFmt.format(localDate(ts)).uppercase(Locale.US)
fun shortDate(ts: Long): String = shortDateFmt.format(localDate(ts))
