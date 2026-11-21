/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.kanade.tachiyomi.ui.player

import android.content.Context
import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.utils.Anime4KManager
import `is`.xyz.mpv.MPV
import logcat.LogPriority
import logcat.logcat

// ANZ -->
fun applyFilter(mpv: MPV?, filter: VideoFilters, value: Int, prefs: DecoderPreferences) {
    val property = filter.mpvProperty

    mpv?.setPropertyInt(property, value)
}

fun applyDebandMode(mpv: MPV?, mode: Debanding, prefs: DecoderPreferences) {
    when (mode) {
        Debanding.None -> {
            mpv?.setOptionString("deband", "no")
            mpv?.command("vf", "remove", "@deband")
        }
        Debanding.CPU -> {
            mpv?.setOptionString("deband", "no")
            mpv?.command("vf", "add", "@deband:gradfun=radius=12")
        }
        Debanding.GPU -> {
            mpv?.setOptionString("deband", "yes")
            mpv?.command("vf", "remove", "@deband")
            // Apply current GPU settings
            DebandSettings.entries.forEach {
                mpv?.setPropertyInt(it.mpvProperty, it.preference(prefs).get())
            }
        }
    }
}

fun applyDebandSetting(mpv: MPV?, setting: DebandSettings, value: Int) {
    mpv?.setPropertyInt(setting.mpvProperty, value)
}
// ANZ <--

fun buildVFChain(decoderPreferences: DecoderPreferences): String {
    val filters = mutableListOf<String>() // ANZ
    if (decoderPreferences.useYUV420P().get()) {
        filters.add("format=yuv420p")
    }
    val fpsLimit = decoderPreferences.interpolationFPSLimit().get()
    if (decoderPreferences.smoothMotion().get() && fpsLimit > 0) {
        filters.add("fps=$fpsLimit")
    }
    return filters.joinToString(",")
}

// ANZ -->
fun applyTheme(mpv: MPV?, theme: VideoFilterTheme, prefs: DecoderPreferences) {
    prefs.brightnessFilter().set(theme.brightness)
    prefs.contrastFilter().set(theme.contrast)
    prefs.saturationFilter().set(theme.saturation)
    prefs.gammaFilter().set(theme.gamma)
    prefs.hueFilter().set(theme.hue)
    prefs.sharpenFilter().set(theme.sharpen)

    // Reset deband
    prefs.debandFilter().set(0)
    prefs.grainFilter().set(0)
    prefs.debandThreshold().set(32)
    prefs.debandRange().set(16)

    // Apply direct properties
    mpv?.setPropertyInt("brightness", theme.brightness)
    mpv?.setPropertyInt("contrast", theme.contrast)
    mpv?.setPropertyInt("saturation", theme.saturation)
    mpv?.setPropertyInt("gamma", theme.gamma)
    mpv?.setPropertyInt("hue", theme.hue)
    mpv?.setPropertyInt("sharpen", theme.sharpen)

    // Apply VF chain once
    mpv?.setPropertyString("vf", buildVFChain(prefs))

    // Reset deband engine properties
    mpv?.setPropertyBoolean("deband", false)
    mpv?.setPropertyInt("deband-iterations", 1)
    mpv?.setPropertyInt("deband-threshold", 32)
    mpv?.setPropertyInt("deband-range", 16)
    mpv?.setPropertyInt("deband-grain", 48)
}

fun isAnime4KActive(prefs: DecoderPreferences): Boolean {
    if (!prefs.enableAnime4K().get()) return false
    val mode = try {
        Anime4KManager.Mode.valueOf(prefs.anime4kMode().get())
    } catch (e: Exception) {
        Anime4KManager.Mode.OFF
    }
    return mode != Anime4KManager.Mode.OFF
}

private val customShaderLineRegex = Regex("""^\s*(?:--)?glsl-shaders?(?:-append)?\s*=""", RegexOption.MULTILINE)

fun hasCustomGlslShaders(mpvConf: String): Boolean {
    return customShaderLineRegex.containsMatchIn(mpvConf)
}

