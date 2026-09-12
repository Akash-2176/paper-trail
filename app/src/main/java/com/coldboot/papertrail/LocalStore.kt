package com.coldboot.papertrail

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Persistent local storage for the product layer.
 *
 * Everything the user does - pending entries, reconciled records, capture paths -
 * lived only in JS memory and vanished on every app restart. Only SMS survived,
 * because that is re-read from the provider each launch. This makes the ledger
 * durable.
 *
 * Deliberately a single JSON document on local disk, not SQLite:
 *  - ADR-011 keeps the product layer in plain JS with no build step, and the
 *    ledger is small (hundreds of rows, not millions)
 *  - the shell stays thin (ADR-002): native owns bytes, JS owns meaning
 *  - no schema migration to get wrong at 3am
 *
 * Storage stays bounded: writes are size-capped, so a long-running demo cannot
 * grow this without limit. Nothing here leaves the device (ADR-007).
 */
object LocalStore {

    private const val TAG = "PTLAB"
    private const val FILE = "ledger.json"

    /**
     * Hard ceiling on the persisted document. The product layer trims its own
     * row counts; this is the backstop that guarantees a constant footprint
     * even if that logic is wrong.
     */
    private const val MAX_BYTES = 512 * 1024

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE)

    /** Returns the stored JSON, or an empty object when nothing is saved yet. */
    fun load(ctx: Context): String {
        return try {
            val f = file(ctx)
            if (!f.isFile) return "{}"
            val text = f.readText()
            Log.i(TAG, "store: loaded ${text.length} bytes")
            text.ifBlank { "{}" }
        } catch (e: Throwable) {
            Log.e(TAG, "store: load failed: ${e.message}")
            "{}"
        }
    }

    /**
     * Persist the ledger. Writes to a temp file and renames, so a crash or a
     * kill mid-write cannot leave a half-written document that fails to parse
     * on next launch.
     */
    fun save(ctx: Context, json: String): Boolean {
        return try {
            if (json.length > MAX_BYTES) {
                Log.w(TAG, "store: refused ${json.length} bytes (cap $MAX_BYTES)")
                return false
            }
            val target = file(ctx)
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(target)) {
                // renameTo can fail if the target exists on some filesystems.
                target.delete()
                if (!tmp.renameTo(target)) {
                    Log.e(TAG, "store: rename failed")
                    return false
                }
            }
            Log.i(TAG, "store: saved ${json.length} bytes")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "store: save failed: ${e.message}")
            false
        }
    }

    fun clear(ctx: Context): Boolean {
        return try {
            file(ctx).delete()
            Log.i(TAG, "store: cleared")
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** Footprint reporting, so storage growth is observable rather than guessed. */
    fun usage(ctx: Context): String {
        fun dirSize(d: File): Long =
            d.listFiles()?.sumOf { if (it.isDirectory) dirSize(it) else it.length() } ?: 0L
        val files = ctx.filesDir
        return org.json.JSONObject().apply {
            put("ledgerBytes", file(ctx).length())
            put("capturesBytes", dirSize(File(files, "captures")))
            put("audioBytes", dirSize(File(files, "audio")))
            put("wwwBytes", dirSize(File(files, "www")))
            put("totalBytes", dirSize(files))
            put("cacheBytes", dirSize(ctx.cacheDir))
        }.toString()
    }
}
