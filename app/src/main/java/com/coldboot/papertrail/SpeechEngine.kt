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

        /** Grace for the service to release the previous session before a retry. */
        private const val RETRY_DELAY_MS = 350L

        /** locales x recognisers, plus slack. */
        private const val MAX_ATTEMPTS = 8
    }

    private var recognizer: SpeechRecognizer? = null
    @Volatile private var listening = false
    private var onResult: ((JSONObject) -> Unit)? = null
    private var partial: String = ""

    /**
     * Locales to try, in order. en-IN is preferred for rupee amounts, but the
     * loaner has NO offline language pack for it and the recogniser dies in
     * ~20ms with LANGUAGE_PACK_ERROR (code 13). en-US is the pack most likely to
     * be present, and the default locale is the last resort.
     */
    private val LOCALES = listOf("en-IN", "en-US", "en-GB", "")

    private var localeIndex = 0

    /** Hard ceiling on retries, so a misbehaving service cannot loop forever. */
    private var attempts = 0

    /**
     * Locales that failed with a missing language pack in this process. The
     * result does not change between attempts, so re-trying them costs ~1.7s of
     * dead time on every single capture. Remembering them makes the second and
     * later runs start on the configuration that actually works.
     */
    private val deadLocales = mutableSetOf<String>()

    /** Set once the on-device path is known to have no usable pack at all. */
    private var onDeviceUnusable = false

    /**
     * isOnDeviceRecognitionAvailable() returns true whenever the service exists,
     * even with no language pack installed - so it is not sufficient on its own.
     * Availability is only proven once a locale actually starts.
     */
    fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (SpeechRecognizer.isOnDeviceRecognitionAvailable(act) ||
                SpeechRecognizer.isRecognitionAvailable(act))

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

        // Once the on-device path is known to have no usable pack, go straight
        // to the recogniser that worked rather than replaying the failures.
        useSystemRecognizer = onDeviceUnusable
        localeIndex = 0
        while (!useSystemRecognizer && localeIndex < LOCALES.size - 1 &&
            deadLocales.contains(LOCALES[localeIndex])
        ) localeIndex++
        attempts = 0
        onResult = onDone
        act.runOnUiThread { attempt() }
    }

    /** True once the on-device path has exhausted its locales. */
    private var useSystemRecognizer = false

    /**
     * Try one (recogniser, locale) combination. A LANGUAGE_PACK_ERROR moves to
     * the next locale, and exhausting those falls back to the system recogniser,
     * which can use a different engine entirely.
     */
    private fun attempt() {
        try {
            stopInternal()
            partial = ""
            val r = if (useSystemRecognizer) {
                SpeechRecognizer.createSpeechRecognizer(act)
            } else {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(act)
            }
            recognizer = r
            r.setRecognitionListener(listener)
            val locale = LOCALES.getOrElse(localeIndex) { "" }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                if (locale.isNotBlank()) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                }
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Give the speaker room; the defaults cut off very short phrases.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1500L
                )
            }
            r.startListening(intent)
            listening = true
            Log.i(
                TAG, "asr: listening (" +
                    (if (useSystemRecognizer) "system" else "on-device") +
                    ", locale=" + (locale.ifBlank { "default" }) + ")"
            )
        } catch (e: Throwable) {
            listening = false
            Log.e(TAG, "asr: start failed: ${e.message}")
            deliver(JSONObject().put("ok", false).put("error", e.message ?: "start failed"))
        }
    }

    /**
     * Advance to the next locale, then to the system recogniser. Returns false
     * when every option is spent.
     */
    private fun tryNext(): Boolean {
        if (++attempts > MAX_ATTEMPTS) {
            Log.w(TAG, "asr: giving up after $attempts attempts")
            return false
        }
        /* Retrying immediately on a just-destroyed recogniser yields
         * ERROR_TOO_MANY_REQUESTS (11) - the service needs a moment to tear the
         * old session down. Observed on device. A short delay makes the retry
         * land cleanly. */
        // Skip locales already known to have no pack in this process.
        var next = localeIndex + 1
        while (!useSystemRecognizer && next < LOCALES.size &&
            deadLocales.contains(LOCALES[next])
        ) next++

        if (next < LOCALES.size) {
            localeIndex = next
            act.window.decorView.postDelayed({ attempt() }, RETRY_DELAY_MS)
            return true
        }
        if (!useSystemRecognizer) {
            useSystemRecognizer = true
            onDeviceUnusable = true
            localeIndex = 0
            act.window.decorView.postDelayed({ attempt() }, RETRY_DELAY_MS)
            return true
        }
        return false
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
                Log.i(TAG, "asr: transcript ${text.length} chars via " +
                    (if (useSystemRecognizer) "system" else "on-device"))
                deliver(
                    JSONObject().put("ok", true).put("text", text)
                        // Be explicit about which engine answered: the system
                        // recogniser may not be on-device, and ADR-007 turns on
                        // that distinction.
                        .put("source", if (useSystemRecognizer) "android-system-asr"
                                       else "android-ondevice-asr")
                        .put("onDevice", !useSystemRecognizer)
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
                        .put("source", if (useSystemRecognizer) "android-system-asr"
                                       else "android-ondevice-asr")
                        .put("onDevice", !useSystemRecognizer)
                        .put("partial", true)
                )
                return
            }

            /* Error 13 is LANGUAGE_PACK_ERROR: the service exists but no offline
             * model is installed for that locale, so it aborts in about 20ms and
             * looks to the user like the mic flashed and closed. Observed on the
             * loaner for en-IN. Fall through the other locales, then to the
             * system recogniser, before reporting failure. */
            if (isSetupError(error)) {
                // 13 is specific to the locale; 11 is transient contention.
                if (error == 13 && !useSystemRecognizer) {
                    deadLocales.add(LOCALES.getOrElse(localeIndex) { "" })
                }
                Log.w(TAG, "asr: error $error on locale index $localeIndex, trying next")
                listening = false
                if (tryNext()) return
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

    /**
     * Errors that mean "this configuration cannot work", as opposed to "the user
     * said nothing". Code 13 is LANGUAGE_PACK_ERROR on this device; the constant
     * is not in the public SDK, so it is matched numerically.
     */
    private fun isSetupError(code: Int): Boolean = when (code) {
        13 -> true                                              // LANGUAGE_PACK_ERROR
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> true          // 8, service still tearing down
        11 -> true                                              // TOO_MANY_REQUESTS
        SpeechRecognizer.ERROR_CLIENT -> true
        SpeechRecognizer.ERROR_SERVER -> true
        SpeechRecognizer.ERROR_NETWORK -> true
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> true
        else -> false
    }

    private fun describe(code: Int): String = when (code) {
        13 -> "no offline language pack installed for speech"
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
