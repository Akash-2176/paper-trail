package com.coldboot.papertrail

import android.net.Uri
import org.json.JSONObject

/**
 * UPI deep-link parsing and construction (NPCI UPI Linking Specification).
 *
 * A merchant QR is a `upi://pay?...` URI. We are not a payment processor: we
 * read the payee's own QR, and later hand a URI back to whichever UPI app the
 * user already trusts. Nothing here authorises money.
 *
 * Parsing is deliberately lenient about EXTRA parameters and strict about the
 * ones that decide who gets paid. Merchant QRs in the wild carry bank-specific
 * fields (mode, orgid, sign, purpose...) that we must not discard - a later
 * dispute may need them - but must also not interpret.
 */
object UpiUri {

    /** The only scheme we accept. */
    const val SCHEME = "upi"

    /**
     * Parameters we model explicitly. Everything else is preserved verbatim in
     * `extras` rather than dropped.
     *
     * pa  payee address (VPA)      pn  payee name
     * am  amount                    cu  currency
     * mc  merchant category code    tr  transaction reference id
     * tn  transaction note          tid transaction id
     * mn  merchant name             url merchant url
     * mam minimum amount
     */
    private val KNOWN = setOf("pa", "pn", "am", "cu", "mc", "tr", "tn", "tid", "mn", "url", "mam")

    /**
     * A VPA is `user@handle`. We validate shape only - whether it can actually
     * receive money is the UPI app's business, not ours, and guessing would
     * mean rejecting valid handles we have never heard of.
     *
     * Deliberately permissive on the local part (banks allow dots, hyphens,
     * underscores) and strict that a single '@' with a non-empty alphabetic
     * handle exists.
     *
     * The local part allows ONE character. It was written as {2,} and that
     * rejected `a@bank`, which is a perfectly legal VPA - a validator that
     * refuses a real merchant's address blocks a payment the user is standing
     * in a shop trying to make, which is far worse than passing a malformed
     * one through to the UPI app that will reject it properly anyway.
     */
    private val VPA = Regex("^[A-Za-z0-9._-]{1,256}@[A-Za-z][A-Za-z0-9.-]{1,64}$")

    /** Amounts are decimal rupees, at most two places. Never negative or zero. */
    private val AMOUNT = Regex("^\\d{1,10}(\\.\\d{1,2})?$")

