package com.zachzundel.encrier.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zachzundel.encrier.Tunables
import com.zachzundel.encrier.data.InkPoint
import com.zachzundel.encrier.data.ItemEntity
import com.zachzundel.encrier.data.dayMarkerLabel
import com.zachzundel.encrier.data.decodeCandidates
import com.zachzundel.encrier.data.localDate
import com.zachzundel.encrier.data.shortDate
import java.time.LocalDate
import kotlin.math.abs

private const val TOUCH_TAP_SLOP_PX = 12f

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TapeScreen(vm: TapeViewModel) {
    val rows by vm.rows.collectAsState()
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
    val eraseRadiusPx = with(density) { 10.dp.toPx() }
    // Display mode latched per line at the first amendment stroke: true = ink
    // was revealed when writing began. Sticky until the amendment commits.
    val latchedInk = remember { mutableStateMapOf<Long, Boolean>() }
    val active = remember { mutableStateListOf<InkPoint>() } // content coords

    LaunchedEffect(uncommitted) { latchedInk.keys.retainAll(uncommitted) }

    // Day markers derived at render time from data (spec §3).
    val dayMarkers: Map<Int, String> = remember(rows) {
        val markers = mutableMapOf<Int, String>()
        var lastDate: LocalDate? = null
        for ((i, row) in rows.withIndex()) {
            val ts = row.line.firstInkAt ?: continue
            val d = localDate(ts)
            if (d != lastDate) {
                markers[i] = dayMarkerLabel(ts)
                lastDate = d
            }
        }
        markers
    }

    // Child completion ratios for parent rows.
    val childStats: Map<Long, Pair<Int, Int>> = remember(rows) {
        rows.mapNotNull { it.item }.filter { it.parentId != null }
            .groupBy { it.parentId!! }
            .mapValues { (_, kids) -> kids.count { it.status == ItemEntity.DONE } to kids.size }
    }

    // Variable-height layout: tight rows for committed text, writing height for
    // ink/blank rows and for the hover-grown or ink-latched row.
    val layout = run {
        val tops = FloatArray(rows.size)
        val heights = FloatArray(rows.size)
        var y = 0f
        for ((i, row) in rows.withIndex()) {
            val pending = row.line.id in uncommitted
            val grownInk = row.item == null ||
                (!pending && hoverSlot == i) ||
                (pending && latchedInk[row.line.id] == true)
            var h = if (grownInk) lh else textRowPx
            if (!grownInk && dayMarkers.containsKey(i)) h += markerPx
            tops[i] = y
            heights[i] = h
            y += h
        }
        RowLayout(tops, heights, lh)
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
        vm.gestureMinRunPx = with(density) { Tunables.GESTURE_MIN_RUN_DP.dp.toPx() }
        vm.tapMaxLenPx = with(density) { 10.dp.toPx() }
        vm.amendGapPx = with(density) { Tunables.AMEND_GAP_DP.dp.toPx() }
    }

    // Open scrolled to the last occupied line (spec §3).
    var initialScrollDone by remember { mutableStateOf(false) }
    LaunchedEffect(rows.size, viewportH) {
        if (!initialScrollDone && viewportH > 0f) {
            if (rows.isNotEmpty()) scrollToLatest()
            initialScrollDone = true
        }
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
                        if (down.type == PointerType.Stylus &&
                            (event.buttons.isSecondaryPressed || event.buttons.isTertiaryPressed)
                        ) {
                            // Barrel button held: stroke eraser. Deletes whole
                            // strokes it contacts, on rows displaying ink.
                            fun eraseSample(pos: Offset) {
                                val cy = pos.y + scroll
                                val l = layoutState.value
                                val slot = l.slotAt(cy)
                                val row = rows.getOrNull(slot) ?: return
                                val pending = row.line.id in uncommitted
                                val inkVisible = row.item == null ||
                                    (pending && latchedInk[row.line.id] == true) ||
                                    (!pending && hoverSlot == slot)
                                if (!inkVisible) return
                                vm.eraseAt(
                                    row.line.id,
                                    InkPoint(pos.x, cy - l.topOf(slot), 0L),
                                    eraseRadiusPx,
                                )
                            }
                            eraseSample(down.position)
                            eraserPos = down.position
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                for (h in ch.historical) eraseSample(h.position)
                                if (ch.pressed) eraseSample(ch.position)
                                eraserPos = ch.position
                                ch.consume()
                                if (!ch.pressed) break
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
                            active.add(InkPoint(down.position.x, down.position.y + scroll, down.uptimeMillis))
                            while (true) {
                                val event2 = awaitPointerEvent()
                                val ch = event2.changes.firstOrNull { it.id == down.id } ?: break
                                for (h in ch.historical) {
                                    active.add(InkPoint(h.position.x, h.position.y + scroll, h.uptimeMillis))
                                }
                                if (ch.pressed) {
                                    active.add(InkPoint(ch.position.x, ch.position.y + scroll, ch.uptimeMillis))
                                }
                                ch.consume()
                                if (!ch.pressed) break
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
                                if (moved > TOUCH_TAP_SLOP_PX) {
                                    scroll = (scroll - (ch.position.y - prevY)).coerceIn(0f, maxScroll())
                                }
                                prevY = ch.position.y
                                ch.consume()
                                if (!ch.pressed) break
                            }
                            if (moved <= TOUCH_TAP_SLOP_PX) {
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

                val isChild = row.isPendingChild || row.item?.parentId != null
                val pending = row.line.id in uncommitted
                val item = row.item
                val showInk = when {
                    item == null -> true
                    pending -> latchedInk[row.line.id] == true
                    else -> hoverSlot == i
                }
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
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        ),
                    )
                }

                if (isChild) drawConnector(top + markerInset, rowH - markerInset)

                if (!showInk) {
                    drawItemRow(
                        tm, item!!, childStats[item.id], top + markerInset,
                        rowH - markerInset, isChild, vm.textBounds,
                    )
                    // Amendment strokes stay visible where they were written.
                    for (s in amendDisplay[row.line.id].orEmpty()) drawInkStroke(s.points, top)
                } else {
                    for (s in cache[row.line.id].orEmpty()) drawInkStroke(s.points, top)
                }
                if (pending) {
                    drawRoundRect(
                        color = InkGray,
                        topLeft = Offset(8.dp.toPx(), top + rowH - 14.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(40.dp.toPx(), 10.dp.toPx()),
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

            for (strokePoints in overlay) drawContentStroke(strokePoints, scroll)
            if (active.isNotEmpty()) drawContentStroke(active, scroll)

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
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun ItemPanel(vm: TapeViewModel, panel: TapeViewModel.Panel, modifier: Modifier) {
    val item = panel.item
    val cardShape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val fieldShape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    var suggestionsOpen by remember(item.lineId) { mutableStateOf(false) }
    Column(
        modifier
            .background(InkWhite, cardShape)
            .border(1.5.dp, InkBlack, cardShape)
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.runtime.key(item.lineId) {
            WritableInkPreview(
                strokes = panel.strokes,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .border(1.dp, InkMargin, fieldShape),
                onStroke = { vm.appendInkTo(item.lineId, it) },
                onErase = { p, r -> vm.eraseAt(item.lineId, p, r) },
            )
        }
        Text(
            "written " + shortDate(item.createdAt).lowercase(),
            fontFamily = Serif,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            fontSize = 12.sp,
            color = InkGray,
        )
        // Suggestions as a collapsed dropdown.
        Column(Modifier.fillMaxWidth().border(1.dp, InkMargin, fieldShape).padding(2.dp)) {
            Row(
                Modifier.fillMaxWidth().hardClickable { suggestionsOpen = !suggestionsOpen }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.text,
                    fontFamily = Serif,
                    fontSize = 15.sp,
                    color = InkBlack,
                    modifier = Modifier.weight(1f),
                )
                Text(if (suggestionsOpen) "▴" else "▾", color = InkGray, fontSize = 13.sp)
            }
            if (suggestionsOpen) {
                for (candidate in decodeCandidates(item.candidatesJson)) {
                    val selected = candidate == item.text
                    Text(
                        candidate,
                        fontFamily = Serif,
                        fontSize = 15.sp,
                        color = if (selected) InkWhite else InkBlack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (selected) InkBlack else InkWhite, fieldShape)
                            .hardClickable { vm.chooseCandidate(item.lineId, candidate) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.status == ItemEntity.OPEN) {
                HardButton("done", onClick = { vm.markDone(item.lineId) })
                HardButton("drop", onClick = { vm.markDropped(item.lineId) })
            } else {
                HardButton("reopen", onClick = { vm.reopen(item.lineId) })
            }
            HardButton("delete", onClick = { vm.deleteItem(item.lineId) })
            Spacer(Modifier.width(8.dp))
            HardButton("close", onClick = { vm.closePanel() })
        }
    }
}

private fun DrawScope.drawItemRow(
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

private fun DrawScope.drawInkStroke(points: List<InkPoint>, top: Float) {
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

private fun DrawScope.drawContentStroke(points: List<InkPoint>, scroll: Float) {
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
private fun DrawScope.drawConnector(top: Float, rowH: Float) {
    val x = 60.dp.toPx()
    val midY = top + rowH * 0.55f
    drawLine(InkGray, Offset(x, top + rowH * 0.1f), Offset(x, midY), strokeWidth = 2f)
    drawLine(InkGray, Offset(x, midY), Offset(x + 18.dp.toPx(), midY), strokeWidth = 2f)
}
