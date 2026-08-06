package com.zachzundel.encrier.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.zachzundel.encrier.data.InkPoint

/** Draws one ink stroke, mapping each point to screen space via [map]. A
 *  single-point stroke renders as a dot. */
internal fun DrawScope.drawStroke(
    points: List<InkPoint>,
    map: (InkPoint) -> Offset,
    width: Float = 3f,
) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(InkBlack, radius = 2f, center = map(points[0]))
        return
    }
    val path = Path()
    for ((j, p) in points.withIndex()) {
        val o = map(p)
        if (j == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
    }
    drawPath(path, InkBlack, style = Stroke(width = width))
}
