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
        /**
         * Intent classification prompt.
         *
         * Qwen3 is a reasoning model: left to itself it emits a <think> block
         * and prose around the answer, which is why two of three probe
         * utterances came back "unparseable". So: no reasoning, one line, and a
         * worked example of each label. Few-shot beats instructions on a 1.7B.
         */
        private const val SYSTEM =
            "/no_think\n" +
            "Classify an expense utterance. Output ONE line of JSON, nothing else.\n" +
            "Fields: intent (capture|context|query), purpose, subject.\n" +
            "capture = states a new spend with an amount.\n" +
            "context = says what a spend was for, no amount.\n" +
            "query = asks a question about past spending.\n" +
            "Never output a number. No explanation. No markdown.\n" +
            "Examples:\n" +
            "three hundred for petrol -> " +
            "{\"intent\":\"capture\",\"purpose\":\"petrol\",\"subject\":\"\"}\n" +
            "this is for my college project -> " +
            "{\"intent\":\"context\",\"purpose\":\"college project\",\"subject\":\"\"}\n" +
            "how much did I spend on tomato -> " +
            "{\"intent\":\"query\",\"purpose\":\"\",\"subject\":\"tomato\"}"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val initStarted = AtomicBoolean(false)
    @Volatile private var llm: LlmWrapper? = null
    @Volatile private var lastError: String? = null
    @Volatile private var loading = false
    @Volatile private var computeUnit = "npu"

    /**
     * Fired once the NPU load has finished, whether it succeeded or not.
     * MainActivity uses it to start the VLM only after the DSP is free: both
     * engines probing Hexagon at once crashes inside libggml-hexagon.so.
     */
    @Volatile var onSettled: (() -> Unit)? = null

    private fun settle() {
        val cb = onSettled
        onSettled = null
        try { cb?.invoke() } catch (e: Throwable) {
            Log.e(TAG, "llm: settle callback failed: ${e.message}")
        }
    }

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

    /**
     * ModelConfig for the qairt/NPU path.
     *
     * The plugin rejects several llama.cpp-shaped options outright. Its own
     * binary carries the messages:
     *   "--nctx (n_ctx) is not supported by the qairt plugin"
     *   "--ngl (n_gpu_layers) is not supported by the qairt plugin"
     *   "--stop / --stop-file (stop sequences) is not supported by the qairt plugin"
     *
     * A default ModelConfig() populates those fields, which is why creation
     * failed with "Parameter not supported by this plugin" in ~3ms - before any
     * file was opened, so it was never a bad bundle. Zero them so the loader
     * takes the values from the bundle's own genie_config.json, which already
     * declares context size 4096, the vocab, and the HTP backend.
     */
    private fun qairtConfig(): ModelConfig = ModelConfig().apply {
        nCtx = 0          // context comes from genie_config.json
        nGpuLayers = 0    // meaningless on Hexagon; qairt rejects it
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
        /* GenieXSdk.init() is what registers the JNI natives. Calling
         * Llm.create before that completes throws "No implementation found for
         * com.geniex.sdk.jni.Llm.create" - seen twice on device when warmUp
         * raced the SDK init at launch.
         *
         * Class.forName is not a sufficient guard: the class resolves fine
         * while its native methods are still unregistered. So drive init here
         * and load only from its success callback. init() is idempotent, and
         * VisionEngine calling it too is harmless. */
        try {
            GenieXSdk.getInstance().init(ctx, object : GenieXSdk.InitCallback {
                override fun onSuccess() {
                    Log.i(TAG, "llm: sdk ready, loading bundle")
                    scope.launch { load() }
                }

                override fun onFailure(msg: String) {
                    lastError = "sdk init: $msg"
                    Log.e(TAG, "llm: $lastError")
                    settle()
                }
            })
        } catch (e: Throwable) {
            lastError = "sdk init threw: ${e.message}"
            Log.e(TAG, "llm: $lastError")
            settle()
        }
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
            settle()
            return
        }
        val tok = tokenizerIn(root)
        if (tok == null) {
            lastError = "tokenizer.json missing from ${root.name}"
            Log.w(TAG, "llm: $lastError")
            settle()
            return
        }

        loading = true
        try {
            /* NPU only. The plugin states it plainly at runtime:
             * "qairt plugin only supports NPU inference; ignoring device='cpu'".
             * Retrying the same bundle as CPU was therefore a no-op that only
             * produced a duplicate, misleading error line. CPU coverage comes
             * from the separate llama.cpp/GGUF path, not from here. */
            val units = listOf(ComputeUnitValue.NPU.value ?: "npu")
            val cfg0 = qairtConfig()
            Log.i(TAG, "llm: qairt cfg nCtx=" + cfg0.nCtx + " nGpu=" + cfg0.nGpuLayers +
                " nThreads=" + cfg0.nThreads + " nBatch=" + cfg0.nBatch)
            for (unit in units) {
                /* Pass a FILE inside the bundle, not the directory.
                 *
                 * The loader takes the parent of model_path as the bundle root:
                 * given the directory it resolved one level too high and
                 * reported "tokenizer.json not found in /data/local/tmp/models".
                 * Pointing at genie_config.json makes the parent the bundle
                 * itself, which is where the shards and tokenizer actually are. */
                val modelArg = File(root, "genie_config.json")
                    .takeIf { it.isFile }?.absolutePath ?: root.absolutePath
                val input = LlmCreateInput(
                    modelArg,
                    tok.absolutePath,
                    qairtConfig(),
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
                        /* First classification after load took 4.7s against
                         * ~400ms for every one after it - the cold prompt pays
                         * for graph setup. Spend that at launch instead of on
                         * the user's first utterance. */
                        scope.launch {
                            val t0 = System.currentTimeMillis()
                            run(it, "warm up")
                            Log.i(TAG, "llm: warmed in " +
                                (System.currentTimeMillis() - t0) + "ms")
                        }
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
            settle()
        }
    }

    /**
     * Classify one utterance. Calls back with {ok, intent, purpose, subject,
     * ms, computeUnit} or {ok:false, error}. Never throws into the caller, and
     * never returns a number.
     */
    /**
     * RAG over the ledger: the model is given the actual rows as context and
     * answers the question from them.
     *
     * This replaces an index-selection scheme that asked the model which items
     * belonged to a category. That misfired in ways that matter - it copied
     * digits out of its own few-shot example, and it answered "stationery"
     * against a ledger containing none - so a total could silently include an
     * unrelated payment.
     *
     * Retrieval is deterministic and happens in the product layer: the caller
     * decides which rows are relevant and how much money they represent. The
     * model receives that grounded context and only phrases the answer, and is
     * told plainly to say so when the context does not contain one. It cannot
     * invent a row it was not given.
     *
     * ADR-004 holds: every figure in `context` was computed by code before the
     * model saw it.
     */
    fun answerFromContext(
        question: String,
        context: String,
        timeoutMs: Long = 15_000,
        onDone: (JSONObject) -> Unit
    ) {
        val engine = llm
        if (engine == null) {
            onDone(JSONObject().put("ok", false).put("error", lastError ?: "llm not loaded"))
            return
        }
        scope.launch {
            val t0 = System.currentTimeMillis()
            val sys =
                "/no_think\n" +
                "You answer questions about a spending ledger.\n" +
                "Use ONLY the RECORDS given. Never invent a payment.\n" +
                "Never add up numbers yourself - totals are already provided.\n" +
                "Answer in ONE short sentence, plain English, no markdown.\n" +
                "If the records do not answer the question, say so plainly."
            val user = "RECORDS:\n" + context + "\n\nQUESTION: " + question + "\nANSWER:"

            val raw = withTimeoutOrNull(timeoutMs) { runChat(engine, sys, user, 160) }
            val ms = System.currentTimeMillis() - t0
            if (raw == null) {
                onDone(JSONObject().put("ok", false).put("error", "answer timed out"))
                return@launch
            }
            val text = cleanAnswer(raw)
            if (text.isNullOrBlank()) {
                Log.w(TAG, "llm: answer unusable -> " + raw.take(120))
                onDone(JSONObject().put("ok", false).put("error", "no usable answer"))
            } else {
                Log.i(TAG, "llm: answered in ${ms}ms on $computeUnit")
                onDone(
                    JSONObject().put("ok", true).put("text", text)
                        .put("ms", ms).put("computeUnit", computeUnit)
                )
            }
        }
    }

    /** Strip reasoning blocks and keep the first real sentence or two. */
    private fun cleanAnswer(raw: String): String? {
        var t = raw.replace(Regex("(?s)<think>.*?</think>"), "")
            .replace(Regex("(?s)<think>.*"), "")
            .replace(Regex("[*_`#]"), "")
            .trim()
        if (t.isBlank()) return null
        // Guard against a runaway generation: keep it to a short answer.
        if (t.length > 400) t = t.substring(0, 400)
        return t.lines().filter { it.isNotBlank() }.take(3).joinToString(" ").trim()
    }
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
                // Keep the raw text when parsing fails: guessing at prompt
                // problems without seeing the output wastes attempts.
                Log.w(TAG, "llm: unparseable -> " + raw.take(180))
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

    private suspend fun run(engine: LlmWrapper, text: String): String? =
        runChat(engine, SYSTEM, text, 256)

    /** One generation with an explicit system prompt. Shared by every LLM task. */
    private suspend fun runChat(
        engine: LlmWrapper,
        system: String,
        user: String,
        maxTokens: Int
    ): String? {
        return try {
            /* Clear the KV cache between classifications.
             *
             * Each call is independent, but the wrapper keeps context across
             * generate() calls. Without a reset the prompts accumulate and the
             * model degenerates - observed on device as a repeating token run
             * ("STRACTSTRACTSTRACT...") on the third consecutive classification
             * after two clean ones. */
            try { engine.reset() } catch (e: Throwable) {
                Log.w(TAG, "llm: reset failed: " + e.message)
            }
            val messages = arrayOf(
                ChatMessage("system", system),
                ChatMessage("user", user)
            )
            val templated = engine.applyChatTemplate(messages, null, true, false)
            val prompt = templated.getOrNull()?.formattedText ?: return null

            val cfg = GenerationConfig()
            // An intent label is a handful of tokens; a long budget only invites
            // the model to keep talking after the JSON is closed.
            /* Qwen3 emits an empty <think></think> pair even with /no_think,
             * and that costs tokens before the JSON starts. At 96 the answer was
             * being truncated mid-think, leaving "<think>" as the whole reply.
             * 256 leaves room for the block plus one JSON object. */
            cfg.maxTokens = maxTokens

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
        /* Qwen3 is a reasoning model and may still emit a <think> block despite
         * /no_think. Drop it before looking for JSON, or the first '{' found
         * could be inside the reasoning rather than the answer. */
        val cleaned = raw.replace(Regex("(?s)<think>.*?</think>"), "")
            .replace(Regex("(?s)<think>.*"), "")
        val start = cleaned.indexOf('{')
        val end = cleaned.indexOf('}', start)
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(cleaned.substring(start, end + 1))
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
