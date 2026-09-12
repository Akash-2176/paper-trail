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
    fun stopRecording(): String = (audio?.stop()
        ?: JSONObject().put("ok", false).put("error", "no recorder")).toString()

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
        // VLM first when it is genuinely loaded; otherwise ML Kit OCR, which
        // runs on-device with no download and no missing projector.
        if (v != null && v.isReady()) {
            v.extract(path) { result ->
                if (result.optBoolean("ok")) push("vision", result.toString())
                else runOcr(path, result.optString("error", "vlm failed"))
            }
        } else {
            runOcr(path, v?.status()?.optString("error") ?: "vlm not loaded")
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

    @JavascriptInterface
    fun visionStatus(): String =
        (vision?.status() ?: JSONObject().put("ok", false).put("error", "no engine")).toString()

    /**
     * Audio -> text. NOT IMPLEMENTED: GenieX 0.4.0 ships no ASR - it bundles
     * llama.cpp and ggml, but no whisper.cpp and no speech class. Reports the
     * gap honestly so the UI falls back to typing rather than pretending.
     */
    @JavascriptInterface
    fun transcribe(path: String) {
        push(
            "transcript",
            JSONObject().put("ok", false)
                .put("error", "no on-device ASR: GenieX 0.4.0 ships no whisper runtime")
                .put("path", path).toString()
        )
    }

    /** Set by MainActivity once the Activity exists. */
    var realCamera: CameraCapture? = null
    var audio: AudioRecorder? = null
    var vision: VisionEngine? = null
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
