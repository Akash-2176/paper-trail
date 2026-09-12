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

        /**
         * GenieX's own media marker. applyChatTemplate emits this, and the native
         * pipeline substitutes the encoded image embeddings for it. It is NOT
         * <|image|> - that belongs to other stacks and is an unknown token here.
         */
        private const val MEDIA_TOKEN = "<__media__>"

        /**
         * Kept deliberately blunt: describe, do not compute (ADR-004).
         *
         * "Use only plain English letters and digits" is load-bearing, not style.
         * GenieX hands token text to JNI as it streams, and a multi-byte character
         * split across two chunks aborts the PROCESS with "JNI DETECTED ERROR ...
         * illegal continuation byte" - not a catchable exception. Observed on
         * device with a rupee sign. Constraining the output to ASCII keeps every
         * token single-byte, so no character can straddle a chunk boundary.
         */
        private const val PROMPT =
            "Read this receipt. Write the merchant name and the total amount " +
            "exactly as printed. Do not calculate anything. " +
            "Use only plain English letters and digits. " +
            "Write the currency as Rs, never as a symbol."
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val initStarted = AtomicBoolean(false)
    @Volatile private var sdkReady = false
    @Volatile private var vlm: VlmWrapper? = null
    @Volatile private var lastError: String? = null

    /**
     * Model file discovery - a VLM needs both weights and an mmproj projector.
     *
     * Prefer Q4_K_M over Q4_0 here. ARCHITECTURE section 4 asks for Q4_0 because
     * K-quants are not Hexagon-optimised, but that only matters on the qairt/NPU
     * path; we currently run llama_cpp on CPU, where Q4_0 of this model produced
     * fluent multilingual garbage while Q4_K_M is the build ggml-org actually
     * ships and tests. Correct output beats a quantisation we are not yet using
     * the accelerator for. Revisit when qairt is wired.
     */
    private fun modelFile(): File? {
        val all = File(MODELS).listFiles()
            ?.filter { it.name.endsWith(".gguf") && it.name.contains("VL", true) &&
                !it.name.contains("mmproj", true) }
            ?: return null
        return all.firstOrNull { it.name.contains("Q4_K", true) }
            ?: all.firstOrNull()
    }

    /**
     * The projector must come from the SAME publisher as the weights - a
     * ggml-org mmproj against an Unsloth model is a mismatched pair. Prefer a
     * projector whose name shares the model's quantisation lineage.
     */
    private fun mmprojFile(): File? {
        val all = File(MODELS).listFiles()
            ?.filter { it.name.contains("mmproj", true) } ?: return null
        val m = modelFile()?.name?.lowercase() ?: ""
        val unsloth = m.contains("unsloth")
        return all.firstOrNull { it.name.contains("unsloth", true) == unsloth }
            ?: all.firstOrNull()
    }

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
            put("crashedLastRun", didCrashLastRun())
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
     * A VLM run that abort()s the process leaves this flag set, because the
     * process dies before any catch or finally can run. On the next launch its
     * presence means the last attempt crashed, so we do not try again - one
     * crash is a bug, a crash loop during a demo is unrecoverable. Cleared only
     * on a clean completion.
     */
    private fun crashMarker(): File = File(ctx.filesDir, "vlm-inflight")

    fun didCrashLastRun(): Boolean = crashMarker().exists()

    /** Allow a deliberate retry after a crash, e.g. from a debug control. */
    fun clearCrashMarker() {
        try { crashMarker().delete() } catch (e: Throwable) {}
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

        if (didCrashLastRun()) {
            // Previous run took the process down mid-inference. Refuse and let
            // the caller fall back to OCR rather than crash-looping on stage.
            onDone(
                JSONObject().put("ok", false)
                    .put("error", "vlm crashed on the previous attempt, not retrying")
                    .put("crashed", true)
            )
            return
        }

        scope.launch {
            val t0 = System.currentTimeMillis()
            try { crashMarker().createNewFile() } catch (e: Throwable) {}
            val text = withTimeoutOrNull(timeoutMs) { runVlm(engine, img.absolutePath) }
            clearCrashMarker()
            val ms = System.currentTimeMillis() - t0
            if (text == null) {
                onDone(
                    JSONObject().put("ok", false)
                        .put("error", lastError ?: "vlm timed out after ${ms}ms")
                )
            } else if (!looksSane(text)) {
                /* A degraded model streams fluent-looking but unrelated tokens -
                 * observed as CJK and mixed-script output for an English receipt.
                 * Handing that to the parser risks a plausible wrong number on
                 * screen, which ADR-004 treats as worse than no number. Reject it
                 * and let the caller fall back to OCR. */
                Log.w(TAG, "vision: rejected non-ASCII-dominant output (${text.length} chars)")
                onDone(
                    JSONObject().put("ok", false)
                        .put("error", "model returned unusable output")
                        .put("sample", text.take(80))
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

    /**
     * A receipt read in English should be overwhelmingly ASCII. Anything else
     * means the model is producing noise rather than reading the image.
     */
    private fun looksSane(text: String): Boolean {
        if (text.isBlank()) return false
        val ascii = text.count { it.code in 32..126 }
        return ascii.toDouble() / text.length >= 0.85
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
            Log.i(
                TAG, "vision: cfg imageCount=" + cfg.imageCount +
                    " paths=" + (cfg.imagePaths?.joinToString(",") ?: "none")
            )
            if (cfg.imageCount <= 0) {
                // The image never reached the generation config, so the model
                // would be answering about nothing. Fail loudly instead.
                lastError = "image path not injected into config (imageCount=0)"
                Log.e(TAG, "vision: ${lastError}")
                return null
            }
            val templated = engine.applyChatTemplate(messages, null, true)
            val prompt = templated.getOrNull()?.formattedText ?: return null

            /* applyChatTemplate already emits GenieX's own media marker,
             * <__media__>, which the native pipeline replaces with the encoded
             * image. Do not insert anything else - an extra <|image|> is an
             * unknown token to this model and only corrupts the prompt. */
            if (!prompt.contains(MEDIA_TOKEN)) {
                lastError = "prompt carries no $MEDIA_TOKEN marker - image would be ignored"
                Log.e(TAG, "vision: ${lastError}")
                return null
            }
            Log.i(TAG, "vision: prompt head=" + prompt.take(110).replace("\n", "\\n"))

            val sb = StringBuilder()
            engine.generateStreamFlow(prompt, cfg).collect { chunk ->
                when (chunk) {
                    is LlmStreamResult.Token -> {
                        /* GenieX emits token text straight from native. A multi-byte
                         * UTF-8 character split across two chunks reaches JNI as a
                         * half-character and aborts the whole process with
                         * "JNI DETECTED ERROR ... illegal continuation byte".
                         * Touching .text is what triggers it, so read it defensively
                         * and drop the fragment rather than take the app down. */
                        val piece = try {
                            chunk.text
                        } catch (e: Throwable) {
                            Log.w(TAG, "vision: dropped undecodable token chunk")
                            null
                        }
                        if (piece != null) sb.append(piece)
                    }
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
