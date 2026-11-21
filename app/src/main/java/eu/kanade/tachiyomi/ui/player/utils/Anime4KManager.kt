package eu.kanade.tachiyomi.ui.player.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Anime4K Manager
 * Manages GLSL shaders for real-time anime upscaling
 */
class Anime4KManager(private val context: Context) {

    companion object {
        private const val SHADER_DIR = "shaders"

        // ANZ -->
        private const val SHADER_VERSION_FILE = ".shader_version"
        private const val SHADER_BUNDLE_VERSION = "2.6-fix-adreno-v1"
        // ANZ <--
    }

    // Shader quality levels
    enum class Quality(val suffix: String) {
        FAST("S"),
        BALANCED("M"),
        HIGH("L"),
    }

    // Anime4K modes
    enum class Mode {
        OFF,
        A,
        B,
        C,
        A_PLUS,
        B_PLUS,
        C_PLUS,
    }

    private var shaderDir: File? = null
    private var isInitialized = false

    /**
     * Initialize: copy shaders from assets to internal storage
     * This must be called and complete successfully before using getShaderChain()
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            return true
        }

        return try {
            // Create shader directory
            shaderDir = File(context.filesDir, SHADER_DIR)
            if (!shaderDir!!.exists()) {
                val created = shaderDir!!.mkdirs()
                if (!created) {
                    return false
                }
            }

            // ANZ -->
            val versionFile = File(shaderDir, SHADER_VERSION_FILE)
            val needsRefresh = !versionFile.exists() ||
                runCatching { versionFile.readText().trim() }.getOrNull() != SHADER_BUNDLE_VERSION
            // ANZ <--

            // List and copy all shader files from assets
            val shaderFiles = context.assets.list(SHADER_DIR) ?: emptyArray()

            for (fileName in shaderFiles) {
                if (fileName.endsWith(".glsl")) {
                    // ANZ -->
                    copyShaderFromAssets(fileName, overwrite = needsRefresh)
                    // ANZ <--
                }
            }

            // ANZ -->
            if (needsRefresh) {
                runCatching { versionFile.writeText(SHADER_BUNDLE_VERSION) }
            }
            // ANZ <--

            isInitialized = true
            true
        } catch (e: Exception) {
            isInitialized = false
            false
        }
    }

    // ANZ -->
    private fun copyShaderFromAssets(fileName: String, overwrite: Boolean = false): Boolean {
        val destFile = File(shaderDir, fileName)

        // Skip only if file already exists, is valid, and bundle version matches
        if (!overwrite && destFile.exists() && destFile.length() > 0) {
            return false
        }
        // ANZ <--

        return try {
            context.assets.open("$SHADER_DIR/$fileName").use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get shader chain for the specified mode and quality
     * Returns empty string if mode is OFF or initialization failed
     */
    fun getShaderChain(mode: Mode, quality: Quality): String {
        if (mode == Mode.OFF) {
            return ""
        }

        if (!isInitialized) {
            initialize() // Try to initialize if not yet done
        }

        if (shaderDir == null || !shaderDir!!.exists()) {
            return ""
        }

        val shaders = mutableListOf<String>()
        // ANZ -->
        val primary = quality.suffix
        // FAST uses single lightweight x2 CNN pass (no double-stacking) for smooth tablet playback.
        // BALANCED pairs M -> S and HIGH pairs L -> M so 480p->1440p stays crisp without heavy L+L stacking.
        val secondary: String? = when (quality) {
            Quality.FAST -> null
            Quality.BALANCED -> "S"
            Quality.HIGH -> "M"
        }

        // Always add Clamp_Highlights (prevent ringing)
        shaders.add(getShaderPath("Anime4K_Clamp_Highlights.glsl"))

        // Add shaders based on mode
        when (mode) {
            Mode.A -> {
                // Mode A (720p modern anime): Restore -> Upscale (-> optional 2nd Upscale for <=480p)
                shaders.add(getShaderPath("Anime4K_Restore_CNN_$primary.glsl"))
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$primary.glsl"))
                if (secondary != null) {
                    shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
                    shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$secondary.glsl"))
                }
            }
            Mode.B -> {
                // Mode B (720p/older anime): Restore_Soft -> Upscale (-> optional 2nd Upscale)
                shaders.add(getShaderPath("Anime4K_Restore_CNN_Soft_$primary.glsl"))
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$primary.glsl"))
                if (secondary != null) {
                    shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
                    shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$secondary.glsl"))
                }
            }
            Mode.C -> {
                // Mode C (480p compressed streams): Upscale_Denoise (-> optional 2nd Upscale)
                shaders.add(getShaderPath("Anime4K_Upscale_Denoise_CNN_x2_$primary.glsl"))
                if (secondary != null) {
                    shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
                    shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$secondary.glsl"))
                }
            }
            Mode.A_PLUS -> {
                // Mode A+A: Restore -> Upscale -> Restore (-> optional 2nd Upscale)
                shaders.add(getShaderPath("Anime4K_Restore_CNN_$primary.glsl"))
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$primary.glsl"))
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
                shaders.add(getShaderPath("Anime4K_Restore_CNN_${secondary ?: "S"}.glsl"))
                if (secondary != null) {
                    shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$secondary.glsl"))
                }
            }
            Mode.B_PLUS -> {
                // Mode B+B: Restore_Soft -> Upscale -> Restore_Soft (-> optional 2nd Upscale)
                shaders.add(getShaderPath("Anime4K_Restore_CNN_Soft_$primary.glsl"))
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$primary.glsl"))
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
                shaders.add(getShaderPath("Anime4K_Restore_CNN_Soft_${secondary ?: "S"}.glsl"))
                if (secondary != null) {
                    shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$secondary.glsl"))
                }
            }
            Mode.C_PLUS -> {
                // Mode C+A: Upscale_Denoise -> Restore (-> optional 2nd Upscale)
                shaders.add(getShaderPath("Anime4K_Upscale_Denoise_CNN_x2_$primary.glsl"))
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
                shaders.add(getShaderPath("Anime4K_Restore_CNN_${secondary ?: "S"}.glsl"))
                if (secondary != null) {
                    shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$secondary.glsl"))
                }
            }
            Mode.OFF -> {}
        }
        // ANZ <--

        // Validate that all shader files exist
        val missingShaders = shaders.filter { path ->
            !File(path).exists()
        }

        if (missingShaders.isNotEmpty()) {
            return ""
        }

        // Join with colon separator
        return shaders.joinToString(":")
    }

    private fun getShaderPath(fileName: String): String {
        return File(shaderDir, fileName).absolutePath
    }
}
