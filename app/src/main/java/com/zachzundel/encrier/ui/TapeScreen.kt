package com.zachzundel.encrier.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToDown
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
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

private const val EXTRA_BLANK_LINES = 30
private const val TOUCH_TAP_SLOP_PX = 12f

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TapeScreen(vm: TapeViewModel) {
    val rows by vm.rows.collectAsState()
    val cache by vm.strokeCache.collectAsState()
    val uncommitted by vm.uncommitted.collectAsState()
    val overlay by vm.overlayStrokes.collectAsState()
    val panel by vm.panel.collectAsState()
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current
    val lh = with(density) { Tunables.LINE_HEIGHT_DP.dp.toPx() }

    var scroll by remember { mutableFloatStateOf(0f) }
    var viewportH by remember { mutableFloatStateOf(0f) }
    var hoverSlot by remember { mutableStateOf<Int?>(null) } // content slot under the pen
    val active = remember { mutableStateListOf<InkPoint>() } // content coords

    fun contentH() = (rows.size + EXTRA_BLANK_LINES) * lh
    fun maxScroll() = (contentH() - viewportH).coerceAtLeast(0f)
    fun scrollToLatest() {
        scroll = ((rows.size * lh) - viewportH + lh).coerceIn(0f, maxScroll())
    }

    LaunchedEffect(lh) {
        vm.lineHeightPx = lh
        vm.gestureMinRunPx = with(density) { Tunables.GESTURE_MIN_RUN_DP.dp.toPx() }
        vm.tapMaxLenPx = with(density) { 10.dp.toPx() }
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
        val firstIdx = ((scroll / lh).toInt() - 5).coerceAtLeast(0)
        val lastIdx = (((scroll + viewportH) / lh).toInt() + 5).coerceAtMost(rows.size - 1)
        if (lastIdx >= firstIdx) {
            vm.loadVisible((firstIdx..lastIdx).map { rows[it].line.id })
        }
    }

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
        val byParent = rows.mapNotNull { it.item }.filter { it.parentId != null }.groupBy { it.parentId!! }
        byParent.mapValues { (_, kids) ->
            kids.count { it.status == ItemEntity.DONE } to kids.size
        }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged {
                    viewportH = it.height.toFloat()
                    vm.tapeWidthPx = it.width.toFloat()
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                        val event = awaitPointerEvent()
                        val down = event.changes.firstOrNull { it.changedToDown() }
                        if (down == null) {
                            // Stylus hover reveals a committed row's ink for amending.
                            val h = event.changes.firstOrNull {
                                it.type == PointerType.Stylus && !it.pressed
                            }
                            if (h != null) {
                                hoverSlot = if (event.type == PointerEventType.Exit) null
                                else ((h.position.y + scroll) / lh).toInt()
                            }
                            continue
                        }
                        down.consume()
                        if (down.type == PointerType.Stylus) {
                            hoverSlot = ((down.position.y + scroll) / lh).toInt()
                            // Stylus draws (spec §3). Raw capture incl. historical points.
                            active.clear()
                            active.add(InkPoint(down.position.x, down.position.y + scroll, down.uptimeMillis))
                            while (true) {
                                val event = awaitPointerEvent()
                                val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                for (h in ch.historical) {
                                    active.add(InkPoint(h.position.x, h.position.y + scroll, h.uptimeMillis))
                                }
                                if (ch.pressed) {
                                    active.add(InkPoint(ch.position.x, ch.position.y + scroll, ch.uptimeMillis))
                                }
                                ch.consume()
                                if (!ch.pressed) break
                            }
                            if (active.isNotEmpty()) vm.onStrokeFinished(active.toList())
                            active.clear()
                        } else {
                            // Touch scrolls; a movement-free touch is a tap.
                            hoverSlot = null
                            val downPos = down.position
                            var moved = 0f
                            var prevY = downPos.y
                            while (true) {
                                val event = awaitPointerEvent()
                                val ch = event.changes.firstOrNull { it.id == down.id } ?: break
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
                                vm.tapAt(downPos.y + scroll)
                            }
                        }
                        }
                    }
                }
        ) {
            drawRect(InkWhite)
            val firstSlot = (scroll / lh).toInt()
            val lastSlot = ((scroll + size.height) / lh).toInt() + 1

            for (i in firstSlot..lastSlot) {
                val y = (i + 1) * lh - scroll
                drawLine(InkFaint, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
            }

            for ((i, row) in rows.withIndex()) {
                val top = i * lh - scroll
                if (top + lh < -lh || top > size.height + lh) continue

                dayMarkers[i]?.let { label ->
                    drawText(
                        textMeasurer = tm,
                        text = label,
                        topLeft = Offset(8.dp.toPx(), top + 3.dp.toPx()),
                        style = TextStyle(color = InkGray, fontSize = 10.sp, fontFamily = Mono),
                    )
                }

                val isChild = row.isPendingChild || row.item?.parentId != null
                val pending = row.line.id in uncommitted
                val item = row.item
                if (isChild) drawConnector(top, lh)

                // Committed rows stay TEXT even while an amendment is pending —
                // ink shows only under stylus hover (to see where to amend).
                if (item != null && hoverSlot != i) {
                    drawItemRow(tm, item, childStats[item.id], top, lh, isChild, vm.textBounds)
                } else {
                    val strokes = cache[row.line.id].orEmpty()
                    for (s in strokes) drawInkStroke(s.points, top)
                }
                if (pending) {
                    drawRoundRect(
                        color = InkGray,
                        topLeft = Offset(8.dp.toPx(), top + lh - 16.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(48.dp.toPx(), 12.dp.toPx()),
                        style = Stroke(
                            width = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                        ),
                    )
                }
            }

            for (strokePoints in overlay) drawContentStroke(strokePoints, scroll)
            if (active.isNotEmpty()) drawContentStroke(active, scroll)
        }

        HardButton(
            label = "↓ LATEST",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            onClick = { scrollToLatest() },
        )

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
    Column(
        modifier
            .background(InkWhite)
            .border(2.dp, InkBlack)
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InkPreview(panel.strokes, Modifier.fillMaxWidth().height(64.dp))
        Text(
            "written " + shortDate(item.createdAt),
            fontFamily = Mono,
            fontSize = 11.sp,
            color = InkGray,
        )
        for (candidate in decodeCandidates(item.candidatesJson)) {
            HardButton(
                label = candidate,
                modifier = Modifier.fillMaxWidth(),
                selected = candidate == item.text,
                onClick = { vm.chooseCandidate(item.lineId, candidate) },
            )
        }
        Text(
            "none right? amend the ink on the tape",
            fontFamily = Mono,
            fontSize = 11.sp,
            color = InkGray,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.status == ItemEntity.OPEN) {
                HardButton("DONE", onClick = { vm.markDone(item.lineId) })
                HardButton("DROP", onClick = { vm.markDropped(item.lineId) })
            } else {
                HardButton("REOPEN", onClick = { vm.reopen(item.lineId) })
            }
            HardButton("DELETE", onClick = { vm.deleteItem(item.lineId) })
            Spacer(Modifier.width(8.dp))
            HardButton("CLOSE", onClick = { vm.closePanel() })
        }
    }
}

