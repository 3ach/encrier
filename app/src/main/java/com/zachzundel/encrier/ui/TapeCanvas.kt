package com.zachzundel.encrier.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zachzundel.encrier.data.InkPoint
import com.zachzundel.encrier.data.ItemEntity
import com.zachzundel.encrier.data.shortDate

internal fun DrawScope.drawItemRow(
    tm: TextMeasurer,
    item: ItemEntity,
    childStat: Pair<Int, Int>?,
    top: Float,
    rowH: Float,
    isChild: Boolean,
    boundsOut: MutableMap<Long, FloatArray>,
) {
    val done = item.status == ItemEntity.DONE
    val dropped = item.status == ItemEntity.DROPPED
    val textX = if (isChild) 84.dp.toPx() else 54.dp.toPx()
    // TextMeasurer's layout cache ignores draw-only attributes (color,
    // textDecoration) but returns layouts that still carry them — a struck-
    // through layout would be replayed after REOPEN. Measure with a constant
    // style; apply color at draw time and the strike as an explicit line.
    val layout = tm.measure(
        item.text,
        style = TextStyle(fontSize = 16.sp, fontFamily = Serif),
        maxLines = 1,
    )
    boundsOut[item.lineId] = floatArrayOf(textX, textX + layout.size.width)
    val textTop = top + (rowH - layout.size.height) / 2f
    drawText(
        layout,
        color = if (dropped) InkGray else InkBlack,
        topLeft = Offset(textX, textTop),
    )
    if (done) {
        val cy = textTop + layout.size.height / 2f
        drawLine(
            InkBlack,
            Offset(textX - 2.dp.toPx(), cy),
            Offset(textX + layout.size.width + 2.dp.toPx(), cy),
            strokeWidth = 2.5f,
        )
    }

    val meta = buildString {
        childStat?.let { (d, t) -> append(d).append("/").append(t).append("  ") }
        append(shortDate(item.createdAt))
    }
    val metaLayout = tm.measure(
        meta,
        style = TextStyle(color = InkGray, fontSize = 11.sp, fontFamily = Mono),
        maxLines = 1,
    )
    drawText(
        metaLayout,
        topLeft = Offset(
            size.width - metaLayout.size.width - 12.dp.toPx(),
            top + (rowH - metaLayout.size.height) / 2f,
        ),
    )
}

internal fun DrawScope.drawInkStroke(points: List<InkPoint>, top: Float) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(InkBlack, radius = 2f, center = Offset(points[0].x, points[0].y + top))
        return
    }
    val path = Path()
    for ((j, p) in points.withIndex()) {
        if (j == 0) path.moveTo(p.x, p.y + top) else path.lineTo(p.x, p.y + top)
    }
    drawPath(path, InkBlack, style = Stroke(width = 3f))
}

internal fun DrawScope.drawContentStroke(points: List<InkPoint>, scroll: Float) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(InkBlack, radius = 2f, center = Offset(points[0].x, points[0].y - scroll))
        return
    }
    val path = Path()
    for ((j, p) in points.withIndex()) {
        if (j == 0) path.moveTo(p.x, p.y - scroll) else path.lineTo(p.x, p.y - scroll)
    }
    drawPath(path, InkBlack, style = Stroke(width = 3f))
}

/** Crisp connector glyph (└─) marking a spawned child line (spec §5). */
internal fun DrawScope.drawConnector(top: Float, rowH: Float) {
    val x = 60.dp.toPx()
    val midY = top + rowH * 0.55f
    drawLine(InkGray, Offset(x, top + rowH * 0.1f), Offset(x, midY), strokeWidth = 2f)
    drawLine(InkGray, Offset(x, midY), Offset(x + 18.dp.toPx(), midY), strokeWidth = 2f)
}
