package com.coldboot.papertrail

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Launches the user's own UPI app and interprets what it hands back.
 *
 * Paper Trail never authorises a payment. It builds a standards-compliant
 * `upi://pay` intent and gets out of the way; the installed app collects the
 * PIN and talks to NPCI. We only care which app ran and what it reported.
 *
 * DISCOVERY IS GENERIC. Apps are found by querying the system for handlers of
 * the upi://pay scheme, never by a hard-coded package list - a fixed list ages
 * badly, misses regional and bank-issued apps, and would make the feature
 * GPay-specific in all but name.
 */
object UpiIntentLauncher {

    private const val TAG = "PTLAB"

    /** Request code for startActivityForResult. */
    const val REQ_PAY = 0x5091

    /**
     * UPI apps installed on this device, as JSON for the UI.
     *
     * Android 11+ requires a <queries> declaration in the manifest for this to
     * return anything; without it the list comes back empty and the user is
     * told no UPI app is installed, which would be a lie.
     */
    fun availableApps(ctx: Context): JSONArray {
        val arr = JSONArray()
        val pm = ctx.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay"))
        for (ri in resolve(pm, probe)) {
            val pkg = ri.activityInfo?.packageName ?: continue
            arr.put(JSONObject().apply {
                put("packageName", pkg)
                put("label", ri.loadLabel(pm)?.toString() ?: pkg)
            })
        }
        return arr
    }

    private fun resolve(pm: PackageManager, intent: Intent): List<ResolveInfo> = try {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    } catch (e: Throwable) {
        Log.e(TAG, "upi: resolve failed: ${e.message}")
        emptyList()
    }

    fun hasAnyUpiApp(ctx: Context): Boolean = availableApps(ctx).length() > 0

    /**
     * Launch a payment.
     *
     * @param packageName when set, go straight to that app; otherwise let
     *        Android show its own chooser, which is the mechanism users already
     *        know and which respects their default.
     *
     * Returns null on success, or a human-readable reason it could not start.
     */
    fun launch(act: Activity, txn: UpiTransaction, packageName: String?): String? {
        val uri = UpiUri.buildPayUri(
            vpa = txn.vpa,
            payeeName = txn.payeeName,
            amount = txn.amount,
            currency = txn.currency,
            refId = txn.refId,
            note = txn.note,
            merchantCode = txn.merchantCode
        )

        /* Never log the full URI. It carries the payee's VPA and the amount;
         * logcat is readable by anyone with the device plugged in. */
        Log.i(TAG, "upi: launching txn=${txn.id} app=${packageName ?: "chooser"}")

        val base = Intent(Intent.ACTION_VIEW, uri)
        val intent = if (packageName.isNullOrBlank()) {
            // A chooser cannot be started for-result directly on all versions;
            // createChooser wraps it so the result still comes back to us.
            if (resolve(act.packageManager, base).isEmpty()) {
                return "No UPI app is installed on this device"
            }
            Intent.createChooser(base, "Pay with")
        } else {
            base.setPackage(packageName)
            if (resolve(act.packageManager, base).isEmpty()) {
                return "That UPI app is no longer available"
            }
            base
        }

        return try {
            act.startActivityForResult(intent, REQ_PAY)
            null
        } catch (e: android.content.ActivityNotFoundException) {
            "No UPI app could handle the payment"
        } catch (e: Throwable) {
            Log.e(TAG, "upi: launch failed: ${e.message}")
            "Could not open a UPI app"
        }
    }

    /**
     * Interpret what the UPI app returned.
     *
     * The response is a `Status=SUCCESS&txnId=...&responseCode=...` string,
     * usually in the "response" extra. There is no guarantee of it: several
     * apps return RESULT_CANCELED with no data even after a successful
     * payment, because the user swiped back rather than tapping "Done".
     *
     * So the mapping is deliberately conservative. Only an explicit success
     * token produces SUBMITTED, and even that is not VERIFIED - that requires
     * bank evidence. Everything ambiguous becomes UNKNOWN rather than being
     * guessed either way.
     */
    fun interpret(resultCode: Int, data: Intent?): Response {
        val raw = extractResponse(data)
        if (raw.isNullOrBlank()) {
            /* No payload at all. RESULT_OK with nothing is still not evidence
             * of payment, and RESULT_CANCELED here genuinely is ambiguous: the
             * user may have paid and swiped back. Neither may claim success. */
            return Response(
                state = if (resultCode == Activity.RESULT_CANCELED) UpiState.UNKNOWN
                        else UpiState.UNKNOWN,
                status = null,
                txnId = null,
                refNumber = null,
                responseCode = null,
                note = "The payment app returned without a result"
            )
        }

        val fields = parseResponse(raw)
        val status = fields["status"]?.trim()

        val state = when {
            status.equals("SUCCESS", true) -> UpiState.SUBMITTED
            status.equals("FAILURE", true) || status.equals("FAILED", true) -> UpiState.FAILED
            status.equals("SUBMITTED", true) -> UpiState.PENDING
            status.equals("PENDING", true) -> UpiState.PENDING
            // Some apps return the user's own cancellation as a status string.
            status?.contains("CANCEL", true) == true -> UpiState.CANCELLED
            else -> UpiState.UNKNOWN
        }

        return Response(
            state = state,
            status = status,
            txnId = fields["txnid"] ?: fields["txnref"],
            refNumber = fields["approvalrefno"] ?: fields["rrn"],
            responseCode = fields["responsecode"],
            note = null
        )
    }

    /** Response payload, whichever extra the app chose to use. */
    private fun extractResponse(data: Intent?): String? {
        val d = data ?: return null
        // "response" is the documented key; the others are observed in the wild.
        for (key in listOf("response", "Status", "status", "result")) {
            val v = try { d.getStringExtra(key) } catch (e: Throwable) { null }
            if (!v.isNullOrBlank()) return v
        }
        // A few apps put it on the data URI instead of an extra.
        return d.dataString
    }

    /** `Status=SUCCESS&txnId=...` -> lowercase-keyed map. */
    private fun parseResponse(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in raw.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            out[pair.substring(0, eq).trim().lowercase()] = pair.substring(eq + 1).trim()
        }
        // A bare "SUCCESS" with no key=value structure still means something.
        if (out.isEmpty() && raw.isNotBlank()) out["status"] = raw.trim()
        return out
    }

    data class Response(
        val state: UpiState,
        val status: String?,
        val txnId: String?,
        val refNumber: String?,
        val responseCode: String?,
        /** Explanation for the user when the app told us nothing useful. */
        val note: String?
    )
}