fun setAdrenoSafeFboFormat(mpv: MPV?, isInit: Boolean = true, mpvOptionNames: Set<String> = emptySet()) {
    if (mpv == null || "fbo-format" in mpvOptionNames) return
    if (isInit) {
        val rc = mpv.setOptionString("fbo-format", "rgba16hf")
        if (rc < 0) {
            logcat("Anime4K", LogPriority.WARN) { "fbo-format=rgba16hf rejected (rc=$rc), falling back to rgba16f" }
            val fallbackRc = mpv.setOptionString("fbo-format", "rgba16f")
            if (fallbackRc < 0) {
                logcat("Anime4K", LogPriority.WARN) { "fbo-format=rgba16f rejected (rc=$fallbackRc), using auto" }
                mpv.setOptionString("fbo-format", "auto")
            }
        }
    } else {
        runCatching { mpv.setPropertyString("fbo-format", "rgba16hf") }
    }
}

private fun setMpvOptionOrProperty(
    mpv: MPV?,
    name: String,
    value: String,
    isInit: Boolean,
    mpvOptionNames: Set<String> = emptySet(),
) {
    if (mpv == null || name in mpvOptionNames) return
    if (isInit) {
        mpv.setOptionString(name, value)
    } else {
        mpv.setPropertyString(name, value)
    }
}

fun applyAnime4KScalers(
    mpv: MPV?,
    active: Boolean,
    prefs: DecoderPreferences,
    isInit: Boolean,
    mpvOptionNames: Set<String> = emptySet(),
) {
    if (mpv == null) return
    if (active) {
        setMpvOptionOrProperty(mpv, "scale", "ewa_lanczossharp", isInit, mpvOptionNames)
        setMpvOptionOrProperty(mpv, "cscale", "ewa_lanczossharp", isInit, mpvOptionNames)
        setMpvOptionOrProperty(mpv, "dscale", "mitchell", isInit, mpvOptionNames)
        setMpvOptionOrProperty(mpv, "correct-downscaling", "yes", isInit, mpvOptionNames)
        setMpvOptionOrProperty(mpv, "sigmoid-upscaling", "yes", isInit, mpvOptionNames)
        setMpvOptionOrProperty(mpv, "dither", "fruit", isInit, mpvOptionNames)
        setMpvOptionOrProperty(mpv, "gpu-dumb-mode", "no", isInit, mpvOptionNames)
    } else {
        val isHighQuality = prefs.highQualityScaling().get()
        val scaler = if (isHighQuality) "spline36" else "bilinear"
        setMpvOptionOrProperty(mpv, "scale", scaler, isInit, mpvOptionNames)
        setMpvOptionOrProperty(mpv, "cscale", scaler, isInit, mpvOptionNames)
        setMpvOptionOrProperty(mpv, "dscale", scaler, isInit, mpvOptionNames)
        setMpvOptionOrProperty(mpv, "correct-downscaling", "no", isInit, mpvOptionNames)
        setMpvOptionOrProperty(mpv, "sigmoid-upscaling", "no", isInit, mpvOptionNames)
        setMpvOptionOrProperty(mpv, "dither", if (isHighQuality) "fruit" else "no", isInit, mpvOptionNames)
        val dumbMode = if (prefs.smoothMotion().get()) "no" else "auto"
        setMpvOptionOrProperty(mpv, "gpu-dumb-mode", dumbMode, isInit, mpvOptionNames)
    }
}