private fun DrawScope.drawItemRow(
    tm: TextMeasurer,
    item: ItemEntity,
    childStat: Pair<Int, Int>?,
    top: Float,
    lh: Float,
    isChild: Boolean,
    boundsOut: MutableMap<Long, FloatArray>,
) {
    val done = item.status == ItemEntity.DONE
    val dropped = item.status == ItemEntity.DROPPED
    val textX = if (isChild) 52.dp.toPx() else 16.dp.toPx()
    // TextMeasurer's layout cache ignores draw-only attributes (color,
    // textDecoration) but returns layouts that still carry them — a struck-
    // through layout would be replayed after REOPEN. Measure with a constant
    // style; apply color at draw time and the strike as an explicit line.
    val layout = tm.measure(
        item.text,
        style = TextStyle(fontSize = 17.sp),
        maxLines = 1,
    )
    boundsOut[item.lineId] = floatArrayOf(textX, textX + layout.size.width)
    val textTop = top + (lh - layout.size.height) / 2f
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
            top + (lh - metaLayout.size.height) / 2f,
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
private fun DrawScope.drawConnector(top: Float, lh: Float) {
    val x = 16.dp.toPx()
    val midY = top + lh * 0.55f
    drawLine(InkBlack, Offset(x, top + lh * 0.1f), Offset(x, midY), strokeWidth = 3f)
    drawLine(InkBlack, Offset(x, midY), Offset(x + 22.dp.toPx(), midY), strokeWidth = 3f)
}
