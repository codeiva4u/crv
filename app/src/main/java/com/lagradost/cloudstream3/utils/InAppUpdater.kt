package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

class InAppUpdater {
    companion object {
        private const val NOTIFICATION_ID = 8975
        private const val UPDATE_CHANNEL_ID = "app_updates"
        private const val UPDATE_CHANNEL_NAME = "App Updates"
        private val updateMutex = Mutex()

        // Define missing string resources (add these to strings.xml)
        private const val INSTALL_UPDATE = "Install Update"
        private const val DOWNLOAD_STARTED = "Download started"

        private data class UpdateInfo(
            @JsonProperty("version") val version: String?,
            @JsonProperty("versionCode") val versionCode: Int?,
            @JsonProperty("changelog") val changelog: String?,
            @JsonProperty("downloadUrl") val downloadUrl: String?
        )

        private const val GITHUB_USER_NAME = "codeiva4u"
        private const val GITHUB_REPO = "crv"
        private const val LOG_TAG = "InAppUpdater"

        // === IN APP UPDATER ===
        data class GithubAsset(
            @JsonProperty("name") val name: String,
            @JsonProperty("size") val size: Int, // Size bytes
            @JsonProperty("browser_download_url") val browserDownloadUrl: String, // download link
            @JsonProperty("content_type") val contentType: String, // application/vnd.android.package-archive
        )

        data class GithubRelease(
            @JsonProperty("tag_name") val tagName: String, // Version code
            @JsonProperty("body") val body: String, // Desc
            @JsonProperty("assets") val assets: List<GithubAsset>,
            @JsonProperty("target_commitish") val targetCommitish: String, // branch
            @JsonProperty("prerelease") val prerelease: Boolean,
            @JsonProperty("node_id") val nodeId: String // Node Id
        )

        data class GithubObject(
            @JsonProperty("sha") val sha: String, // sha 256 hash
            @JsonProperty("type") val type: String, // object type
            @JsonProperty("url") val url: String,
        )

        data class GithubTag(
            @JsonProperty("object") val githubObject: GithubObject,
        )

        data class Update(
            @JsonProperty("shouldUpdate") val shouldUpdate: Boolean,
            @JsonProperty("updateURL") val updateURL: String?,
            @JsonProperty("updateVersion") val updateVersion: String?,
            @JsonProperty("changelog") val changelog: String?,
            @JsonProperty("updateNodeId") val updateNodeId: String?
        )

        private suspend fun Activity.getAppUpdate(): Update {
            return try {
                val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
                if (settingsManager.getBoolean(
                        getString(R.string.prerelease_update_key),
                        resources.getBoolean(R.bool.is_prerelease)
                    )
                ) {
                    getPreReleaseUpdate()
                } else {
                    getReleaseUpdate()
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, Log.getStackTraceString(e))
                Update(false, null, null, null, null)
            }
        }

        private suspend fun Activity.getReleaseUpdate(): Update {
            val url = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
            val headers = mapOf("Accept" to "application/vnd.github.v3+json")
            val response =
                parseJson<List<GithubRelease>>(
                    app.get(
                        url,
                        headers = headers
                    ).text
                )
            val versionRegex = Regex("""(.*?((\d+)\.(\d+)\.(\d+))\.apk)""")
            val versionRegexLocal = Regex("""(.*?((\d+)\.(\d+)\.(\d+)).*)""")

            val foundList =
                response.filter { rel ->
                    !rel.prerelease
                }.sortedWith(compareBy { release ->
                    release.assets.firstOrNull { it.contentType == "application/vnd.android.package-archive" }?.name?.let { it1 ->
                        versionRegex.find(
                            it1
                        )?.groupValues?.let {
                            it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
                        }
                    }
                }).toList()
            val found = foundList.lastOrNull()
            val foundAsset = found?.assets?.getOrNull(0)
            val currentVersion = packageName?.let {
                packageManager.getPackageInfo(
                    it,
                    0
                )
            }
            foundAsset?.name?.let { assetName ->
                val foundVersion = versionRegex.find(assetName)
                val shouldUpdate =
                    if (foundAsset.browserDownloadUrl != "" && foundVersion != null) currentVersion?.versionName?.let { versionName ->
                        versionRegexLocal.find(versionName)?.groupValues?.let {
                            it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
                        }
                    }?.compareTo(
                        foundVersion.groupValues.let {
                            it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
                        }
                    )!! < 0 else false
                return if (foundVersion != null) {
                    Update(
                        shouldUpdate,
                        foundAsset.browserDownloadUrl,
                        foundVersion.groupValues[2],
                        found.body,
                        found.nodeId
                    )
                } else {
                    Update(false, null, null, null, null)
                }
            }
            return Update(false, null, null, null, null)
        }

        private suspend fun Activity.getPreReleaseUpdate(): Update {
            val tagUrl =
                "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/git/ref/tags/pre-release"
            val releaseUrl = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
            val headers = mapOf("Accept" to "application/vnd.github.v3+json")
            val response =
                parseJson<List<GithubRelease>>(app.get(releaseUrl, headers = headers).text)
            val found =
                response.lastOrNull { rel ->
                    rel.prerelease || rel.tagName == "pre-release"
                }
            val foundAsset = found?.assets?.filter { it ->
                it.contentType == "application/vnd.android.package-archive"
            }?.getOrNull(0)
            val tagResponse =
                parseJson<GithubTag>(app.get(tagUrl, headers = headers).text)
            Log.d(LOG_TAG, "Fetched GitHub tag: ${tagResponse.githubObject.sha.take(7)}")
            val shouldUpdate =
                (getString(R.string.commit_hash)
                    .trim { c -> c.isWhitespace() }
                    .take(7)
                        !=
                        tagResponse.githubObject.sha
                            .trim { c -> c.isWhitespace() }
                            .take(7))
            return if (foundAsset != null) {
                Update(
                    shouldUpdate,
                    foundAsset.browserDownloadUrl,
                    tagResponse.githubObject.sha.take(10),
                    found.body,
                    found.nodeId
                )
            } else {
                Update(false, null, null, null, null)
            }
        }

        private suspend fun Activity.downloadUpdate(url: String, silent: Boolean = false): Boolean {
            return withContext(Dispatchers.IO) {
                try {
                    val downloadDir = getExternalFilesDir("updates") ?: return@withContext false
                    val apkFile = File(downloadDir, "update.apk")
                    if (!silent) {
                        runOnUiThread {
                            showProgressDialog(getString(R.string.update_progress_downloading))
                        }
                    }
                    val success = downloadFile(url, apkFile) { progress ->
                        if (!silent) {
                            runOnUiThread {
                                updateProgressDialog(progress)
                            }
                        }
                    }
                    if (success) {
                        if (!silent) {
                            runOnUiThread {
                                dismissProgressDialog()
                                showInstallPrompt(apkFile)
                            }
                        } else {
                            showUpdateNotification()
                        }
                        true
                    } else {
                        if (!silent) {
                            runOnUiThread {
                                dismissProgressDialog()
                                showToast(R.string.download_failed, Toast.LENGTH_LONG)
                            }
                        }
                        false
                    }
                } catch (e: Exception) {
                    logError(e)
                    if (!silent) {
                        runOnUiThread {
                            dismissProgressDialog()
                            showToast(e.message ?: getString(R.string.error), Toast.LENGTH_LONG)
                        }
                    }
                    false
                }
            }
        }

        private fun Activity.showInstallPrompt(apkFile: File) {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                data = FileProvider.getUriForFile(
                    this@showInstallPrompt,
                    "${packageName}.fileprovider",
                    apkFile
                )
            }
            startActivity(installIntent)
        }

