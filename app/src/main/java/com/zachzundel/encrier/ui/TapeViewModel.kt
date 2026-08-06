package com.zachzundel.encrier.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zachzundel.encrier.Graph
import com.zachzundel.encrier.Tunables
import com.zachzundel.encrier.data.CorrectionEntity
import com.zachzundel.encrier.data.InkPoint
import com.zachzundel.encrier.data.ItemEntity
import com.zachzundel.encrier.data.LineEntity
import com.zachzundel.encrier.data.LineRow
import com.zachzundel.encrier.data.StrokeEntity
import com.zachzundel.encrier.data.encodeCandidates
import com.zachzundel.encrier.data.encodePoints
import com.zachzundel.encrier.data.encodeStrokes
import com.zachzundel.encrier.data.decodePoints
import com.zachzundel.encrier.gesture.Elbow
import com.zachzundel.encrier.gesture.RowMarks
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.floor

class TapeViewModel : ViewModel() {
    private val dao = Graph.db.dao()
    private val recog = Graph.recognition

    data class RenderStroke(val points: List<InkPoint>, val id: Long = 0L) // line-relative
    data class TapeRow(val line: LineRow, val item: ItemEntity?, val isPendingChild: Boolean)

    // Set by the UI once it knows its geometry.
    var lineHeightPx = 1f
    var tapeWidthPx = 1f
    var gestureMinRunPx = 0f
    var tapMaxLenPx = 0f
    var amendGapPx = 0f

    /** Rendered text span [x0, x1] per committed line, reported by the UI each draw. */
    val textBounds = java.util.concurrent.ConcurrentHashMap<Long, FloatArray>()

    /** Spawned-but-uncommitted child lines: lineId -> parent item id. In-memory by design. */
    private val pendingParent = MutableStateFlow<Map<Long, Long>>(emptyMap())

    /** Lines with ink written since their last recognition commit ("pending" in spec terms). */
    private val _uncommitted = MutableStateFlow<Set<Long>>(emptySet())
    val uncommitted: StateFlow<Set<Long>> = _uncommitted.asStateFlow()

    private val _strokeCache = MutableStateFlow<Map<Long, List<RenderStroke>>>(emptyMap())
    val strokeCache: StateFlow<Map<Long, List<RenderStroke>>> = _strokeCache.asStateFlow()

    /** Just-finished strokes drawn in content coords until Room emits them, to avoid flicker. */
    private val _overlayStrokes = MutableStateFlow<List<List<InkPoint>>>(emptyList())
    val overlayStrokes: StateFlow<List<List<InkPoint>>> = _overlayStrokes.asStateFlow()

    /**
     * Strokes appended to a text-displayed row, kept at their on-screen positions
     * (line-relative) so they stay visible where written until the commit. The
     * stored copies are x-shifted past the original ink instead.
     */
    private val _amendDisplay = MutableStateFlow<Map<Long, List<RenderStroke>>>(emptyMap())
    val amendDisplay: StateFlow<Map<Long, List<RenderStroke>>> = _amendDisplay.asStateFlow()
    private val amendOffset = mutableMapOf<Long, Float>()

    /** Inline action panel for a tapped committed line: source ink on demand + actions. */
    data class Panel(val item: ItemEntity, val strokes: List<List<InkPoint>>)
    private val _panelLineId = MutableStateFlow<Long?>(null)

