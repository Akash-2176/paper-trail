package com.coldboot.papertrail

import android.app.Activity
import android.content.Context
import android.net.Uri
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

    // --- UPI --------------------------------------------------------------

    /**
     * Start the QR scanner. Camera state arrives as a 'cameraOpen' push;
     * a detected code arrives as a 'upiQr' push carrying the parse result.
     *
     * Parsing happens natively so the product layer never has to reason about
     * UPI URI syntax - it receives either a payable transaction or a reason it
     * is not one.
     */
    @JavascriptInterface
    fun startQrScan() {
        val cam = realCamera
        if (cam == null) {
            push("cameraOpen", JSONObject().put("ok", false)
                .put("error", "no camera").toString())
            return
        }
        val scanner = QrScanner { text ->
            val parsed = UpiUri.parse(text)
            /* Pause on the FIRST readable code so the analyser stops firing
             * while the user decides. An unreadable code leaves scanning live:
             * pointing at a website QR should not require restarting the
             * scanner, it should just keep looking. */
            if (parsed.ok) qr?.paused = true
            push("upiQr", parsed.toJson().toString())
        }
        qr = scanner
        cam.openWithAnalyzer(scanner) { ok, hasPreview ->
            push("cameraOpen", JSONObject().put("ok", ok).put("preview", hasPreview)
                .put("scanning", ok)
                .put("error", if (ok) JSONObject.NULL else "bind failed").toString())
        }
    }

    @JavascriptInterface
    fun stopQrScan() {
        qr?.close()
        qr = null
        realCamera?.stopAnalyzer()
        realCamera?.close()
    }

    /** Re-arm after a rejected or cancelled code, without rebinding the camera. */
    @JavascriptInterface
    fun resumeQrScan() {
        qr?.reset()
    }

    /** Installed UPI apps, discovered by intent resolution. */
    @JavascriptInterface
    fun upiApps(): String = UpiIntentLauncher.availableApps(ctx).toString()

    /**
     * Create a Paper Trail transaction from a scanned QR, BEFORE any payment.
     *
     * The record exists first so that a payment which succeeds while our
     * process is killed is still attributable afterwards.
     */
    @JavascriptInterface
    fun upiCreateTransaction(qrJson: String, amountStr: String, note: String): String {
        return try {
            val parsed = UpiUri.parse(JSONObject(qrJson).optString("raw").ifBlank { null }
                ?: rebuildUri(JSONObject(qrJson)))
            if (!parsed.ok) {
                return JSONObject().put("ok", false)
                    .put("error", parsed.error ?: "invalid QR").toString()
            }
            /* The amount is re-validated here even though the UI collected it.
             * Client-side state is not authoritative - FEATURE 14 - and this is
             * the last point before a payment intent is built from it. */
            val amount = amountStr.toDoubleOrNull()
            if (amount == null || amount <= 0.0 || amount > 200000.0) {
                return JSONObject().put("ok", false)
                    .put("error", "Enter an amount between ₹1 and ₹2,00,000").toString()
            }
            // A QR that fixed the amount must be paid at that amount.
            if (parsed.amountLocked && parsed.amount != null &&
                Math.abs(parsed.amount - amount) > 0.005
            ) {
                return JSONObject().put("ok", false)
                    .put("error", "This QR has a fixed amount").toString()
            }
            val txn = UpiTransaction.fromQr(parsed, amount, note)
            UpiStore.put(ctx, txn)
            Log.i(TAG, "upi: created txn=${txn.id} state=${txn.state}")
            JSONObject().put("ok", true).put("txn", txn.toJson()).toString()
        } catch (e: Throwable) {
            Log.e(TAG, "upi: create failed: ${e.message}")
            JSONObject().put("ok", false).put("error", "Could not prepare payment").toString()
        }
    }

    /** Reassemble a upi:// URI from a parsed payload the UI round-tripped. */
    private fun rebuildUri(o: JSONObject): String {
        val b = StringBuilder("upi://pay?pa=").append(Uri.encode(o.optString("vpa")))
        o.optString("payeeName").takeIf { it.isNotBlank() }
            ?.let { b.append("&pn=").append(Uri.encode(it)) }
        o.optString("merchantCode").takeIf { it.isNotBlank() }
            ?.let { b.append("&mc=").append(Uri.encode(it)) }
        o.optString("refId").takeIf { it.isNotBlank() }
            ?.let { b.append("&tr=").append(Uri.encode(it)) }
        o.optString("note").takeIf { it.isNotBlank() }
            ?.let { b.append("&tn=").append(Uri.encode(it)) }
        if (!o.isNull("amount")) b.append("&am=").append(o.optDouble("amount"))
        b.append("&cu=").append(Uri.encode(o.optString("currency", "INR")))
        return b.toString()
    }

    /**
     * Hand the payment to a UPI app. `packageName` empty means show the system
     * chooser.
     *
     * Returns {ok} once the app has been launched - NOT once it has been paid.
     * The outcome arrives later as a 'upiResult' push.
     */
    @JavascriptInterface
    fun upiPay(txnId: String, packageName: String): String {
        val act = ctx as? Activity
            ?: return JSONObject().put("ok", false)
                .put("error", "no activity").toString()
        val txn = UpiStore.get(ctx, txnId)
            ?: return JSONObject().put("ok", false)
                .put("error", "Payment not found").toString()

        /* Only a freshly created transaction may be launched. Re-launching one
         * that is already in flight or settled would create a second real
         * payment against a record that claims to be one. */
        if (txn.state != UpiState.CREATED) {
            return JSONObject().put("ok", false)
                .put("error", "This payment has already been started").toString()
        }

        val err = UpiIntentLauncher.launch(act, txn, packageName.ifBlank { null })
        if (err != null) {
            return JSONObject().put("ok", false).put("error", err).toString()
        }
        UpiStore.update(ctx, txnId) {
            it.state = UpiState.PAYMENT_INITIATED
            it.initiatedAt = System.currentTimeMillis()
            it.payerApp = packageName.ifBlank { null }
        }
        return JSONObject().put("ok", true).put("state", UpiState.PAYMENT_INITIATED.name)
            .toString()
    }

    /** One transaction by id, or null. */
    @JavascriptInterface
    fun upiTransaction(id: String): String =
        (UpiStore.get(ctx, id)?.toJson() ?: JSONObject().put("ok", false)).toString()

    /** Every stored UPI transaction, newest last. The ledger merges these in. */
    @JavascriptInterface
    fun upiTransactions(): String {
        val arr = org.json.JSONArray()
        UpiStore.all(ctx).forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    /**
     * A payment whose result the UI has not shown yet.
     *
     * The WebView is reloaded on resume, so the result screen cannot rely on
     * in-page state surviving the trip to the UPI app. On boot the product
     * layer asks for this and shows the result screen if one is waiting.
     */
    @JavascriptInterface
    fun upiPendingResult(): String {
        val t = pendingResult ?: return JSONObject().put("ok", false).toString()
        return JSONObject().put("ok", true).put("txn", t.toJson()).toString()
    }

    /** Called once the result screen has been shown, so it is not shown twice. */
    @JavascriptInterface
    fun upiClearPendingResult() {
        pendingResult = null
    }

    /**
     * Attach what the user said to a transaction.
     *
     * The raw text is stored verbatim and unconditionally; the LLM's structured
     * reading is stored alongside it, never instead of it. If the model is
     * unavailable or returns nonsense the context is still captured - it is the
     * user's own words that matter, and interpretation is enrichment.
     */
    @JavascriptInterface
    fun upiAttachContext(txnId: String, text: String, source: String) {
        val clean = text.trim().take(300)
        if (clean.isEmpty()) {
            push("upiContext", JSONObject().put("ok", false)
                .put("error", "empty context").toString())
            return
        }
        val txn = UpiStore.update(ctx, txnId) {
            it.contextText = clean
            it.contextSource = if (source == "voice") "voice" else "text"
        }
        if (txn == null) {
            push("upiContext", JSONObject().put("ok", false)
                .put("error", "Payment not found").toString())
            return
        }

        val engine = llm
        if (engine == null || !engine.isReady()) {
            // No model: the words are saved, and that is a complete outcome.
            push("upiContext", JSONObject().put("ok", true)
                .put("txn", txn.toJson()).put("structured", false).toString())
            return
        }
        engine.structureContext(clean, txn.payeeName) { r ->
            val updated = if (r.optBoolean("ok")) {
                UpiStore.update(ctx, txnId) {
                    it.purpose = r.optString("purpose").ifBlank { null }
                    it.contextNote = r.optString("note").ifBlank { null }
                } ?: txn
            } else txn
            push(
                "upiContext",
                JSONObject().put("ok", true).put("txn", updated.toJson())
                    .put("structured", r.optBoolean("ok"))
                    .put("ms", r.opt("ms") ?: JSONObject.NULL).toString()
            )
        }
    }

    /**
     * Mark a transaction verified by independent evidence.
     *
     * Called by the product layer's reconciliation when a bank SMS corroborates
     * the payment. Native-side guard: only a SUBMITTED or PENDING payment can
     * become VERIFIED - nothing may promote a cancelled or failed one.
     */
    @JavascriptInterface
    fun upiMarkVerified(txnId: String, evidence: String): Boolean {
        val t = UpiStore.update(ctx, txnId) {
            if (it.state == UpiState.SUBMITTED || it.state == UpiState.PENDING) {
                it.state = UpiState.VERIFIED
                it.completedAt = System.currentTimeMillis()
            }
        }
        val ok = t?.state == UpiState.VERIFIED
        if (ok) Log.i(TAG, "upi: txn=$txnId verified by $evidence")
        return ok
    }

    /** Where MainActivity leaves a result the UI has not yet displayed. */
    @Volatile var pendingResult: UpiTransaction? = null

    private var qr: QrScanner? = null

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
