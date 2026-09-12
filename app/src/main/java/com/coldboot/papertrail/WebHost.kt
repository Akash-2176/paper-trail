package com.coldboot.papertrail

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Owns the hot-reloadable product layer on disk.
 *
 * ADR-002 / SPIKE-FINDINGS §5: the WebView loads from getFilesDir()/www, NOT from
 * assets, so the layer can be replaced on-device with no reinstall and no compile.
 *
 * Push route (debuggable build only, SPIKE-FINDINGS §5 Edge 1):
 *   adb push index.html /data/local/tmp/
 *   adb shell run-as com.coldboot.papertrail cp /data/local/tmp/index.html files/www/
 */
object WebHost {

    private const val TAG = "PTLAB"
    const val DIR = "www"

    fun wwwDir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    fun indexFile(ctx: Context): File = File(wwwDir(ctx), "index.html")

    fun indexUrl(ctx: Context): String = "file://" + indexFile(ctx).absolutePath

    /**
     * Copy-from-assets on first run. Existing files are NOT overwritten unless forced,
     * so a hot-reloaded page survives an app restart.
     */
    fun seedFromAssets(ctx: Context, force: Boolean = false) {
        val dir = wwwDir(ctx)
        val names = try {
            ctx.assets.list(DIR)?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "web: asset list failed: " + e.message)
            emptyList()
        }

        var copied = 0
        for (name in names) {
            val target = File(dir, name)
            if (target.exists() && !force) continue
            try {
                ctx.assets.open(DIR + "/" + name).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                copied++
            } catch (e: Exception) {
                Log.e(TAG, "web: copy failed for " + name + ": " + e.message)
            }
        }
        Log.i(TAG, "web: seeded " + copied + "/" + names.size + " into " + dir.absolutePath)
    }
}
