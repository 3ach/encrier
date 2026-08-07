package com.zachzundel.encrier.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.zachzundel.encrier.Tunables
import com.zachzundel.encrier.data.InkPoint
import com.zachzundel.encrier.data.PageEntity
import kotlin.math.abs

/**
 * A free sketch page: stylus draws, finger scrolls, barrel button erases.
 * The page extends downward as far as its ink plus one screen.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SketchScreen(vm: SketchViewModel) {
    val strokes by vm.strokes.collectAsState()
    val page by vm.currentPage.collectAsState()
    val overlay by vm.overlay.collectAsState()
    val density = LocalDensity.current
    val gridPx = with(density) { Tunables.SKETCH_GRID_DP.dp.toPx() }
    val eraseRadiusPx = with(density) { Tunables.ERASE_RADIUS_DP.dp.toPx() }

    var scroll by remember { mutableFloatStateOf(0f) }
    var viewportH by remember { mutableFloatStateOf(0f) }
    var eraserPos by remember { mutableStateOf<Offset?>(null) }
    val barrelHeld = remember { mutableStateOf(false) }
    val active = remember { mutableStateListOf<InkPoint>() } // page coords

    val inkBottom = remember(strokes) {
        strokes.maxOfOrNull { s -> s.points.maxOf { it.y } } ?: 0f
    }
    fun maxScroll() = inkBottom.coerceAtLeast(0f)

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { viewportH = it.height.toFloat() }
                .motionEventSpy { ev -> barrelHeld.value = isBarrelButtonState(ev.buttonState) }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val down = event.changes.firstOrNull { it.changedToDown() } ?: continue
                            down.consume()
                            val stylusLike = down.type == PointerType.Stylus ||
                                down.type == PointerType.Eraser
                            if (stylusLike && (down.type == PointerType.Eraser || barrelHeld.value)) {
                                trackStroke(down) { pos, _ ->
                                    eraserPos = pos
                                    vm.eraseAt(InkPoint(pos.x, pos.y + scroll, 0L), eraseRadiusPx)
                                }
                                eraserPos = null
                            } else if (stylusLike) {
                                active.clear()
                                trackStroke(down) { pos, t ->
                                    active.add(InkPoint(pos.x, pos.y + scroll, t))
                                }
                                if (active.isNotEmpty()) vm.addStroke(active.toList())
                                active.clear()
                            } else {
                                // Touch scrolls.
                                var prevY = down.position.y
                                while (true) {
                                    val ev2 = awaitPointerEvent()
                                    val ch = ev2.changes.firstOrNull { it.id == down.id } ?: break
                                    scroll = (scroll - (ch.position.y - prevY))
                                        .coerceIn(0f, maxScroll())
                                    prevY = ch.position.y
                                    ch.consume()
                                    if (!ch.pressed) break
                                }
                            }
                        }
                    }
                }
        ) {
            drawRect(InkWhite)
            when (page?.background) {
                PageEntity.DOTS -> {
                    var y = -(scroll % gridPx)
                    while (y <= size.height) {
                        var x = gridPx / 2f
                        while (x < size.width) {
                            drawCircle(InkMargin, radius = 1.5f, center = Offset(x, y))
                            x += gridPx
                        }
                        y += gridPx
                    }
                }
                PageEntity.LINES -> {
                    var y = -(scroll % gridPx)
                    while (y <= size.height) {
                        drawLine(InkFaint, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        y += gridPx
                    }
                    var x = gridPx / 2f
                    while (x < size.width) {
                        drawLine(InkFaint, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                        x += gridPx
                    }
                }
                else -> Unit // blank
            }

            for (s in strokes) drawStroke(s.points, dy = -scroll)
            for (s in overlay) drawStroke(s, dy = -scroll)
            if (active.size > 1) drawStroke(active, dy = -scroll)

            eraserPos?.let { pos ->
                drawCircle(InkGray, radius = eraseRadiusPx, center = pos, style = Stroke(width = 1.5f))
            }
        }
    }
}
