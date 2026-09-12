package com.coldboot.papertrail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service - REQUIRED, not defensive.
 *
 * SPIKE-FINDINGS §3: backgrounded, the OS trimmed RSS 210MB -> 137MB within 90s and
 * set oom_score_adj=700. It survived only because the device was idle with RAM free.
 * With a VLM resident on a venue-loaded device, that is exactly the process that gets
 * reaped. OriginOS is expected to be at least as aggressive.
 *
 * Battery-optimisation exemption must ALSO be granted on the handset before any long run.
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "PTLAB"
        private const val CHANNEL = "papertrail.keepalive"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val n: Notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
            Log.i(TAG, "svc: foreground started")
        } catch (e: Exception) {
            Log.e(TAG, "svc: startForeground failed: " + e.message)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Paper Trail", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle("Paper Trail")
            .setContentText("Watching for transactions - on device")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .build()
    }
}
