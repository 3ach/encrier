package com.zachzundel.encrier.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.zachzundel.encrier.data.InkPoint

/** Source ink drawn from stored strokes, not a font (spec §6). */
@Composable
fun InkPreview(strokes: List<List<InkPoint>>, modifier: Modifier) {
    Canvas(modifier) {
        if (strokes.isEmpty()) return@Canvas
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (s in strokes) for (p in s) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val w = (maxX - minX).coerceAtLeast(1f)
        val h = (maxY - minY).coerceAtLeast(1f)
        val scale = minOf(size.width / (w + 20f), size.height / (h + 20f), 1.5f)
        val ox = (size.width - w * scale) / 2f
        val oy = (size.height - h * scale) / 2f
        for (s in strokes) {
            if (s.size == 1) {
                drawCircle(
                    InkBlack,
                    radius = 2f,
                    center = Offset(ox + (s[0].x - minX) * scale, oy + (s[0].y - minY) * scale),
                )
                continue
            }
            val path = Path()
            for ((j, p) in s.withIndex()) {
                val x = ox + (p.x - minX) * scale
                val y = oy + (p.y - minY) * scale
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, InkBlack, style = Stroke(width = 2.5f))
        }
    }
}
