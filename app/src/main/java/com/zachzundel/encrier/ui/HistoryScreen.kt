package com.zachzundel.encrier.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zachzundel.encrier.Graph
import com.zachzundel.encrier.TapeSession
import com.zachzundel.encrier.data.DAY_MS
import com.zachzundel.encrier.data.ItemEntity
import com.zachzundel.encrier.data.NotebookDao
import com.zachzundel.encrier.data.shortDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(
    private val dao: NotebookDao = Graph.db.dao(),
    private val session: TapeSession = Graph.session,
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<ItemEntity>> = session.currentTapeId
        .flatMapLatest { dao.observeItemsForTape(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}

@Composable
fun HistoryScreen(vm: HistoryViewModel) {
    val items by vm.items.collectAsState()
    var days by remember { mutableIntStateOf(7) }
    val from = System.currentTimeMillis() - days * DAY_MS

    val added = items.filter { it.createdAt >= from }.sortedByDescending { it.createdAt }
    val completed = items.filter { (it.completedAt ?: 0) >= from }
        .sortedByDescending { it.completedAt }
    val droppedCount = items.count { (it.droppedAt ?: 0) >= from }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in listOf(7, 30, 90)) {
                HardButton(
                    label = "${preset}d",
                    selected = days == preset,
                    onClick = { days = preset },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "added ${added.size}   completed ${completed.size}   dropped $droppedCount",
            fontFamily = Mono,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Text("completed", fontFamily = Serif, fontSize = 14.sp, fontStyle = FontStyle.Italic)
            }
            items(completed, key = { "c${it.id}" }) { ReportRow(it.completedAt ?: 0, it.text) }
            item {
                Spacer(Modifier.height(16.dp))
                Text("added", fontFamily = Serif, fontSize = 14.sp, fontStyle = FontStyle.Italic)
            }
            items(added, key = { "a${it.id}" }) { ReportRow(it.createdAt, it.text) }
        }
    }
}

@Composable
private fun ReportRow(ts: Long, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(shortDate(ts), fontFamily = Mono, fontSize = 13.sp, color = InkGray)
        Spacer(Modifier.padding(horizontal = 6.dp))
        Text(text, fontSize = 15.sp, fontFamily = Serif)
    }
}
