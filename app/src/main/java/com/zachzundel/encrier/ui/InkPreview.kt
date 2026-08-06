package com.zachzundel.encrier.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.zachzundel.encrier.data.InkPoint

private data class InkXform(
    val minX: Float,
    val minY: Float,
    val scale: Float,
    val ox: Float,
    val oy: Float,
)

/**
 * The panel's handwriting box: shows the source ink and accepts new stylus
 * strokes, mapping them back through the (frozen) display transform into line
 * coordinates for [onStroke]. Frozen so the ink doesn't reflow mid-writing.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun WritableInkPreview(
    strokes: List<List<InkPoint>>,
    modifier: Modifier,
    onStroke: (List<InkPoint>) -> Unit,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var xform by remember { mutableStateOf<InkXform?>(null) }
    val active = remember { mutableStateListOf<InkPoint>() } // box coords
    // Finished strokes (line coords) kept locally until the stored copies come
    // back through the flow — bridging the gap so they don't flash out.
    val justWritten = remember { mutableStateListOf<Pair<Int, List<InkPoint>>>() }
    val strokesState = rememberUpdatedState(strokes)
    val onStrokeState = rememberUpdatedState(onStroke)
    val xformState = rememberUpdatedState(xform)

    LaunchedEffect(strokes.size) {
        justWritten.removeAll { it.first <= strokes.size }
    }

    LaunchedEffect(boxSize, strokes.isNotEmpty()) {
        if (xform == null && boxSize.width > 0 && strokes.isNotEmpty()) {
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
            val scale = minOf(
                boxSize.width / (w + 40f),
                boxSize.height / (h + 20f),
                1.5f,
            )
            xform = InkXform(
                minX, minY, scale,
                ox = (boxSize.width - w * scale) / 2f,
                oy = (boxSize.height - h * scale) / 2f,
            )
        }
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val down = event.changes.firstOrNull { it.changedToDown() } ?: continue
                        if (down.type != PointerType.Stylus) continue // touch passes to scroll
                        down.consume()
                        active.clear()
                        active.add(InkPoint(down.position.x, down.position.y, down.uptimeMillis))
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            for (h in ch.historical) {
                                active.add(InkPoint(h.position.x, h.position.y, h.uptimeMillis))
                            }
                            if (ch.pressed) {
                                active.add(InkPoint(ch.position.x, ch.position.y, ch.uptimeMillis))
                            }
                            ch.consume()
                            if (!ch.pressed) break
                        }
                        val xf = xformState.value
                        if (xf != null && active.isNotEmpty()) {
                            val mapped = active.map {
                                InkPoint(
                                    xf.minX + (it.x - xf.ox) / xf.scale,
                                    xf.minY + (it.y - xf.oy) / xf.scale,
                                    it.t,
                                )
                            }
                            justWritten.add(
                                (strokesState.value.size + justWritten.size + 1) to mapped
                            )
                            onStrokeState.value(mapped)
                        }
                        active.clear()
                    }
                }
            }
    ) {
        val xf = xformState.value
        if (xf != null) {
            for (s in strokesState.value + justWritten.map { it.second }) {
                if (s.isEmpty()) continue
                if (s.size == 1) {
                    drawCircle(
                        InkBlack,
                        radius = 2f,
                        center = Offset(
                            xf.ox + (s[0].x - xf.minX) * xf.scale,
                            xf.oy + (s[0].y - xf.minY) * xf.scale,
                        ),
                    )
                    continue
                }
                val path = Path()
                for ((j, p) in s.withIndex()) {
                    val x = xf.ox + (p.x - xf.minX) * xf.scale
                    val y = xf.oy + (p.y - xf.minY) * xf.scale
                    if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, InkBlack, style = Stroke(width = 2.5f))
            }
        }
        if (active.size > 1) {
            val path = Path()
            for ((j, p) in active.withIndex()) {
                if (j == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, InkBlack, style = Stroke(width = 2.5f))
        }
    }
}
