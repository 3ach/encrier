package com.zachzundel.encrier.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zachzundel.encrier.Graph
import com.zachzundel.encrier.Tunables
import com.zachzundel.encrier.data.InkPoint
import com.zachzundel.encrier.data.ItemEntity
import com.zachzundel.encrier.data.LineEntity
import com.zachzundel.encrier.data.LineRow
import com.zachzundel.encrier.data.StrokeEntity
import com.zachzundel.encrier.data.encodeCandidates
import com.zachzundel.encrier.data.encodePoints
import com.zachzundel.encrier.data.decodePoints
import com.zachzundel.encrier.gesture.Elbow
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

    data class RenderStroke(val points: List<InkPoint>) // line-relative
    data class TapeRow(val line: LineRow, val item: ItemEntity?, val isPendingChild: Boolean)

    // Set by the UI once it knows its geometry.
    var lineHeightPx = 1f
    var tapeWidthPx = 1f
    var gestureMinRunPx = 0f
    var tapMaxLenPx = 0f

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

    /** Inline action panel for a tapped committed line: source ink on demand + actions. */
    data class Panel(val item: ItemEntity, val strokes: List<List<InkPoint>>)
    private val _panel = MutableStateFlow<Panel?>(null)
    val panel: StateFlow<Panel?> = _panel.asStateFlow()

    val rows: StateFlow<List<TapeRow>> =
        combine(dao.observeLines(), dao.observeItems(), pendingParent) { lines, items, pending ->
            val byLine = items.associateBy { it.lineId }
            lines.map { TapeRow(it, byLine[it.id], pending.containsKey(it.id)) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val dirty = mutableSetOf<Long>() // lines needing (re-)recognition
    private var idleJob: Job? = null
    private val mutex = Mutex()

    fun loadVisible(lineIds: List<Long>) {
        val missing = lineIds.filter { it !in _strokeCache.value }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            val loaded = dao.strokesFor(missing)
                .groupBy { it.lineId }
                .mapValues { (_, ss) -> ss.map { RenderStroke(decodePoints(it.pointsJson)) } }
            _strokeCache.update { cur ->
                cur + missing.associateWith { loaded[it] ?: emptyList() }
            }
        }
    }

    fun onStrokeFinished(points: List<InkPoint>) {
        if (points.isEmpty()) return
        _overlayStrokes.update { it + listOf(points) }
        viewModelScope.launch {
            try {
                mutex.withLock { handleStroke(points) }
            } finally {
                // Keep the overlay briefly so Room's emission lands before removal.
                launch {
                    delay(400)
                    _overlayStrokes.update { cur -> cur.filter { it !== points } }
                }
            }
        }
    }

    private suspend fun handleStroke(points: List<InkPoint>) {
        val now = System.currentTimeMillis()
        val lh = lineHeightPx
        val rowsNow = rows.value

        // Committed lines render as text (ink hidden), so the anchor region for
        // taps and gestures is the row's slot band, not the ink bbox.
        val startSlot = floor(points.first().y / lh).toInt()
        val startRow = rowsNow.getOrNull(startSlot)
        val anchored = startRow?.item != null && startRow.line.id !in _uncommitted.value

        // A tap-length stylus stroke on a committed row opens its panel, not ink.
        if (anchored && pathLength(points) < tapMaxLenPx) {
            openPanelForLine(startRow!!.line.id)
            return
        }

        // Gesture detection runs BEFORE handwriting routing (spec §5).
        if (anchored) {
            val m = Elbow.detect(points, (startSlot + 1) * lh, lh, gestureMinRunPx)
            if (m.matched) {
                spawnChild(rowsNow, startSlot, now)
                scheduleIdleCommit()
                return
            }
            if (Tunables.GESTURE_DEBUG) {
                Log.i(
                    "ElbowDebug",
                    "rejected (${m.reason}): drop=%.1fpx turn=%.1f° run=%.1fpx"
                        .format(m.dropPastRulePx, m.turnDeg, m.runPx)
                )
            }
        }

        // Handwriting routing by y-centroid (spec §3).
        val centroidY = (points.sumOf { it.y.toDouble() } / points.size).toFloat()
        val slot = floor(centroidY / lh).toInt().coerceAtLeast(0)
        val targetId: Long
        if (slot < rowsNow.size) {
            targetId = rowsNow[slot].line.id
        } else {
            // Create lines for every empty slot up to the written one so ink
            // stays exactly where it was written (y = seq order × lineHeight).
            var seq = dao.maxSeq() ?: 0.0
            var lastId = -1L
            for (s in rowsNow.size..slot) {
                seq += 1.0
                lastId = dao.insertLine(LineEntity(seq = seq, createdAt = now))
            }
            targetId = lastId
        }
        ensureLoaded(targetId)
        val rel = points.map { InkPoint(it.x, it.y - slot * lh, it.t) }
        val ord = dao.maxOrd(targetId) + 1
        dao.insertStroke(
            StrokeEntity(lineId = targetId, ord = ord, pointsJson = encodePoints(rel), addedAt = now)
        )
        _strokeCache.update { it + (targetId to it[targetId].orEmpty() + RenderStroke(rel)) }
        dirty += targetId
        _uncommitted.update { it + targetId }
        scheduleIdleCommit()
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

    /** Touch tap on the tape at content-space y. Opens the panel if the row is committed. */
    fun tapAt(contentY: Float) {
        viewModelScope.launch {
            mutex.withLock {
                val row = rows.value.getOrNull(floor(contentY / lineHeightPx).toInt()) ?: return@withLock
                if (row.item != null && row.line.id !in _uncommitted.value) {
                    openPanelForLine(row.line.id)
                }
            }
        }
    }

    private suspend fun openPanelForLine(lineId: Long) {
        val item = dao.itemForLine(lineId) ?: return
        ensureLoaded(lineId)
        _panel.value = Panel(item, _strokeCache.value[lineId].orEmpty().map { it.points })
    }

    fun closePanel() {
        _panel.value = null
    }

    fun markDone(item: ItemEntity) = panelAction {
        dao.updateItem(item.copy(status = ItemEntity.DONE, completedAt = System.currentTimeMillis()))
    }

    fun markDropped(item: ItemEntity) = panelAction {
        dao.updateItem(item.copy(status = ItemEntity.DROPPED, droppedAt = System.currentTimeMillis()))
    }

    fun reopen(item: ItemEntity) = panelAction {
        dao.updateItem(item.copy(status = ItemEntity.OPEN, completedAt = null, droppedAt = null))
    }

    fun chooseCandidate(item: ItemEntity, text: String) = panelAction {
        dao.updateItem(item.copy(text = text))
    }

    /** Delete removes the item AND its line + ink; the tape closes the gap. */
    fun deleteItem(item: ItemEntity) = panelAction {
        dao.deleteItem(item.id)
        dao.deleteStrokesForLine(item.lineId)
        dao.deleteLine(item.lineId)
        _strokeCache.update { it - item.lineId }
        dirty.remove(item.lineId)
        _uncommitted.update { it - item.lineId }
    }

    private fun panelAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutex.withLock {
                block()
                _panel.value = null
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
        Log.i("InkTask", "commit: dirty=${dirty.size} pendingChildren=${pendingParent.value.size}")
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
                Log.i("InkTask", "recognition failed, will retry: ${e.message}")
                continue // stays dirty; next idle commit retries
            }
            val text = candidates.firstOrNull()?.trim().orEmpty()
            Log.i("InkTask", "commit line=$lineId -> \"$text\" (${candidates.size} candidates)")
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
        }
    }

    private suspend fun ensureLoaded(lineId: Long) {
        if (_strokeCache.value.containsKey(lineId)) return
        val loaded = dao.strokesFor(listOf(lineId)).map { RenderStroke(decodePoints(it.pointsJson)) }
        _strokeCache.update { it + (lineId to loaded) }
    }
}

private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val cur = value
        if (compareAndSet(cur, transform(cur))) return
    }
}
