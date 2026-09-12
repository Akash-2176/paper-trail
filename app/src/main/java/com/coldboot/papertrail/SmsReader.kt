package com.coldboot.papertrail

import android.content.Context
import android.provider.Telephony
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the SMS inbox via ContentResolver. POLLING ONLY - no BroadcastReceiver (ADR-001).
 *
 * ADR-003: parse + redact NATIVE-SIDE. Raw bodies never cross the bridge.
 * Only structured, redacted objects are emitted.
 */
object SmsReader {

    private const val TAG = "PTLAB"

    /** Issuer codes recognised as the MIDDLE token of a DLT header. */
    private val ISSUERS = mapOf(
        "HDFCBK" to "HDFC", "HDFCBN" to "HDFC",
        "ICICIB" to "ICICI", "ICICIT" to "ICICI",
        "AXISBK" to "AXIS", "AXISB" to "AXIS",
        "SBIINB" to "SBI", "SBICRD" to "SBI", "ATMSBI" to "SBI", "SBIUPI" to "SBI",
        "KOTAKB" to "KOTAK", "KMBL" to "KOTAK",
        "CANBNK" to "CANARA",
        "KVBANK" to "KVB", "KVB" to "KVB",
        "INDBNK" to "INDIANBANK",
        "IOBCHN" to "IOB",
        "PYTMBK" to "PAYTM", "PAYTMB" to "PAYTM",
        "AMZNIN" to "AMAZONPAY",
        "GPAYIN" to "GPAY",
        "PHONPE" to "PHONEPE"
    )

    /**
     * ADR-010 rule 2: body-signature fallback for bare-number (forwarded) senders.
     * A trailing signature such as "-KVB" is the only issuer marker on a forward.
     */
    private val BODY_SIGNATURES: List<Pair<Regex, String>> = listOf(
        Regex("-\\s*HDFC\\b", RegexOption.IGNORE_CASE) to "HDFC",
        Regex("-\\s*ICICI\\b", RegexOption.IGNORE_CASE) to "ICICI",
        Regex("-\\s*Axis\\b", RegexOption.IGNORE_CASE) to "AXIS",
        Regex("-\\s*SBI\\b", RegexOption.IGNORE_CASE) to "SBI",
        Regex("-\\s*KVB\\b", RegexOption.IGNORE_CASE) to "KVB",
        Regex("-\\s*Kotak\\b", RegexOption.IGNORE_CASE) to "KOTAK",
        Regex("-\\s*Canara\\b", RegexOption.IGNORE_CASE) to "CANARA",
        Regex("\\bHDFC Bank\\b", RegexOption.IGNORE_CASE) to "HDFC",
        Regex("\\bICICI Bank\\b", RegexOption.IGNORE_CASE) to "ICICI",
        Regex("\\bAxis Bank\\b", RegexOption.IGNORE_CASE) to "AXIS",
        Regex("\\bState Bank of India\\b", RegexOption.IGNORE_CASE) to "SBI",
        Regex("\\bKarur Vysya\\b", RegexOption.IGNORE_CASE) to "KVB"
    )

    /**
     * ADR-010 rule 1: match the MIDDLE token only, case-insensitive.
     * Real headers: AD-HDFCBK-S / VM-HDFCBK-S / JD-HDFCBK-S.
     * The prefix encodes the telco route, not the sender. The -S DLT suffix is noise.
     * Tolerates 3-token, 2-token and bare forms. Returns null for bare numbers so the
     * body-signature fallback runs.
     */
    fun issuerFromSender(address: String?): String? {
        if (address.isNullOrBlank()) return null
        val a = address.trim().uppercase()
        val digitsOnly = a.removePrefix("+").all { it.isDigit() }
        if (digitsOnly) return null
        val parts = a.split("-").filter { it.isNotBlank() }
        val middle = when {
            parts.size >= 3 -> parts[1]
            parts.size == 2 -> parts[1]
            parts.size == 1 -> parts[0]
            else -> return null
        }
        ISSUERS[middle]?.let { return it }
        return if (middle.length in 3..10 && middle.all { it.isLetterOrDigit() }) middle else null
    }

    fun issuerFromBody(body: String): String? =
        BODY_SIGNATURES.firstOrNull { it.first.containsMatchIn(body) }?.second

    // --- field extraction -------------------------------------------------