        private fun Activity.showUpdateNotification() {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    UPDATE_CHANNEL_ID,
                    UPDATE_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                )
                notificationManager.createNotificationChannel(channel)
            }
            val skipIntent = Intent(this, UpdateReceiver::class.java).apply {
                action = "SKIP_UPDATE"
            }
            val cancelIntent = Intent(this, UpdateReceiver::class.java).apply {
                action = "CANCEL_UPDATE"
            }
            val installIntent = Intent(this, UpdateReceiver::class.java).apply {
                action = "INSTALL_UPDATE"
            }
            val notification = NotificationCompat.Builder(this, UPDATE_CHANNEL_ID)
                .setContentTitle(getString(R.string.update_available_title))
                .setContentText(getString(R.string.update_available_message))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .addAction(
                    android.R.drawable.ic_media_pause,
                    getString(R.string.skip_update_button),
                    PendingIntent.getBroadcast(this, 0, skipIntent, PendingIntent.FLAG_IMMUTABLE)
                )
                .addAction(
                    android.R.drawable.ic_media_pause,
                    getString(R.string.cancel_update_button),
                    PendingIntent.getBroadcast(this, 1, cancelIntent, PendingIntent.FLAG_IMMUTABLE)
                )
                .addAction(
                    android.R.drawable.ic_media_play,
                    getString(R.string.install_update_button),
                    PendingIntent.getBroadcast(this, 2, installIntent, PendingIntent.FLAG_IMMUTABLE)
                )
                .setAutoCancel(true)
                .build()
            notificationManager.notify(NOTIFICATION_ID, notification)
        }

        /**
         * @param checkAutoUpdate if the update check was launched automatically
         */
        suspend fun Activity.runAutoUpdate(checkAutoUpdate: Boolean = true): Boolean {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
            if (!checkAutoUpdate || settingsManager.getBoolean(getString(R.string.auto_update_key), true)) {
                val update = getAppUpdate()
                if (update.shouldUpdate && update.updateURL != null) {
                    // Check if update should be skipped
                    val updateNodeId = settingsManager.getString(getString(R.string.skip_update_key), "")
                    // Skips the update if its an automatic update and the update is skipped
                    if (update.updateNodeId.equals(updateNodeId) && checkAutoUpdate) {
                        return false
                    }
                    // Start silent download if auto-update is enabled
                    if (checkAutoUpdate) {
                        ioSafe {
                            downloadUpdate(update.updateURL, true)
                        }
                    }
                    runOnUiThread {
                        try {
                            val currentVersion = packageName?.let {
                                packageManager.getPackageInfo(
                                    it,
                                    0
                                )
                            }
                            val builder: AlertDialog.Builder = AlertDialog.Builder(this)
                            builder.setTitle(
                                getString(R.string.new_update_format).format(
                                    currentVersion?.versionName,
                                    update.updateVersion
                                )
                            )
                            val logRegex = Regex("\\[(.*?)\\]\\((.*?)\\)")
                            val sanitizedChangelog = update.changelog?.replace(logRegex) { matchResult ->
                                matchResult.groupValues[1]
                            }
                            builder.setMessage(sanitizedChangelog)
                            builder.apply {
                                setPositiveButton(INSTALL_UPDATE) { _, _ ->
                                    showToast(DOWNLOAD_STARTED, Toast.LENGTH_LONG)
                                    ioSafe {
                                        downloadUpdate(update.updateURL, false)
                                    }
                                }
                                setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                                    dialog.dismiss()
                                }
                                if (checkAutoUpdate) {
                                    setNeutralButton(getString(R.string.skip_update)) { _, _ ->
                                        settingsManager.edit().putString(
                                            getString(R.string.skip_update_key),
                                            update.updateNodeId ?: ""
                                        ).apply()
                                    }
                                }
                            }
                            builder.show().setDefaultFocus()
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                    return true
                }
            }
            return false
        }

        private fun isMiUi(): Boolean {
            return !TextUtils.isEmpty(getSystemProperty("ro.miui.ui.version.name"))
        }

        private fun getSystemProperty(propName: String): String? {
            return try {
                val p = Runtime.getRuntime().exec("getprop $propName")
                BufferedReader(InputStreamReader(p.inputStream), 1024).use {
                    it.readLine()
                }
            } catch (ex: IOException) {
                null
            }
        }

        private var progressDialog: AlertDialog? = null

        private fun Activity.showProgressDialog(message: String) {
            progressDialog?.dismiss()
            progressDialog = AlertDialog.Builder(this)
                .setTitle(R.string.update_progress_title)
                .setView(R.layout.dialog_update_progress)
                .setCancelable(false)
                .create()
                .apply {
                    show()
                    findViewById<TextView>(R.id.txt_status)?.text = message
                }
        }

        private fun Activity.updateProgressDialog(progress: Int) {
            progressDialog?.run {
                findViewById<ProgressBar>(R.id.progress_bar)?.progress = progress
                findViewById<TextView>(R.id.txt_percent)?.text = "$progress%"
            }
        }

        private fun dismissProgressDialog() {
            progressDialog?.dismiss()
            progressDialog = null
        }

        private suspend fun downloadFile(url: String, destination: File, progressCallback: (Int) -> Unit): Boolean {
            return withContext(Dispatchers.IO) {
                try {
                    val response = app.get(url)
                    val body = response.body
                    val contentLength = body.contentLength()
                    destination.outputStream().use { output ->
                        body.source().use { source ->
                            var bytesRead: Long = 0
                            val buffer = ByteArray(8192)
                            var bytes = source.read(buffer)
                            while (bytes >= 0) {
                                output.write(buffer, 0, bytes)
                                bytesRead += bytes
                                val progress = ((bytesRead * 100) / contentLength).toInt()
                                progressCallback(progress)
                                bytes = source.read(buffer)
                            }
                        }
                    }
                    true
                } catch (e: Exception) {
                    logError(e)
                    false
                }
            }
        }

        // Define showToast function
        private fun Activity.showToast(message: String, duration: Int) {
            Toast.makeText(this, message, duration).show()
        }

        private fun Activity.showToast(resId: Int, duration: Int) {
            Toast.makeText(this, getString(resId), duration).show()
        }

        class UpdateReceiver : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    "SKIP_UPDATE" -> {
                        // Save skip version to preferences
                        PreferenceManager.getDefaultSharedPreferences(context)
                            .edit()
                            .putString("skipped_version", BuildConfig.VERSION_NAME)
                            .apply()
                    }
                    "CANCEL_UPDATE" -> {
                        // Clear downloaded file and notification
                        context.getExternalFilesDir("updates")?.listFiles()?.forEach { it.delete() }
                        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                            .cancel(NOTIFICATION_ID)
                    }
                    "INSTALL_UPDATE" -> {
                        // Start installation
                        val apkFile = File(context.getExternalFilesDir("updates"), "update.apk")
                        if (apkFile.exists()) {
                            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    apkFile
                                )
                            }
                            context.startActivity(installIntent)
                        }
                    }
                }
            }
        }
    }
}