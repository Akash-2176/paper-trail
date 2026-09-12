package com.coldboot.papertrail

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage

/**
 * QR detection over the CameraX preview stream.
 *
 * ML Kit's bundled barcode model, same family as the OCR engine already in the
 * app - fully on-device, no download, nothing leaves the phone. Restricted to
 * QR_CODE because a UPI payment code is always a QR and every other format is
 * noise that only slows detection down.
 *
 * Detection is one job: turn frames into a string. Deciding whether that string
 * is a payable UPI URI belongs to UpiUri, and is done by the caller.
 */
class QrScanner(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "PTLAB"

        /**
         * Ignore repeats of the same code for this long.
         *
         * The analyser sees ~30 frames a second and a QR stays in frame for
         * seconds, so without this one scan would fire hundreds of callbacks -
         * and each one would try to create a transaction.
         */
        private const val DEDUPE_MS = 2500L
    }

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    @Volatile private var lastText: String? = null
    @Volatile private var lastAt = 0L

    /** Stops emitting without tearing the camera down. */
    @Volatile var paused = false

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || paused) { proxy.close(); return }

        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                val text = codes.firstNotNullOfOrNull { it.rawValue }?.takeIf { it.isNotBlank() }
                if (text != null) emit(text)
            }
            .addOnFailureListener { e ->
                // A single bad frame is normal; log at debug volume only.
                Log.d(TAG, "qr: frame failed: ${e.message}")
            }
            /* close() must happen exactly once and only after ML Kit is done
             * with the buffer - closing early starves the detector, closing
             * never stalls the whole pipeline after a handful of frames. */
            .addOnCompleteListener { proxy.close() }
    }

    private fun emit(text: String) {
        val now = System.currentTimeMillis()
        if (text == lastText && now - lastAt < DEDUPE_MS) return
        lastText = text
        lastAt = now
        /* Never log the payload: a UPI QR contains the payee's VPA, and on a
         * dynamic QR the amount too. Length alone is enough to debug with. */
        Log.i(TAG, "qr: detected ${text.length} chars")
        onText(text)
    }

    /** Allow the same code to be scanned again, e.g. after the user cancels. */
    fun reset() {
        lastText = null
        lastAt = 0L
        paused = false
    }

    fun close() {
        try { scanner.close() } catch (e: Throwable) { }
    }
}
