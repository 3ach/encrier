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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zachzundel.encrier.data.TapeEntity
import com.zachzundel.encrier.data.dayMarkerLabel

@Composable
internal fun Tabs() {
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showTapePicker by rememberSaveable { mutableStateOf(false) }
    var showDates by rememberSaveable { mutableStateOf(false) }
    val tapeVm = viewModel<TapeViewModel>()
    val tapes by tapeVm.tapes.collectAsState()
    val currentTapeId by tapeVm.currentTapeId.collectAsState()
    val availableDates by tapeVm.availableDates.collectAsState()
    val tapeName = tapeDisplayName(tapes.firstOrNull { it.id == currentTapeId })
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (showHistory) "history" else tapeName,
                fontFamily = Serif,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontSize = 21.sp,
                color = InkBlack,
                modifier = if (showHistory) Modifier
                else Modifier.hardClickable {
                    showTapePicker = !showTapePicker
                    showDates = false
                },
            )
            Spacer(Modifier.weight(1f))
            if (!showHistory) {
                CalendarButton(
                    selected = showDates,
                    onClick = {
                        showDates = !showDates
                        showTapePicker = false
                    },
                )
                Spacer(Modifier.width(10.dp))
            }
            ClockButton(
                selected = showHistory,
                onClick = {
                    showHistory = !showHistory
                    showTapePicker = false
                    showDates = false
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(InkMargin))
        Box(Modifier.weight(1f)) {
            if (showHistory) HistoryScreen(viewModel<HistoryViewModel>())
            else TapeScreen(tapeVm)
            if (showTapePicker && !showHistory) {
                TapePickerCard(
                    tapes = tapes,
                    currentTapeId = currentTapeId,
                    onSwitch = { tapeVm.switchTape(it); showTapePicker = false },
                    onCreate = { tapeVm.createTape(it); showTapePicker = false },
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                )
            }
            if (!showHistory && showDates) {
                DatePickerCard(
                    dates = availableDates,
                    onPick = { lineId ->
                        tapeVm.requestScrollTo(lineId)
                        showDates = false
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                )
            }
        }
    }
}

private fun tapeDisplayName(tape: TapeEntity?): String =
    when {
        tape == null -> "encrier"
        tape.id == TapeEntity.DEFAULT_ID -> "encrier"
        else -> tape.name
    }

/** Notebook card listing tapes; the bottom row creates a new one (keyboard input). */
@Composable
private fun TapePickerCard(
    tapes: List<TapeEntity>,
    currentTapeId: Long,
    onSwitch: (Long) -> Unit,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val fieldShape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    var newName by rememberSaveable { mutableStateOf("") }
    Column(
        modifier
            .background(InkWhite, cardShape)
            .border(1.5.dp, InkBlack, cardShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (tape in tapes) {
            val selected = tape.id == currentTapeId
            Text(
                tapeDisplayName(tape),
                fontFamily = Serif,
                fontSize = 15.sp,
                color = if (selected) InkWhite else InkBlack,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) InkBlack else InkWhite, fieldShape)
                    .hardClickable { onSwitch(tape.id) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicTextField(
                value = newName,
                onValueChange = { newName = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = Serif,
                    fontSize = 15.sp,
                    color = InkBlack,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(InkBlack),
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, InkMargin, fieldShape)
                    .background(InkWhite, fieldShape)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                decorationBox = { inner ->
                    Box {
                        if (newName.isEmpty()) {
                            Text(
                                "new tape",
                                fontFamily = Serif,
                                fontSize = 15.sp,
                                color = InkGray,
                            )
                        }
                        inner()
                    }
                },
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
private fun DatePickerCard(
    dates: List<Pair<java.time.LocalDate, Long>>,
    onPick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    Column(
        modifier
            .background(InkWhite, shape)
            .border(1.5.dp, InkBlack, shape)
            .padding(8.dp)
            .heightIn(max = 340.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        if (dates.isEmpty()) {
            Text(
                "no dates yet",
                fontFamily = Serif,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontSize = 14.sp,
                color = InkGray,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        for ((date, lineId) in dates) {
            val ts = date.atStartOfDay(com.zachzundel.encrier.data.TAPE_ZONE).toInstant().toEpochMilli()
            Text(
                dayMarkerLabel(ts).lowercase(),
                fontFamily = Serif,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontSize = 14.sp,
                color = InkBlack,
                modifier = Modifier
                    .hardClickable { onPick(lineId) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** Calendar glyph in the same drawn style as ClockButton. */
@Composable
private fun CalendarButton(selected: Boolean, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp)
    Box(
        Modifier
            .border(1.5.dp, InkBlack, shape)
            .background(if (selected) InkBlack else InkWhite, shape)
            .hardClickable(onClick)
            .padding(8.dp),
    ) {
        Canvas(Modifier.size(20.dp)) {
            val c = if (selected) InkWhite else InkBlack
            val w = size.width
            val h = size.height
            val bodyTop = h * 0.18f
            drawRoundRect(
                color = c,
                topLeft = Offset(w * 0.05f, bodyTop),
                size = Size(w * 0.9f, h - bodyTop - 1f),
                cornerRadius = CornerRadius(3f, 3f),
                style = Stroke(width = 2f),
            )
            // Header rule under the top edge.
            val ruleY = bodyTop + h * 0.2f
            drawLine(c, Offset(w * 0.05f, ruleY), Offset(w * 0.95f, ruleY), strokeWidth = 2f)
            // Binding ticks above the body.
            drawLine(c, Offset(w * 0.32f, 0f), Offset(w * 0.32f, bodyTop + 2f), strokeWidth = 2f)
            drawLine(c, Offset(w * 0.68f, 0f), Offset(w * 0.68f, bodyTop + 2f), strokeWidth = 2f)
        }
    }
}

@Composable
private fun ClockButton(selected: Boolean, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp)
    Box(
        Modifier
            .border(1.5.dp, InkBlack, shape)
            .background(if (selected) InkBlack else InkWhite, shape)
            .hardClickable(onClick)
            .padding(8.dp),
    ) {
        Canvas(Modifier.size(20.dp)) {
            val c = if (selected) InkWhite else InkBlack
            val r = size.minDimension / 2f
            drawCircle(c, radius = r - 1f, style = Stroke(width = 2f))
            drawLine(c, center, center + Offset(0f, -r * 0.55f), strokeWidth = 2f)
            drawLine(c, center, center + Offset(r * 0.4f, r * 0.12f), strokeWidth = 2f)
        }
    }
}
