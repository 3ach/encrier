package com.zachzundel.encrier.ui

import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import com.zachzundel.encrier.data.InkPoint

/**
 * The stylus barrel button specifically. Compose folds BUTTON_STYLUS_PRIMARY
 * into isPrimaryPressed (same flag as plain tip contact) and hides the raw
 * bitmask, so the barrel state must be read off the raw MotionEvent via
 * motionEventSpy and tested with this mask.
 */
internal fun isBarrelButtonState(buttonState: Int): Boolean =
    buttonState and (
        MotionEvent.BUTTON_STYLUS_PRIMARY or
            MotionEvent.BUTTON_STYLUS_SECONDARY or
            MotionEvent.BUTTON_SECONDARY
        ) != 0

/**
 * Standard stylus capture loop: emits the (already consumed) down sample,
 * then every historical and current sample per event — consuming each — and
 * returns when the pen lifts.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal suspend fun AwaitPointerEventScope.trackStroke(
    down: PointerInputChange,
    onSample: (Offset, Long) -> Unit,
) {
    onSample(down.position, down.uptimeMillis)
    while (true) {
        val event = awaitPointerEvent()
        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
        for (h in ch.historical) onSample(h.position, h.uptimeMillis)
        if (ch.pressed) onSample(ch.position, ch.uptimeMillis)
        ch.consume()
        if (!ch.pressed) break
    }
}

/** Draws one ink stroke shifted vertically by [dy]. */
internal fun DrawScope.drawStroke(points: List<InkPoint>, dy: Float = 0f, width: Float = 3f) =
    drawStroke(points, { Offset(it.x, it.y + dy) }, width)

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
