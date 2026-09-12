package com.coldboot.papertrail

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
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

                val pv = previewView
                if (pv != null) {
                    val preview = Preview.Builder().build()
                    preview.surfaceProvider = pv.surfaceProvider
                    p.bindToLifecycle(act, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic)
                    Log.i(TAG, "camera: bound with preview")
                } else {
                    p.bindToLifecycle(act, CameraSelector.DEFAULT_BACK_CAMERA, ic)
                    Log.i(TAG, "camera: bound (no preview)")
                }
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

    /** Release the sensor. Leaving it bound keeps the camera hot. */
    fun close() {
        try {
            provider?.unbindAll()
            bound = false
            imageCapture = null
            Log.i(TAG, "camera: released")
        } catch (e: Exception) {
            Log.e(TAG, "camera: release failed: ${e.message}")
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
