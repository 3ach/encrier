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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zachzundel.encrier.Tunables
import com.zachzundel.encrier.data.InkPoint
import com.zachzundel.encrier.data.bounds

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
    onErase: (InkPoint, Float) -> Unit,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val barrelHeld = remember { mutableStateOf(false) }
    var xform by remember { mutableStateOf<InkXform?>(null) }
    val active = remember { mutableStateListOf<InkPoint>() } // box coords
    // Finished strokes (line coords) kept locally until the stored copies come
    // back through the flow — bridging the gap so they don't flash out.
    val justWritten = remember { mutableStateListOf<Pair<Int, List<InkPoint>>>() }
    val strokesState = rememberUpdatedState(strokes)
    val onStrokeState = rememberUpdatedState(onStroke)
    val onEraseState = rememberUpdatedState(onErase)
    val xformState = rememberUpdatedState(xform)

    var prevSize by remember { mutableStateOf(strokes.size) }
    LaunchedEffect(strokes.size) {
        if (strokes.size < prevSize) {
            justWritten.clear() // strokes were erased; stored list is authoritative
        } else {
            justWritten.removeAll { it.first <= strokes.size }
        }
        prevSize = strokes.size
    }

    LaunchedEffect(boxSize, strokes.isNotEmpty()) {
        if (xform == null && boxSize.width > 0 && strokes.isNotEmpty()) {
            val b = strokes.bounds() ?: return@LaunchedEffect
            val w = (b.maxX - b.minX).coerceAtLeast(1f)
            val h = (b.maxY - b.minY).coerceAtLeast(1f)
            val scale = minOf(
                boxSize.width / (w + 40f),
                boxSize.height / (h + 20f),
                1.5f,
            )
            xform = InkXform(
                b.minX, b.minY, scale,
                ox = (boxSize.width - w * scale) / 2f,
                oy = (boxSize.height - h * scale) / 2f,
            )
        }
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { boxSize = it }
            .motionEventSpy { ev -> barrelHeld.value = isBarrelButtonState(ev.buttonState) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val down = event.changes.firstOrNull { it.changedToDown() } ?: continue
                        if (down.type != PointerType.Stylus &&
                            down.type != PointerType.Eraser
                        ) continue // touch passes to scroll
                        down.consume()
                        android.util.Log.i(
                            "EraserDebug",
                            "box down type=${down.type} barrel=${barrelHeld.value}"
                        )
                        if (down.type == PointerType.Eraser ||
                            barrelHeld.value
                        ) {
                            // Barrel button: erase strokes in line coordinates.
                            fun erase(pos: Offset) {
                                val xf = xformState.value ?: return
                                onEraseState.value(
                                    InkPoint(
                                        xf.minX + (pos.x - xf.ox) / xf.scale,
                                        xf.minY + (pos.y - xf.oy) / xf.scale,
                                        0L,
                                    ),
                                    Tunables.ERASE_RADIUS_DP.dp.toPx() / xf.scale,
                                )
                            }
                            trackStroke(down) { pos, _ -> erase(pos) }
                            continue
                        }
                        active.clear()
                        trackStroke(down) { pos, t -> active.add(InkPoint(pos.x, pos.y, t)) }
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
                drawStroke(
                    s,
                    { Offset(xf.ox + (it.x - xf.minX) * xf.scale, xf.oy + (it.y - xf.minY) * xf.scale) },
                    width = 2.5f,
                )
            }
        }
        if (active.size > 1) drawStroke(active, { Offset(it.x, it.y) }, width = 2.5f)
    }
}
