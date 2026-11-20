// ANZ -->
package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.DataStore
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object PluginManager {
    const val ONLINE_PLUGINS_FOLDER = "Plugins"
    const val PLUGINS_KEY = "PLUGINS_KEY"
    const val PLUGIN_TAG = "PluginManager"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val stateLock = Any()

    val plugins: MutableMap<String, BasePlugin> = ConcurrentHashMap()

    private val _pluginsChangedFlow = MutableStateFlow(0L)
    val pluginsChangedFlow: StateFlow<Long> = _pluginsChangedFlow.asStateFlow()

    val urlPlugins = lazy {
        getPluginsOnline().map { it.internalName }
    }

    data class PluginData(
        @JsonProperty("internalName") val internalName: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("tvTypes") val tvTypes: List<String>? = null,
        @JsonProperty("version") val version: Int = 1,
        @JsonProperty("url") val url: String = "",
        @JsonProperty("iconUrl") val iconUrl: String? = null,
        @JsonProperty("filePath") val filePath: String = "",
        @JsonProperty("isDisabled") val isDisabled: Boolean = false,
        @JsonProperty("isDownloaded") val isDownloaded: Boolean = true,
        @JsonProperty("repositoryUrl") val repositoryUrl: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("description") val description: String? = null,
    )

    data class OnlinePluginData(
        val savedData: PluginData,
        val onlineData: Pair<String, SitePlugin>,
    )

    private fun notifyPluginsChanged() {
        _pluginsChangedFlow.value = System.nanoTime()
    }

    fun getPluginsOnline(): Array<PluginData> {
        synchronized(stateLock) {
            return DataStore.getPrefs()?.let { prefs ->
                prefs.getString(PLUGINS_KEY, null)?.let { json ->
                    runCatching { parseJson<Array<PluginData>>(json) }.getOrNull()
                }
            } ?: emptyArray()
        }
    }

    private fun setPluginsOnline(plugins: Array<PluginData>) {
        synchronized(stateLock) {
            DataStore.setKey(PLUGINS_KEY, plugins)
        }
        notifyPluginsChanged()
    }

    fun updatePluginData(data: PluginData) {
        synchronized(stateLock) {
            val current = getPluginsOnline().toMutableList()
            val index = current.indexOfFirst {
                it.internalName == data.internalName &&
                    (it.repositoryUrl.isNullOrBlank() || data.repositoryUrl.isNullOrBlank() || it.repositoryUrl == data.repositoryUrl)
            }
            if (index != -1) {
                current[index] = data
            } else {
                current.add(data)
            }
            DataStore.setKey(PLUGINS_KEY, current.toTypedArray())
        }
        notifyPluginsChanged()
    }

    private fun deletePluginData(filePath: String, internalName: String? = null) {
        synchronized(stateLock) {
            val current = getPluginsOnline().toMutableList()
            current.removeAll {
                it.filePath == filePath || (internalName != null && it.internalName == internalName)
            }
            DataStore.setKey(PLUGINS_KEY, current.toTypedArray())
        }
        notifyPluginsChanged()
    }

    fun getPluginSanitizedFileName(url: String): String {
        return url.replace(Regex("""[^a-zA-Z0-9.\-]"""), "_")
    }

    fun getPluginPath(
        context: Context,
        internalName: String,
        repositoryUrl: String,
    ): File {
        val folderName = getPluginSanitizedFileName(repositoryUrl.ifBlank { "local" })
        val fileName = getPluginSanitizedFileName(internalName)
        return File("${context.filesDir}/$ONLINE_PLUGINS_FOLDER/$folderName/$fileName.cs3")
    }

    suspend fun downloadAndInstallPlugin(
        context: Context,
        sitePlugin: SitePlugin,
    ): Result<PluginData> = withContext(Dispatchers.IO) {
        runCatching {
            val repoUrl = sitePlugin.repositoryUrl.orEmpty()
            val targetFile = getPluginPath(context, sitePlugin.internalName, repoUrl)
            val resolvedUrl = RepositoryManager.convertRawGitUrl(sitePlugin.url)

            // Unload existing version before replacing the .cs3 file
            if (plugins.containsKey(targetFile.absolutePath)) {
                unloadPlugin(targetFile.absolutePath)
            }

            val request = Request.Builder().url(resolvedUrl).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code} downloading ${sitePlugin.name}")
                }
                val body = response.body ?: error("Empty response downloading ${sitePlugin.name}")

                targetFile.parentFile?.mkdirs()
                val tempFile = File.createTempFile("cs3_dl_", ".tmp", context.cacheDir)
                try {
                    tempFile.outputStream().use { output ->
                        body.byteStream().use { input ->
                            input.copyTo(output)
                        }
                    }

                    val expectedHash = sitePlugin.fileHash
                    if (!expectedHash.isNullOrBlank()) {
                        val actualHash = sha256(tempFile)
                        if (!expectedHash.equals(actualHash, ignoreCase = true)) {
                            error("SHA-256 mismatch for ${sitePlugin.name}")
                        }
                    }

                    if (targetFile.exists()) {
                        targetFile.setWritable(true)
                        targetFile.delete()
                    }
                    if (!tempFile.renameTo(targetFile)) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                } catch (e: Throwable) {
                    tempFile.delete()
                    throw e
                }
            }

            val pluginData = PluginData(
                internalName = sitePlugin.internalName,
                name = sitePlugin.name.ifBlank { sitePlugin.internalName },
                tvTypes = sitePlugin.tvTypes,
                version = sitePlugin.version,
                url = sitePlugin.url,
                iconUrl = sitePlugin.iconUrl,
                filePath = targetFile.absolutePath,
                isDisabled = false,
                isDownloaded = true,
                repositoryUrl = repoUrl,
                language = sitePlugin.language,
                description = sitePlugin.description,
            )
            updatePluginData(pluginData)

            val loaded = loadPlugin(context, targetFile)
            if (!loaded) {
                error("Downloaded ${sitePlugin.name} but failed to load .cs3 plugin")
            }

            pluginData
        }
    }

    suspend fun downloadPlugin(
        context: Context,
        pluginUrl: String,
        internalName: String,
        repositoryUrl: String,
    ): Boolean {
        return downloadPlugin(
            context,
            pluginUrl,
            internalName,
            getPluginPath(context, internalName, repositoryUrl),
        )
    }

    suspend fun downloadPlugin(
        context: Context,
        pluginUrl: String,
        internalName: String,
        file: File,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(RepositoryManager.convertRawGitUrl(pluginUrl)).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false

                if (plugins.containsKey(file.absolutePath)) {
                    unloadPlugin(file.absolutePath)
                }

                file.parentFile?.mkdirs()
                if (file.exists()) {
                    file.setWritable(true)
                }
                file.outputStream().use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }

            Log.i(PLUGIN_TAG, "Downloaded plugin to ${file.absolutePath}")
            val pluginData = getPluginsOnline().firstOrNull { it.internalName == internalName }
            if (pluginData != null) {
                updatePluginData(pluginData.copy(isDownloaded = true, filePath = file.absolutePath))
            }

            loadPlugin(context, file)
        } catch (e: Exception) {
            Log.e(PLUGIN_TAG, "Failed to download plugin $internalName", e)
            false
        }
    }

    suspend fun loadAllPlugins(context: Context): Int = withContext(Dispatchers.IO) {
        var loadedCount = 0
        val savedPlugins = getPluginsOnline()
        val seenPaths = mutableSetOf<String>()

        for (data in savedPlugins) {
            if (data.isDisabled || !data.isDownloaded || data.filePath.isBlank()) continue
            val file = File(data.filePath)
            if (file.exists()) {
                seenPaths.add(file.absolutePath)
                if (plugins.containsKey(file.absolutePath) || loadPlugin(context, file)) {
                    loadedCount++
                }
            }
        }

        // Also scan Plugins folder for any untracked .cs3 files
        val pluginsRoot = File(context.filesDir, ONLINE_PLUGINS_FOLDER)
        if (pluginsRoot.exists()) {
            pluginsRoot.walkTopDown()
                .filter { it.isFile && it.extension.equals("cs3", ignoreCase = true) }
                .forEach { file ->
                    if (file.absolutePath !in seenPaths && !plugins.containsKey(file.absolutePath)) {
                        val disabled = savedPlugins.any { it.filePath == file.absolutePath && it.isDisabled }
                        if (!disabled && loadPlugin(context, file)) {
                            loadedCount++
                        }
                    }
                }
        }

        notifyPluginsChanged()
        loadedCount
    }

    suspend fun loadPlugin(context: Context, file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            Log.w(PLUGIN_TAG, "Plugin file ${file.absolutePath} does not exist.")
            return@withContext false
        }
        val filePath = file.absolutePath

        val pluginData = getPluginsOnline().firstOrNull { it.filePath == filePath }
        if (pluginData?.isDisabled == true || pluginData?.isDownloaded == false) {
            Log.w(PLUGIN_TAG, "Plugin $filePath is disabled or not fully downloaded.")
            return@withContext false
        }

        try {
            if (plugins.containsKey(filePath)) {
                unloadPlugin(filePath)
            }

            Log.i(PLUGIN_TAG, "Loading plugin via DexClassLoader from $filePath")
            // Required on Android 14+ (API 34+) to avoid SecurityException from dynamic code loading
            file.setReadOnly()

            val dexOutputDir = File(context.codeCacheDir, "cs3_dex").apply { mkdirs() }
            val loader = DexClassLoader(
                filePath,
                dexOutputDir.absolutePath,
                null,
                PluginManager::class.java.classLoader,
            )

            val manifestUrl = loader.getResource("manifest.json")
            if (manifestUrl == null) {
                Log.e(PLUGIN_TAG, "Plugin ${file.name} does not contain manifest.json")
                return@withContext false
            }

            val manifestText = manifestUrl.openStream().bufferedReader().use { it.readText() }
            val manifest = parseManifest(manifestText)

            if (manifest.pluginClassName.isBlank()) {
                Log.e(PLUGIN_TAG, "Plugin ${file.name} has empty pluginClassName in manifest.json")
                return@withContext false
            }

            Log.i(PLUGIN_TAG, "Parsed manifest: ${manifest.name} v${manifest.version} (${manifest.pluginClassName})")

            @Suppress("UNCHECKED_CAST")
            val pluginClass = loader.loadClass(manifest.pluginClassName) as Class<out BasePlugin>
            val pluginInstance: BasePlugin = pluginClass.getDeclaredConstructor().newInstance()

            plugins[filePath] = pluginInstance
            pluginInstance.filename = filePath

            if (pluginInstance is Plugin) {
                try {
                    val assetManager = android.content.res.AssetManager::class.java.getDeclaredConstructor().newInstance()
                    val addAssetPath = android.content.res.AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                    addAssetPath.invoke(assetManager, filePath)
                    @Suppress("DEPRECATION")
                    val res = android.content.res.Resources(
                        assetManager,
                        context.resources.displayMetrics,
                        context.resources.configuration,
                    )
                    pluginInstance.resources = res
                } catch (e: Throwable) {
                    Log.w(PLUGIN_TAG, "Failed to attach plugin resources for $filePath: ${e.message}")
                }

                val loadContext = resolveAppCompatActivity(context) ?: context
                pluginInstance.load(loadContext)
            } else {
                pluginInstance.load()
            }

            if (pluginData == null) {
                updatePluginData(
                    PluginData(
                        internalName = manifest.name.ifBlank { file.nameWithoutExtension },
                        name = manifest.name.ifBlank { file.nameWithoutExtension },
                        tvTypes = manifest.tvTypes,
                        version = manifest.version,
                        url = "",
                        iconUrl = null,
                        filePath = filePath,
                        isDisabled = false,
                        isDownloaded = true,
                    ),
                )
            } else {
                notifyPluginsChanged()
            }

            true
        } catch (e: Throwable) {
            Log.e(PLUGIN_TAG, "Error loading plugin $filePath", e)
            plugins.remove(filePath)
            false
        }
    }

    private fun parseManifest(manifestText: String): BasePlugin.Manifest {
        return try {
            Gson().fromJson(manifestText, BasePlugin.Manifest::class.java)
                ?: throw IllegalStateException("Null manifest")
        } catch (_: Throwable) {
            val json = org.json.JSONObject(manifestText)
            val tvTypesList = mutableListOf<String>()
            val tvTypesArray = json.optJSONArray("tvTypes")
            if (tvTypesArray != null) {
                for (i in 0 until tvTypesArray.length()) {
                    tvTypesList.add(tvTypesArray.getString(i))
                }
            }
            BasePlugin.Manifest().apply {
                name = json.optString("name", "")
                pluginClassName = json.optString("pluginClassName", "")
                version = json.optInt("version", 0)
                requiresResources = json.optBoolean("requiresResources", false)
                tvTypes = tvTypesList
            }
        }
    }

    private fun resolveAppCompatActivity(context: Context): AppCompatActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is AppCompatActivity) return current
            current = current.baseContext
        }
        return MainActivity.activity as? AppCompatActivity
    }

    fun unloadPlugin(filePath: String) {
        val plugin = plugins.remove(filePath)
        if (plugin != null) {
            runCatching { plugin.beforeUnload() }
            synchronized(APIHolder.allProviders) {
                APIHolder.allProviders.removeAll { it.sourcePlugin == filePath }
            }
            synchronized(APIHolder.apis) {
                APIHolder.apis.removeAll { it.sourcePlugin == filePath }
            }
            synchronized(com.lagradost.cloudstream3.utils.extractorApis) {
                com.lagradost.cloudstream3.utils.extractorApis.removeAll { it.sourcePlugin == filePath }
            }
            Log.i(PLUGIN_TAG, "Unloaded plugin from $filePath")
            notifyPluginsChanged()
        }
    }

    fun deletePlugin(filePath: String): Boolean {
        val file = File(filePath)
        return try {
            unloadPlugin(filePath)
            val saved = getPluginsOnline().firstOrNull { it.filePath == filePath }
            if (file.exists()) {
                file.setWritable(true)
                file.delete()
            }
            deletePluginData(filePath, saved?.internalName)
            true
        } catch (e: Exception) {
            Log.e(PLUGIN_TAG, "Failed to delete plugin $filePath", e)
            false
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var read = fis.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = fis.read(buffer)
            }
        }
        return "sha256-" + digest.digest().joinToString("") { "%02x".format(it) }
    }
}

