package com.coldboot.papertrail

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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

    fun capturesDir(): File = File(act.filesDir, DIR).apply { mkdirs() }

    /** Bind once, lazily. Safe to call repeatedly. */
    private fun ensureBound(onReady: (Boolean) -> Unit) {
        if (bound && imageCapture != null) { onReady(true); return }
        val future = ProcessCameraProvider.getInstance(act)
        future.addListener({
            try {
                val provider = future.get()
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(act, CameraSelector.DEFAULT_BACK_CAMERA, ic)
                imageCapture = ic
                bound = true
                Log.i(TAG, "camera: bound")
                onReady(true)
            } catch (e: Exception) {
                Log.e(TAG, "camera: bind failed: ${e.message}")
                onReady(false)
            }
        }, ContextCompat.getMainExecutor(act))
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
