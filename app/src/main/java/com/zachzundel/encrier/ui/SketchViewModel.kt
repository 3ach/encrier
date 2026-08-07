package com.zachzundel.encrier.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zachzundel.encrier.Graph
import com.zachzundel.encrier.TapeSession
import com.zachzundel.encrier.Tunables
import com.zachzundel.encrier.data.InkPoint
import com.zachzundel.encrier.data.PageEntity
import com.zachzundel.encrier.data.PageStrokeEntity
import com.zachzundel.encrier.data.TapeDao
import com.zachzundel.encrier.data.decodePoints
import com.zachzundel.encrier.data.encodePoints
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** Free sketch pages: whiteboard ink in page coordinates, no recognition. */
class SketchViewModel(
    private val dao: TapeDao = Graph.db.dao(),
    private val session: TapeSession = Graph.session,
) : ViewModel() {

    data class SketchStroke(val id: Long, val points: List<InkPoint>)

    val currentPageId: StateFlow<Long> = session.currentPageId

    val pages: StateFlow<List<PageEntity>> =
        dao.observePages().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val currentPage: StateFlow<PageEntity?> =
        combine(pages, currentPageId) { all, id -> all.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val strokes: StateFlow<List<SketchStroke>> =
        session.currentPageId.flatMapLatest { dao.observePageStrokes(it) }
            .map { list -> list.map { SketchStroke(it.id, decodePoints(it.pointsJson)) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Just-finished strokes shown until Room re-emits them, to avoid flicker. */
    private val _overlay = MutableStateFlow<List<List<InkPoint>>>(emptyList())
    val overlay: StateFlow<List<List<InkPoint>>> = _overlay.asStateFlow()

    fun addStroke(points: List<InkPoint>) {
        if (points.isEmpty()) return
        _overlay.update { it + listOf(points) }
        viewModelScope.launch {
            try {
                dao.insertPageStroke(
                    PageStrokeEntity(
                        pageId = session.currentPageId.value,
                        pointsJson = encodePoints(points),
                        addedAt = System.currentTimeMillis(),
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "sketch addStroke failed: ${e.message}")
            } finally {
                launch {
                    delay(Tunables.OVERLAY_LINGER_MS)
                    _overlay.update { cur -> cur.filter { it !== points } }
                }
            }
        }
    }

    /** Whole-stroke eraser, page coordinates. */
    fun eraseAt(p: InkPoint, radiusPx: Float) {
        val hit = strokes.value.filter { s ->
            s.points.any { q -> hypot(q.x - p.x, q.y - p.y) <= radiusPx }
        }
        if (hit.isEmpty()) return
        viewModelScope.launch {
            try {
                for (s in hit) dao.deletePageStroke(s.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "sketch erase failed: ${e.message}")
            }
        }
    }

    fun setBackground(bg: String) {
        viewModelScope.launch {
            try {
                dao.setPageBackground(session.currentPageId.value, bg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "setBackground failed: ${e.message}")
            }
        }
    }

    fun switchPage(id: Long) = session.switchPage(id)

    fun renamePage(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                dao.renamePage(id, trimmed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "renamePage failed: ${e.message}")
            }
        }
    }

    /** Deletes a page and its ink. The default page is not deletable. */
    fun deletePage(id: Long) {
        if (id == PageEntity.DEFAULT_ID) return
        viewModelScope.launch {
            try {
                if (session.currentPageId.value == id) session.switchPage(PageEntity.DEFAULT_ID)
                dao.deleteStrokesForPage(id)
                dao.deletePageRow(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "deletePage failed: ${e.message}")
            }
        }
    }

    fun createPage(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val id = dao.insertPage(
                    PageEntity(
                        name = trimmed,
                        background = PageEntity.DOTS,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                session.switchPage(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i("Encrier", "createPage failed: ${e.message}")
            }
        }
    }
}
