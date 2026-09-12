package com.coldboot.papertrail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Downscale a capture before inference.
 *
 * A phone camera JPEG is ~12MP. Qwen2.5-VL tokenises at roughly 28x28 pixels per
 * token, so a 3072x4096 photo becomes on the order of 16,000 image tokens against
 * a 2048-token context - it cannot fit, and the run times out. Measured on device
 * with a real Paytm receipt: 60s timeout, no output.
 *
 * Downscaling to ~1024px on the long edge brings that to roughly 1,300 tokens,
 * which fits. It also helps ML Kit OCR, which prefers normalised glyph sizes over
 * raw resolution.
 */
object ImagePrep {

    private const val TAG = "PTLAB"

    /** Long-edge target. Large enough for receipt text, small enough to fit context. */
    const val MAX_EDGE = 1024

    /**
     * Returns a downscaled copy, or the original path when no work is needed.
     * Never throws - on any failure the caller gets the original image back,
     * because a capture that cannot be prepared should still be readable.
     */
    fun prepare(srcPath: String, maxEdge: Int = MAX_EDGE): String {
        val src = File(srcPath)
        if (!src.isFile) return srcPath
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(srcPath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return srcPath
            if (maxOf(w, h) <= maxEdge) {
                Log.i(TAG, "prep: ${w}x${h} already within ${maxEdge}, unchanged")
                return srcPath
            }

            // inSampleSize halves cheaply during decode, so we never hold the
            // full 12MP bitmap in memory.
            var sample = 1
            while (maxOf(w, h) / (sample * 2) >= maxEdge) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeFile(srcPath, opts) ?: return srcPath

            val scale = maxEdge.toFloat() / maxOf(decoded.width, decoded.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else decoded

            val rotated = applyExifRotation(srcPath, scaled)

            val out = File(src.parentFile, src.nameWithoutExtension + "-prep.jpg")
            FileOutputStream(out).use { fos ->
                rotated.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            if (rotated !== decoded) decoded.recycle()

            Log.i(
                TAG, "prep: ${w}x${h} -> ${rotated.width}x${rotated.height} " +
                    "(${src.length() / 1024}KB -> ${out.length() / 1024}KB)"
            )
            out.absolutePath
        } catch (e: Throwable) {
            Log.e(TAG, "prep: failed, using original: ${e.message}")
            srcPath
        }
    }

    /** A sideways receipt reads far worse, and phones tag rotation in EXIF. */
    private fun applyExifRotation(path: String, bmp: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(path)
            val deg = when (
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg == 0f) return bmp
            val m = android.graphics.Matrix().apply { postRotate(deg) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (e: Throwable) {
            bmp
        }
    }
}
