package com.zachzundel.encrier

import android.content.Context
import android.content.SharedPreferences
import com.zachzundel.encrier.data.TapeEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which tape the app is currently showing. Persisted so a relaunch resumes
 * on the same tape. Lives in Graph, not a ViewModel: the tape view and the
 * history view both react to it.
 */
class TapeSession(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("encrier_prefs", Context.MODE_PRIVATE)

    private val _currentTapeId =
        MutableStateFlow(prefs.getLong(KEY, TapeEntity.DEFAULT_ID))
    val currentTapeId: StateFlow<Long> = _currentTapeId.asStateFlow()

    fun switchTo(id: Long) {
        _currentTapeId.value = id
        prefs.edit().putLong(KEY, id).apply()
    }

    private companion object {
        const val KEY = "currentTapeId"
    }
}