    private val AMOUNT = Regex(
        "(?:INR|Rs\\.?|RS\\.?|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
        RegexOption.IGNORE_CASE
    )
    private val DEBIT = Regex(
        "\\b(debited|debit|spent|paid|withdrawn|purchase)\\b", RegexOption.IGNORE_CASE
    )
    private val CREDIT = Regex(
        "\\b(credited|credit|received|deposited|refund)\\b", RegexOption.IGNORE_CASE
    )
    private val ACCT = Regex(
        "(?:a/c|acct|account|card)\\s*(?:no\\.?)?\\s*[:# ]?\\s*[xX*]*([0-9]{3,4})\\b",
        RegexOption.IGNORE_CASE
    )
    /**
     * Real KVB refs look like "info :UPI/P2A/610254432802" or "info :P2A/895909491666".
     * Also tolerates the classic "Ref 123456" / "RRN: 123456" forms.
     */
    private val UPI_REF = Regex(
        "(?:\\b(?:UPI|Ref|RRN)\\b[:/ ]*)?\\bP2[AM]/([0-9]{6,})|\\b(?:UPI|Ref|RRN)[:. ]*([0-9]{6,})",
        RegexOption.IGNORE_CASE
    )

    /**
     * Counterparty. Real forms observed on device:
     *   "is credited Rs. 3000.00 from GOUTHAM M on 12-Apr-2026"
     *   "is debited Rs. 166.00 on 12-Apr-2026 to SWIGGY LIMITED info :P2A/..."
     * So both "from X" and "to X" must be handled, and the trailing stop words differ.
     */
    private val MERCHANT = Regex(
        "\\b(?:to|from|at|towards|VPA)\\s+([A-Za-z0-9@.\\-_*&' ]{2,40}?)" +
            "(?=\\s+(?:on|info|Avl|Not|UPI|Ref)\\b|[.,]|$)",
        RegexOption.IGNORE_CASE
    )

    /** Honorifics that are not part of the name (ADR-009 masking). */
    private val HONORIFIC = Regex("^(?:Mr|Mrs|Ms|Dr|Shri|Smt)\\.?\\s+", RegexOption.IGNORE_CASE)

    /**
     * SPIKE-FINDINGS §1 says bodies are not truncated - but a forwarded row can carry
     * SEVERAL messages concatenated in one BODY. Split on blank lines so each becomes
     * its own transaction rather than silently parsing only the first.
     */
    private val SEGMENT = Regex("\\n\\s*\\n+")

    private fun segments(body: String): List<String> =
        SEGMENT.split(body).map { it.trim() }.filter { it.isNotEmpty() }

    /** ADR-009: P2P vs merchant classified at parse time. */
    private val P2P_HINT = Regex("\\b(?:UPI|VPA|transfer|sent to)\\b", RegexOption.IGNORE_CASE)

    private fun parseAmount(body: String): Double? =
        AMOUNT.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

    /**
     * CREDIT IS CHECKED FIRST, deliberately. Real KVB credit messages contain the
     * string "Not You? call 1800..." and other debit-ish noise, so a debit-first
     * check mislabels a credit as a debit - observed on device, ₹3000 credit read
     * as a debit. Direction is a hard gate in reconciliation (ARCHITECTURE §3.2),
     * so getting this backwards corrupts matching.
     */
    private fun direction(body: String): String = when {
        CREDIT.containsMatchIn(body) -> "credit"
        DEBIT.containsMatchIn(body) -> "debit"
        else -> "unknown"
    }

    private fun ref(body: String): String? {
        val m = UPI_REF.find(body) ?: return null
        return m.groupValues[1].ifBlank { m.groupValues[2] }.ifBlank { null }
    }

    /** Account number masked AT SOURCE - only the last 4 ever leave this class. */
    private fun acctLast4(body: String): String? = ACCT.find(body)?.groupValues?.get(1)

    private fun merchant(body: String): String? =
        MERCHANT.find(body)?.groupValues?.get(1)
            ?.replace(HONORIFIC, "")
            ?.trim()?.trimEnd('.', ',')?.take(40)?.ifBlank { null }

