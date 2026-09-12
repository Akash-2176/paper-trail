package com.coldboot.papertrail

import android.app.Activity
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * The native<->JS bridge. ADR-003: postMessage-style, NO localhost HTTP server.
 *
 * Two-way:
 *   JS -> native   window.PT.<method>()      (this class, @JavascriptInterface)
 *   native -> JS   Bridge.push(event, json)  (evaluateJavascript -> window.PTOnEvent)
 *
 * Only structured, redacted objects cross. Raw SMS bodies never do.
 */
class Bridge(
    private val ctx: Context,
    private val webView: WebView,
    private val camera: CameraStub,
    private val genie: GenieBinding
) {

    companion object {
        private const val TAG = "PTLAB"
        const val NAME = "PT"

        /** Below this, OCR found too little to trust and the VLM is worth the wait. */
        private const val OCR_MIN_CHARS = 40
    }

    // --- JS -> native -----------------------------------------------------

    @JavascriptInterface
    fun ping(): String {
        Log.i(TAG, "bridge: ping")
        return JSONObject().apply {
            put("ok", true)
            put("shell", BuildConfig.VERSION_NAME)
            put("ts", System.currentTimeMillis())
        }.toString()
    }

    /** Structured, redacted SMS rows (ADR-003). Returns a JSON array as a string. */
    @JavascriptInterface
    fun readSms(limit: Int): String = SmsReader.query(ctx, if (limit > 0) limit else 200).toString()

    @JavascriptInterface
    fun readSmsSince(sinceMillis: String): String {
        val since = sinceMillis.toLongOrNull() ?: 0L
        return SmsReader.query(ctx, 200, since).toString()
    }

    @JavascriptInterface
    fun hasSmsPermission(): Boolean = Perms.hasSms(ctx)

    @JavascriptInterface
    fun requestSmsPermission() {
        (ctx as? Activity)?.let { Perms.requestSms(it) }
    }

    /**
     * Real CameraX capture. Returns {ok,pending:true} immediately; the saved path
     * arrives as a 'capture' push when the JPEG is written. Falls back to the stub
     * if no Activity-backed camera is available.
     */
    @JavascriptInterface
    fun capturePhoto(): String {
        val cam = realCamera
            ?: return camera.captureStub().toString()
        return cam.capture { result -> push("capture", result.toString()) }.toString()
    }

    /** 16kHz mono PCM WAV - the rate Whisper expects. */
    @JavascriptInterface
    fun startRecording(): String = (audio?.start()
        ?: JSONObject().put("ok", false).put("error", "no recorder")).toString()

    @JavascriptInterface
    fun stopRecording(): String {
        val r = audio?.stop()
            ?: JSONObject().put("ok", false).put("error", "no recorder")
        // WAV clips accumulate the same way captures did; keep them bounded too.
        ImagePrep.trim(java.io.File(ctx.filesDir, "audio"), 8)
        return r.toString()
    }

    /** Start the viewfinder. Result arrives as a 'cameraOpen' push. */
    @JavascriptInterface
    fun openCamera() {
        val cam = realCamera
        if (cam == null) {
            push("cameraOpen", JSONObject().put("ok", false)
                .put("error", "no camera").toString())
            return
        }
        cam.open { ok, hasPreview ->
            push(
                "cameraOpen",
                JSONObject().put("ok", ok).put("preview", hasPreview)
                    .put("error", if (ok) JSONObject.NULL else "bind failed").toString()
            )
        }
    }

    @JavascriptInterface
    fun closeCamera() {
        realCamera?.close()
    }

    /**
     * Receipt -> TEXT. ADR-004: the model never computes; the product layer
     * parses numbers out of this deterministically. Result arrives as a
     * 'vision' push.
     */
    @JavascriptInterface
    fun visionExtract(path: String) {
        val v = vision
        // Downscale once, up front. A raw 12MP capture exceeds the VLM's context
        // entirely and slows OCR down for no accuracy gain.
        val prepared = ImagePrep.prepare(path)
        // Keep captures/ bounded; it reached 49MB in one afternoon unchecked.
        ImagePrep.trim(java.io.File(ctx.filesDir, "captures"))

        /* OCR FIRST, deliberately.
         *
         * Measured on device with a real Paytm receipt: ML Kit returns usable
         * text in ~110ms, while the VLM on CPU still exceeds its 45s budget even
         * after downscaling - roughly a thousand image tokens is simply slow
         * without the NPU. A 50s stall is not usable in front of a user.
         *
         * The VLM is not wasted: it is better on creased, angled or handwritten
         * receipts where OCR returns little. So run OCR, and only escalate to
         * the model when OCR's text is too thin to parse. Revisit the ordering
         * when qairt/NPU is wired and inference is fast. */
        if (ocr != null) {
            ocr!!.extract(prepared) { result ->
                val text = result.optString("text", "")
                if (result.optBoolean("ok") && text.length >= OCR_MIN_CHARS) {
                    push("vision", result.toString())
                } else if (v != null && v.isReady()) {
                    Log.i(TAG, "vision: ocr thin (${text.length} chars), escalating to vlm")
                    v.extract(prepared) { vres ->
                        if (vres.optBoolean("ok")) push("vision", vres.toString())
                        else push("vision", result.put("vlmError",
                            vres.optString("error", "vlm failed")).toString())
                    }
                } else {
                    push("vision", result.toString())
                }
            }
            return
        }
        // No OCR engine at all - fall back to the model.
        if (v != null && v.isReady()) {
            v.extract(prepared) { result -> push("vision", result.toString()) }
        } else {
            push("vision", JSONObject().put("ok", false)
                .put("error", v?.status()?.optString("error") ?: "no extraction available")
                .toString())
        }
    }

    private fun runOcr(path: String, vlmError: String) {
        val o = ocr
        if (o == null) {
            push("vision", JSONObject().put("ok", false)
                .put("error", vlmError).toString())
            return
        }
        o.extract(path) { result ->
            // Keep why the VLM was skipped, so the gap stays visible rather
            // than silently looking like the model did the work.
            result.put("vlmError", vlmError)
            push("vision", result.toString())
        }
    }

    /** OCR only, bypassing the VLM. Used to compare the two paths on one image. */
    @JavascriptInterface
    fun ocrOnly(path: String) {
        runOcr(ImagePrep.prepare(path), "ocrOnly requested")
    }

    @JavascriptInterface
    fun visionStatus(): String =
        (vision?.status() ?: JSONObject().put("ok", false).put("error", "no engine")).toString()

    /**
     * Audio -> text. Uses Android's on-device recogniser rather than a bundled
     * Whisper: zero added app size, no model download, and it never leaves the
     * device. GenieX 0.4.0 ships no ASR of its own.
     *
     * The file-based form is kept for the recorded WAV, but the recogniser works
     * on a live mic stream, so [startListening] is the real path.
     */
    @JavascriptInterface
    fun transcribe(path: String) {
        push(
            "transcript",
            JSONObject().put("ok", false)
                .put("error", "file transcription unsupported; use live listening")
                .put("path", path).toString()
        )
    }

    /** Live on-device speech. Result arrives as a 'transcript' push. */
    @JavascriptInterface
    fun startListening() {
        val s = speech
        if (s == null) {
            push("transcript", JSONObject().put("ok", false)
                .put("error", "no speech engine").toString())
            return
        }
        s.onPartial = { text ->
            push("partial", JSONObject().put("text", text).toString())
        }
        s.start { result -> push("transcript", result.toString()) }
    }

    @JavascriptInterface
    fun stopListening() {
        speech?.stop()
    }

    @JavascriptInterface
    fun cancelListening() {
        speech?.cancel()
    }

    /* P0-3: Qwen3-1.7B on the Hexagon NPU. Intent only - never a number. */
    @JavascriptInterface
    fun llmAvailable(): Boolean = llm?.isReady() == true

    @JavascriptInterface
    fun llmStatus(): String =
        (llm?.status() ?: JSONObject().put("available", false)
            .put("error", "no engine")).toString()

    @JavascriptInterface
    fun classifyIntent(text: String) {
        val e = llm
        if (e == null) {
            push("intent", JSONObject().put("ok", false)
                .put("error", "no llm engine").toString())
            return
        }
        e.classify(text) { result -> push("intent", result.toString()) }
    }

    /**
     * RAG: answer a question from ledger rows the product layer retrieved.
     * The caller computes every figure; the model only phrases the answer and
     * is instructed to say so when the records do not contain one.
     */
    @JavascriptInterface
    fun answerFromContext(question: String, context: String) {
        val e = llm
        if (e == null) {
            push("answer", JSONObject().put("ok", false)
                .put("error", "no llm engine").toString())
            return
        }
        e.answerFromContext(question, context) { r -> push("answer", r.toString()) }
    }

    /** Re-scan for a bundle pushed after launch, without a restart. */
    @JavascriptInterface
    fun llmReload() { llm?.reload() }

    @JavascriptInterface
    fun speechStatus(): String =
        (speech?.status() ?: JSONObject().put("available", false)).toString()

    // --- persistence ------------------------------------------------------

    /** The ledger survives restarts; only SMS was durable before. */
    @JavascriptInterface
    fun loadLedger(): String = LocalStore.load(ctx)

    @JavascriptInterface
    fun saveLedger(json: String): Boolean = LocalStore.save(ctx, json)

    @JavascriptInterface
    fun clearLedger(): Boolean = LocalStore.clear(ctx)

    /** Observable storage footprint, so growth is measured rather than assumed. */
    @JavascriptInterface
    fun storageUsage(): String = LocalStore.usage(ctx)

    /** Set by MainActivity once the Activity exists. */
    var realCamera: CameraCapture? = null
    var audio: AudioRecorder? = null
    var vision: VisionEngine? = null
    var speech: SpeechEngine? = null
    var llm: LlmEngine? = null
    var ocr: OcrEngine? = null

    /** GenieX binding is PRESENT but deliberately NOT WIRED yet. */
    @JavascriptInterface
    fun genieStatus(): String = genie.status().toString()

    @JavascriptInterface
    fun log(msg: String) {
        Log.i(TAG, "js: " + msg)
    }

    @JavascriptInterface
    fun wwwPath(): String = WebHost.wwwDir(ctx).absolutePath

    /**
     * Test hook: simulate a provider change so the ContentObserver -> re-read ->
     * native->JS push path can be exercised without writing to the SMS provider.
     * Android 16 refuses adb writes to content://sms when there is no default SMS
     * app, so a real insert cannot be staged from the host.
     */
    @JavascriptInterface
    fun simulateSmsChange() {
        Log.i(TAG, "bridge: simulateSmsChange")
        onSimulate?.invoke()
    }

    /** Set by MainActivity to route into the real SmsWatcher debounce path. */
    var onSimulate: (() -> Unit)? = null

    /** Force re-copy from assets, then reload. Escape hatch if a push breaks the page. */
    @JavascriptInterface
    fun resetFromAssets() {
        WebHost.seedFromAssets(ctx, force = true)
        webView.post { webView.reload() }
    }

    @JavascriptInterface
    fun reload() {
        webView.post { webView.reload() }
    }

    // --- native -> JS -----------------------------------------------------

    /**
     * Push an event into the product layer. JS side implements:
     *   window.PTOnEvent = function (name, payload) { ... }
     */
    fun push(event: String, payloadJson: String) {
        val js = "window.PTOnEvent && window.PTOnEvent(" +
            JSONObject.quote(event) + "," + payloadJson + ")"
        webView.post {
            webView.evaluateJavascript(js) { }
        }
        Log.i(TAG, "bridge: push -> " + event)
    }
}
