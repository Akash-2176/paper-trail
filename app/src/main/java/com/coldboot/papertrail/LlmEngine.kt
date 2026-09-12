package com.coldboot.papertrail

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Qwen3-1.7B on the HEXAGON NPU, via GenieX's qairt plugin.
 *
 * This is a genuinely different path from VisionEngine, which runs llama.cpp on
 * CPU with a GGUF. The qairt plugin cannot load GGUF at all: it wants an AI Hub
 * bundle - a directory of pre-compiled `.bin` context binaries plus
 * metadata.json and tokenizer.json, built for one specific chipset. Strings in
 * libgeniex.so state the contract directly: "metadata.json + *.bin (AI Hub
 * extracted), or a .zip file (AI Hub archive)".
 *
 * The bundle used here is Qualcomm's own W4A16 (4-bit weight) build for
 * snapdragon-8-elite-gen5, which is the loaner's SM8850. A bundle for any other
 * chipset will not load, so the file is chosen by name and the failure is
 * reported rather than silently falling back to something slower.
 *
 * ADR-004 is unchanged. This model classifies INTENT - what a person meant -
 * and never produces a number. Every figure in the product is computed by
 * deterministic JavaScript over ledger rows.
 */
class LlmEngine(private val ctx: Context) {

    companion object {
        private const val TAG = "PTLAB"
        private const val MODELS = GenieBinding.WEIGHTS_DIR

        /** Where an extracted AI Hub bundle lives on the device. */
        const val BUNDLE_DIR = "$MODELS/qwen3-1.7b-npu"

        /**
         * Intent classification prompt.
         *
         * Constrained hard on purpose: a fixed label set, JSON only, no prose,
         * and an explicit instruction never to emit an amount. The router
         * re-validates whatever comes back and re-parses money with code, so a
         * hallucinated field cannot reach the ledger.
         */
        private const val SYSTEM =
            "You label short expense utterances. Reply with ONE JSON object and " +
            "nothing else. Schema: {\"intent\":\"capture|context|query\"," +
            "\"purpose\":\"<short label or empty>\",\"subject\":\"<topic or empty>\"}. " +
            "capture = recording a new spend. context = labelling what a spend was for. " +
            "query = asking a question about past spending. " +
            "Never include an amount or any number. Use plain English letters only."
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val initStarted = AtomicBoolean(false)
    @Volatile private var llm: LlmWrapper? = null
    @Volatile private var lastError: String? = null
    @Volatile private var loading = false
    @Volatile private var computeUnit = "npu"

    /** The extracted bundle directory, if it is present and looks complete. */
    private fun bundleDir(): File? {
        val d = File(BUNDLE_DIR)
        if (!d.isDirectory) return null
        // A usable bundle needs metadata.json plus at least one .bin shard.
        val root = findBundleRoot(d) ?: return null
        return root
    }

    /**
     * AI Hub zips extract with a nested directory, so the bundle root is not
     * always the directory we created. Find the level that actually holds
     * metadata.json.
     */
    private fun findBundleRoot(dir: File, depth: Int = 0): File? {
        if (depth > 3) return null
        val files = dir.listFiles() ?: return null
        val hasMeta = files.any { it.name.equals("metadata.json", true) }
        val hasBin = files.any { it.name.endsWith(".bin", true) }
        if (hasMeta && hasBin) return dir
        for (f in files) {
            if (f.isDirectory) {
                val found = findBundleRoot(f, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    private fun tokenizerIn(root: File): File? =
        root.listFiles()?.firstOrNull { it.name.equals("tokenizer.json", true) }

    fun isReady(): Boolean = llm != null

    fun status(): JSONObject {
        val root = bundleDir()
        val shards = root?.listFiles()?.count { it.name.endsWith(".bin", true) } ?: 0
        return JSONObject().apply {
            put("available", llm != null)
            put("loading", loading)
            put("computeUnit", computeUnit)
            put("runtime", GenieXSdk.PLUGIN_ID_QAIRT)
            put("bundle", root?.absolutePath ?: JSONObject.NULL)
            put("shards", shards)
            put("tokenizer", root?.let { tokenizerIn(it) != null } ?: false)
            put("model", "Qwen3-1.7B W4A16")
            put("error", lastError ?: JSONObject.NULL)
        }
    }

    /** Fire-and-forget load. Safe to call repeatedly. */
    fun warmUp() {
        if (!initStarted.compareAndSet(false, true)) return
        scope.launch { load() }
    }

    /** Retry after a bundle has been pushed, without restarting the app. */
    fun reload() {
        initStarted.set(false)
        llm = null
        lastError = null
        warmUp()
    }

    private suspend fun load() {
        val root = bundleDir()
        if (root == null) {
            lastError = "no AI Hub bundle at $BUNDLE_DIR (needs metadata.json + *.bin)"
            Log.w(TAG, "llm: $lastError")
            return
        }
        val tok = tokenizerIn(root)
        if (tok == null) {
            lastError = "tokenizer.json missing from ${root.name}"
            Log.w(TAG, "llm: $lastError")
            return
        }

        loading = true
        try {
            /* NPU first - that is the whole point of this path. If Hexagon
             * refuses, try the same qairt bundle on CPU rather than leaving the
             * model unusable, but record honestly which one actually ran. The
             * badge and the status object report the real compute unit, never
             * the requested one: a claim of NPU execution has to be true. */
            val units = listOf(
                // Use each enum's own wire value rather than lowercasing its
                // name: the two happen to match today, but the SDK carries a
                // separate `value` field and that is the contract.
                ComputeUnitValue.NPU.value ?: "npu",
                ComputeUnitValue.CPU.value ?: "cpu"
            )
            for (unit in units) {
                val input = LlmCreateInput(
                    root.absolutePath,
                    tok.absolutePath,
                    ModelConfig(),
                    GenieXSdk.PLUGIN_ID_QAIRT,
                    unit
                )
                val built = LlmWrapper.builder()
                    .llmCreateInput(input)
                    .dispatcher(Dispatchers.IO)
                    .build()
                var ok = false
                built.fold(
                    onSuccess = {
                        llm = it
                        computeUnit = unit
                        lastError = null
                        ok = true
                        Log.i(
                            TAG,
                            "llm: Qwen3-1.7B loaded on ${unit.uppercase()} from ${root.name}"
                        )
                    },
                    onFailure = {
                        lastError = "$unit load failed: ${it.message}"
                        Log.e(TAG, "llm: $lastError")
                    }
                )
                if (ok) break
            }
        } catch (e: Throwable) {
            lastError = "load threw: ${e.message}"
            Log.e(TAG, "llm: $lastError")
        } finally {
            loading = false
        }
    }

    /**
     * Classify one utterance. Calls back with {ok, intent, purpose, subject,
     * ms, computeUnit} or {ok:false, error}. Never throws into the caller, and
     * never returns a number.
     */
    fun classify(text: String, timeoutMs: Long = 12_000, onDone: (JSONObject) -> Unit) {
        val engine = llm
        if (engine == null) {
            warmUp()
            onDone(
                JSONObject().put("ok", false)
                    .put("error", lastError ?: "llm not loaded")
            )
            return
        }
        scope.launch {
            val t0 = System.currentTimeMillis()
            val raw = withTimeoutOrNull(timeoutMs) { run(engine, text) }
            val ms = System.currentTimeMillis() - t0
            if (raw == null) {
                onDone(
                    JSONObject().put("ok", false)
                        .put("error", lastError ?: "llm timed out after ${ms}ms")
                )
                return@launch
            }
            val parsed = parse(raw)
            if (parsed == null) {
                onDone(
                    JSONObject().put("ok", false)
                        .put("error", "unparseable model output")
                        .put("sample", raw.take(90))
                )
            } else {
                Log.i(TAG, "llm: intent=${parsed.optString("intent")} in ${ms}ms on $computeUnit")
                onDone(parsed.put("ok", true).put("ms", ms).put("computeUnit", computeUnit))
            }
        }
    }

    private suspend fun run(engine: LlmWrapper, text: String): String? {
        return try {
            val messages = arrayOf(
                ChatMessage("system", SYSTEM),
                ChatMessage("user", text)
            )
            val templated = engine.applyChatTemplate(messages, null, true, false)
            val prompt = templated.getOrNull()?.formattedText ?: return null

            val cfg = GenerationConfig()
            // An intent label is a handful of tokens; a long budget only invites
            // the model to keep talking after the JSON is closed.
            cfg.maxTokens = 64

            val sb = StringBuilder()
            engine.generateStreamFlow(prompt, cfg).collect { chunk ->
                when (chunk) {
                    is LlmStreamResult.Token -> {
                        /* Reading .text can abort the process on a split
                         * multi-byte character - the same JNI hazard seen on the
                         * VLM path. The ASCII-only instruction in the prompt
                         * keeps tokens single-byte; this guard is the backstop. */
                        val piece = try { chunk.text } catch (e: Throwable) { null }
                        if (piece != null) sb.append(piece)
                    }
                    is LlmStreamResult.Error -> {
                        lastError = "stream: ${chunk.throwable.message}"
                        Log.e(TAG, "llm: $lastError")
                    }
                    else -> { }
                }
            }
            sb.toString().ifBlank { null }
        } catch (e: Throwable) {
            lastError = "run: ${e.message}"
            Log.e(TAG, "llm: $lastError")
            null
        }
    }

    /**
     * Pull the JSON object out of whatever the model said, and keep ONLY the
     * fields we asked for. Anything numeric is dropped on the floor: the model
     * is not permitted to influence money (ADR-004).
     */
    private fun parse(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(raw.substring(start, end + 1))
            val intent = o.optString("intent", "").lowercase().trim()
            if (intent != "capture" && intent != "context" && intent != "query") return null
            JSONObject()
                .put("intent", intent)
                .put("purpose", o.optString("purpose", "").take(40))
                .put("subject", o.optString("subject", "").take(40))
        } catch (e: Throwable) {
            null
        }
    }

    fun close() {
        try { llm?.close() } catch (e: Throwable) { }
        llm = null
    }
}
