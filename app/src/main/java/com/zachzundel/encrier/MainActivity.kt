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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import com.zachzundel.encrier.ui.ReportsScreen
import com.zachzundel.encrier.ui.ReportsViewModel
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
            HardButton("RETRY", onClick = { Graph.recognition.ensureModel(scope) })
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> TapeScreen(viewModel<TapeViewModel>())
                else -> ReportsScreen(viewModel<ReportsViewModel>())
            }
        }
        Row(Modifier.fillMaxWidth().border(2.dp, InkBlack)) {
            for ((i, label) in listOf("WRITE", "REPORTS").withIndex()) {
                Text(
                    label,
                    fontFamily = Mono,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = if (tab == i) InkWhite else InkBlack,
                    modifier = Modifier
                        .weight(1f)
                        .background(if (tab == i) InkBlack else InkWhite)
                        .clickable { tab = i }
                        .padding(vertical = 14.dp),
                )
            }
        }
    }
}
