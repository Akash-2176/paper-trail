package com.coldboot.papertrail

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 16kHz mono PCM16 WAV. Whisper wants exactly this - getting the rate right here
 * avoids a resample step later.
 */
class AudioRecorder(private val ctx: Context) {

    companion object {
        private const val TAG = "PTLAB"
        private const val RATE = 16000
        private const val DIR = "audio"
    }

    @Volatile private var running = false
    private var rec: AudioRecord? = null
    private var out: File? = null

    fun audioDir(): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    @SuppressLint("MissingPermission")
    fun start(): JSONObject {
        if (running) return JSONObject().put("ok", false).put("error", "already recording")

        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            return JSONObject().put("ok", false).put("error", "bad buffer size $minBuf")
        }
        val bufSize = minBuf * 2

        return try {
            val r = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return JSONObject().put("ok", false).put("error", "AudioRecord not initialised")
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val f = File(audioDir(), "note-$stamp.wav")
            writeWavHeader(f, 0)

            rec = r
            out = f
            running = true
            r.startRecording()

            thread(name = "pt-audio") {
                val buf = ByteArray(bufSize)
                var total = 0L
                f.outputStream().use { os ->
                    os.write(ByteArray(44))   // placeholder, header rewritten on stop
                    while (running) {
                        val n = r.read(buf, 0, buf.size)
                        if (n > 0) { os.write(buf, 0, n); total += n }
                    }
                }
                writeWavHeader(f, total)
                Log.i(TAG, "audio: wrote ${f.name} bytes=$total")
            }
            Log.i(TAG, "audio: recording -> ${f.absolutePath}")
            JSONObject().put("ok", true).put("path", f.absolutePath)
                .put("sampleRate", RATE).put("channels", 1)
        } catch (e: Exception) {
            Log.e(TAG, "audio: start failed: ${e.message}")
            running = false
            JSONObject().put("ok", false).put("error", e.message ?: "start failed")
        }
    }

    fun stop(): JSONObject {
        if (!running) return JSONObject().put("ok", false).put("error", "not recording")
        running = false
        return try {
            rec?.let { try { it.stop() } catch (e: Exception) {}; it.release() }
            rec = null
            Thread.sleep(120)  // let the writer thread flush and fix the header
            val f = out
            JSONObject().put("ok", true)
                .put("path", f?.absolutePath ?: "")
                .put("bytes", f?.length() ?: 0)
                .put("sampleRate", RATE).put("channels", 1)
        } catch (e: Exception) {
            Log.e(TAG, "audio: stop failed: ${e.message}")
            JSONObject().put("ok", false).put("error", e.message ?: "stop failed")
        }
    }

    /** Standard 44-byte RIFF/WAVE header for PCM16 mono. */
    private fun writeWavHeader(f: File, dataLen: Long) {
        val byteRate = RATE * 2
        val totalLen = dataLen + 36
        RandomAccessFile(f, "rw").use { raf ->
            raf.seek(0)
            raf.write("RIFF".toByteArray())
            raf.write(le32(totalLen.toInt()))
            raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray())
            raf.write(le32(16))
            raf.write(le16(1))          // PCM
            raf.write(le16(1))          // mono
            raf.write(le32(RATE))
            raf.write(le32(byteRate))
            raf.write(le16(2))          // block align
            raf.write(le16(16))         // bits
            raf.write("data".toByteArray())
            raf.write(le32(dataLen.toInt()))
        }
    }

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()
    )

    private fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
}