class AppCompatActivityWrapper(base: Context) : AppCompatActivity() {
    init {
        attachBaseContext(base)
    }

    override fun getSupportFragmentManager(): androidx.fragment.app.FragmentManager {
        val hostActivity = (baseContext as? androidx.fragment.app.FragmentActivity)
            ?: (baseContext as? ContextWrapper)?.baseContext as? androidx.fragment.app.FragmentActivity
        if (hostActivity != null) {
            return hostActivity.supportFragmentManager
        }
        return super.getSupportFragmentManager()
    }

    override fun isFinishing(): Boolean {
        val hostActivity = (baseContext as? Activity)
            ?: (baseContext as? ContextWrapper)?.baseContext as? Activity
        return hostActivity?.isFinishing ?: super.isFinishing()
    }

    override fun isDestroyed(): Boolean {
        val hostActivity = (baseContext as? Activity)
            ?: (baseContext as? ContextWrapper)?.baseContext as? Activity
        return hostActivity?.isDestroyed ?: super.isDestroyed()
    }

    override fun getApplicationContext(): Context = baseContext.applicationContext

    override fun getResources(): android.content.res.Resources = baseContext.resources

    override fun getAssets(): android.content.res.AssetManager = baseContext.assets

    override fun getTheme(): android.content.res.Resources.Theme = baseContext.theme

    override fun getPackageName(): String = baseContext.packageName

    override fun getPackageManager(): android.content.pm.PackageManager = baseContext.packageManager

    override fun getClassLoader(): ClassLoader = baseContext.classLoader

    override fun getSystemService(name: String): Any? = baseContext.getSystemService(name)
}
// ANZ <--
