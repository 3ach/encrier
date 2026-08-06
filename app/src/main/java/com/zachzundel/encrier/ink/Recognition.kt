package com.zachzundel.encrier.ink

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import com.zachzundel.encrier.data.InkPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class Recognition {
    sealed interface ModelState {
        data object Checking : ModelState
        data object Downloading : ModelState
        data object Ready : ModelState
        data class Failed(val message: String) : ModelState
    }

    // App-lifetime scope: the model download must not die with a composable.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val model =
        DigitalInkRecognitionModel.builder(DigitalInkRecognitionModelIdentifier.EN_US).build()
    private val recognizer by lazy {
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
    }

    val state = MutableStateFlow<ModelState>(ModelState.Checking)

    fun ensureModel() {
        if (state.value == ModelState.Ready) return
        scope.launch {
            state.value = ModelState.Checking
            try {
                val mgr = RemoteModelManager.getInstance()
                if (!mgr.isModelDownloaded(model).await()) {
                    state.value = ModelState.Downloading
                    mgr.download(model, DownloadConditions.Builder().build()).await()
                }
                state.value = ModelState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = ModelState.Failed(e.message ?: "unknown error")
            }
        }
    }

    /** One Ink per line, line-relative coordinates, per spec §4. Returns ranked candidates. */
    suspend fun recognizeLine(
        strokes: List<List<InkPoint>>,
        widthPx: Float,
        lineHeightPx: Float,
    ): List<String> {
        val inkBuilder = Ink.builder()
        for (s in strokes) {
            val sb = Ink.Stroke.builder()
            for (p in s) sb.addPoint(Ink.Point.create(p.x, p.y, p.t))
            inkBuilder.addStroke(sb.build())
        }
        val ctx = RecognitionContext.builder()
            .setPreContext("")
            .setWritingArea(WritingArea(widthPx, lineHeightPx))
            .build()
        return recognizer.recognize(inkBuilder.build(), ctx).await().candidates.map { it.text }
    }
}
