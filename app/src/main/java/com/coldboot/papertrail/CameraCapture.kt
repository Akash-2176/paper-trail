package com.coldboot.papertrail

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CameraX ImageCapture straight to a file. No preview surface - the demo only needs
 * the JPEG on disk and the path back in JS. Completion is async, so the result is
 * pushed to the product layer as a 'capture' event.
 *
 * The same binding also carries an optional ImageAnalysis use case for QR
 * scanning. One camera binding, two jobs: binding a second provider for the
 * scanner would fight this one for the sensor.
 */
class CameraCapture(private val act: AppCompatActivity) {

    companion object {
        private const val TAG = "PTLAB"
        private const val DIR = "captures"
    }

    private var imageCapture: ImageCapture? = null
    private var bound = false
    private var provider: ProcessCameraProvider? = null

    /**
     * Set before open() to attach a frame analyser (QR scanning). Changing it
     * requires a rebind, which [scanning] handles.
     */
    private var analyzer: ImageAnalysis.Analyzer? = null
    private var analysis: ImageAnalysis? = null

    /** True while the QR analyser is attached to the binding. */
    val scanning: Boolean get() = analyzer != null

    /**
     * Optional viewfinder. The WebView sits on top with a transparent hole
     * punched through it, so the preview shows behind the capture sheet.
     */
    var previewView: PreviewView? = null

    fun capturesDir(): File = File(act.filesDir, DIR).apply { mkdirs() }

    /** Bind once, lazily. Safe to call repeatedly. */
    private fun ensureBound(onReady: (Boolean) -> Unit) {
        if (bound && imageCapture != null) { onReady(true); return }
        val future = ProcessCameraProvider.getInstance(act)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                p.unbindAll()

                /* Analysis runs only while a QR scan is active. STRATEGY_KEEP_
                 * ONLY_LATEST matters: the detector is slower than the sensor,
                 * and a backpressure queue would make the viewfinder lag behind
                 * what the user is pointing at. */
                val an = analyzer?.let { a ->
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(ContextCompat.getMainExecutor(act), a) }
                }
                analysis = an

                val uses = listOfNotNull(
                    previewView?.let { pv ->
                        Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
                    },
                    ic,
                    an
                ).toTypedArray()

                p.bindToLifecycle(act, CameraSelector.DEFAULT_BACK_CAMERA, *uses)
                Log.i(TAG, "camera: bound preview=${previewView != null} scan=${an != null}")
                imageCapture = ic
                bound = true
                onReady(true)
            } catch (e: Exception) {
                Log.e(TAG, "camera: bind failed: ${e.message}")
                onReady(false)
            }
        }, ContextCompat.getMainExecutor(act))
    }

    /** Start the viewfinder. onReady reports whether a live preview exists. */
    fun open(onReady: (Boolean, Boolean) -> Unit) {
        ensureBound { ok -> onReady(ok, ok && previewView != null) }
    }

    /**
     * Start the viewfinder with a frame analyser attached (QR scanning).
     *
     * Forces a rebind: CameraX use cases are fixed at bind time, so an already
     * bound session has no analysis pipeline to add one to.
     */
    fun openWithAnalyzer(a: ImageAnalysis.Analyzer, onReady: (Boolean, Boolean) -> Unit) {
        act.runOnUiThread {
            analyzer = a
            unbind()
            ensureBound { ok -> onReady(ok, ok && previewView != null) }
        }
    }

    /** Detach the analyser but leave the camera available for stills. */
    fun stopAnalyzer() {
        act.runOnUiThread {
            if (analyzer == null) return@runOnUiThread
            analyzer = null
            try { analysis?.clearAnalyzer() } catch (e: Exception) { }
            analysis = null
            unbind()
        }
    }

    /** Main-thread unbind. CameraX requires it; callers arrive from JS threads. */
    private fun unbind() {
        try {
            provider?.unbindAll()
            bound = false
            imageCapture = null
        } catch (e: Exception) {
            Log.e(TAG, "camera: unbind failed: ${e.message}")
        }
    }

    /**
     * Release the sensor. Leaving it bound keeps the camera hot.
     * CameraX requires unbind on the main thread, and this is called from the
     * WebView's JS thread, so hop explicitly.
     */
    fun close() {
        act.runOnUiThread {
            try { analysis?.clearAnalyzer() } catch (e: Exception) { }
            analyzer = null
            analysis = null
            unbind()
            Log.i(TAG, "camera: released")
        }
    }

    /**
     * Fires a capture. Returns immediately with {ok, pending:true}; the finished
     * path arrives via the 'capture' push.
     */
    fun capture(onDone: (JSONObject) -> Unit): JSONObject {
        ensureBound { ok ->
            if (!ok) {
                onDone(JSONObject().put("ok", false).put("error", "camera bind failed"))
                return@ensureBound
            }
            val ic = imageCapture
            if (ic == null) {
                onDone(JSONObject().put("ok", false).put("error", "no ImageCapture"))
                return@ensureBound
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val f = File(capturesDir(), "receipt-$stamp.jpg")
            val opts = ImageCapture.OutputFileOptions.Builder(f).build()
            ic.takePicture(
                opts,
                ContextCompat.getMainExecutor(act),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(o: ImageCapture.OutputFileResults) {
                        Log.i(TAG, "camera: saved ${f.absolutePath} bytes=${f.length()}")
                        onDone(
                            JSONObject().put("ok", true).put("path", f.absolutePath)
                                .put("bytes", f.length())
                        )
                    }

                    override fun onError(e: ImageCaptureException) {
                        Log.e(TAG, "camera: capture failed: ${e.message}")
                        onDone(JSONObject().put("ok", false).put("error", e.message ?: "capture failed"))
                    }
                }
            )
        }
        return JSONObject().put("ok", true).put("pending", true)
    }
}
