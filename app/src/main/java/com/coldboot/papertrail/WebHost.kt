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
     *
     * RECURSIVE. The product layer is index.html plus a js/ directory of modules,
     * and a flat copy treated js/ as a file: assets.open() on a directory throws,
     * so a FRESH INSTALL shipped index.html with none of its scripts and the page
     * died on the first module reference ("PTUpi is not defined"). It went
     * unnoticed because the deploy script pushes the js/ files itself, so every
     * development device already had them.
     */
    fun seedFromAssets(ctx: Context, force: Boolean = false) {
        val dir = wwwDir(ctx)
        val stats = intArrayOf(0, 0)   // copied, total
        copyDir(ctx, DIR, dir, force, stats)
        Log.i(TAG, "web: seeded " + stats[0] + "/" + stats[1] + " into " + dir.absolutePath)
    }

    /**
     * Copy one asset directory into [target], descending into subdirectories.
     *
     * AssetManager gives no isDirectory(), so a node is classified by listing it:
     * a non-empty listing is a directory, anything else is treated as a file.
     */
    private fun copyDir(
        ctx: Context,
        assetPath: String,
        target: File,
        force: Boolean,
        stats: IntArray
    ) {
        val names = try {
            ctx.assets.list(assetPath)?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "web: asset list failed for " + assetPath + ": " + e.message)
            return
        }

        for (name in names) {
            val childAsset = "$assetPath/$name"
            val children = try { ctx.assets.list(childAsset) } catch (e: Exception) { null }

            if (children != null && children.isNotEmpty()) {
                val sub = File(target, name)
                if (!sub.exists() && !sub.mkdirs()) {
                    Log.e(TAG, "web: mkdir failed for " + sub.absolutePath)
                    continue
                }
                copyDir(ctx, childAsset, sub, force, stats)
                continue
            }

            stats[1]++
            val file = File(target, name)
            if (file.exists() && !force) continue
            try {
                ctx.assets.open(childAsset).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                stats[0]++
            } catch (e: Exception) {
                Log.e(TAG, "web: copy failed for " + childAsset + ": " + e.message)
            }
        }
    }
}
