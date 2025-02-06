package com.lagradost.cloudstream3.services

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ApkInstaller
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UpdateProgressActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

class PackageInstallerService : Service() {
    private var installer: ApkInstaller? = null
    private lateinit var progressActivityIntent: Intent // Intent for progress activity
    private val baseNotification by lazy {
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else 0
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(this, 0, intent, flag)
        NotificationCompat.Builder(this, UPDATE_CHANNEL_ID)
            .setAutoCancel(false)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // If low priority then the notification might not show :(
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(this.colorFromAttribute(R.attr.colorPrimary))
            .setContentTitle(getString(R.string.update_notification_downloading))
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.drawable.rdload)
    }

    override fun onCreate() {
        this.createNotificationChannel(
            UPDATE_CHANNEL_ID,
            UPDATE_CHANNEL_NAME,
            UPDATE_CHANNEL_DESCRIPTION
        )
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(
                UPDATE_NOTIFICATION_ID,
                baseNotification.build(),
                FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        else startForeground(UPDATE_NOTIFICATION_ID, baseNotification.build())
        // Initialize progress activity intent
        progressActivityIntent = UpdateProgressActivity.intent(this).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }

    private val updateLock = Mutex()

    private suspend fun downloadUpdate(url: String): Boolean {
        try {
            Log.d("PackageInstallerService", "Downloading update: $url")
            // Delete all old updates
            ioSafe {
                val appUpdateName = "CloudStream"
                val appUpdateSuffix = "apk"
                this@PackageInstallerService.cacheDir.listFiles()?.filter {
                    it.name.startsWith(appUpdateName) && it.extension == appUpdateSuffix
                }?.forEach {
                    deleteFileOnExit(it)
                }
            }
            updateLock.withLock {
                updateNotificationProgress(
                    0f,
                    ApkInstaller.InstallProgressStatus.Downloading
                )
                // Start progress activity
                startActivity(progressActivityIntent)
                val body = app.get(url).body
                val inputStream = body.byteStream()
                installer = ApkInstaller(this)
                val totalSize = body.contentLength()
                var currentSize = 0
                installer?.installApk(this, inputStream, totalSize, { bytesDownloaded ->
                    currentSize += bytesDownloaded
                    // Prevent div 0
                    if (totalSize == 0L) return@installApk
                    val percentage = currentSize / totalSize.toFloat()
                    updateProgressActivity(percentage) // Update progress activity
                    updateNotificationProgress(
                        percentage,
                        ApkInstaller.InstallProgressStatus.Downloading
                    )
                }) { status ->
                    updateNotificationProgress(0f, status)
                    updateProgressActivityStatus(status) // Update progress activity status
                    if (status == ApkInstaller.InstallProgressStatus.Installing) {
                        updateProgressActivity(1f) // Max progress for installing
                    }
                }
            }
            restartApp() // Restart app after successful install
            return true
        } catch (e: Exception) {
            logError(e)
            updateNotificationProgress(0f, ApkInstaller.InstallProgressStatus.Failed)
            updateProgressActivityStatus(ApkInstaller.InstallProgressStatus.Failed) // Update progress activity status
            return false
        }
    }

    private fun updateProgressActivity(percentage: Float) {
        // Update progress in progress activity
        progressActivityIntent.putExtra("progress", percentage)
        sendBroadcast(Intent(PROGRESS_UPDATE_ACTION).putExtra("progress", percentage))
    }

    private fun updateProgressActivityStatus(status: ApkInstaller.InstallProgressStatus) {
        // Update status text in progress activity
        progressActivityIntent.putExtra("status", status.toString())
        sendBroadcast(
            Intent(STATUS_UPDATE_ACTION).putExtra("status", status.toString())
        )
    }

    private fun updateNotificationProgress(
        percentage: Float,
        state: ApkInstaller.InstallProgressStatus
    ) {
        val text = when (state) {
            ApkInstaller.InstallProgressStatus.Installing -> R.string.update_notification_installing
            ApkInstaller.InstallProgressStatus.Preparing, ApkInstaller.InstallProgressStatus.Downloading -> R.string.update_notification_downloading
            ApkInstaller.InstallProgressStatus.Failed -> R.string.update_notification_failed
            ApkInstaller.InstallProgressStatus.Finished -> TODO()
        }
        val newNotification = baseNotification
            .setContentTitle(getString(text))
            .apply {
                if (state == ApkInstaller.InstallProgressStatus.Failed) {
                    setSmallIcon(R.drawable.rderror)
                    setAutoCancel(true)
                } else {
                    setProgress(
                        10000,
                        (10000 * percentage).roundToInt(),
                        state != ApkInstaller.InstallProgressStatus.Downloading
                    )
                }
            }
            .build()
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // Persistent notification on failure
        val id =
            if (state == ApkInstaller.InstallProgressStatus.Failed) UPDATE_NOTIFICATION_ID + 1 else UPDATE_NOTIFICATION_ID
        notificationManager.notify(id, newNotification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        ioSafe {
            downloadUpdate(url)
            // Close the service after the update is done
            // If no sleep then the install prompt may not appear and the notification
            // will disappear instantly
            delay(10_000)
            this@PackageInstallerService.stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        installer?.unregisterInstallActionReceiver()
        installer = null
        this.stopSelf()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onTimeout(reason: Int) {
        stopSelf()
        Log.e("PackageInstallerService", "Service stopped due to timeout: $reason")
    }

    companion object {
        private const val EXTRA_URL = "EXTRA_URL"
        const val PROGRESS_UPDATE_ACTION = "PROGRESS_UPDATE_ACTION" // Progress update action
        const val STATUS_UPDATE_ACTION = "STATUS_UPDATE_ACTION"     // Status update action
        const val UPDATE_CHANNEL_ID = "cloudstream3.updates"
        const val UPDATE_CHANNEL_NAME = "App Updates"
        const val UPDATE_CHANNEL_DESCRIPTION = "App updates notification channel"
        const val UPDATE_NOTIFICATION_ID = -68454136 // Random unique

        fun getIntent(
            context: Context,
            url: String
        ): Intent {
            return Intent(context, PackageInstallerService::class.java)
                .putExtra(EXTRA_URL, url)
        }

        // Add function to restart app
        fun Context.restartApp() {
            val packageManager = packageManager
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            val componentName = intent!!.component
            val mainIntent = Intent.makeRestartActivityTask(componentName)
            startActivity(mainIntent)
            Runtime.getRuntime().exit(0) // Kill the old process
        }
    }
}