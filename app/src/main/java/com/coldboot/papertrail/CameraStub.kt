package com.coldboot.papertrail

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CameraX capture path - STUB.
 *
 * Purpose in Green Block 1 is only to prove the FILE PATH exists and is writable,
 * so the receipt pipeline has somewhere to land. Real ImageCapture wiring is a
 * Red Light / Green Block 2 job. The CameraX dependencies are already compiled in.
 */
class CameraStub(private val ctx: Context) {

    companion object {
        private const val TAG = "PTLAB"
        private const val DIR = "captures"
    }

    fun capturesDir(): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    /**
     * Creates a real, zero-byte placeholder file at the path a real capture would use
     * and returns its metadata. Proves the directory is writable from the JS side.
     */
    fun captureStub(): JSONObject {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(capturesDir(), "receipt-" + stamp + ".jpg")
        return try {
            f.createNewFile()
            Log.i(TAG, "camera: stub path " + f.absolutePath)
            JSONObject().apply {
                put("ok", true)
                put("stub", true)
                put("path", f.absolutePath)
                put("bytes", f.length())
                put("note", "stub only - CameraX ImageCapture not wired yet")
            }
        } catch (e: Exception) {
            Log.e(TAG, "camera: stub failed: " + e.message)
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "unknown")
            }
        }
    }
}
