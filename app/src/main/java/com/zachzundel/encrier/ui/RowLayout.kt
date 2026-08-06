package com.zachzundel.encrier.ui

/**
 * Variable-height row layout: committed text rows are tight, ink/blank/grown
 * rows are full writing height. Slots at or past the end of the rows are
 * writing-height blanks. Shared by rendering and stroke routing so both agree
 * on the y ↔ row mapping; a stroke carries the layout it was captured under.
 */
class RowLayout(
    val tops: FloatArray,
    val heights: FloatArray,
    val writeLh: Float,
) {
    val end: Float
        get() = if (tops.isEmpty()) 0f else tops[tops.size - 1] + heights[heights.size - 1]

    fun slotAt(y: Float): Int {
        if (y < 0f) return 0
        if (tops.isEmpty() || y >= end) {
            val past = if (tops.isEmpty()) y else y - end
            return tops.size + (past / writeLh).toInt()
        }
        var lo = 0
        var hi = tops.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun topOf(slot: Int): Float =
        if (slot < tops.size) tops[slot] else end + (slot - tops.size) * writeLh

    fun heightOf(slot: Int): Float = if (slot < heights.size) heights[slot] else writeLh
}
