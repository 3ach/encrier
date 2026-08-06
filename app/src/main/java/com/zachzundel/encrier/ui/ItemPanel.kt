package com.zachzundel.encrier.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zachzundel.encrier.data.ItemEntity
import com.zachzundel.encrier.data.decodeCandidates
import com.zachzundel.encrier.data.shortDate

@Composable
internal fun ItemPanel(vm: TapeViewModel, panel: TapeViewModel.Panel, modifier: Modifier) {
    val item = panel.item
    val cardShape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val fieldShape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    var suggestionsOpen by remember(item.lineId) { mutableStateOf(false) }
    Column(
        modifier
            .background(InkWhite, cardShape)
            .border(1.5.dp, InkBlack, cardShape)
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.runtime.key(item.lineId) {
            WritableInkPreview(
                strokes = panel.strokes,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .border(1.dp, InkMargin, fieldShape),
                onStroke = { vm.appendInkTo(item.lineId, it) },
                onErase = { p, r -> vm.eraseAt(item.lineId, p, r) },
            )
        }
        // Date row: the caption plus quiet nudges to relist the item on another day.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "written " + shortDate(item.createdAt).lowercase(),
                fontFamily = Serif,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontSize = 12.sp,
                color = InkGray,
                modifier = Modifier.weight(1f),
            )
            DateNudge("◂") { vm.shiftItemDate(item.lineId, -1) }
            DateNudge("▸") { vm.shiftItemDate(item.lineId, +1) }
        }
        // Suggestions as a collapsed dropdown.
        Column(Modifier.fillMaxWidth().border(1.dp, InkMargin, fieldShape).padding(2.dp)) {
            Row(
                Modifier.fillMaxWidth().hardClickable { suggestionsOpen = !suggestionsOpen }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.text,
                    fontFamily = Serif,
                    fontSize = 15.sp,
                    color = InkBlack,
                    modifier = Modifier.weight(1f),
                )
                Text(if (suggestionsOpen) "▴" else "▾", color = InkGray, fontSize = 13.sp)
            }
            if (suggestionsOpen) {
                for (candidate in decodeCandidates(item.candidatesJson)) {
                    val selected = candidate == item.text
                    Text(
                        candidate,
                        fontFamily = Serif,
                        fontSize = 15.sp,
                        color = if (selected) InkWhite else InkBlack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (selected) InkBlack else InkWhite, fieldShape)
                            .hardClickable { vm.chooseCandidate(item.lineId, candidate) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
        // Typed correction keyboard field. A typed text (unlike
        // a candidate pick) is saved with the ink as recognizer training data.
        var correction by remember(item.lineId) { mutableStateOf("") }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = correction,
                onValueChange = { correction = it },
                singleLine = true,
                textStyle = TextStyle(fontFamily = Serif, fontSize = 15.sp, color = InkBlack),
                decorationBox = { inner ->
                    Box {
                        if (correction.isEmpty()) {
                            Text(
                                "type a correction…",
                                fontFamily = Serif,
                                fontSize = 15.sp,
                                color = InkGray,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .background(InkWhite, fieldShape)
                    .border(1.dp, InkMargin, fieldShape)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            )
            HardButton("apply", onClick = { vm.correctText(item.lineId, correction) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.status == ItemEntity.OPEN) {
                HardButton("done", onClick = { vm.markDone(item.lineId) })
                HardButton("drop", onClick = { vm.markDropped(item.lineId) })
            } else {
                HardButton("reopen", onClick = { vm.reopen(item.lineId) })
            }
            HardButton("delete", onClick = { vm.deleteItem(item.lineId) })
            Spacer(Modifier.width(8.dp))
            HardButton("close", onClick = { vm.closePanel() })
        }
    }
}

/** A HardButton scaled down to caption weight: margin-gray hairline, no fill. */
@Composable
private fun DateNudge(label: String, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp)
    Text(
        label,
        color = InkGray,
        fontSize = 12.sp,
        fontFamily = Mono,
        modifier = Modifier
            .border(1.dp, InkMargin, shape)
            .background(InkWhite, shape)
            .hardClickable(onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}
