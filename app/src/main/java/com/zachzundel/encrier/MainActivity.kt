package com.zachzundel.encrier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink

/**
 * Spec §0 validation gates, nothing more:
 *  1. en-US Digital Ink model downloads and recognizes a line on the DC-1.
 *  2. Captured ink is rendered back — judge stroke fidelity by eye; if chunky,
 *     switch capture to MotionEvent with historical points.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SmokeTestScreen() }
    }
}

private class InkStrokePoints {
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    val ts = ArrayList<Long>()
}

@Composable
private fun SmokeTestScreen() {
    var modelStatus by remember { mutableStateOf("checking model…") }
    var modelReady by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val strokes = remember { mutableStateListOf<InkStrokePoints>() }
    var redraws by remember { mutableStateOf(0) }

    val model = remember {
        DigitalInkRecognitionModel.builder(DigitalInkRecognitionModelIdentifier.EN_US).build()
    }

    LaunchedEffect(Unit) {
        val manager = RemoteModelManager.getInstance()
        manager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (downloaded) {
                    modelStatus = "model ready"
                    modelReady = true
                } else {
                    modelStatus = "downloading model…"
                    manager.download(model, DownloadConditions.Builder().build())
                        .addOnSuccessListener {
                            modelStatus = "model ready"
                            modelReady = true
                        }
                        .addOnFailureListener { modelStatus = "download FAILED: ${it.message}" }
                }
            }
            .addOnFailureListener { modelStatus = "model check FAILED: ${it.message}" }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(modelStatus)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .border(2.dp, Color.Black)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            strokes.add(InkStrokePoints().apply {
                                xs.add(offset.x); ys.add(offset.y)
                                ts.add(System.currentTimeMillis())
                            })
                            redraws++
                        },
                        onDrag = { change, _ ->
                            strokes.lastOrNull()?.apply {
                                xs.add(change.position.x); ys.add(change.position.y)
                                ts.add(System.currentTimeMillis())
                            }
                            change.consume()
                            redraws++
                        }
                    )
                }
        ) {
            redraws // read so new points trigger a redraw
            for (s in strokes) {
                if (s.xs.isEmpty()) continue
                val path = Path().apply {
                    moveTo(s.xs[0], s.ys[0])
                    for (i in 1 until s.xs.size) lineTo(s.xs[i], s.ys[i])
                }
                drawPath(path, Color.Black, style = Stroke(width = 3f))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = modelReady && strokes.isNotEmpty(),
                onClick = {
                    val inkBuilder = Ink.builder()
                    for (s in strokes) {
                        val sb = Ink.Stroke.builder()
                        for (i in s.xs.indices) {
                            sb.addPoint(Ink.Point.create(s.xs[i], s.ys[i], s.ts[i]))
                        }
                        inkBuilder.addStroke(sb.build())
                    }
                    result = "recognizing…"
                    DigitalInkRecognition.getClient(
                        DigitalInkRecognizerOptions.builder(model).build()
                    )
                        .recognize(inkBuilder.build())
                        .addOnSuccessListener { r ->
                            result = r.candidates.joinToString("\n") { it.text }
                        }
                        .addOnFailureListener { result = "recognition FAILED: ${it.message}" }
                }
            ) { Text("Recognize") }

            Button(onClick = { strokes.clear(); result = ""; redraws++ }) { Text("Clear") }
        }

        Text(result)
    }
}