    /** Business suffixes mean it is a merchant even when the name looks person-shaped. */
    private val BUSINESS_WORD = Regex(
        "\\b(LIMITED|LTD|PVT|PRIVATE|INC|LLP|CORP|COMPANY|STORES?|SERVICES?|TECHNOLOGIES|" +
            "SOLUTIONS|ENTERPRISES|TRADERS|AGENCY|AGENCIES|FARM|MART|SUPERMARKET|HOTEL|" +
            "RESTAURANT|CAFE|MEDICALS?|PHARMACY|FOODS?|PAY|BANK|GOOGLE|AMAZON|SWIGGY|ZOMATO)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * ADR-009: classify P2P vs merchant at parse time.
     *
     * Real P2P names arrive ALL-CAPS ("GOUTHAM M", "Mr GANGESHWAR SUBRAMANIAM"), so a
     * TitleCase-only test - which is what the first cut used - never fires and every
     * person is mislabelled a merchant. Test on word shape plus a business-word veto.
     */
    private fun isP2P(body: String, merchant: String?): Boolean {
        if (merchant == null) return false
        if (merchant.contains("@")) return true          // VPA is always a person-addressable handle
        if (merchant.contains("*")) return false         // PAYTM*38291 style = merchant
        if (BUSINESS_WORD.containsMatchIn(merchant)) return false
        val words = merchant.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size !in 1..4) return false
        // Alphabetic words only (initials allowed). Digits imply a merchant code.
        val nameShaped = words.all { w -> w.all { ch -> ch.isLetter() || ch == '.' } }
        return nameShaped && P2P_HINT.containsMatchIn(body)
    }

    /**
     * ADR-009: P2P names are stored on-device but MASKED for display to first name +
     * initial, because demos are shown on projectors in public rooms.
     */
    private fun maskP2P(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return name
        // Names arrive ALL-CAPS on real messages; normalise so the projector shows
        // "Goutham M." rather than "GOUTHAM M."
        val first = parts[0].lowercase().replaceFirstChar { it.uppercaseChar() }
        val initial = parts.getOrNull(1)?.firstOrNull { it.isLetter() }?.uppercaseChar()
        return if (initial != null) first + " " + initial + "." else first
    }

    /**
     * Query the inbox and emit STRUCTURED, REDACTED objects only.
     * The raw body is never placed in the returned JSON (ADR-003).
     */
    fun query(ctx: Context, limit: Int = 200, sinceMillis: Long = 0L): JSONArray {
        val out = JSONArray()
        val cols = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )
        val sel = if (sinceMillis > 0) Telephony.Sms.DATE + " > ?" else null
        val args = if (sinceMillis > 0) arrayOf(sinceMillis.toString()) else null

        try {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, cols, sel, args,
                Telephony.Sms.DATE + " DESC LIMIT " + limit
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)

                while (c.moveToNext()) {
                    val addr = c.getString(iAddr)
                    val rawBody = c.getString(iBody) ?: continue
                    val rowId = c.getLong(iId)
                    val rowTs = c.getLong(iDate)
                    val routed = issuerFromSender(addr)

                    // A forwarded row can hold several messages concatenated. Emit one
                    // transaction per segment instead of only the first.
                    val segs = segments(rawBody)
                    segs.forEachIndexed { idx, seg ->
                        val amount = parseAmount(seg) ?: return@forEachIndexed
                        val issuer = routed ?: issuerFromBody(seg) ?: issuerFromBody(rawBody)
                        val m = merchant(seg)
                        val p2p = isP2P(seg, m)

                        out.put(JSONObject().apply {
                            // Segment-qualified id so multiple txns from one row stay distinct.
                            put("id", if (segs.size > 1) "$rowId.$idx" else rowId.toString())
                            put("smsId", rowId)
                            put("ts", rowTs)
                            put("issuer", issuer ?: JSONObject.NULL)
                            put("amount", amount)
                            put("direction", direction(seg))
                            put("acctLast4", acctLast4(seg) ?: JSONObject.NULL)
                            put("ref", ref(seg) ?: JSONObject.NULL)
                            put("kind", if (p2p) "p2p" else "merchant")
                            put(
                                "counterparty", when {
                                    m == null -> JSONObject.NULL
                                    p2p -> maskP2P(m)
                                    else -> m
                                }
                            )
                            put("senderRouted", routed != null)
                            // raw body and full account number deliberately absent (ADR-003)
                        })
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "sms: READ_SMS not granted")
        } catch (e: Exception) {
            Log.e(TAG, "sms: query failed: " + e.message)
        }
        Log.i(TAG, "sms: emitted " + out.length() + " structured rows")
        return out
    }
}
