package com.zachzundel.encrier.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange

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
