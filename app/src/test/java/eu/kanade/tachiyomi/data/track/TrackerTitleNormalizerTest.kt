// ANZ -->
package eu.kanade.tachiyomi.data.track

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackerTitleNormalizerTest {

    @Test
    fun `strips Arabic streaming tags around English anime title`() {
        TrackerTitleNormalizer.normalizeForAutoMatch(
            "انمي One Piece الموسم الأول الحلقة 1000 مترجم اون لاين",
        ) shouldBe "One Piece"
    }

    @Test
    fun `strips Arabic movie prefix and dubbed suffix`() {
        TrackerTitleNormalizer.normalizeForAutoMatch(
            "فيلم Demon Slayer: Mugen Train مدبلج كامل HD",
        ) shouldBe "Demon Slayer: Mugen Train"
    }

    @Test
    fun `strips Arabic season and episode numbers with brackets`() {
        TrackerTitleNormalizer.normalizeForAutoMatch(
            "Attack on Titan (الموسم 4) - مترجم",
        ) shouldBe "Attack on Titan"
    }

    @Test
    fun `leaves non-Arabic titles completely untouched`() {
        TrackerTitleNormalizer.normalizeForAutoMatch(
            "Frieren: Beyond Journey's End",
        ) shouldBe "Frieren: Beyond Journey's End"
    }

    @Test
    fun `preserves MyAnimeList special query prefixes`() {
        TrackerTitleNormalizer.normalizeForAutoMatch("id:52991") shouldBe "id:52991"
        TrackerTitleNormalizer.normalizeForAutoMatch("my:Naruto مترجم") shouldBe "my:Naruto مترجم"
    }

    @Test
    fun `falls back safely when title only contains Arabic tag words`() {
        TrackerTitleNormalizer.normalizeForAutoMatch("فيلم مترجم") shouldBe "فيلم مترجم"
    }
}
// ANZ <--
