package com.coldboot.papertrail

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * ContentObserver on content://sms. NOT a BroadcastReceiver, and it does NOT need
 * RECEIVE_SMS - READ_SMS is sufficient to observe the provider.
 *
 * This does not violate ADR-001. SMS is still not the trigger for a capture flow:
 * the user's action is what creates a PendingEntry. The observer only refreshes the
 * inbox read and lets the product layer re-attempt reconciliation against pending
 * entries that already exist.
 *
 * Several rows can land at once (a forward dump, or a burst after connectivity
 * returns), so changes are debounced before a single re-read is issued.
 */
class SmsWatcher(
    private val ctx: Context,
    private val onChanged: (String) -> Unit
) {

    companion object {
        private const val TAG = "PTLAB"
        private const val DEBOUNCE_MS = 500L
        private val SMS_URI: Uri = Uri.parse("content://sms")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val pending = Runnable {
        try {
            val json = SmsReader.query(ctx).toString()
            Log.i(TAG, "watch: re-read fired")
            onChanged(json)
        } catch (e: Exception) {
            Log.e(TAG, "watch: re-read failed: " + e.message)
        }
    }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = onChange(selfChange, null)

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // Collapse a burst into one re-read.
            handler.removeCallbacks(pending)
            handler.postDelayed(pending, DEBOUNCE_MS)
        }
    }

    /**
     * Drive the exact debounce + re-read + push path the observer uses, without a
     * provider write. Android 16 refuses adb writes to content://sms with no default
     * SMS app, so this is the only way to exercise the path from the host.
     */
    fun simulateChange() {
        observer.onChange(false, null)
    }

    fun register() {
        if (registered) return
        if (!Perms.hasSms(ctx)) {
            Log.w(TAG, "watch: READ_SMS not granted, observer not registered")
            return
        }
        try {
            ctx.contentResolver.registerContentObserver(SMS_URI, true, observer)
            registered = true
            Log.i(TAG, "watch: observer registered on content://sms")
        } catch (e: Exception) {
            Log.e(TAG, "watch: register failed: " + e.message)
        }
    }

    fun unregister() {
        if (!registered) return
        handler.removeCallbacks(pending)
        try {
            ctx.contentResolver.unregisterContentObserver(observer)
        } catch (e: Exception) {
            Log.e(TAG, "watch: unregister failed: " + e.message)
        }
        registered = false
        Log.i(TAG, "watch: observer unregistered")
    }
}
