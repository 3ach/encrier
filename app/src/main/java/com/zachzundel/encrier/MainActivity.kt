package com.zachzundel.encrier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zachzundel.encrier.ink.Recognition
import com.zachzundel.encrier.ui.EncrierTheme
import com.zachzundel.encrier.ui.HardButton
import com.zachzundel.encrier.ui.InkBlack
import com.zachzundel.encrier.ui.InkWhite
import com.zachzundel.encrier.ui.Mono
import com.zachzundel.encrier.ui.hardClickable
import com.zachzundel.encrier.ui.HistoryScreen
import com.zachzundel.encrier.ui.HistoryViewModel
import com.zachzundel.encrier.ui.TapeScreen
import com.zachzundel.encrier.ui.TapeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EncrierTheme {
                Box(Modifier.fillMaxSize().background(InkWhite)) { AppRoot() }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val state by Graph.recognition.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Graph.recognition.ensureModel(this) }
    when (val s = state) {
        Recognition.ModelState.Ready -> Tabs()
        Recognition.ModelState.Checking -> BlockingScreen("checking recognition model…")
        Recognition.ModelState.Downloading -> BlockingScreen("downloading recognition model…")
        is Recognition.ModelState.Failed -> BlockingScreen("model download failed:\n${s.message}") {
            HardButton("retry", onClick = { Graph.recognition.ensureModel(scope) })
        }
    }
}

// App is unusable for conversion until the model is present (spec §4).
@Composable
private fun BlockingScreen(message: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, fontFamily = Mono, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

@Composable
private fun Tabs() {
    var showHistory by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (showHistory) "history" else "encrier",
                fontFamily = com.zachzundel.encrier.ui.Serif,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontSize = 21.sp,
                color = InkBlack,
            )
            Spacer(Modifier.weight(1f))
            ClockButton(selected = showHistory, onClick = { showHistory = !showHistory })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(com.zachzundel.encrier.ui.InkMargin))
        Box(Modifier.weight(1f)) {
            if (showHistory) HistoryScreen(viewModel<HistoryViewModel>())
            else TapeScreen(viewModel<TapeViewModel>())
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
