package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.AcraApplication.Companion.context
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import com.lagradost.cloudstream3.plugins.PluginManager.getPluginSanitizedFileName
import com.lagradost.cloudstream3.plugins.PluginManager.unloadPlugin
import com.lagradost.cloudstream3.ui.settings.extensions.REPOSITORIES_KEY
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Comes with the app, always available in the app, non removable.
 * */

data class Repository(
    @JsonProperty("name") val name: String,
    @JsonProperty("description") val description: String?,
    @JsonProperty("manifestVersion") val manifestVersion: Int,
    @JsonProperty("pluginLists") val pluginLists: List<String>
)

/**
 * Status int as the following:
 * 0: Down
 * 1: Ok
 * 2: Slow
 * 3: Beta only
 * */
data class SitePlugin(
    // Url to the .cs3 file
    @JsonProperty("url") val url: String,
    // Status to remotely disable the provider
    @JsonProperty("status") val status: Int,
    // Integer over 0, any change of this will trigger an auto update
    @JsonProperty("version") val version: Int,
    // Unused currently, used to make the api backwards compatible?
    // Set to 1
    @JsonProperty("apiVersion") val apiVersion: Int,
    // Name to be shown in app
    @JsonProperty("name") val name: String,
    // Name to be referenced internally. Separate to make name and url changes possible
    @JsonProperty("internalName") val internalName: String,
    @JsonProperty("authors") val authors: List<String>,
    @JsonProperty("description") val description: String?,
    // Might be used to go directly to the plugin repo in the future
    @JsonProperty("repositoryUrl") val repositoryUrl: String?,
    // These types are yet to be mapped and used, ignore for now
    @JsonProperty("tvTypes") val tvTypes: List<String>?,
    @JsonProperty("language") val language: String?,
    @JsonProperty("iconUrl") val iconUrl: String?,
    // Automatically generated by the gradle plugin
    @JsonProperty("fileSize") val fileSize: Long?,
)


//===================== इसे यहा बादलों ===========================================================

object RepositoryManager {
    const val ONLINE_PLUGINS_FOLDER = BuildConfig.ONLINE_PLUGINS_FOLDER
    val PREBUILT_REPOSITORIES: Array<RepositoryData> by lazy {
        arrayOf(
            RepositoryData(
                name = BuildConfig.REPO_NAME,
                url = BuildConfig.REPO_URL
            )
        )
    }
//=============================== इसे यहा बादलो ==============================================================
    private val GH_REGEX = Regex("^https://raw.githubusercontent.com/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+)/(.*)$")

    /* Convert raw.githubusercontent.com urls to cdn.jsdelivr.net if enabled in settings */
    fun convertRawGitUrl(url: String): String {
        if (getKey<Boolean>(context!!.getString(R.string.jsdelivr_proxy_key)) != true) return url
        val match = GH_REGEX.find(url) ?: return url
        val (user, repo, rest) = match.destructured
        return "https://cdn.jsdelivr.net/gh/$user/$repo@$rest"
    }

    suspend fun parseRepoUrl(url: String): String? {
        val fixedUrl = url.trim()
        return if (fixedUrl.contains("^https?://".toRegex())) {
            fixedUrl
        } else if (fixedUrl.contains("^(cloudstreamrepo://)|(https://cs\\.repo/\\??)".toRegex())) {
            fixedUrl.replace("^(cloudstreamrepo://)|(https://cs\\.repo/\\??)".toRegex(), "").let {
                return@let if (!it.contains("^https?://".toRegex()))
                    "https://${it}"
                else fixedUrl
            }
        } else if (fixedUrl.matches("^[a-zA-Z0-9!_-]+$".toRegex())) {
            suspendSafeApiCall {
                app.get("https://cutt.ly/${fixedUrl}", allowRedirects = false).let { it2 ->
                    it2.headers["Location"]?.let { url ->
                        if (url.startsWith("https://cutt.ly/404")) return@suspendSafeApiCall null
                        if (url.removeSuffix("/") == "https://cutt.ly") return@suspendSafeApiCall null
                        return@suspendSafeApiCall url
                    }
                }
            }
        } else null
    }

    suspend fun parseRepository(url: String): Repository? {
        return suspendSafeApiCall {
            // Take manifestVersion and such into account later
            app.get(convertRawGitUrl(url)).parsedSafe()
        }
    }

    private suspend fun parsePlugins(pluginUrls: String): List<SitePlugin> {
        // Take manifestVersion and such into account later
        return try {
            val response = app.get(convertRawGitUrl(pluginUrls))
            // Normal parsed function not working?
            // return response.parsedSafe()
            tryParseJson<Array<SitePlugin>>(response.text)?.toList() ?: emptyList()
        } catch (t: Throwable) {
            logError(t)
            emptyList()
        }
    }

    /**
     * Gets all plugins from repositories and pairs them with the repository url
     * */
    suspend fun getRepoPlugins(repositoryUrl: String): List<Pair<String, SitePlugin>>? {
        val repo = parseRepository(repositoryUrl) ?: return null
        return repo.pluginLists.amap { url ->
            parsePlugins(url).map {
                repositoryUrl to it
            }
        }.flatten()
    }

    suspend fun downloadPluginToFile(
        pluginUrl: String,
        file: File
    ): File? {
        return suspendSafeApiCall {
            file.mkdirs()

            // Overwrite if exists
            if (file.exists()) {
                file.delete()
            }
            file.createNewFile()

            val body = app.get(convertRawGitUrl(pluginUrl)).okhttpResponse.body
            write(body.byteStream(), file.outputStream())
            file
        }
    }

    fun getRepositories(): Array<RepositoryData> {
        return getKey(REPOSITORIES_KEY) ?: emptyArray()
    }

    // Don't want to read before we write in another thread
    private val repoLock = Mutex()
    suspend fun addRepository(repository: RepositoryData) {
        repoLock.withLock {
            val currentRepos = getRepositories()
            // No duplicates
            setKey(REPOSITORIES_KEY, (currentRepos + repository).distinctBy { it.url })
        }
    }

    /**
     * Also deletes downloaded repository plugins
     * */
    suspend fun removeRepository(context: Context, repository: RepositoryData) {
        val extensionsDir = File(context.filesDir, ONLINE_PLUGINS_FOLDER)

        repoLock.withLock {
            val currentRepos = getKey<Array<RepositoryData>>(REPOSITORIES_KEY) ?: emptyArray()
            // No duplicates
            val newRepos = currentRepos.filter { it.url != repository.url }
            setKey(REPOSITORIES_KEY, newRepos)
        }

        val file = File(
            extensionsDir,
            getPluginSanitizedFileName(repository.url)
        )

        // Unload all plugins, not using deletePlugin since we
        // delete all data and files in deleteRepositoryData
        normalSafeApiCall {
            file.listFiles { plugin: File ->
                unloadPlugin(plugin.absolutePath)
                false
            }
        }

        PluginManager.deleteRepositoryData(file.absolutePath)
    }

    private fun write(stream: InputStream, output: OutputStream) {
        val input = BufferedInputStream(stream)
        val dataBuffer = ByteArray(512)
        var readBytes: Int
        while (input.read(dataBuffer).also { readBytes = it } != -1) {
            output.write(dataBuffer, 0, readBytes)
        }
    }
}
