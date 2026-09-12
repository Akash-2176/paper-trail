package com.coldboot.papertrail

import android.net.Uri
import android.content.Context
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.File

/**
 * ML Kit on-device OCR - the receipt path that actually works today.
 *
 * The staged Qwen2.5-VL GGUF has no vision tensors (the mmproj projector was
 * never downloaded and there is no network), so the VLM cannot read images.
 * ML Kit's bundled Latin recogniser runs fully on-device with no download and
 * is already compiled in, so it reads the receipt instead.
 *
 * ADR-004 is unaffected: this returns TEXT ONLY. Every number is parsed out of
 * that text by deterministic code in the product layer.
 */
class OcrEngine(private val ctx: Context) {

    companion object {
        private const val TAG = "PTLAB"
    }

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun extract(imagePath: String, onDone: (JSONObject) -> Unit) {
        val f = File(imagePath)
        if (!f.isFile) {
            onDone(JSONObject().put("ok", false).put("error", "image not found"))
            return
        }
        val t0 = System.currentTimeMillis()
        try {
            val image = InputImage.fromFilePath(ctx, Uri.fromFile(f))
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val ms = System.currentTimeMillis() - t0
                    val text = result.text ?: ""
                    Log.i(TAG, "ocr: ${text.length} chars in ${ms}ms")
                    onDone(
                        JSONObject().put("ok", text.isNotBlank())
                            .put("text", text)
                            .put("source", "mlkit-ocr")
                            .put("ms", ms)
                            .apply {
                                if (text.isBlank()) put("error", "no text found in image")
                            }
                    )
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ocr: failed: ${e.message}")
                    onDone(
                        JSONObject().put("ok", false)
                            .put("error", e.message ?: "ocr failed")
                    )
                }
        } catch (e: Throwable) {
            Log.e(TAG, "ocr: threw: ${e.message}")
            onDone(JSONObject().put("ok", false).put("error", e.message ?: "ocr threw"))
        }
    }
}
