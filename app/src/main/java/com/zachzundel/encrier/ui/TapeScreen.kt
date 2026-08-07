package com.zachzundel.encrier.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zachzundel.encrier.Tunables
import com.zachzundel.encrier.data.InkPoint
import com.zachzundel.encrier.data.ItemEntity
import com.zachzundel.encrier.data.TAPE_ZONE
import com.zachzundel.encrier.data.dayMarkerLabel
import com.zachzundel.encrier.data.shortDate
import kotlin.math.abs

/**
 * Whether a row displays its ink rather than its committed text: blank rows
 * always, pending rows once their display latched to ink, committed rows
 * under a dwelled hover. (Hover *eligibility* in the pointer loop is a
 * different predicate — has an item, and not pending unless latched — so it
 * stays inline there.)
 */
private fun showsInk(item: ItemEntity?, pending: Boolean, latched: Boolean, hovered: Boolean): Boolean =
    item == null || (pending && latched) || (!pending && hovered)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TapeScreen(vm: TapeViewModel) {
    val rows by vm.rows.collectAsState()
    val currentTapeId by vm.currentTapeId.collectAsState()
    val scrollRequest by vm.scrollToLineId.collectAsState()
    val cache by vm.strokeCache.collectAsState()
    val uncommitted by vm.uncommitted.collectAsState()
    val overlay by vm.overlayStrokes.collectAsState()
    val amendDisplay by vm.amendDisplay.collectAsState()
    val panel by vm.panel.collectAsState()
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current
    val lh = with(density) { Tunables.LINE_HEIGHT_DP.dp.toPx() }
    val textRowPx = with(density) { Tunables.TEXT_ROW_DP.dp.toPx() }
    val markerPx = with(density) { Tunables.DAY_MARKER_INSET_DP.dp.toPx() }

    var scroll by remember { mutableFloatStateOf(0f) }
    var viewportH by remember { mutableFloatStateOf(0f) }
    var hoverSlot by remember { mutableStateOf<Int?>(null) } // content slot under the pen
    var eraserPos by remember { mutableStateOf<Offset?>(null) } // screen coords while erasing
    // Raw barrel-button state, observed off MotionEvents (Compose hides the bits).
    val barrelHeld = remember { mutableStateOf(false) }
    val eraseRadiusPx = with(density) { Tunables.ERASE_RADIUS_DP.dp.toPx() }
    val touchSlopPx = with(density) { Tunables.TOUCH_TAP_SLOP_DP.dp.toPx() }
    // Display mode latched per line at the first amendment stroke: true = ink
    // was revealed when writing began. Sticky until the amendment commits.
    val latchedInk = remember { mutableStateMapOf<Long, Boolean>() }
    val active = remember { mutableStateListOf<InkPoint>() } // content coords

    LaunchedEffect(uncommitted) { latchedInk.keys.retainAll(uncommitted) }

    // Day markers derived at render time from data (spec §3).
    val dayMarkers: Map<Int, String> = remember(rows) {
        dayMarkerSlots(rows.map { DatedRow(it.line.id, it.item?.createdAt ?: it.line.firstInkAt) }, TAPE_ZONE)
            .mapValues { (_, ts) -> dayMarkerLabel(ts) }
    }

    // Variable-height layout: tight rows for committed text, writing height for
    // ink/blank rows and for the hover-grown or ink-latched row.
    val layout = run {
        val tops = FloatArray(rows.size)
        val heights = FloatArray(rows.size)
        val lineIds = LongArray(rows.size)
        var y = 0f
        for ((i, row) in rows.withIndex()) {
            val pending = row.line.id in uncommitted
            val grownInk = showsInk(row.item, pending, latchedInk[row.line.id] == true, hoverSlot == i)
            var h = if (grownInk) lh else textRowPx
            if (!grownInk && dayMarkers.containsKey(i)) h += markerPx
            tops[i] = y
            heights[i] = h
            lineIds[i] = row.line.id
            y += h
        }
        RowLayout(tops, heights, lh, lineIds)
    }
    val layoutState = rememberUpdatedState(layout)

    fun maxScroll(): Float {
        val l = layoutState.value
        return (if (l.tops.isEmpty()) 0f else l.tops[l.tops.size - 1]).coerceAtLeast(0f)
    }

    fun scrollToLatest() {
        val l = layoutState.value
        scroll = (l.end - viewportH + lh).coerceIn(0f, maxScroll())
    }

    LaunchedEffect(lh) {
        vm.lineHeightPx = lh
        vm.tapMaxLenPx = with(density) { Tunables.TAP_MAX_LEN_DP.dp.toPx() }
        vm.amendGapPx = with(density) { Tunables.AMEND_GAP_DP.dp.toPx() }
    }

    // Open scrolled to the last occupied line (spec §3). Keyed on the tape so
    // a tape switch re-runs it; per-tape pen state must not carry over either.
    var initialScrollDone by remember(currentTapeId) { mutableStateOf(false) }
    LaunchedEffect(currentTapeId) { hoverSlot = null }
    LaunchedEffect(currentTapeId, rows.size, viewportH) {
        if (!initialScrollDone && viewportH > 0f) {
            if (rows.isNotEmpty()) scrollToLatest()
            initialScrollDone = true
        }
    }
    // Rows can shrink out from under the scroll position (tape switch lands
    // its rows a beat after the switch; deletes close gaps): clamp.
    LaunchedEffect(rows) { scroll = scroll.coerceIn(0f, maxScroll()) }

    // Jump to date: put the requested row's top at the top of the viewport.
    LaunchedEffect(scrollRequest, rows) {
        val id = scrollRequest ?: return@LaunchedEffect
        val i = rows.indexOfFirst { it.line.id == id }
        if (i < 0) return@LaunchedEffect
        scroll = layoutState.value.topOf(i).coerceIn(0f, maxScroll())
        vm.consumeScrollRequest()
    }

    // Lazy-load strokes for the visible line range (spec §2).
    LaunchedEffect(scroll, rows, viewportH) {
        if (viewportH <= 0f || rows.isEmpty()) return@LaunchedEffect
        val l = layoutState.value
        val ids = rows.indices.filter { i ->
            l.topOf(i) + l.heightOf(i) >= scroll - 2 * lh && l.topOf(i) <= scroll + viewportH + 2 * lh
        }.map { rows[it].line.id }
        if (ids.isNotEmpty()) vm.loadVisible(ids)
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged {
                    viewportH = it.height.toFloat()
                    vm.tapeWidthPx = it.width.toFloat()
                }
                .motionEventSpy { ev -> barrelHeld.value = isBarrelButtonState(ev.buttonState) }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var hoverCandidate = -1
                        var hoverSince = 0L
                        while (true) {
                        val event = awaitPointerEvent()
                        val down = event.changes.firstOrNull { it.changedToDown() }
                        if (down == null) {
                            // Stylus hover grows a committed row back to ink —
                            // only after a deliberate dwell, so a pen descending
                            // to write doesn't trip it.
                            val h = event.changes.firstOrNull {
                                it.type == PointerType.Stylus && !it.pressed
                            }
                            if (h != null) {
                                if (event.type == PointerEventType.Exit) {
                                    hoverSlot = null
                                    hoverCandidate = -1
                                } else {
                                    val slot = layoutState.value.slotAt(h.position.y + scroll)
                                    if (slot != hoverCandidate) {
                                        hoverCandidate = slot
                                        hoverSince = h.uptimeMillis
                                        if (hoverSlot != null && hoverSlot != slot) hoverSlot = null
                                    } else if (h.uptimeMillis - hoverSince >= Tunables.HOVER_REVEAL_MS) {
                                        val row = rows.getOrNull(slot)
                                        val id = row?.line?.id
                                        val eligible = row?.item != null &&
                                            (id !in uncommitted || latchedInk[id] == true)
                                        if (eligible) hoverSlot = slot else hoverSince = h.uptimeMillis
                                    }
                                }
                            }
                            continue
                        }
                        down.consume()
                        val stylusLike =
                            down.type == PointerType.Stylus || down.type == PointerType.Eraser
                        if (stylusLike) {
                        }
                        if (down.type == PointerType.Eraser ||
                            (stylusLike && barrelHeld.value)
                        ) {
                            // Barrel button held: stroke eraser. Deletes whole
                            // strokes it contacts, on rows displaying ink.
                            fun eraseSample(pos: Offset) {
                                val cy = pos.y + scroll
                                val l = layoutState.value
                                val slot = l.slotAt(cy)
                                val row = rows.getOrNull(slot) ?: return
                                val pending = row.line.id in uncommitted
                                val inkVisible =
                                    showsInk(row.item, pending, latchedInk[row.line.id] == true, hoverSlot == slot)
                                if (!inkVisible) return
                                vm.eraseAt(
                                    row.line.id,
                                    InkPoint(pos.x, cy - l.topOf(slot), 0L),
                                    eraseRadiusPx,
                                )
                            }
                            trackStroke(down) { pos, _ ->
                                eraseSample(pos)
                                eraserPos = pos
                            }
                            eraserPos = null
                        } else if (down.type == PointerType.Stylus) {
                            // Latch the row's display mode at the first stroke of an
                            // amendment; it must not change until the commit.
                            val downSlot = layoutState.value.slotAt(down.position.y + scroll)
                            var strokeRevealed = false
                            rows.getOrNull(downSlot)?.let { r ->
                                if (r.item != null && !latchedInk.containsKey(r.line.id)) {
                                    latchedInk[r.line.id] = (hoverSlot == downSlot)
                                }
                                strokeRevealed = latchedInk[r.line.id] == true
                            }
                            // Stylus draws (spec §3). Raw capture incl. historical points.
                            active.clear()
                            trackStroke(down) { pos, t ->
                                active.add(InkPoint(pos.x, pos.y + scroll, t))
                            }
                            if (active.isNotEmpty()) {
                                vm.onStrokeFinished(active.toList(), strokeRevealed, layoutState.value)
                            }
                            active.clear()
                            hoverCandidate = -1 // fresh dwell required after each stroke
                        } else {
                            // Touch scrolls; a movement-free touch is a tap.
                            hoverSlot = null
                            val downPos = down.position
                            var moved = 0f
                            var prevY = downPos.y
                            while (true) {
                                val event2 = awaitPointerEvent()
                                val ch = event2.changes.firstOrNull { it.id == down.id } ?: break
                                moved = maxOf(
                                    moved,
                                    abs(ch.position.x - downPos.x),
                                    abs(ch.position.y - downPos.y),
                                )
                                if (moved > touchSlopPx) {
                                    scroll = (scroll - (ch.position.y - prevY)).coerceIn(0f, maxScroll())
                                }
                                prevY = ch.position.y
                                ch.consume()
                                if (!ch.pressed) break
                            }
                            if (moved <= touchSlopPx) {
                                vm.tapAt(downPos.y + scroll, layoutState.value)
                            }
                        }
                        }
                    }
                }
        ) {
            drawRect(InkWhite)

            // Notebook margin line.
            val marginX = 44.dp.toPx()
            drawLine(InkMargin, Offset(marginX, 0f), Offset(marginX, size.height), strokeWidth = 1f)

            for ((i, row) in rows.withIndex()) {
                val top = layout.tops[i] - scroll
                val rowH = layout.heights[i]
                if (top + rowH < -lh || top > size.height + lh) continue

                // Bottom rule.
                drawLine(InkFaint, Offset(0f, top + rowH), Offset(size.width, top + rowH), strokeWidth = 1f)

                val pending = row.line.id in uncommitted
                val item = row.item
                val showInk = showsInk(item, pending, latchedInk[row.line.id] == true, hoverSlot == i)
                val marker = dayMarkers[i]
                val markerInset = if (marker != null && !showInk) markerPx else 0f

                marker?.let { label ->
                    drawText(
                        textMeasurer = tm,
                        text = label.lowercase(),
                        topLeft = Offset(8.dp.toPx(), top + 2.dp.toPx()),
                        style = TextStyle(
                            color = InkGray,
                            fontSize = 11.sp,
                            fontFamily = Serif,
                            fontStyle = FontStyle.Italic,
                        ),
                    )
                }

                if (!showInk) {
                    drawItemRow(
                        tm, item!!, top + markerInset,
                        rowH - markerInset, vm.textBounds,
                    )
                    // Amendment strokes stay visible where they were written.
                    for (s in amendDisplay[row.line.id].orEmpty()) drawStroke(s.points, dy = top)
                } else {
                    for (s in cache[row.line.id].orEmpty()) drawStroke(s.points, dy = top)
                }
                if (pending) {
                    drawRoundRect(
                        color = InkGray,
                        topLeft = Offset(8.dp.toPx(), top + rowH - 14.dp.toPx()),
                        size = Size(40.dp.toPx(), 10.dp.toPx()),
                        style = Stroke(
                            width = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                        ),
                    )
                }
            }

            // Blank writing area below the last row.
            var yb = layout.end + lh
            while (yb - scroll <= size.height + lh) {
                drawLine(InkFaint, Offset(0f, yb - scroll), Offset(size.width, yb - scroll), strokeWidth = 1f)
                yb += lh
            }

            for (strokePoints in overlay) drawStroke(strokePoints, dy = -scroll)
            if (active.isNotEmpty()) drawStroke(active, dy = -scroll)

            eraserPos?.let { pos ->
                drawCircle(InkGray, radius = eraseRadiusPx, center = pos, style = Stroke(width = 1.5f))
            }
        }

        // Only offer the jump when the latest line is actually off-screen.
        if (rows.isNotEmpty() && layout.end - scroll > viewportH + 1f) {
            HardButton(
                label = "↓ latest",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                onClick = { scrollToLatest() },
            )
        }

        panel?.let { p ->
            ItemPanel(
                vm = vm,
                panel = p,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
                    .padding(12.dp),
            )
        }
    }
}


private fun DrawScope.drawItemRow(
    tm: TextMeasurer,
    item: ItemEntity,
    top: Float,
    rowH: Float,
    boundsOut: MutableMap<Long, FloatArray>,
) {
    val done = item.status == ItemEntity.DONE
    val dropped = item.status == ItemEntity.DROPPED
    val textX = 54.dp.toPx()
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
    val metaLayout = tm.measure(
        shortDate(item.createdAt),
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
