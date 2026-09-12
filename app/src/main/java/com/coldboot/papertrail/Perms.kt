package com.coldboot.papertrail

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * SPIKE-FINDINGS §4: on the spike, READ_SMS came back granted via
 * RESTRICTION_INSTALLER_EXEMPT - a side-load exemption a store install would not get.
 * The runtime prompt flow was therefore never actually exercised. This helper exists
 * so it CAN be, deliberately, before the demo.
 *
 * To force the real flow:
 *   adb shell pm revoke com.coldboot.papertrail android.permission.READ_SMS
 */
object Perms {

    const val REQ = 7001

    fun hasSms(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun needed(): Array<String> {
        val list = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    fun requestSms(act: Activity) {
        ActivityCompat.requestPermissions(act, needed(), REQ)
    }
}
