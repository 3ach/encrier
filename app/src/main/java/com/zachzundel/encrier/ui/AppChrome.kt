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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zachzundel.encrier.data.PageEntity
import com.zachzundel.encrier.data.TAPE_ZONE
import com.zachzundel.encrier.data.TapeEntity
import com.zachzundel.encrier.data.dayMarkerLabel
import java.time.LocalDate

private enum class AppView { TAPE, SKETCH, HISTORY }

@Composable
internal fun Tabs() {
    var view by rememberSaveable { mutableStateOf(AppView.TAPE) }
    var showTapePicker by rememberSaveable { mutableStateOf(false) }
    var showPagePicker by rememberSaveable { mutableStateOf(false) }
    var showDates by rememberSaveable { mutableStateOf(false) }
    fun closeCards() {
        showTapePicker = false
        showPagePicker = false
        showDates = false
    }
    val tapeVm = viewModel<TapeViewModel>()
    val sketchVm = viewModel<SketchViewModel>()
    val tapes by tapeVm.tapes.collectAsState()
    val currentTapeId by tapeVm.currentTapeId.collectAsState()
    val availableDates by tapeVm.availableDates.collectAsState()
    val pages by sketchVm.pages.collectAsState()
    val currentPage by sketchVm.currentPage.collectAsState()
    val tapeName = tapeDisplayName(tapes.firstOrNull { it.id == currentTapeId })
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (view) {
                    AppView.HISTORY -> "history"
                    AppView.SKETCH -> currentPage?.name ?: "sketch"
                    AppView.TAPE -> tapeName
                },
                fontFamily = Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 21.sp,
                color = InkBlack,
                modifier = when (view) {
                    AppView.TAPE -> Modifier.hardClickable {
                        val open = showTapePicker
                        closeCards()
                        showTapePicker = !open
                    }
                    AppView.SKETCH -> Modifier.hardClickable {
                        val open = showPagePicker
                        closeCards()
                        showPagePicker = !open
                    }
                    AppView.HISTORY -> Modifier
                },
            )
            Spacer(Modifier.weight(1f))
            if (view == AppView.SKETCH) {
                val bg = currentPage?.background
                BackgroundButton(PageEntity.BLANK, bg) { sketchVm.setBackground(it) }
                Spacer(Modifier.width(6.dp))
                BackgroundButton(PageEntity.DOTS, bg) { sketchVm.setBackground(it) }
                Spacer(Modifier.width(6.dp))
                BackgroundButton(PageEntity.LINES, bg) { sketchVm.setBackground(it) }
                Spacer(Modifier.width(14.dp))
            }
            if (view == AppView.TAPE) {
                CalendarButton(
                    selected = showDates,
                    onClick = {
                        val open = showDates
                        closeCards()
                        showDates = !open
                    },
                )
                Spacer(Modifier.width(10.dp))
            }
            SketchButton(
                selected = view == AppView.SKETCH,
                onClick = {
                    closeCards()
                    view = if (view == AppView.SKETCH) AppView.TAPE else AppView.SKETCH
                },
            )
            Spacer(Modifier.width(10.dp))
            ClockButton(
                selected = view == AppView.HISTORY,
                onClick = {
                    closeCards()
                    view = if (view == AppView.HISTORY) AppView.TAPE else AppView.HISTORY
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(InkMargin))
        Box(Modifier.weight(1f)) {
            when (view) {
                AppView.HISTORY -> HistoryScreen(viewModel<HistoryViewModel>())
                AppView.SKETCH -> SketchScreen(sketchVm)
                AppView.TAPE -> TapeScreen(tapeVm)
            }
            if (showTapePicker && view == AppView.TAPE) {
                TapePickerCard(
                    tapes = tapes,
                    currentTapeId = currentTapeId,
                    onSwitch = { tapeVm.switchTape(it); showTapePicker = false },
                    onCreate = { tapeVm.createTape(it); showTapePicker = false },
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                )
            }
            if (showPagePicker && view == AppView.SKETCH) {
                PagePickerCard(
                    pages = pages,
                    currentPageId = currentPage?.id,
                    onSwitch = { sketchVm.switchPage(it); showPagePicker = false },
                    onCreate = { sketchVm.createPage(it); showPagePicker = false },
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                )
            }
            if (view == AppView.TAPE && showDates) {
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
    var newName by rememberSaveable { mutableStateOf("") }
    Column(
        modifier.notebookCard(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (tape in tapes) {
            SelectableRow(tapeDisplayName(tape), selected = tape.id == currentTapeId) {
                onSwitch(tape.id)
            }
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
private fun DatePickerCard(
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
private fun PagePickerCard(
    pages: List<PageEntity>,
    currentPageId: Long?,
    onSwitch: (Long) -> Unit,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newName by rememberSaveable { mutableStateOf("") }
    Column(
        modifier.notebookCard(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (page in pages) {
            SelectableRow(page.name, selected = page.id == currentPageId) {
                onSwitch(page.id)
            }
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

/** Background chooser glyphs: blank square, dot grid, line grid. */
@Composable
private fun BackgroundButton(bg: String, current: String?, onPick: (String) -> Unit) =
    GlyphButton(selected = bg == current, onClick = { onPick(bg) }) { c ->
        val w = size.width
        val h = size.height
        when (bg) {
            PageEntity.DOTS -> {
                for (i in 0..2) for (j in 0..2) {
                    drawCircle(c, radius = 1.6f, center = Offset(w * (0.2f + 0.3f * i), h * (0.2f + 0.3f * j)))
                }
            }
            PageEntity.LINES -> {
                for (i in 0..2) {
                    val p = 0.2f + 0.3f * i
                    drawLine(c, Offset(w * 0.1f, h * p), Offset(w * 0.9f, h * p), strokeWidth = 1.5f)
                    drawLine(c, Offset(w * p, h * 0.1f), Offset(w * p, h * 0.9f), strokeWidth = 1.5f)
                }
            }
            else -> drawRoundRect(
                color = c,
                topLeft = Offset(w * 0.15f, h * 0.15f),
                size = Size(w * 0.7f, h * 0.7f),
                cornerRadius = CornerRadius(2f, 2f),
                style = Stroke(width = 1.5f),
            )
        }
    }

/** Pencil-over-page glyph for the sketch view toggle. */
@Composable
private fun SketchButton(selected: Boolean, onClick: () -> Unit) =
    GlyphButton(selected, onClick) { c ->
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = c,
            topLeft = Offset(w * 0.08f, h * 0.05f),
            size = Size(w * 0.7f, h * 0.9f),
            cornerRadius = CornerRadius(3f, 3f),
            style = Stroke(width = 2f),
        )
        // Pencil diagonal across the page.
        drawLine(c, Offset(w * 0.3f, h * 0.68f), Offset(w * 0.85f, h * 0.15f), strokeWidth = 2f)
        drawLine(c, Offset(w * 0.3f, h * 0.68f), Offset(w * 0.26f, h * 0.8f), strokeWidth = 2f)
    }

/** Small toggle button drawing its glyph in the selection-inverted color. */
@Composable
private fun GlyphButton(
    selected: Boolean,
    onClick: () -> Unit,
    glyph: DrawScope.(Color) -> Unit,
) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier
            .border(1.5.dp, InkBlack, shape)
            .background(if (selected) InkBlack else InkWhite, shape)
            .hardClickable(onClick)
            .padding(8.dp),
    ) {
        Canvas(Modifier.size(20.dp)) { glyph(if (selected) InkWhite else InkBlack) }
    }
}

/** Calendar glyph in the same drawn style as ClockButton. */
@Composable
private fun CalendarButton(selected: Boolean, onClick: () -> Unit) =
    GlyphButton(selected, onClick) { c ->
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

@Composable
private fun ClockButton(selected: Boolean, onClick: () -> Unit) =
    GlyphButton(selected, onClick) { c ->
        val r = size.minDimension / 2f
        drawCircle(c, radius = r - 1f, style = Stroke(width = 2f))
        drawLine(c, center, center + Offset(0f, -r * 0.55f), strokeWidth = 2f)
        drawLine(c, center, center + Offset(r * 0.4f, r * 0.12f), strokeWidth = 2f)
    }
