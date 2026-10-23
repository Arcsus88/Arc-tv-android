package com.arcsus.arcdisney.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.arcsus.arcdisney.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Keeps the app on the newest GitHub release by itself: checks every hour,
 * downloads the APK for this device, and installs it once nothing is playing
 * (the update restarts the app). As the device owner that install is silent;
 * otherwise Android asks first.
 */
class AutoUpdater(
    private val context: Context,
    private val releases: ReleaseClient?,
    private val currentVersion: String,
    private val abis: List<String> = Build.SUPPORTED_ABIS.toList(),
) {
    /** True while a film is starting or playing: an install then would cut it off. */
    val busy = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private val dir = File(context.noBackupFilesDir, "updates")

    fun start() {
        if (releases == null || loop != null) return
        loop = scope.launch {
            delay(FIRST_CHECK_MS)
            while (isActive) {
                try {
                    checkAndInstall()
                } catch (e: IOException) {
                    Log.w(TAG, "Update check failed: ${e.message}")
                }
                delay(CHECK_EVERY_MS)
            }
        }
    }

    /** One round: look, fetch if newer, wait until idle, install. */
    suspend fun checkAndInstall() {
        val client = releases ?: return
        val update = pickUpdate(client.releases(), currentVersion, abis) ?: run {
            dir.deleteRecursively() // nothing pending: drop old downloads
            return
        }
        dir.mkdirs()
        val apk = File(dir, "arc-disney-${update.version}.apk")
        dir.listFiles()?.filter { it != apk }?.forEach { it.delete() }
        if (!apk.exists() || (update.asset.size > 0 && apk.length() != update.asset.size)) {
            client.download(update.asset, apk)
        }
        busy.first { !it }
        install(apk)
    }

    private suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("arc-disney.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val result = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
            session.commit(result.intentSender)
        }
    }

    private companion object {
        const val TAG = "ArcDisneyUpdate"
        const val FIRST_CHECK_MS = 60_000L
        const val CHECK_EVERY_MS = 60 * 60_000L
    }
}

/** Where the package installer reports back; only a non-owner install needs anything doing. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // Not the device owner: Android wants a tap on "Install".
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Log.w("ArcDisneyUpdate", "Install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}

/** After an update replaces the app, bring it straight back. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
