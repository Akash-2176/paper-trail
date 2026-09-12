package com.coldboot.papertrail

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Single Activity. Thin shell (ADR-002) - it hosts the WebView and the bridge and
 * owns nothing product-shaped. All product logic lives in the hot-reloadable layer.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PTLAB"
    }

    private lateinit var webView: WebView
    private lateinit var bridge: Bridge
    private lateinit var watcher: SmsWatcher
    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var vision: VisionEngine
    private lateinit var llm: LlmEngine

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "shell: onCreate v" + BuildConfig.VERSION_NAME)

        // Copy-from-assets on first run; never clobbers a hot-reloaded page.
        WebHost.seedFromAssets(this)

        // SPIKE-FINDINGS §3: foreground service is required, not defensive.
        KeepAliveService.start(this)

        // PreviewView underneath, WebView on top. The WebView is transparent so
        // the viewfinder shows through wherever the page paints nothing.
        previewView = androidx.camera.view.PreviewView(this)
        webView = WebView(this)
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        val root = android.widget.FrameLayout(this)
        root.addView(
            previewView,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            webView,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        WebView.setWebContentsDebuggingEnabled(true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Product layer is loaded from file:// in getFilesDir(); allow local reads.
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }

        // SPIKE-FINDINGS §5 Edge 3: a JS syntax error fails SILENTLY - the reload
        // marker updates but content never renders. Mirror console into PTLAB so the
        // single logcat tail catches it.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                Log.i(
                    TAG, "console[" + cm.messageLevel() + "] " + cm.message() +
                        " @" + cm.sourceId() + ":" + cm.lineNumber()
                )
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i(TAG, "web: loaded " + url)
            }
        }

        bridge = Bridge(this, webView, CameraStub(this), GenieBinding(this))
        val cam = CameraCapture(this)
        cam.previewView = previewView
        vision = VisionEngine(this)
        bridge.realCamera = cam
        bridge.audio = AudioRecorder(this)
        bridge.vision = vision
        bridge.ocr = OcrEngine(this)
        bridge.speech = SpeechEngine(this)
        llm = LlmEngine(this)
        bridge.llm = llm
        // Loading NPU context binaries takes seconds; start at launch so the
        // first utterance is not the thing that waits for it.
        /* SERIALISE the two GenieX engines.
         *
         * Both want the Hexagon DSP. Warming them together SIGSEGVs inside
         * libggml-hexagon.so (ggml_backend_sched_new), because the VLM's
         * llama.cpp backend probes the DSP while the qairt LLM is mid-load.
         *
         * The NPU LLM goes first - it is the one that genuinely needs Hexagon -
         * and hands over to vision when it has finished, succeeded or not. */
        llm.onSettled = { vision.warmUp() }
        llm.warmUp()
        // Loading a multi-GB VLM takes time; start as early as possible so the
        // first receipt capture is not the thing that waits for it.
        webView.addJavascriptInterface(bridge, Bridge.NAME)

        // ContentObserver, not a BroadcastReceiver (ADR-001 holds: SMS still does not
        // trigger a capture flow, it only prompts a re-read and a reconciliation retry).
        watcher = SmsWatcher(this) { json -> bridge.push("sms", json) }
        bridge.onSimulate = { watcher.simulateChange() }

        if (!Perms.hasSms(this)) Perms.requestSms(this) else watcher.register()

        val url = WebHost.indexUrl(this)
        Log.i(TAG, "web: loading " + url)
        webView.loadUrl(url)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Perms.REQ) {
            val granted = Perms.hasSms(this)
            Log.i(TAG, "perm: READ_SMS granted=" + granted)
            // Observer can only register once the permission actually lands.
            if (granted) watcher.register()
            // native -> JS push, proves the reverse direction of the bridge
            bridge.push(
                "permissions",
                JSONObject().put("readSms", granted).toString()
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload on resume so an edit-push cycle shows up without a relaunch.
        webView.reload()
    }

    override fun onDestroy() {
        watcher.unregister()
        vision.close()
        llm.close()
        webView.destroy()
        super.onDestroy()
    }
}
