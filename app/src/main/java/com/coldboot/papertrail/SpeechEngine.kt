package com.coldboot.papertrail

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * On-device speech recognition via Android's own recogniser.
 *
 * Chosen over a bundled Whisper build for three reasons that matter here:
 *  - it adds ZERO bytes to the app and needs no model download, where a Whisper
 *    GGUF plus its encoder projector is well over a gigabyte
 *  - it runs fully on-device, so ADR-007's no-egress claim still holds
 *  - GenieX 0.4.0 ships no ASR at all, and the NPU path cannot load GGUF
 *
 * Verified present on the loaner:
 *   voice_recognition_service = com.google.android.tts/GoogleTTSRecognitionService
 *   device API 36, so createOnDeviceSpeechRecognizer (API 31+) is available.
 *
 * ADR-004 is untouched: this produces TEXT. Every number is parsed out of that
 * text deterministically in the product layer.
 */
class SpeechEngine(private val act: AppCompatActivity) {

    companion object {
        private const val TAG = "PTLAB"
    }

    private var recognizer: SpeechRecognizer? = null
    @Volatile private var listening = false
    private var onResult: ((JSONObject) -> Unit)? = null
    private var partial: String = ""

    fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(act)

    fun status(): JSONObject = JSONObject().apply {
        put("available", isAvailable())
        put("listening", listening)
        put("onDevice", true)
        put("source", "android-ondevice-asr")
        put("sdk", Build.VERSION.SDK_INT)
    }

    /**
     * Start listening. Results arrive through [onDone] exactly once - either a
     * transcript or an error. Must be called on the main thread; SpeechRecognizer
     * requires it.
     */
    fun start(onDone: (JSONObject) -> Unit) {
        if (!isAvailable()) {
            onDone(
                JSONObject().put("ok", false)
                    .put("error", "on-device recognition unavailable on this device")
            )
            return
        }
        if (listening) {
            onDone(JSONObject().put("ok", false).put("error", "already listening"))
            return
        }

        act.runOnUiThread {
            try {
                stopInternal()
                partial = ""
                onResult = onDone
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(act)
                recognizer = r
                r.setRecognitionListener(listener)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    // Indian English: the spoken amounts are rupees.
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                r.startListening(intent)
                listening = true
                Log.i(TAG, "asr: listening (on-device)")
            } catch (e: Throwable) {
                listening = false
                Log.e(TAG, "asr: start failed: ${e.message}")
                deliver(JSONObject().put("ok", false).put("error", e.message ?: "start failed"))
            }
        }
    }

    /** Ask the recogniser to finish; the final transcript arrives via the callback. */
    fun stop() {
        act.runOnUiThread {
            try {
                recognizer?.stopListening()
                Log.i(TAG, "asr: stop requested")
            } catch (e: Throwable) {
                Log.e(TAG, "asr: stop failed: ${e.message}")
            }
        }
    }

    fun cancel() {
        act.runOnUiThread {
            listening = false
            onResult = null
            stopInternal()
        }
    }

    private fun stopInternal() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Throwable) {
            // destroying an already-dead recogniser is not worth reporting
        }
        recognizer = null
    }

    /** Fires the callback at most once, then releases it. */
    private fun deliver(result: JSONObject) {
        val cb = onResult
        onResult = null
        listening = false
        stopInternal()
        cb?.invoke(result)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val hyp = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!hyp.isNullOrBlank()) {
                partial = hyp
                // Live feedback so the sheet can show words as they are spoken.
                act.runOnUiThread { onPartial?.invoke(hyp) }
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?: partial
            if (text.isBlank()) {
                deliver(JSONObject().put("ok", false).put("error", "no speech recognised"))
            } else {
                Log.i(TAG, "asr: transcript ${text.length} chars")
                deliver(
                    JSONObject().put("ok", true).put("text", text)
                        .put("source", "android-ondevice-asr")
                )
            }
        }

        override fun onError(error: Int) {
            /* A partial hypothesis is still a usable transcript. The recogniser
             * reports NO_MATCH or SPEECH_TIMEOUT routinely at the end of a short
             * utterance, and discarding what it already heard loses the capture. */
            if (partial.isNotBlank()) {
                Log.i(TAG, "asr: error $error, using partial")
                deliver(
                    JSONObject().put("ok", true).put("text", partial)
                        .put("source", "android-ondevice-asr").put("partial", true)
                )
                return
            }
            Log.w(TAG, "asr: error $error")
            deliver(
                JSONObject().put("ok", false)
                    .put("error", describe(error)).put("code", error)
            )
        }
    }

    /** Live partial transcript, set by the caller for on-screen feedback. */
    var onPartial: ((String) -> Unit)? = null

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio error"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK -> "network error (should not happen on-device)"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no speech recognised"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recogniser busy"
        SpeechRecognizer.ERROR_SERVER -> "server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech detected"
        else -> "recognition error $code"
    }
}
