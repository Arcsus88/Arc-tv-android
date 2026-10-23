package com.arcsus.arcdisney.admin

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * The device-admin receiver `dpm set-device-owner` points at (RELEASING.md).
 * Being device owner is what lets updates install with nobody tapping
 * anything, and lets the app lock the device to itself.
 */
class AdminReceiver : DeviceAdminReceiver()

/**
 * Kiosk mode, when the app is the device owner: it becomes the home screen,
 * pins itself (Android's lock task mode, so there's no Home, Recents or
 * notification shade to leave by), and gives itself what the Wi-Fi screen
 * needs. Without device owner it does nothing and the app behaves normally.
 */
object Kiosk {

    fun isDeviceOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true

    /** From MainActivity.onCreate: the policies, each time (they're cheap and idempotent). */
    fun setUp(activity: Activity) {
        if (!isDeviceOwner(activity)) return
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(activity, AdminReceiver::class.java)
        val pkg = activity.packageName

        dpm.setLockTaskPackages(admin, arrayOf(pkg))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Keep the power menu (restart, power off) and the status bar's clock and battery.
            dpm.setLockTaskFeatures(
                admin,
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO,
            )
        }

        // The home screen, so the app is what the device boots into.
        val home = ComponentName(pkg, "$pkg.KioskHome")
        activity.packageManager.setComponentEnabledSetting(
            home,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(admin, homeFilter, home)

        // Android 9 lists nearby Wi-Fi only to apps with location access, and
        // only with location on; a locked device can't show either prompt.
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            dpm.setPermissionGrantState(admin, pkg, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setLocationEnabled(admin, true)
            } else {
                @Suppress("DEPRECATION")
                dpm.setSecureSetting(admin, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_HIGH_ACCURACY.toString())
            }
        }
    }

    /** From MainActivity.onResume: pin the app if it isn't already. */
    fun lock(activity: Activity) {
        if (!isDeviceOwner(activity)) return
        val am = activity.getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) runCatching { activity.startLockTask() }
    }
}
