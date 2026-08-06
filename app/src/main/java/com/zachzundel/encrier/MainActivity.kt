package com.zachzundel.encrier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zachzundel.encrier.ink.Recognition
import com.zachzundel.encrier.ui.EncrierTheme
import com.zachzundel.encrier.ui.HardButton
import com.zachzundel.encrier.ui.InkWhite
import com.zachzundel.encrier.ui.Mono
import com.zachzundel.encrier.ui.Tabs

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
    LaunchedEffect(Unit) { Graph.recognition.ensureModel() }
    when (val s = state) {
        Recognition.ModelState.Ready -> Tabs()
        Recognition.ModelState.Checking -> BlockingScreen("checking recognition model…")
        Recognition.ModelState.Downloading -> BlockingScreen("downloading recognition model…")
        is Recognition.ModelState.Failed -> BlockingScreen("model download failed:\n${s.message}") {
            HardButton("retry", onClick = { Graph.recognition.ensureModel() })
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