fun applyAnime4K(
    mpv: MPV?,
    prefs: DecoderPreferences,
    manager: Anime4KManager,
    isInit: Boolean = false,
    mpvView: AniyomiMPVView? = null,
    mpvOptionNames: Set<String> = emptySet(),
    hasCustomShaders: Boolean = false,
) {
    val enabled = prefs.enableAnime4K().get()

    val mode = try {
        Anime4KManager.Mode.valueOf(prefs.anime4kMode().get())
    } catch (e: Exception) {
        Anime4KManager.Mode.OFF
    }
    val quality = try {
        Anime4KManager.Quality.valueOf(prefs.anime4kQuality().get())
    } catch (e: Exception) {
        Anime4KManager.Quality.BALANCED
    }

    // Ensure initialization happened (and refresh updated shader bundle if needed)
    manager.initialize()

    val chain = if (enabled) manager.getShaderChain(mode, quality) else ""
    val shadersActive = chain.isNotEmpty()
    logcat("Anime4K", LogPriority.DEBUG) { "Applying Anime4K chain (enabled=$enabled, mode=$mode, quality=$quality): $chain" }

    if (shadersActive) {
        // Enforce vo=gpu + gpu-api=opengl (never gpu-next) whenever Anime4K is enabled
        if (prefs.gpuNext().get()) {
            logcat("Anime4K", LogPriority.INFO) { "Disabling gpu-next because Anime4K requires vo=gpu + opengl" }
            prefs.gpuNext().set(false)
        }
        mpvView?.setVo("gpu")
        setMpvOptionOrProperty(mpv, "gpu-api", "opengl", isInit, mpvOptionNames)
        if (!isInit) {
            val currentVo = mpv?.getPropertyString("current-vo")
            if (currentVo != null && currentVo != "gpu") {
                mpv.setPropertyString("vo", "gpu")
            }
        }
        setAdrenoSafeFboFormat(mpv, isInit = isInit, mpvOptionNames = mpvOptionNames)

        // Force mediacodec-copy so MediaCodec frames are copied instead of OES SurfaceTexture
        if (prefs.tryHWDecoding().get()) {
            setMpvOptionOrProperty(mpv, "hwdec", "mediacodec-copy", isInit)
        }

        applyAnime4KScalers(mpv, active = true, prefs = prefs, isInit = isInit, mpvOptionNames = mpvOptionNames)
        setMpvOptionOrProperty(mpv, "glsl-shaders", chain, isInit)
    } else {
        setMpvOptionOrProperty(mpv, "glsl-shaders", "", isInit)
        if (!hasCustomShaders) {
            applyAnime4KScalers(mpv, active = false, prefs = prefs, isInit = isInit, mpvOptionNames = mpvOptionNames)
            if (prefs.tryHWDecoding().get()) {
                val needsCopy = prefs.smoothMotion().get() && prefs.interpolationFPSLimit().get() > 0
                setMpvOptionOrProperty(mpv, "hwdec", if (needsCopy) "mediacodec-copy" else "mediacodec", isInit)
            }
        }
    }
}

fun applyInterpolation(mpv: MPV?, prefs: DecoderPreferences, context: Context?) {
    val enabled = prefs.smoothMotion().get()
    val anime4kActive = isAnime4KActive(prefs)
    if (enabled) {
        val fpsLimit = prefs.interpolationFPSLimit().get() // ANZ

        if (prefs.tryHWDecoding().get()) {
            val hwdecMode = if (fpsLimit > 0 || anime4kActive) "mediacodec-copy" else "mediacodec"
            mpv?.setPropertyString("hwdec", hwdecMode)
        }

        mpv?.setPropertyString("vf", buildVFChain(prefs))
        mpv?.setPropertyString("video-sync", "display-resample")
        mpv?.setPropertyString("video-sync-max-audio-change", "0.125")
        mpv?.setPropertyString("video-sync-max-video-change", "1")
        mpv?.setPropertyString("interpolation", "yes")
        mpv?.setPropertyString("interpolation-threshold", "-1")
        mpv?.setPropertyString("correct-pts", "yes")
        mpv?.setPropertyString("tscale", prefs.interpolationMode().get().value)
        mpv?.setPropertyString("gpu-dumb-mode", "no")
    } else {
        if (prefs.tryHWDecoding().get()) {
            val hwdecMode = if (anime4kActive) "mediacodec-copy" else "mediacodec"
            mpv?.setPropertyString("hwdec", hwdecMode)
        }
        mpv?.setPropertyString("vf", buildVFChain(prefs))
        mpv?.setPropertyString("video-sync", "audio")
        mpv?.setPropertyString("video-sync-max-audio-change", "0.125")
        mpv?.setPropertyString("video-sync-max-video-change", "1")
        mpv?.setPropertyString("interpolation", "no")
        mpv?.setPropertyString("gpu-dumb-mode", if (anime4kActive) "no" else "auto")
    }
}
// ANZ <--
