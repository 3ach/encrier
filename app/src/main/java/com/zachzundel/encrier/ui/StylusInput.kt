package com.zachzundel.encrier.ui

import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange

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
