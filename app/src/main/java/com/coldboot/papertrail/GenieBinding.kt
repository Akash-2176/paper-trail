package com.coldboot.papertrail

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * GenieX runtime binding - PRESENT BUT NOT WIRED (deliberate, Green Block 1 scope).
 *
 * This class only reports what the device looks like to the model layer and whether
 * staged weights are visible. No SDK dependency is declared yet, so nothing here can
 * break the shell build. ML owns wiring this in the 01:00-06:30 Green block.
 *
 * ADR-006: weights are pre-staged and loaded with HubSource.LOCALFS. Never downloaded.
 * ARCHITECTURE §4: chipset SM8850, runtime_id qairt (fallback llama_cpp), GGUF Q4_0.
 */
class GenieBinding(private val ctx: Context) {

    companion object {
        private const val TAG = "PTLAB"
        /** Weights land here via adb push. */
        const val WEIGHTS_DIR = "/data/local/tmp/models"
    }

    fun weightsDir(): File = File(WEIGHTS_DIR)

    fun status(): JSONObject {
        val dir = weightsDir()
        val files = dir.listFiles()?.map { it.name } ?: emptyList()
        val soc = socModel()

        return JSONObject().apply {
            put("wired", false)
            put("note", "binding present, SDK not linked yet - ML owns this in Green 01:00")
            put("socModel", soc)
            put("expectedChipset", "SM8850")
            put("chipsetMatch", soc.equals("SM8850", ignoreCase = true))
            put("runtimeIdTarget", "qairt")
            put("runtimeIdFallback", "llama_cpp")
            put("quantRequired", "Q4_0")
            put("hubSource", "LOCALFS")
            put("weightsDir", WEIGHTS_DIR)
            put("weightsDirExists", dir.isDirectory)
            put("weightsVisible", files.size)
            put("weights", files.joinToString(","))
        }.also {
            Log.i(TAG, "genie: status soc=" + soc + " weights=" + files.size)
        }
    }
}

/** Build.SOC_MODEL is API 31+; minSdk is 26 so guard it. */
private fun socModel(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "unknown"