    /** Result of reading a scanned payload. */
    data class Parsed(
        val ok: Boolean,
        val error: String? = null,
        val vpa: String = "",
        val payeeName: String = "",
        val amount: Double? = null,
        val currency: String = "INR",
        val merchantCode: String = "",
        val refId: String = "",
        val note: String = "",
        val txnId: String = "",
        val url: String = "",
        /** Unmodelled parameters, kept so evidence is not silently lost. */
        val extras: Map<String, String> = emptyMap(),
        /** True when the QR fixed the amount and the user must not change it. */
        val amountLocked: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ok", ok)
            put("error", error ?: JSONObject.NULL)
            put("vpa", vpa)
            put("payeeName", payeeName)
            put("amount", amount ?: JSONObject.NULL)
            put("currency", currency)
            put("merchantCode", merchantCode)
            put("refId", refId)
            put("note", note)
            put("txnId", txnId)
            put("url", url)
            put("amountLocked", amountLocked)
            put("extras", JSONObject(extras as Map<*, *>))
        }
    }

    private fun fail(reason: String) = Parsed(ok = false, error = reason)

    /**
     * Parse a scanned string into a payment intent we can act on.
     *
     * Returns ok=false with a human-meaningful reason for anything that is not
     * a UPI payment QR - a website, a WiFi QR, a UPI *request* we do not
     * support - so the UI can say what is wrong instead of failing blankly.
     */
    fun parse(raw: String?): Parsed {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return fail("Empty QR code")

        val uri = try { Uri.parse(text) } catch (e: Throwable) { null }
            ?: return fail("Not a readable QR code")

        val scheme = uri.scheme?.lowercase()
        if (scheme != SCHEME) {
            // Say what it actually is; "invalid" alone tells the user nothing.
            val what = when (scheme) {
                "http", "https" -> "a web link"
                null -> "plain text"
                else -> "a $scheme code"
            }
            return fail("This is $what, not a UPI payment QR")
        }

        /* Only 'pay' moves money to a payee. 'collect'/'mandate' are requests
         * and recurring authorisations - different flows with different
         * consent semantics, and we deliberately do not handle them. */
        val host = (uri.host ?: uri.authority.orEmpty()).lowercase()
        if (host.isNotEmpty() && host != "pay") {
            return fail("Unsupported UPI action '$host' - only payment QRs work here")
        }

        val params = readParams(uri)

        val vpa = params["pa"]?.trim().orEmpty()
        if (vpa.isEmpty()) return fail("QR has no payee address")
        if (!VPA.matches(vpa)) return fail("Payee address is not a valid UPI ID")

        /* Amount is optional by design: a shop's static QR usually omits it and
         * the customer types what they owe. An amount that IS present must be
         * well-formed - a malformed one is a parse failure, never a silent 0. */
        val amountRaw = params["am"]?.trim().orEmpty()
        var amount: Double? = null
        if (amountRaw.isNotEmpty()) {
            if (!AMOUNT.matches(amountRaw)) return fail("QR has an unreadable amount")
            amount = amountRaw.toDoubleOrNull()
            if (amount == null || amount <= 0.0) return fail("QR has an invalid amount")
        }

        val currency = params["cu"]?.trim()?.uppercase()?.ifEmpty { "INR" } ?: "INR"

        return Parsed(
            ok = true,
            vpa = vpa,
            payeeName = (params["pn"] ?: params["mn"]).orEmpty().trim(),
            amount = amount,
            currency = currency,
            merchantCode = params["mc"]?.trim().orEmpty(),
            refId = params["tr"]?.trim().orEmpty(),
            note = params["tn"]?.trim().orEmpty(),
            txnId = params["tid"]?.trim().orEmpty(),
            url = params["url"]?.trim().orEmpty(),
            extras = params.filterKeys { it !in KNOWN },
            amountLocked = amount != null
        )
    }

    /**
     * Read query parameters tolerantly.
     *
     * Uri.getQueryParameter throws on some malformed merchant QRs (unescaped
     * '%' in a payee name is common on printed static QRs), and a crash in the
     * scanner is a far worse outcome than a missing optional field.
     */
    private fun readParams(uri: Uri): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val query = uri.encodedQuery ?: return out
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = pair.substring(0, eq).lowercase()
            val value = pair.substring(eq + 1)
            // Later duplicates do not override an earlier good value.
            if (out.containsKey(key)) continue
            out[key] = try { Uri.decode(value) } catch (e: Throwable) { value }
        }
        return out
    }

    /**
     * Build the URI handed to the user's UPI app.
     *
     * `refId` is OUR reference (tr). It is how a bank statement or the app's
     * response can later be tied back to a Paper Trail transaction, which is
     * the whole point of generating one. Paper Trail's internal id is NOT put
     * on the wire: `tr` has a defined meaning to the payee's bank and stuffing
     * an app-private identifier into it would violate the spec's semantics.
     *
     * We forward the payee's own `tr` when the QR supplied one - a dynamic
     * merchant QR uses it to identify the bill, and overwriting it can break
     * the merchant's own settlement.
     */
    fun buildPayUri(
        vpa: String,
        payeeName: String,
        amount: Double,
        currency: String,
        refId: String,
        note: String,
        merchantCode: String
    ): Uri {
        val b = Uri.Builder().scheme(SCHEME).authority("pay")
        b.appendQueryParameter("pa", vpa)
        if (payeeName.isNotBlank()) b.appendQueryParameter("pn", payeeName)
        if (merchantCode.isNotBlank()) b.appendQueryParameter("mc", merchantCode)
        if (refId.isNotBlank()) b.appendQueryParameter("tr", refId)
        if (note.isNotBlank()) b.appendQueryParameter("tn", note.take(50))
        // Two decimal places: some PSP apps reject "300.0" or bare integers.
        b.appendQueryParameter("am", String.format(java.util.Locale.US, "%.2f", amount))
        b.appendQueryParameter("cu", currency.ifBlank { "INR" })
        return b.build()
    }

    /**
     * A UPI-safe reference id: uppercase alphanumeric, <= 35 chars.
     *
     * Derived from Paper Trail's transaction id so the two are linkable, but
     * carrying no personal data and no internal structure a third party could
     * rely on.
     */
    fun refIdFor(txnId: String): String =
        ("PT" + txnId.filter { it.isLetterOrDigit() }.uppercase()).take(35)
}
