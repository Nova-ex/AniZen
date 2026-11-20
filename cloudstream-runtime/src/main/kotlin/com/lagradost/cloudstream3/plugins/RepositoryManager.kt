// ANZ -->
package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.ui.settings.extensions.REPOSITORIES_KEY
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.util.concurrent.TimeUnit

data class SitePlugin(
    @JsonProperty("internalName") val internalName: String = "",
    @JsonProperty("name") val name: String = "",
    @JsonProperty("authors") val authors: List<String> = emptyList(),
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("updateURL") val updateURL: String? = null,
    @JsonProperty("plugins") val plugins: List<String> = emptyList(),
    @JsonProperty("tvTypes") val tvTypes: List<String>? = null,
    @JsonProperty("version") val version: Int = 1,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("url") val url: String = "",
    @JsonProperty("iconUrl") val iconUrl: String? = null,
    @JsonProperty("status") val status: Int? = 1,
    @JsonProperty("price") val price: Int? = 0,
    @JsonProperty("apiVersion") val apiVersion: Int = 1,
    @JsonProperty("fileSize") val fileSize: Long? = null,
    @JsonProperty("fileHash") val fileHash: String? = null,
    @JsonProperty("repositoryUrl") var repositoryUrl: String? = null,
)

data class Repository(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("pluginLists") val pluginLists: List<String> = emptyList(),
    @JsonProperty("iconUrl") val iconUrl: String? = null,
    @JsonProperty("manifestVersion") val manifestVersion: Int = 1,
)

object RepositoryManager {
    private const val TAG = "RepositoryManager"

    val PREBUILT_REPOSITORIES = arrayOf<RepositoryData>()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val noRedirectClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val repoLock = Any()

    private val _reposChangedFlow = MutableStateFlow(0L)
    val reposChangedFlow: StateFlow<Long> = _reposChangedFlow.asStateFlow()

    private fun notifyReposChanged() {
        _reposChangedFlow.value = System.nanoTime()
    }

    fun convertRawGitUrl(url: String): String {
        return url.trim()
            .replace(
                Regex("""^https?://github\.com/([^/]+)/([^/]+)/blob/(.*)$"""),
                "https://raw.githubusercontent.com/$1/$2/$3",
            )
    }

