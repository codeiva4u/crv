package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

class InAppUpdater {
    companion object {
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

        private val updateLock = Mutex()

        private suspend fun Activity.downloadUpdate(url: String, autoInstall: Boolean = false): File {
            try {
                Log.d(LOG_TAG, "Downloading update: $url")
                val appUpdateName = "CloudStream"
                val appUpdateSuffix = "apk"
                // Delete all old updates
                this.cacheDir.listFiles()?.filter {
                    it.name.startsWith(appUpdateName) && it.extension == appUpdateSuffix
                }?.forEach {
                    deleteFileOnExit(it)
                }
                val downloadedFile =
                    withContext(Dispatchers.IO) {
                        File.createTempFile(appUpdateName, ".$appUpdateSuffix", cacheDir)
                    }
                val sink: BufferedSink = downloadedFile.sink().buffer()
                updateLock.withLock {
                    sink.writeAll(app.get(url).body.source())
                    sink.close()
                    if (autoInstall) {
                        openApk(this, Uri.fromFile(downloadedFile))
                    }
                }
                return downloadedFile
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to download update: ${e.message}")
                throw e
            }
        }

        fun Activity.showInstallDialog(apkPath: String, apkVersion: String) {
            val downloadedFile = File(apkPath)
            if (!downloadedFile.exists()) {
                showToast(getString(R.string.update_file_missing), Toast.LENGTH_LONG)
                return
            }
            val currentVersion = this.packageName?.let {
                this.packageManager.getPackageInfo(it, 0)?.versionName
            }
            val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            builder.setTitle(
                getString(R.string.install_update_format).format(
                    currentVersion,
                    apkVersion
                )
            )
            builder.setMessage(this.getString(R.string.ready_to_install_message))
            builder.setPositiveButton(this.getString(R.string.installing_update)) { dialog: DialogInterface, _: Int ->
                openApk(this@showInstallDialog, Uri.fromFile(downloadedFile))
                dialog.dismiss()
            }
            builder.setNegativeButton(this.getString(R.string.cancel)) { dialog: DialogInterface, _: Int ->
                dialog.dismiss()
            }
            val dialog = builder.create()
            dialog.show()
            dialog.setDefaultFocus()
        }

        private fun openApk(context: Activity, uri: Uri) {
            try {
                uri.path?.let {
                    val contentUri = FileProvider.getUriForFile(
                        context,
                        BuildConfig.APPLICATION_ID + ".provider",
                        File(it)
                    )
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                        data = contentUri
                    }
                    context.startActivity(installIntent)
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        private fun Activity.showUpdateNotification(updateVersion: String?) {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
            val apkPath = settingsManager.getString("downloaded_apk_path", null)
            val apkVersion = settingsManager.getString("downloaded_apk_version", null)

            // If no APK path or version is found, do not show the notification
            if (apkPath == null || apkVersion == null) {
                Log.e(LOG_TAG, "Downloaded APK path or version missing for notification")
                return
            }

            runOnUiThread {
                try {
                    val builder: AlertDialog.Builder = AlertDialog.Builder(this)
                    builder.setTitle(getString(R.string.update_available_notification_title))
                    builder.setMessage(getString(R.string.update_available_notification_message))

                    // Install Update Button
                    builder.setPositiveButton(R.string.install_update) { _, _ ->
                        showInstallDialog(apkPath, apkVersion)
                        // Clear the downloaded APK info from SharedPreferences after installation
                        settingsManager.edit()
                            .remove("downloaded_apk_path")
                            .remove("downloaded_apk_version")
                            .apply()
                    }

                    // Cancel Update Button
                    builder.setNegativeButton(R.string.cancel_update) { dialog, _ ->
                        dialog.dismiss()
                        // Do NOT clear the downloaded APK info from SharedPreferences
                        // This ensures the popup will show again next time the app is opened
                    }

                    // Skip This Update Button
                    builder.setNeutralButton(R.string.skip_this_update) { _, _ ->
                        // Save skipped update version but DO NOT clear APK info
                        settingsManager.edit()
                            .putString(getString(R.string.skip_update_key), updateVersion ?: "")
                            .apply()
                        // This ensures the popup will show again next time the app is opened
                    }

                    builder.show().setDefaultFocus()
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }

        suspend fun Activity.runAutoUpdate(checkAutoUpdate: Boolean = true): Boolean {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)

            // Check for existing downloaded APK
            val apkPath = settingsManager.getString("downloaded_apk_path", null)
            val apkVersion = settingsManager.getString("downloaded_apk_version", null)

            if (!checkAutoUpdate && apkPath != null && apkVersion != null) {
                // Show install dialog if downloaded APK exists and not auto-update check
                runOnUiThread {
                    try {
                        showInstallDialog(apkPath, apkVersion)
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
                return true
            }

            if (!checkAutoUpdate || settingsManager.getBoolean(
                    getString(R.string.auto_update_key),
                    true
                )
            ) {
                val update = getAppUpdate()

                if (update.shouldUpdate && update.updateURL != null) {
                    // Check if already downloaded same version
                    val downloadedVersion = settingsManager.getString("downloaded_apk_version", null)
                    if (downloadedVersion == update.updateVersion && checkAutoUpdate) {
                        return false // Already have this version, skip download
                    }

                    // Check if update should be skipped
                    val updateNodeId = settingsManager.getString(getString(R.string.skip_update_key), "")
                    if (update.updateNodeId.equals(updateNodeId) && checkAutoUpdate) {
                        return false
                    }

                    // Start silent download in background
                    ioSafe {
                        try {
                            val downloadedFile = downloadUpdate(update.updateURL, false)

                            // Save downloaded APK info for notification
                            val apkPath = downloadedFile.absolutePath
                            settingsManager.edit()
                                .putString("downloaded_apk_path", apkPath)
                                .putString("downloaded_apk_version", update.updateVersion)
                                .apply()

                            Log.d(LOG_TAG, "Saved APK path: $apkPath")
                            downloadedFile.deleteOnExit() // Ensure file persists until installation

                            // Show notification to user about available update
                            Log.d(LOG_TAG, "Update downloaded silently in background")
                            showUpdateNotification(update.updateVersion)
                        } catch (e: Exception) {
                            Log.e(LOG_TAG, "Update download failed during silent download: ${e.message}")
                        }
                    }
                    return true // Indicate update check was performed and download started (or skipped)
                }
                return false // No update available
            }
            return false // Auto-update is disabled
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
    }
}