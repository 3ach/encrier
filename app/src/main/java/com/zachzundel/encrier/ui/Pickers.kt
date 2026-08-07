package com.zachzundel.encrier.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zachzundel.encrier.data.PageEntity
import com.zachzundel.encrier.data.TAPE_ZONE
import com.zachzundel.encrier.data.TapeEntity
import com.zachzundel.encrier.data.dayMarkerLabel
import java.time.LocalDate

internal fun tapeDisplayName(tape: TapeEntity?): String =
    when {
        tape == null -> "encrier"
        tape.id == TapeEntity.DEFAULT_ID && tape.name == "default" -> "encrier"
        else -> tape.name
    }


/** Notebook card listing tapes; the bottom row creates a new one (keyboard input). */
@Composable
internal fun TapePickerCard(
    tapes: List<TapeEntity>,
    currentTapeId: Long,
    onSwitch: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newName by rememberSaveable { mutableStateOf("") }
    Column(
        modifier.notebookCard(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (tape in tapes) {
            EditablePickerRow(
                name = tapeDisplayName(tape),
                selected = tape.id == currentTapeId,
                deletable = tape.id != TapeEntity.DEFAULT_ID,
                onSelect = { onSwitch(tape.id) },
                onRename = { onRename(tape.id, it) },
                onDelete = { onDelete(tape.id) },
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuietField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = "new tape",
                modifier = Modifier.weight(1f),
            )
            HardButton(
                "create",
                onClick = {
                    if (newName.isNotBlank()) {
                        onCreate(newName)
                        newName = ""
                    }
                },
            )
        }
    }
}


/** Notebook card listing the tape's day markers; tap one to jump there. */
@Composable
internal fun DatePickerCard(
    dates: List<Pair<LocalDate, Long>>,
    onPick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .notebookCard(8.dp)
            .heightIn(max = 340.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        if (dates.isEmpty()) {
            Text(
                "no dates yet",
                fontFamily = Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 14.sp,
                color = InkGray,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        for ((date, lineId) in dates) {
            val ts = date.atStartOfDay(TAPE_ZONE).toInstant().toEpochMilli()
            Text(
                dayMarkerLabel(ts).lowercase(),
                fontFamily = Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 14.sp,
                color = InkBlack,
                modifier = Modifier
                    .hardClickable { onPick(lineId) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}


/** Notebook card listing sketch pages; the bottom row creates a new one. */
@Composable
internal fun PagePickerCard(
    pages: List<PageEntity>,
    currentPageId: Long?,
    onSwitch: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newName by rememberSaveable { mutableStateOf("") }
    Column(
        modifier.notebookCard(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (page in pages) {
            EditablePickerRow(
                name = page.name,
                selected = page.id == currentPageId,
                deletable = page.id != PageEntity.DEFAULT_ID,
                onSelect = { onSwitch(page.id) },
                onRename = { onRename(page.id, it) },
                onDelete = { onDelete(page.id) },
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuietField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = "new page",
                modifier = Modifier.weight(1f),
            )
            HardButton(
                "create",
                onClick = {
                    if (newName.isNotBlank()) {
                        onCreate(newName)
                        newName = ""
                    }
                },
            )
        }
    }
}


private enum class RowMode { NORMAL, RENAME, CONFIRM }


/** Picker row with quiet rename (field + save) and delete (inline confirm). */
@Composable
private fun EditablePickerRow(
    name: String,
    selected: Boolean,
    deletable: Boolean,
    onSelect: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var mode by rememberSaveable(name) { mutableStateOf(RowMode.NORMAL) }
    var draft by rememberSaveable(name) { mutableStateOf(name) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (mode) {
            RowMode.NORMAL -> {
                Box(Modifier.weight(1f)) { SelectableRow(name, selected, onSelect) }
                RowGlyph("\u270e") { draft = name; mode = RowMode.RENAME }
                if (deletable) RowGlyph("\u00d7") { mode = RowMode.CONFIRM }
            }
            RowMode.RENAME -> {
                QuietField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = name,
                    modifier = Modifier.weight(1f),
                )
                HardButton("save", onClick = { onRename(draft); mode = RowMode.NORMAL })
                RowGlyph("\u00d7") { mode = RowMode.NORMAL }
            }
            RowMode.CONFIRM -> {
                Text(
                    "delete \u201c$name\u201d and everything on it?",
                    fontFamily = Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 14.sp,
                    color = InkBlack,
                    modifier = Modifier.weight(1f),
                )
                HardButton("delete", onClick = { onDelete(); mode = RowMode.NORMAL })
                HardButton("keep", onClick = { mode = RowMode.NORMAL })
            }
        }
    }
}


@Composable
private fun RowGlyph(glyph: String, onClick: () -> Unit) {
    Text(
        glyph,
        fontFamily = Serif,
        fontSize = 17.sp,
        color = InkGray,
        modifier = Modifier
            .hardClickable(onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}


