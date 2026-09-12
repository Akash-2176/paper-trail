package com.coldboot.papertrail

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GenieX VLM binding - receipt reading.
 *
 * ADR-004: this returns TEXT ONLY. It never computes a total, never decides an
 * amount. The product layer parses numbers out deterministically.
 *
 * ADR-006: weights are staged locally and loaded from disk. Nothing downloads.
 *
 * Everything here is best-effort. If the SDK is missing, the weights are absent
 * or the NPU refuses, it reports a failure and the capture flow falls back to
 * manual entry. The demo path must never depend on this succeeding.
 */
class VisionEngine(private val ctx: Context) {

    companion object {
        private const val TAG = "PTLAB"
        private const val MODELS = GenieBinding.WEIGHTS_DIR

        /** Kept deliberately blunt: describe, do not compute. */
        private const val PROMPT =
            "Read this receipt. List the merchant name and the total amount " +
            "exactly as printed. Do not calculate anything."
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val initStarted = AtomicBoolean(false)
    @Volatile private var sdkReady = false
    @Volatile private var vlm: VlmWrapper? = null
    @Volatile private var lastError: String? = null

    /** Model file discovery - a VLM needs both weights and an mmproj projector. */
    private fun modelFile(): File? =
        File(MODELS).listFiles()
            ?.firstOrNull { it.name.endsWith(".gguf") && it.name.contains("VL", true) }

    private fun mmprojFile(): File? =
        File(MODELS).listFiles()
            ?.firstOrNull { it.name.contains("mmproj", true) }

    fun isReady(): Boolean = vlm != null

    fun status(): JSONObject {
        val m = modelFile()
        val p = mmprojFile()
        return JSONObject().apply {
            put("sdkReady", sdkReady)
            put("vlmLoaded", vlm != null)
            put("model", m?.name ?: JSONObject.NULL)
            put("mmproj", p?.name ?: JSONObject.NULL)
            put("mmprojMissing", p == null)
            put("error", lastError ?: JSONObject.NULL)
        }
    }

    /** Fire-and-forget SDK init. Safe to call repeatedly. */
    fun warmUp() {
        if (!initStarted.compareAndSet(false, true)) return
        try {
            GenieXSdk.Companion.getInstance().init(ctx, object : GenieXSdk.InitCallback {
                override fun onSuccess() {
                    sdkReady = true
                    Log.i(TAG, "vision: GenieX sdk ready")
                    scope.launch { loadVlm() }
                }

                override fun onFailure(msg: String) {
                    lastError = "sdk init: $msg"
                    Log.e(TAG, "vision: sdk init failed: $msg")
                }
            })
        } catch (e: Throwable) {
            lastError = "sdk init threw: ${e.message}"
            Log.e(TAG, "vision: ${lastError}")
        }
    }

    private suspend fun loadVlm() {
        val model = modelFile()
        if (model == null) {
            lastError = "no *VL*.gguf in $MODELS"
            Log.w(TAG, "vision: ${lastError}")
            return
        }
        val mmproj = mmprojFile()
        if (mmproj == null) {
            /* The staged Qwen2.5-VL GGUF is text-only: arch is qwen2vl but it
             * contains 0 of 434 vision tensors, because llama.cpp splits the
             * vision tower into a separate mmproj-*.gguf that was never pulled.
             * Loading it anyway would produce fluent text about an image the
             * model never saw - the exact failure ADR-004 exists to prevent.
             * Refuse, and let the capture flow fall back to manual entry. */
            lastError = "mmproj projector missing - the staged VL gguf has no " +
                "vision tensors, so it cannot read images. Needs mmproj-*.gguf."
            Log.w(TAG, "vision: ${lastError}")
            return
        }
        try {
            // llama_cpp/CPU first: it is the configuration most likely to load.
            // NPU (qairt) is an optimisation, not a prerequisite, and swapping
            // runtime is a config change - see ARCHITECTURE section 4.
            val input = VlmCreateInput(
                model.absolutePath,
                mmproj.absolutePath,
                ModelConfig(),
                GenieXSdk.PLUGIN_ID_LLAMA_CPP,
                "cpu"
            )
            val built = VlmWrapper.Companion.builder()
                .vlmCreateInput(input)
                .dispatcher(Dispatchers.IO)
                .build()
            built.fold(
                onSuccess = {
                    vlm = it
                    lastError = null
                    Log.i(TAG, "vision: VLM loaded ${model.name}")
                },
                onFailure = {
                    lastError = "vlm build: ${it.message}"
                    Log.e(TAG, "vision: ${lastError}")
                }
            )
        } catch (e: Throwable) {
            lastError = "vlm load threw: ${e.message}"
            Log.e(TAG, "vision: ${lastError}")
        }
    }

    /**
     * Read a receipt image. Calls back with {ok, text, source, ms} or
     * {ok:false, error}. Never throws into the caller.
     */
    fun extract(imagePath: String, timeoutMs: Long = 45_000, onDone: (JSONObject) -> Unit) {
        val img = File(imagePath)
        if (!img.isFile) {
            onDone(JSONObject().put("ok", false).put("error", "image not found"))
            return
        }
        val engine = vlm
        if (engine == null) {
            warmUp()
            onDone(
                JSONObject().put("ok", false)
                    .put("error", lastError ?: "vlm not loaded yet")
                    .put("retryable", true)
            )
            return
        }

        scope.launch {
            val t0 = System.currentTimeMillis()
            val text = withTimeoutOrNull(timeoutMs) { runVlm(engine, img.absolutePath) }
            val ms = System.currentTimeMillis() - t0
            if (text == null) {
                onDone(
                    JSONObject().put("ok", false)
                        .put("error", lastError ?: "vlm timed out after ${ms}ms")
                )
            } else {
                Log.i(TAG, "vision: extracted ${text.length} chars in ${ms}ms")
                onDone(
                    JSONObject().put("ok", true).put("text", text)
                        .put("source", "geniex-vlm").put("ms", ms)
                )
            }
        }
    }

    private suspend fun runVlm(engine: VlmWrapper, imagePath: String): String? {
        return try {
            val msg = VlmChatMessage(
                "user",
                listOf(
                    VlmContent("image", imagePath),
                    VlmContent("text", PROMPT)
                )
            )
            val messages = arrayOf(msg)
            val cfg = engine.injectMediaPathsToConfig(messages, GenerationConfig())
            val templated = engine.applyChatTemplate(messages, null, true)
            val prompt = templated.getOrNull()?.formattedText ?: return null

            val sb = StringBuilder()
            engine.generateStreamFlow(prompt, cfg).collect { chunk ->
                when (chunk) {
                    is LlmStreamResult.Token -> sb.append(chunk.text)
                    is LlmStreamResult.Error -> {
                        lastError = "stream: ${chunk.throwable.message}"
                        Log.e(TAG, "vision: ${lastError}")
                    }
                    else -> { /* Completed - nothing to append */ }
                }
            }
            sb.toString().ifBlank { null }
        } catch (e: Throwable) {
            lastError = "vlm run: ${e.message}"
            Log.e(TAG, "vision: ${lastError}")
            null
        }
    }

    fun close() {
        try { vlm?.close() } catch (e: Throwable) {}
        vlm = null
    }
}