    suspend fun parseRepoUrl(rawInput: String): String? = withContext(Dispatchers.IO) {
        val fixedUrl = rawInput.trim()
        if (fixedUrl.isBlank()) return@withContext null

        when {
            fixedUrl.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE)) -> {
                convertRawGitUrl(fixedUrl)
            }
            fixedUrl.contains(Regex("^(cloudstreamrepo://)|(https://cs\\.repo/\\??)", RegexOption.IGNORE_CASE)) -> {
                val stripped = fixedUrl.replace(
                    Regex("^(cloudstreamrepo://)|(https://cs\\.repo/\\??)", RegexOption.IGNORE_CASE),
                    "",
                )
                val full = if (stripped.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE))) {
                    stripped
                } else {
                    "https://$stripped"
                }
                convertRawGitUrl(full)
            }
            fixedUrl.matches(Regex("^[a-zA-Z0-9!_-]+$")) -> {
                runCatching {
                    val lookupUrl = if (fixedUrl.startsWith("!")) {
                        "https://py.md/${fixedUrl.removePrefix("!")}"
                    } else {
                        "https://cutt.ly/$fixedUrl"
                    }
                    val req = Request.Builder().url(lookupUrl).build()
                    noRedirectClient.newCall(req).execute().use { resp ->
                        val loc = resp.header("Location") ?: return@runCatching null
                        if (loc.contains("/404") || loc.trimEnd('/') == "https://cutt.ly" || loc.trimEnd('/') == "https://py.md") {
                            null
                        } else {
                            convertRawGitUrl(loc)
                        }
                    }
                }.getOrNull()
            }
            else -> null
        }
    }

    private fun fetchBody(url: String): String? {
        val resolved = convertRawGitUrl(url)
        val request = Request.Builder()
            .url(resolved)
            .header("User-Agent", com.lagradost.cloudstream3.USER_AGENT)
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} fetching $resolved")
                return@use null
            }
            response.body?.string()
        }
    }

    private fun parsePluginListJson(body: String, repositoryUrl: String): List<SitePlugin> {
        val trimmed = body.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        return runCatching {
            APIHolder.mapper.readValue(trimmed, Array<SitePlugin>::class.java)
                .filter { it.url.isNotBlank() && (it.internalName.isNotBlank() || it.name.isNotBlank()) }
                .map { plugin ->
                    val normalizedInternal = plugin.internalName.ifBlank { plugin.name }
                    val normalizedName = plugin.name.ifBlank { normalizedInternal }
                    plugin.copy(
                        internalName = normalizedInternal,
                        name = normalizedName,
                        repositoryUrl = repositoryUrl,
                    )
                }
        }.onFailure {
            Log.e(TAG, "Failed to parse plugin list JSON from $repositoryUrl", it)
        }.getOrElse { emptyList() }
    }

    suspend fun getRepoPlugins(repositoryUrl: String): List<Pair<String, SitePlugin>>? = withContext(Dispatchers.IO) {
        try {
            val resolvedUrl = parseRepoUrl(repositoryUrl) ?: convertRawGitUrl(repositoryUrl)
            val body = fetchBody(resolvedUrl) ?: return@withContext null
            val trimmed = body.trim()

            // Case 1: Direct plugins.json array
            if (trimmed.startsWith("[")) {
                val plugins = parsePluginListJson(trimmed, resolvedUrl)
                return@withContext plugins.map { it.url to it }
            }

            // Case 2: Standard CloudStream repo.json with pluginLists
            val repo = runCatching {
                APIHolder.mapper.readValue(trimmed, Repository::class.java)
            }.getOrNull() ?: return@withContext null

            val allPlugins = mutableListOf<Pair<String, SitePlugin>>()
            for (pluginListUrl in repo.pluginLists) {
                val listBody = runCatching { fetchBody(pluginListUrl) }.getOrNull() ?: continue
                val parsed = parsePluginListJson(listBody, resolvedUrl)
                parsed.forEach { plugin ->
                    allPlugins.add(plugin.url to plugin)
                }
            }
            allPlugins
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get plugins from $repositoryUrl", e)
            null
        }
    }

    suspend fun downloadPluginToFile(
        pluginUrl: String,
        file: File,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(convertRawGitUrl(pluginUrl)).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null

                file.parentFile?.mkdirs()
                file.outputStream().use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
                file
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download plugin $pluginUrl", e)
            null
        }
    }

    fun getRepositories(): Array<RepositoryData> {
        synchronized(repoLock) {
            return DataStore.getPrefs()?.let { prefs ->
                prefs.getString(REPOSITORIES_KEY, null)?.let { json ->
                    runCatching { parseJson<Array<RepositoryData>>(json) }.getOrNull()
                }
            } ?: emptyArray()
        }
    }

    fun addRepository(repository: RepositoryData) {
        synchronized(repoLock) {
            val current = getRepositories().toMutableList()
            val existingIdx = current.indexOfFirst { it.url == repository.url }
            if (existingIdx != -1) {
                current[existingIdx] = repository
            } else {
                current.add(repository)
            }
            DataStore.setKey(REPOSITORIES_KEY, current.toTypedArray())
        }
        notifyReposChanged()
    }

    fun removeRepository(context: Context, repositoryUrl: String) {
        synchronized(repoLock) {
            val current = getRepositories().toMutableList()
            current.removeAll { it.url == repositoryUrl }
            DataStore.setKey(REPOSITORIES_KEY, current.toTypedArray())
        }

        // Unload & delete all plugins belonging to this repository
        val sanitizedFolder = PluginManager.getPluginSanitizedFileName(repositoryUrl)
        val repoPluginDir = File(File(context.filesDir, PluginManager.ONLINE_PLUGINS_FOLDER), sanitizedFolder)
        PluginManager.getPluginsOnline()
            .filter { it.repositoryUrl == repositoryUrl || it.filePath.startsWith(repoPluginDir.absolutePath) }
            .forEach { PluginManager.deletePlugin(it.filePath) }
        repoPluginDir.deleteRecursively()

        notifyReposChanged()
    }

    suspend fun parseRepository(url: String): Repository? = withContext(Dispatchers.IO) {
        val resolvedUrl = parseRepoUrl(url) ?: return@withContext null
        val bodyText = runCatching { fetchBody(resolvedUrl) }.getOrNull()
        if (!bodyText.isNullOrBlank()) {
            val trimmed = bodyText.trim()
            if (trimmed.startsWith("[")) {
                val parsedPlugins = parsePluginListJson(trimmed, resolvedUrl)
                if (parsedPlugins.isNotEmpty()) {
                    val inferredName = resolvedUrl
                        .substringBeforeLast('/')
                        .substringAfterLast('/')
                        .ifBlank { "CloudStream Repo" }
                    return@withContext Repository(
                        name = inferredName,
                        description = "${parsedPlugins.size} plugins",
                        pluginLists = listOf(resolvedUrl),
                    )
                }
            } else if (trimmed.startsWith("{")) {
                val repo = runCatching {
                    APIHolder.mapper.readValue(trimmed, Repository::class.java)
                }.getOrNull()
                if (repo != null && repo.pluginLists.isNotEmpty()) {
                    return@withContext repo.copy(
                        name = repo.name.ifBlank { "CloudStream Repo" },
                    )
                }
            }
        }

        // Fallback: try HTML metadata parsing
        runCatching {
            val doc = Jsoup.connect(resolvedUrl).get()
            val descriptionText = doc.selectFirst("head > meta[name=description]")?.attr("content")
            val titleText = doc.selectFirst("head > title")?.text()?.replace("GitHub - ", "")
            Repository(
                name = titleText?.takeIf { it.isNotBlank() } ?: "CloudStream Repo",
                description = descriptionText,
                pluginLists = listOf(resolvedUrl),
            )
        }.onFailure {
            Log.e(TAG, "Failed to parse repository $resolvedUrl", it)
        }.getOrNull()
    }
}
// ANZ <--
