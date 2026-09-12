package com.coldboot.papertrail

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The state of one Paper Trail payment attempt.
 *
 * The distinction this type exists to enforce: RECEIVING A RESPONSE IS NOT
 * VERIFICATION. A UPI app returning Status=SUCCESS tells us what that app
 * believes. Only a bank SMS naming the same amount tells us money actually
 * left the account. Those are two different states and the UI must never
 * conflate them - a payment app can return success on a transaction the bank
 * later reverses.
 */
enum class UpiState {
    /** Transaction created from a scanned QR; nothing has been launched. */
    CREATED,

    /** A UPI app was launched. We are waiting for it to hand control back. */
    PAYMENT_INITIATED,

    /** The app returned and said the payment succeeded. NOT yet verified. */
    SUBMITTED,

    /** Corroborated by independent evidence (bank SMS). Money definitely moved. */
    VERIFIED,

    /** The app reported the payment failed. */
    FAILED,

    /** The user backed out without paying. Not a failure - a choice. */
    CANCELLED,

    /** Bank/PSP is still processing. Genuinely unresolved. */
    PENDING,

    /**
     * The app returned but told us nothing usable, or we never learned the
     * outcome. This is the honest state for "we don't know", and it is why
     * we never assume success on return.
     */
    UNKNOWN;

    /** Terminal for our purposes: no further response is expected. */
    val isSettled: Boolean
        get() = this == VERIFIED || this == FAILED || this == CANCELLED

    companion object {
        fun from(name: String?): UpiState =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * One payment, from QR to evidence.
 *
 * Persisted natively rather than in the WebView because the UPI app takes the
 * foreground: MainActivity can be recreated, and the WebView is reloaded on
 * resume. Anything held only in JS would be gone by the time the user pays.
 */
data class UpiTransaction(
    /** Paper Trail's own id. Never sent to any UPI app. */
    val id: String,
    val createdAt: Long,
    val vpa: String,
    val payeeName: String,
    val amount: Double,
    val currency: String,
    val merchantCode: String,
    /** Reference we put in the intent's `tr`, for later correlation. */
    val refId: String,
    val note: String,
    /** How the payee details were obtained: "qr" or "manual". */
    val source: String,
    var state: UpiState,
    var initiatedAt: Long? = null,
    var completedAt: Long? = null,
    /** Package name of the UPI app that handled it, when known. */
    var payerApp: String? = null,
    /** The UPI app's own transaction id (txnId), when it returns one. */
    var upiTxnId: String? = null,
    /** Bank reference number (RRN) - the field a bank SMS also carries. */
    var upiRefNumber: String? = null,
    /** Raw Status/responseCode the app returned, for the audit trail. */
    var responseCode: String? = null,
    var responseStatus: String? = null,
    /** Free-text context the user attached afterwards. */
    var contextText: String? = null,
    /** "voice" or "text" - how the context arrived. */
    var contextSource: String? = null,
    /** LLM-derived structure over contextText. Never authoritative for money. */
    var purpose: String? = null,
    var contextNote: String? = null,
    /** Extra QR parameters, retained as evidence. */
    val extras: Map<String, String> = emptyMap()
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("createdAt", createdAt)
        put("vpa", vpa)
        put("payeeName", payeeName)
        put("amount", amount)
        put("currency", currency)
        put("merchantCode", merchantCode)
        put("refId", refId)
        put("note", note)
        put("source", source)
        put("state", state.name)
        put("initiatedAt", initiatedAt ?: JSONObject.NULL)
        put("completedAt", completedAt ?: JSONObject.NULL)
        put("payerApp", payerApp ?: JSONObject.NULL)
        put("upiTxnId", upiTxnId ?: JSONObject.NULL)
        put("upiRefNumber", upiRefNumber ?: JSONObject.NULL)
        put("responseCode", responseCode ?: JSONObject.NULL)
        put("responseStatus", responseStatus ?: JSONObject.NULL)
        put("contextText", contextText ?: JSONObject.NULL)
        put("contextSource", contextSource ?: JSONObject.NULL)
        put("purpose", purpose ?: JSONObject.NULL)
        put("contextNote", contextNote ?: JSONObject.NULL)
        put("extras", JSONObject(extras as Map<*, *>))
    }

    companion object {
        private const val TAG = "PTLAB"

        fun fromQr(p: UpiUri.Parsed, amount: Double, note: String): UpiTransaction {
            val id = UUID.randomUUID().toString().replace("-", "").take(16)
            return UpiTransaction(
                id = id,
                createdAt = System.currentTimeMillis(),
                vpa = p.vpa,
                payeeName = p.payeeName,
                amount = amount,
                currency = p.currency.ifBlank { "INR" },
                merchantCode = p.merchantCode,
                // The payee's own reference wins: a dynamic merchant QR uses it
                // to identify the bill being settled.
                refId = p.refId.ifBlank { UpiUri.refIdFor(id) },
                note = note.ifBlank { p.note },
                source = "qr",
                state = UpiState.CREATED,
                extras = p.extras
            )
        }

        fun fromJson(o: JSONObject): UpiTransaction? = try {
            val extras = LinkedHashMap<String, String>()
            o.optJSONObject("extras")?.let { ex ->
                ex.keys().forEach { k -> extras[k] = ex.optString(k, "") }
            }
            UpiTransaction(
                id = o.getString("id"),
                createdAt = o.optLong("createdAt"),
                vpa = o.optString("vpa"),
                payeeName = o.optString("payeeName"),
                amount = o.optDouble("amount", 0.0),
                currency = o.optString("currency", "INR"),
                merchantCode = o.optString("merchantCode"),
                refId = o.optString("refId"),
                note = o.optString("note"),
                source = o.optString("source", "qr"),
                state = UpiState.from(o.optString("state")),
                initiatedAt = o.optLong("initiatedAt").takeIf { it > 0 },
                completedAt = o.optLong("completedAt").takeIf { it > 0 },
                payerApp = o.optString("payerApp").ifBlank { null },
                upiTxnId = o.optString("upiTxnId").ifBlank { null },
                upiRefNumber = o.optString("upiRefNumber").ifBlank { null },
                responseCode = o.optString("responseCode").ifBlank { null },
                responseStatus = o.optString("responseStatus").ifBlank { null },
                contextText = o.optString("contextText").ifBlank { null },
                contextSource = o.optString("contextSource").ifBlank { null },
                purpose = o.optString("purpose").ifBlank { null },
                contextNote = o.optString("contextNote").ifBlank { null },
                extras = extras
            )
        } catch (e: Throwable) {
            Log.w(TAG, "upi: dropping unreadable transaction: ${e.message}")
            null
        }
    }
}

/**
 * Durable store for UPI transactions.
 *
 * Separate file from the JS ledger (LocalStore) on purpose: this one is written
 * from the native side at the moment the intent launches, when the WebView may
 * be about to be reloaded or the process killed behind the UPI app. The JS
 * ledger remains the product's record; this is the crash-safe spine underneath
 * it, and the two are joined by transaction id.
 */
object UpiStore {

