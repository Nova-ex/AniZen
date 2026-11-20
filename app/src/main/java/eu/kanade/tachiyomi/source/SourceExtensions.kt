package eu.kanade.tachiyomi.source

import android.graphics.drawable.Drawable
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cloudstream.isCloudStreamSourceId
import eu.kanade.tachiyomi.extension.ExtensionManager
import tachiyomi.domain.remoteserver.model.isRemoteServerSourceId
import tachiyomi.domain.source.model.StubSource
import tachiyomi.source.localanime.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

fun Source.icon(): Drawable? = Injekt.get<ExtensionManager>().getAppIconForSource(this.id)

fun Source.getPreferenceKey(): String = "source_$id"

fun Source.toStubSource(): StubSource = StubSource(id = id, lang = lang, name = name)

fun Source.getNameForAnimeInfo(): String {
    val preferences = Injekt.get<SourcePreferences>()
    val enabledLanguages = preferences.enabledLanguages().get()
        .filterNot { it in listOf("all", "other") }
    val hasOneActiveLanguages = enabledLanguages.size == 1
    val isInEnabledLanguages = lang in enabledLanguages
    return when {
        // For edge cases where user disables a source they got manga of in their library.
        hasOneActiveLanguages && !isInEnabledLanguages -> toString()
        // Hide the language tag when only one language is used.
        hasOneActiveLanguages && isInEnabledLanguages -> name
        else -> toString()
    }
}

fun Source.isLocalOrStub(): Boolean = isLocal() || this is StubSource

// AM (DISCORD) -->
fun Source?.isNsfw(): Boolean {
    // ANZ -->
    if (this == null || this.isLocalOrStub() || this.id.isRemoteServerSourceId() || this.id.isCloudStreamSourceId()) {
        return false
    }
    val sourceUsed = Injekt.get<ExtensionManager>().installedExtensionsFlow.value
        .find { ext -> ext.sources.any { it.id == this.id } } ?: return false
    return sourceUsed.isNsfw
    // ANZ <--
}
// <-- AM (DISCORD)

// (TORRENT) -->
fun Source?.isSourceForTorrents(): Boolean {
    // ANZ -->
    if (this == null || this.isLocalOrStub() || this.id.isRemoteServerSourceId() || this.id.isCloudStreamSourceId()) {
        return false
    }
    // ANZ <--
    val sourceUsed = Injekt.get<ExtensionManager>().installedExtensionsFlow.value
        .find { it.sources.any { s -> s.id == this.id } } ?: return false
    return sourceUsed.isTorrent
}
// <-- (TORRENT)
