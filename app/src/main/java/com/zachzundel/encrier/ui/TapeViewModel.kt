package com.zachzundel.encrier.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zachzundel.encrier.Graph
import com.zachzundel.encrier.TapeSession
import com.zachzundel.encrier.Tunables
import com.zachzundel.encrier.data.CorrectionEntity
import com.zachzundel.encrier.data.DAY_MS
import com.zachzundel.encrier.data.InkPoint
import com.zachzundel.encrier.data.ItemEntity
import com.zachzundel.encrier.data.LineEntity
import com.zachzundel.encrier.data.LineRow
import com.zachzundel.encrier.data.StrokeEntity
import com.zachzundel.encrier.data.TAPE_ZONE
import com.zachzundel.encrier.data.TapeDao
import com.zachzundel.encrier.data.TapeEntity
import com.zachzundel.encrier.data.encodeCandidates
import com.zachzundel.encrier.data.encodePoints
import com.zachzundel.encrier.data.encodeStrokes
import com.zachzundel.encrier.data.decodePoints
import com.zachzundel.encrier.data.localDate
import com.zachzundel.encrier.gesture.Elbow
import com.zachzundel.encrier.gesture.RowMarks
import com.zachzundel.encrier.ink.Recognition
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TapeViewModel(
    private val dao: TapeDao = Graph.db.dao(),
    private val recog: Recognition = Graph.recognition,
    private val session: TapeSession = Graph.session,
) : ViewModel() {

    data class RenderStroke(val points: List<InkPoint>, val id: Long = 0L) // line-relative
    data class TapeRow(val line: LineRow, val item: ItemEntity?, val isPendingChild: Boolean)

    // Set by the UI once it knows its geometry.
    var lineHeightPx = 1f
    var tapeWidthPx = 1f
    var gestureMinRunPx = 0f
    var tapMaxLenPx = 0f
    var amendGapPx = 0f

    /** Rendered text span [x0, x1] per committed line, reported by the UI each draw. */
    val textBounds = ConcurrentHashMap<Long, FloatArray>()

    /** Spawned-but-uncommitted child lines: lineId -> parent item id. In-memory by design. */
    private val pendingParent = MutableStateFlow<Map<Long, Long>>(emptyMap())

    /** Spawn time per pending child, so the GC can age-gate empty ones. */
    private val spawnedAt = mutableMapOf<Long, Long>()

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

    val currentTapeId: StateFlow<Long> = session.currentTapeId

    val tapes: StateFlow<List<TapeEntity>> =
        dao.observeTapes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<TapeRow>> =
        combine(
            session.currentTapeId.flatMapLatest { dao.observeLines(it) },
            dao.observeItems(),
            pendingParent,
        ) { lines, items, pending ->
            val byLine = items.associateBy { it.lineId }
            lines.map { TapeRow(it, byLine[it.id], pending.containsKey(it.id)) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun switchTape(id: Long) {
        _panelLineId.value = null
        _scrollToLineId.value = null // a pending jump targets the old tape's rows
        session.switchTo(id)
        Log.i("Encrier", "switched to tape $id")
    }

    /** Inserts a tape and makes it current. Blank names are ignored. */
    fun createTape(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val id = dao.insertTape(
                    TapeEntity(name = trimmed, createdAt = System.currentTimeMillis())
                )
                switchTape(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "createTape failed: ${e.message}")
            }
        }
    }

    /**
     * Distinct local dates that have any ink (most recent first), each paired
     * with the line id of the first row of that date's latest run. Drives
     * jump-to-date.
     */
    val availableDates: StateFlow<List<Pair<LocalDate, Long>>> =
        rows.map { rowsNow ->
            availableDates(rowsNow.map { DatedRow(it.line.id, it.item?.createdAt ?: it.line.firstInkAt) }, TAPE_ZONE)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Jump-to-date scroll request: the tape consumes it once handled. */
    private val _scrollToLineId = MutableStateFlow<Long?>(null)
    val scrollToLineId: StateFlow<Long?> = _scrollToLineId.asStateFlow()

    fun requestScrollTo(lineId: Long) {
        Log.i("Encrier", "jump-to-date: requested line=$lineId")
        _scrollToLineId.value = lineId
    }

    fun consumeScrollRequest() {
        _scrollToLineId.value = null
    }

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
    private val recogFailures = mutableMapOf<Long, Int>() // consecutive failures per line
    private var idleJob: Job? = null
    private val mutex = Mutex()

    /**
     * Lines created for slots past the layout's end, keyed by virtual slot, so a
     * fast second stroke reuses the line the first one created instead of
     * duplicating it. Per tape; entries clear once a layout covers their slot.
     */
    private val newLineBySlot = mutableMapOf<Int, Long>()
    private var newLineTapeId = -1L

    init {
        // Startup GC: lines with neither ink nor an item are invisible slots
        // left by gap-creation or interrupted sessions; orphaned strokes and
        // items point at lines that no longer exist.
        viewModelScope.launch {
            try {
                mutex.withLock {
                    dao.gcEmptyLines()
                    dao.gcOrphanStrokes()
                    dao.gcOrphanItems()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "startup GC failed: ${e.message}")
            }
        }
    }

    fun loadVisible(lineIds: List<Long>) {
        if (lineIds.none { it !in _strokeCache.value }) return
        viewModelScope.launch {
            try {
                mutex.withLock {
                    val missing = lineIds.filter { it !in _strokeCache.value }
                    if (missing.isEmpty()) return@withLock
                    val loaded = dao.strokesFor(missing)
                        .groupBy { it.lineId }
                        .mapValues { (_, ss) -> ss.map { RenderStroke(decodePoints(it.pointsJson), it.id) } }
                    _strokeCache.update { cur ->
                        // Only fill keys still absent: never clobber an entry
                        // (e.g. a fresh stroke) that appeared meanwhile.
                        cur + missing.filter { it !in cur }.associateWith { loaded[it] ?: emptyList() }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "loadVisible failed: ${e.message}")
            }
        }
    }

    fun onStrokeFinished(points: List<InkPoint>, inkRevealed: Boolean, layout: RowLayout) {
        if (points.isEmpty()) return
        val tapeId = session.currentTapeId.value // the tape the stroke was written on
        _overlayStrokes.update { it + listOf(points) }
        viewModelScope.launch {
            var storedAsInk = false
            try {
                mutex.withLock { storedAsInk = handleStroke(tapeId, points, inkRevealed, layout) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "stroke handling failed: ${e.message}")
            } finally {
                if (storedAsInk) {
                    // Keep the overlay briefly so Room's emission lands first.
                    launch {
                        delay(Tunables.OVERLAY_LINGER_MS)
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
    private suspend fun handleStroke(
        tapeId: Long,
        points: List<InkPoint>,
        inkRevealed: Boolean,
        layout: RowLayout,
    ): Boolean {
        if (tapeId != session.currentTapeId.value) {
            Log.i("Encrier", "stroke dropped: tape $tapeId no longer current")
            return false
        }
        if (tapeId != newLineTapeId) {
            newLineBySlot.clear()
            newLineTapeId = tapeId
        }
        newLineBySlot.keys.removeAll { it < layout.lineIds.size } // layout covers them now

        val now = System.currentTimeMillis()
        val lh = lineHeightPx
        val rowsNow = rows.value
        // Slots resolve to lines via the capture-time layout, then to current
        // row state by id — rows may have moved under the pen.
        fun rowAt(slot: Int): TapeRow? {
            val lineId = layout.lineIds.getOrNull(slot) ?: return null
            return rowsNow.firstOrNull { it.line.id == lineId }
        }

        // Committed lines render as text (ink hidden), so the anchor region for
        // taps and gestures is the row's slot band, not the ink bbox.
        val startSlot = layout.slotAt(points.first().y)
        val startRow = rowAt(startSlot)
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
                spawnChild(startRow!!.line.id, now)
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
            val row = rowAt(s) ?: continue
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
                            item.copy(status = ItemEntity.DONE, completedAt = now)
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
        if (slot < layout.lineIds.size) {
            targetId = layout.lineIds[slot]
            if (!dao.lineExists(targetId)) {
                Log.i("Encrier", "stroke dropped: line $targetId vanished before insert")
                return false
            }
        } else {
            // Create lines for every empty slot up to the written one so ink
            // stays exactly where it was written, reusing lines a previous
            // stroke already created for those virtual slots.
            var seq = dao.maxSeq(tapeId) ?: 0.0
            var lastId = -1L
            for (s in layout.lineIds.size..slot) {
                val existing = newLineBySlot[s]
                if (existing != null && dao.lineExists(existing)) {
                    lastId = existing
                    continue
                }
                seq += 1.0
                lastId = dao.insertLine(LineEntity(seq = seq, createdAt = now, tapeId = tapeId))
                newLineBySlot[s] = lastId
            }
            targetId = lastId
        }
        ensureLoaded(targetId)
        val slotTop = layout.topOf(slot)
        var rel = points.map { InkPoint(it.x, it.y - slotTop, it.t) }

        // Appending to a text-displayed row: the pen wrote relative to the short
        // rendered text, not the original ink. Store shifted past the ink's right
        // edge; keep an unshifted copy visible where it was written.
        val isTextAmend = slot < layout.lineIds.size && rowAt(slot)?.item != null && !inkRevealed
        var displayRel: List<InkPoint>? = null
        if (isTextAmend) {
            val offset = amendOffset.getOrPut(targetId) {
                val existing = _strokeCache.value[targetId].orEmpty()
                amendShift(
                    existing.flatMap { it.points }.maxOfOrNull { it.x },
                    amendGapPx,
                    rel.minOf { it.x },
                )
            }
            displayRel = rel
            if (offset != 0f) rel = rel.map { InkPoint(it.x + offset, it.y, it.t) }
        }

        val ord = dao.maxOrd(targetId) + 1
        val strokeId = dao.insertStroke(
            StrokeEntity(lineId = targetId, ord = ord, pointsJson = encodePoints(rel), addedAt = now)
        )
        displayRel?.let { d ->
            _amendDisplay.update { it + (targetId to it[targetId].orEmpty() + RenderStroke(d)) }
        }
        _strokeCache.update { it + (targetId to it[targetId].orEmpty() + RenderStroke(rel, strokeId)) }
        dirty += targetId
        _uncommitted.update { it + targetId }
        scheduleIdleCommit()
        return true
    }

    private fun pathLength(points: List<InkPoint>): Float {
        var len = 0f
        for (i in 1 until points.size) {
            len += hypot(
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
            try {
                mutex.withLock {
                    ensureLoaded(lineId)
                    val strokes = _strokeCache.value[lineId].orEmpty()
                    val hit = strokes.filter { s ->
                        s.points.any { q -> hypot(q.x - p.x, q.y - p.y) <= radiusPx }
                    }
                    if (hit.isEmpty()) return@withLock
                    for (s in hit) if (s.id != 0L) dao.deleteStroke(s.id)
                    val remaining = strokes - hit.toSet()
                    if (remaining.isEmpty()) {
                        dao.itemForLine(lineId)?.let { dao.deleteItem(it.id) }
                        dao.deleteLine(lineId)
                        clearLineState(lineId)
                    } else {
                        _strokeCache.update { it + (lineId to remaining) }
                        if (dao.itemForLine(lineId) != null) {
                            dirty += lineId
                            scheduleIdleCommit()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "eraseAt failed: ${e.message}")
            }
        }
    }

    /** Touch tap on the tape at content-space y. Opens the panel if the row is committed. */
    fun tapAt(contentY: Float, layout: RowLayout) {
        viewModelScope.launch {
            try {
                mutex.withLock {
                    val lineId = layout.lineIds.getOrNull(layout.slotAt(contentY)) ?: return@withLock
                    val row = rows.value.firstOrNull { it.line.id == lineId } ?: return@withLock
                    if (row.item != null && row.line.id !in _uncommitted.value) {
                        openPanelForLine(row.line.id)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "tapAt failed: ${e.message}")
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
            try {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "appendInkTo failed: ${e.message}")
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

    /** A chosen candidate is final for the current ink: drop any pending recognition. */
    fun chooseCandidate(lineId: Long, text: String) = panelAction(lineId) {
        clearPendingRecognition(lineId)
        it.copy(text = text)
    }

    /**
     * Typed correction: records ink + candidates + the user's text as training
     * data, then applies the text. Candidate picks deliberately don't record a
     * correction — only typed text is ground truth the recognizer failed to rank.
     */
    fun correctText(lineId: Long, text: String) {
        viewModelScope.launch {
            try {
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
                    // The user's text is final for the current ink: the idle
                    // commit must not overwrite it. Future ink re-dirties.
                    clearPendingRecognition(lineId)
                    _panelLineId.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "correctText failed: ${e.message}")
            }
        }
    }

    /**
     * Shifts the item's listed date by whole days. Keeps the panel open — the
     * reactive panel refreshes itself, so repeated nudges compose naturally.
     */
    fun shiftItemDate(lineId: Long, days: Int) {
        viewModelScope.launch {
            try {
                mutex.withLock {
                    val cur = dao.itemForLine(lineId) ?: return@withLock
                    val newTs = cur.createdAt + days * DAY_MS
                    dao.updateItem(cur.copy(createdAt = newTs))
                    relocateForDate(lineId, newTs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "shiftItemDate failed: ${e.message}")
            }
        }
    }

    /**
     * A re-dated item belongs in its new date's block: move its line to the
     * end of the last run whose effective date ≤ the new date. Effective date
     * = the item's listed date when there is one, else first-ink time.
     */
    private suspend fun relocateForDate(lineId: Long, newTs: Long) {
        val rowsNow = rows.value
        val self = rowsNow.firstOrNull { it.line.id == lineId } ?: return
        val others = rowsNow.filter { it.line.id != lineId }
        if (others.isEmpty()) return
        val newDate = localDate(newTs)
        var afterIdx = -1
        for ((i, r) in others.withIndex()) {
            val ts = (if (r.line.id == lineId) null else r.item?.createdAt ?: r.line.firstInkAt)
                ?: continue
            if (localDate(ts) <= newDate) afterIdx = i
        }
        val prevSeq = others.getOrNull(afterIdx)?.line?.seq
        val nextSeq = others.getOrNull(afterIdx + 1)?.line?.seq
        val newSeq = when {
            prevSeq == null && nextSeq == null -> return
            prevSeq == null -> nextSeq!! - 1.0
            nextSeq == null -> prevSeq + 1.0
            else -> (prevSeq + nextSeq) / 2.0
        }
        if (newSeq != self.line.seq) dao.updateLineSeq(lineId, newSeq)
    }

    /** Delete removes the item AND its line + ink; the tape closes the gap. */
    fun deleteItem(lineId: Long) {
        viewModelScope.launch {
            try {
                mutex.withLock { deleteLocked(lineId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "deleteItem failed: ${e.message}")
            }
        }
    }

    private suspend fun deleteLocked(lineId: Long) {
        val cur = dao.itemForLine(lineId) ?: return
        dao.deleteItem(cur.id)
        dao.deleteStrokesForLine(lineId)
        dao.deleteLine(lineId)
        clearLineState(lineId)
        _panelLineId.value = null // delete closes the panel even for another line
    }

    /** Forgets any in-flight recognition state for the line. */
    private fun clearPendingRecognition(lineId: Long) {
        dirty.remove(lineId)
        recogFailures.remove(lineId)
        _uncommitted.update { it - lineId }
        amendOffset.remove(lineId)
        _amendDisplay.update { it - lineId }
    }

    /** In-memory teardown once a line's row has left the tape. */
    private fun clearLineState(lineId: Long) {
        _strokeCache.update { it - lineId }
        textBounds.remove(lineId)
        clearPendingRecognition(lineId)
        if (_panelLineId.value == lineId) _panelLineId.value = null
    }

    /** Re-fetches the item inside the lock so actions never write stale fields. */
    private fun panelAction(lineId: Long, transform: (ItemEntity) -> ItemEntity) {
        viewModelScope.launch {
            try {
                mutex.withLock {
                    val cur = dao.itemForLine(lineId) ?: return@withLock
                    dao.updateItem(transform(cur))
                    _panelLineId.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "panelAction failed: ${e.message}")
            }
        }
    }

    private suspend fun spawnChild(anchorLineId: Long, now: Long) {
        val rowsNow = rows.value
        val idx = rowsNow.indexOfFirst { it.line.id == anchorLineId }
        if (idx < 0) return
        val anchor = rowsNow[idx]
        val parentItem = anchor.item ?: return
        val next = rowsNow.getOrNull(idx + 1)
        val seq =
            if (next != null) (anchor.line.seq + next.line.seq) / 2.0 else anchor.line.seq + 1.0
        val id = dao.insertLine(
            LineEntity(seq = seq, createdAt = now, tapeId = session.currentTapeId.value)
        )
        pendingParent.update { it + (id to parentItem.id) }
        spawnedAt[id] = now
        _strokeCache.update { it + (id to emptyList()) }
    }

    private fun scheduleIdleCommit() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(Tunables.IDLE_COMMIT_MS)
            try {
                mutex.withLock { commitLocked() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "commit failed: ${e.message}")
            }
        }
    }

    /** Follow-up commit for lines that failed recognition or children too young to GC. */
    private fun scheduleRetryCommit() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(4 * Tunables.IDLE_COMMIT_MS)
            try {
                mutex.withLock { commitLocked() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "commit retry failed: ${e.message}")
            }
        }
    }

    private suspend fun commitLocked() {
        Log.i("Encrier", "commit: dirty=${dirty.size} pendingChildren=${pendingParent.value.size}")
        val now = System.currentTimeMillis()
        var retryNeeded = false
        // GC spawned child lines that received no strokes (spec §5) — but only
        // once old enough that a slow writer can't still be aiming at them.
        for (lineId in pendingParent.value.keys.toList()) {
            if (dao.strokeCount(lineId) == 0) {
                if (now - (spawnedAt[lineId] ?: 0L) < 2 * Tunables.IDLE_COMMIT_MS) {
                    retryNeeded = true // re-check once it has aged
                    continue
                }
                dao.deleteLine(lineId)
                pendingParent.update { it - lineId }
                spawnedAt.remove(lineId)
                clearLineState(lineId)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failures = (recogFailures[lineId] ?: 0) + 1
                if (failures >= 5) {
                    Log.i("Encrier", "recognition failed $failures times, dropping line=$lineId: ${e.message}")
                    dirty.remove(lineId)
                    recogFailures.remove(lineId)
                } else {
                    Log.i("Encrier", "recognition failed (attempt $failures), will retry: ${e.message}")
                    recogFailures[lineId] = failures
                    retryNeeded = true
                }
                continue
            }
            recogFailures.remove(lineId)
            val text = candidates.firstOrNull()?.trim().orEmpty()
            Log.i("Encrier", "commit line=$lineId -> \"$text\" (${candidates.size} candidates)")
            val existing = dao.itemForLine(lineId)
            if (existing != null) {
                // Last-writer-wins update in place; createdAt (the listed date,
                // nudgeable via the panel) is left alone (spec §2, §4).
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
                        // The listed date is when the ink was written, not when
                        // recognition got around to committing it.
                        createdAt = dao.firstInkAt(lineId) ?: System.currentTimeMillis(),
                    )
                )
                pendingParent.update { it - lineId }
                spawnedAt.remove(lineId)
            }
            // Blank recognition: no item, ink retained (spec §4).
            clearPendingRecognition(lineId)
        }
        if (retryNeeded) scheduleRetryCommit() // must stay last: cancels our own job
    }

    private suspend fun ensureLoaded(lineId: Long) {
        if (_strokeCache.value.containsKey(lineId)) return
        val loaded = dao.strokesFor(listOf(lineId)).map { RenderStroke(decodePoints(it.pointsJson), it.id) }
        _strokeCache.update { it + (lineId to loaded) }
    }
}

/**
 * X-shift applied to strokes appended while a row displays as text: past the
 * existing ink's right edge plus a gap. Zero when the line has no ink. Cached
 * per line so every stroke of one amendment shifts by the same amount.
 */
internal fun amendShift(inkMaxX: Float?, gapPx: Float, relMinX: Float): Float =
    if (inkMaxX == null) 0f else (inkMaxX + gapPx) - relMinX
