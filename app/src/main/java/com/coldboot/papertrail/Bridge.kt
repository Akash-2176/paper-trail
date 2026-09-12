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

    /** CameraX capture path. STUB - proves the file path exists end to end. */
    @JavascriptInterface
    fun capturePhoto(): String = camera.captureStub().toString()

    /** GenieX binding is PRESENT but deliberately NOT WIRED yet. */
    @JavascriptInterface
    fun genieStatus(): String = genie.status().toString()

    @JavascriptInterface
    fun log(msg: String) {
        Log.i(TAG, "js: " + msg)
    }

    @JavascriptInterface
    fun wwwPath(): String = WebHost.wwwDir(ctx).absolutePath

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