    private const val TAG = "PTLAB"
    private const val FILE = "upi-transactions.json"

    /** Bounded like the rest of the app's storage - growth must stay flat. */
    private const val MAX = 100

    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun all(ctx: Context): List<UpiTransaction> = synchronized(lock) { read(ctx) }

    private fun read(ctx: Context): MutableList<UpiTransaction> {
        val f = file(ctx)
        if (!f.isFile) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<UpiTransaction>()
            for (i in 0 until arr.length()) {
                UpiTransaction.fromJson(arr.getJSONObject(i))?.let { out.add(it) }
            }
            out
        } catch (e: Throwable) {
            Log.e(TAG, "upi: store unreadable, starting empty: ${e.message}")
            mutableListOf()
        }
    }

    private fun write(ctx: Context, list: List<UpiTransaction>): Boolean = try {
        val trimmed = list.sortedBy { it.createdAt }.takeLast(MAX)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it.toJson()) }
        /* Write to a temp file and rename. A half-written store is worse than
         * an old one: the process can be killed at any point while the UPI app
         * is in front. */
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
        true
    } catch (e: Throwable) {
        Log.e(TAG, "upi: save failed: ${e.message}")
        false
    }

    fun put(ctx: Context, txn: UpiTransaction): Boolean = synchronized(lock) {
        val list = read(ctx)
        val i = list.indexOfFirst { it.id == txn.id }
        if (i >= 0) list[i] = txn else list.add(txn)
        write(ctx, list)
    }

    fun get(ctx: Context, id: String): UpiTransaction? =
        synchronized(lock) { read(ctx).firstOrNull { it.id == id } }

    /**
     * The transaction we are currently waiting on a UPI app for.
     *
     * There can only be one in flight - the user is in a payment app - so the
     * most recently initiated unsettled transaction is unambiguous. Used to
     * recover after process death, when the pending id was lost with the
     * Activity.
     */
    /**
     * How long a launched payment stays claimable by an incoming result.
     *
     * A UPI authorisation is a minutes-long interaction, not an hours-long
     * one. The bound matters because attributing a result to the WRONG
     * transaction is a serious error: without it, a payment abandoned on
     * Monday would still be the newest "in flight" record on Tuesday and
     * would silently absorb Tuesday's result, marking the wrong payee paid.
     */
    private const val IN_FLIGHT_WINDOW_MS = 30 * 60 * 1000L

    fun inFlight(ctx: Context): UpiTransaction? = synchronized(lock) {
        val now = System.currentTimeMillis()
        read(ctx)
            .filter { it.state == UpiState.PAYMENT_INITIATED }
            .filter { now - (it.initiatedAt ?: it.createdAt) <= IN_FLIGHT_WINDOW_MS }
            .maxByOrNull { it.initiatedAt ?: it.createdAt }
    }

    /**
     * Retire launched payments we never heard back about.
     *
     * Called at launch. A transaction stuck in PAYMENT_INITIATED means the
     * user left the UPI app by a route that never returned a result - the
     * outcome is genuinely unknown, and saying so is the honest record. It is
     * NOT failed: the money may well have moved, and a bank SMS can still
     * reconcile it later.
     */
    fun expireStale(ctx: Context): Int = synchronized(lock) {
        val now = System.currentTimeMillis()
        val list = read(ctx)
        var n = 0
        list.forEach {
            if (it.state == UpiState.PAYMENT_INITIATED &&
                now - (it.initiatedAt ?: it.createdAt) > IN_FLIGHT_WINDOW_MS
            ) {
                it.state = UpiState.UNKNOWN
                it.completedAt = now
                n++
            }
        }
        if (n > 0) {
            write(ctx, list)
            Log.i(TAG, "upi: $n abandoned payment(s) marked unknown")
        }
        n
    }

    fun update(ctx: Context, id: String, edit: (UpiTransaction) -> Unit): UpiTransaction? =
        synchronized(lock) {
            val list = read(ctx)
            val txn = list.firstOrNull { it.id == id } ?: return null
            edit(txn)
            write(ctx, list)
            txn
        }

    fun clear(ctx: Context): Boolean = synchronized(lock) {
        try { file(ctx).delete(); true } catch (e: Throwable) { false }
    }
}