    val rows: StateFlow<List<TapeRow>> =
        combine(dao.observeLines(com.zachzundel.encrier.data.TapeEntity.DEFAULT_ID), dao.observeItems(), pendingParent) { lines, items, pending ->
            val byLine = items.associateBy { it.lineId }
            lines.map { TapeRow(it, byLine[it.id], pending.containsKey(it.id)) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Derived from the DB flows, never a snapshot: buttons always reflect current
     * state, and the panel closes itself if the item vanishes.
     */
    val panel: StateFlow<Panel?> =
        combine(_panelLineId, rows, _strokeCache) { lineId, rowsNow, cache ->
            if (lineId == null) null
            else rowsNow.firstOrNull { it.line.id == lineId }?.item?.let { item ->
                Panel(item, cache[lineId].orEmpty().map { it.points })
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val dirty = mutableSetOf<Long>() // lines needing (re-)recognition
    private var idleJob: Job? = null
    private val mutex = Mutex()

    init {
        // Startup GC: lines with neither ink nor an item are invisible slots
        // left by gap-creation or interrupted sessions.
        viewModelScope.launch { mutex.withLock { dao.gcEmptyLines() } }
    }

    fun loadVisible(lineIds: List<Long>) {
        val missing = lineIds.filter { it !in _strokeCache.value }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            val loaded = dao.strokesFor(missing)
                .groupBy { it.lineId }
                .mapValues { (_, ss) -> ss.map { RenderStroke(decodePoints(it.pointsJson), it.id) } }
            _strokeCache.update { cur ->
                cur + missing.associateWith { loaded[it] ?: emptyList() }
            }
        }
    }

    fun onStrokeFinished(points: List<InkPoint>, inkRevealed: Boolean, layout: RowLayout) {
        if (points.isEmpty()) return
        _overlayStrokes.update { it + listOf(points) }
        viewModelScope.launch {
            var storedAsInk = false
            try {
                mutex.withLock { storedAsInk = handleStroke(points, inkRevealed, layout) }
            } finally {
                if (storedAsInk) {
                    // Keep the overlay briefly so Room's emission lands first.
                    launch {
                        delay(400)
                        _overlayStrokes.update { cur -> cur.filter { it !== points } }
                    }
                } else {
                    // Consumed by a gesture (tap/elbow/strike/scribble): no ink
                    // will replace it, clear immediately.
                    _overlayStrokes.update { cur -> cur.filter { it !== points } }
                }
            }
        }
    }

    /** @return true if the stroke was stored as ink (vs consumed by a gesture). */
    private suspend fun handleStroke(points: List<InkPoint>, inkRevealed: Boolean, layout: RowLayout): Boolean {
        val now = System.currentTimeMillis()
        val lh = lineHeightPx
        val rowsNow = rows.value

        // Committed lines render as text (ink hidden), so the anchor region for
        // taps and gestures is the row's slot band, not the ink bbox.
        val startSlot = layout.slotAt(points.first().y)
        val startRow = rowsNow.getOrNull(startSlot)
        val anchored = startRow?.item != null && startRow.line.id !in _uncommitted.value

        // A tap-length stylus stroke on a committed row opens its panel, not ink.
        if (anchored && pathLength(points) < tapMaxLenPx) {
            openPanelForLine(startRow!!.line.id)
            return false
        }

        // Gesture detection runs BEFORE handwriting routing (spec §5).
        if (anchored) {
            val anchorBottom = layout.topOf(startSlot) + layout.heightOf(startSlot)
            val m = Elbow.detect(points, anchorBottom, lh, gestureMinRunPx)
            if (m.matched) {
                spawnChild(rowsNow, startSlot, now)
                scheduleIdleCommit()
                return false
            }
            if (Tunables.GESTURE_DEBUG) {
                Log.i(
                    "ElbowDebug",
                    "rejected (${m.reason}): drop=%.1fpx turn=%.1f° run=%.1fpx"
                        .format(m.dropPastRulePx, m.turnDeg, m.runPx)
                )
            }
        }

        // Strike-out → DONE; scribble-out → DELETE. Neither stores ink. Tight
        // rows make the start point unreliable (a scribble often starts above
        // the band), so test the start row AND the centroid row. Never fires
        // for ink-revealed strokes — writing over revealed ink is amendment.
        val centroidY = (points.sumOf { it.y.toDouble() } / points.size).toFloat()
        for (s in if (inkRevealed) emptySet() else setOf(startSlot, layout.slotAt(centroidY))) {
            val row = rowsNow.getOrNull(s) ?: continue
            val item = row.item ?: continue
            if (row.line.id in _uncommitted.value) continue
            val b = textBounds[row.line.id] ?: continue
            val v = RowMarks.classify(
                points,
                rowTop = layout.topOf(s),
                rowHeight = layout.heightOf(s),
                writeLh = lh,
                textX0 = b[0],
                textX1 = b[1],
            )
            if (Tunables.GESTURE_DEBUG && v.kind == RowMarks.Kind.NONE) {
                Log.i(
                    "MarksDebug",
                    "slot=$s none (${v.reason}): cover=%.2f hf=%.2f xRev=%d yRev=%d"
                        .format(v.cover, v.heightFrac, v.xReversals, v.yReversals)
                )
            }
            when (v.kind) {
                RowMarks.Kind.SCRIBBLE -> {
                    deleteLocked(row.line.id)
                    return false
                }
                RowMarks.Kind.STRIKE -> {
                    if (item.status == ItemEntity.OPEN) {
                        dao.updateItem(
                            item.copy(
                                status = ItemEntity.DONE,
                                completedAt = System.currentTimeMillis(),
                            )
                        )
                        return false
                    }
                }
                RowMarks.Kind.NONE -> {}
            }
        }

        // Handwriting routing by y-centroid (spec §3).
        val slot = layout.slotAt(centroidY)
        val targetId: Long
        if (slot < rowsNow.size) {
            targetId = rowsNow[slot].line.id
        } else {
            // Create lines for every empty slot up to the written one so ink
            // stays exactly where it was written.
            var seq = dao.maxSeq(com.zachzundel.encrier.data.TapeEntity.DEFAULT_ID) ?: 0.0
            var lastId = -1L
            for (s in rowsNow.size..slot) {
                seq += 1.0
                lastId = dao.insertLine(LineEntity(seq = seq, createdAt = now))
            }
            targetId = lastId
        }
        ensureLoaded(targetId)
        val slotTop = layout.topOf(slot)
        var rel = points.map { InkPoint(it.x, it.y - slotTop, it.t) }

        // Appending to a text-displayed row: the pen wrote relative to the short
        // rendered text, not the original ink. Store shifted past the ink's right
        // edge; keep an unshifted copy visible where it was written.
        val isTextAmend = slot < rowsNow.size && rowsNow[slot].item != null && !inkRevealed
        if (isTextAmend) {
            val offset = amendOffset.getOrPut(targetId) {
                val existing = _strokeCache.value[targetId].orEmpty()
                val inkMaxX = existing.flatMap { it.points }.maxOfOrNull { it.x }
                if (inkMaxX == null) 0f
                else (inkMaxX + amendGapPx) - rel.minOf { it.x }
            }
            _amendDisplay.update { it + (targetId to it[targetId].orEmpty() + RenderStroke(rel)) }
            if (offset != 0f) rel = rel.map { InkPoint(it.x + offset, it.y, it.t) }
        }

        val ord = dao.maxOrd(targetId) + 1
        val strokeId = dao.insertStroke(
            StrokeEntity(lineId = targetId, ord = ord, pointsJson = encodePoints(rel), addedAt = now)
        )
        _strokeCache.update { it + (targetId to it[targetId].orEmpty() + RenderStroke(rel, strokeId)) }
        dirty += targetId
        _uncommitted.update { it + targetId }
        scheduleIdleCommit()
        return true
    }

    private fun pathLength(points: List<InkPoint>): Float {
        var len = 0f
        for (i in 1 until points.size) {
            len += kotlin.math.hypot(
                points[i].x - points[i - 1].x,
                points[i].y - points[i - 1].y,
            )
        }
        return len
    }

    /**
     * Stroke eraser: deletes any of the line's strokes within [radiusPx] of the
     * line-relative point. Erasing the last stroke removes the item and line.
     */
    fun eraseAt(lineId: Long, p: InkPoint, radiusPx: Float) {
        viewModelScope.launch {
            mutex.withLock {
                ensureLoaded(lineId)
                val strokes = _strokeCache.value[lineId].orEmpty()
                val hit = strokes.filter { s ->
                    s.points.any { q -> kotlin.math.hypot(q.x - p.x, q.y - p.y) <= radiusPx }
                }
                if (hit.isEmpty()) return@withLock
                for (s in hit) if (s.id != 0L) dao.deleteStroke(s.id)
                val remaining = strokes - hit.toSet()
                if (remaining.isEmpty()) {
                    dao.itemForLine(lineId)?.let { dao.deleteItem(it.id) }
                    dao.deleteLine(lineId)
                    _strokeCache.update { it - lineId }
                    textBounds.remove(lineId)
                    dirty.remove(lineId)
                    _uncommitted.update { it - lineId }
                    amendOffset.remove(lineId)
                    _amendDisplay.update { it - lineId }
                    if (_panelLineId.value == lineId) _panelLineId.value = null
                } else {
                    _strokeCache.update { it + (lineId to remaining) }
                    if (dao.itemForLine(lineId) != null) {
                        dirty += lineId
                        scheduleIdleCommit()
                    }
                }
            }
        }
    }

    /** Touch tap on the tape at content-space y. Opens the panel if the row is committed. */
    fun tapAt(contentY: Float, layout: RowLayout) {
        viewModelScope.launch {
            mutex.withLock {
                val row = rows.value.getOrNull(layout.slotAt(contentY)) ?: return@withLock
                if (row.item != null && row.line.id !in _uncommitted.value) {
                    openPanelForLine(row.line.id)
                }
            }
        }
    }

    private suspend fun openPanelForLine(lineId: Long) {
        ensureLoaded(lineId)
        _panelLineId.value = lineId
    }

    fun closePanel() {
        _panelLineId.value = null
    }

    /** A stroke written inside the panel's ink box, already in line coordinates. */
    fun appendInkTo(lineId: Long, rel: List<InkPoint>) {
        if (rel.isEmpty()) return
        viewModelScope.launch {
            mutex.withLock {
                ensureLoaded(lineId)
                val ord = dao.maxOrd(lineId) + 1
                val strokeId = dao.insertStroke(
                    StrokeEntity(
                        lineId = lineId,
                        ord = ord,
                        pointsJson = encodePoints(rel),
                        addedAt = System.currentTimeMillis(),
                    )
                )
                _strokeCache.update { it + (lineId to it[lineId].orEmpty() + RenderStroke(rel, strokeId)) }
                dirty += lineId
                _uncommitted.update { it + lineId }
                scheduleIdleCommit()
            }
        }
    }

    fun markDone(lineId: Long) = panelAction(lineId) {
        it.copy(status = ItemEntity.DONE, completedAt = System.currentTimeMillis())
    }

    fun markDropped(lineId: Long) = panelAction(lineId) {
        it.copy(status = ItemEntity.DROPPED, droppedAt = System.currentTimeMillis())
    }

    fun reopen(lineId: Long) = panelAction(lineId) {
        it.copy(status = ItemEntity.OPEN, completedAt = null, droppedAt = null)
    }

    fun chooseCandidate(lineId: Long, text: String) = panelAction(lineId) {
        it.copy(text = text)
    }

    /**
     * Typed correction: records ink + candidates + the user's text as training
     * data, then applies the text. Candidate picks deliberately don't record a
     * correction — only typed text is ground truth the recognizer failed to rank.
     */
    fun correctText(lineId: Long, text: String) {
        viewModelScope.launch {
            mutex.withLock {
                val cur = dao.itemForLine(lineId) ?: return@withLock
                val corrected = text.trim()
                if (corrected.isEmpty() || corrected == cur.text) return@withLock
                ensureLoaded(lineId)
                val strokes = _strokeCache.value[lineId].orEmpty().map { it.points }
                dao.insertCorrection(
                    CorrectionEntity(
                        lineId = lineId,
                        strokesJson = encodeStrokes(strokes),
                        candidatesJson = cur.candidatesJson,
                        correctedText = corrected,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                Log.i("Encrier", "correction line=$lineId -> \"$corrected\"")
                dao.updateItem(cur.copy(text = corrected))
                _panelLineId.value = null
            }
        }
    }

    /** Delete removes the item AND its line + ink; the tape closes the gap. */
    fun deleteItem(lineId: Long) {
        viewModelScope.launch {
            mutex.withLock { deleteLocked(lineId) }
        }
    }

    private suspend fun deleteLocked(lineId: Long) {
        val cur = dao.itemForLine(lineId) ?: return
        dao.deleteItem(cur.id)
        dao.deleteStrokesForLine(lineId)
        dao.deleteLine(lineId)
        _strokeCache.update { it - lineId }
        textBounds.remove(lineId)
        dirty.remove(lineId)
        _uncommitted.update { it - lineId }
        amendOffset.remove(lineId)
        _amendDisplay.update { it - lineId }
        _panelLineId.value = null
    }

    /** Re-fetches the item inside the lock so actions never write stale fields. */
    private fun panelAction(lineId: Long, transform: (ItemEntity) -> ItemEntity) {
        viewModelScope.launch {
            mutex.withLock {
                val cur = dao.itemForLine(lineId) ?: return@withLock
                dao.updateItem(transform(cur))
                _panelLineId.value = null
            }
        }
    }

    private suspend fun spawnChild(rowsNow: List<TapeRow>, anchorIdx: Int, now: Long) {
        val anchor = rowsNow[anchorIdx]
        val next = rowsNow.getOrNull(anchorIdx + 1)
        val seq =
            if (next != null) (anchor.line.seq + next.line.seq) / 2.0 else anchor.line.seq + 1.0
        val id = dao.insertLine(LineEntity(seq = seq, createdAt = now))
        pendingParent.update { it + (id to anchor.item!!.id) }
        _strokeCache.update { it + (id to emptyList()) }
    }

    private fun scheduleIdleCommit() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(Tunables.IDLE_COMMIT_MS)
            mutex.withLock { commitLocked() }
        }
    }

    private suspend fun commitLocked() {
        Log.i("Encrier", "commit: dirty=${dirty.size} pendingChildren=${pendingParent.value.size}")
        // GC spawned child lines that received no strokes (spec §5).
        for (lineId in pendingParent.value.keys.toList()) {
            if (dao.strokeCount(lineId) == 0) {
                dao.deleteLine(lineId)
                pendingParent.update { it - lineId }
                _strokeCache.update { it - lineId }
                dirty.remove(lineId)
            }
        }
        for (lineId in dirty.toList()) {
            val strokes = _strokeCache.value[lineId].orEmpty()
            if (strokes.isEmpty()) {
                dirty.remove(lineId)
                _uncommitted.update { it - lineId }
                continue
            }
            val candidates = try {
                recog.recognizeLine(strokes.map { it.points }, tapeWidthPx, lineHeightPx)
            } catch (e: Exception) {
                Log.i("Encrier", "recognition failed, will retry: ${e.message}")
                continue // stays dirty; next idle commit retries
            }
            val text = candidates.firstOrNull()?.trim().orEmpty()
            Log.i("Encrier", "commit line=$lineId -> \"$text\" (${candidates.size} candidates)")
            val existing = dao.itemForLine(lineId)
            if (existing != null) {
                // Last-writer-wins update in place; createdAt untouched (spec §2, §4).
                if (text.isNotEmpty()) {
                    dao.updateItem(
                        existing.copy(text = text, candidatesJson = encodeCandidates(candidates))
                    )
                }
            } else if (text.isNotEmpty()) {
                dao.insertItem(
                    ItemEntity(
                        lineId = lineId,
                        text = text,
                        candidatesJson = encodeCandidates(candidates),
                        parentId = pendingParent.value[lineId],
                        status = ItemEntity.OPEN,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                pendingParent.update { it - lineId }
            }
            // Blank recognition: no item, ink retained (spec §4).
            dirty.remove(lineId)
            _uncommitted.update { it - lineId }
            amendOffset.remove(lineId)
            _amendDisplay.update { it - lineId }
        }
    }

    private suspend fun ensureLoaded(lineId: Long) {
        if (_strokeCache.value.containsKey(lineId)) return
        val loaded = dao.strokesFor(listOf(lineId)).map { RenderStroke(decodePoints(it.pointsJson), it.id) }
        _strokeCache.update { it + (lineId to loaded) }
    }
}

private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val cur = value
        if (compareAndSet(cur, transform(cur))) return
    }
}
