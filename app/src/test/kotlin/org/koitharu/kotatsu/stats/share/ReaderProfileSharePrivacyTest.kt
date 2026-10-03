package org.koitharu.kotatsu.stats.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode
import org.koitharu.kotatsu.readerjourney.domain.ReaderRank
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.stats.domain.ReaderProfileShareModel
import java.io.File

class ReaderProfileSharePrivacyTest {

	@Test
	fun `auto share identity follows Lifetime XP derived rank`() {
		val model = ReaderProfileShareModel.from(
			lifetimeXp = 0L,
			loadout = ReaderJourneyCosmeticLoadout(mode = ReaderJourneyCosmeticMode.AUTO),
		)

		assertEquals(ReaderRank.NEWCOMER, model.rank)
		assertEquals(1, model.level)
		assertEquals(0L, model.lifetimeXp)
		assertEquals(RankThemeId.FIRST_PAGE, model.theme)
	}

	@Test
	fun `Miyorare Default share omits rank theme identity`() {
		val model = ReaderProfileShareModel.from(
			lifetimeXp = 0L,
			loadout = ReaderJourneyCosmeticLoadout(mode = ReaderJourneyCosmeticMode.DEFAULT),
		)

		assertNull(model.theme)
	}

	@Test
	fun `locked persisted theme cannot be exported as owned identity`() {
		val model = ReaderProfileShareModel.from(
			lifetimeXp = 0L,
			loadout = ReaderJourneyCosmeticLoadout(
				mode = ReaderJourneyCosmeticMode.FULL_SET,
				selectedThemeId = RankThemeId.ETERNAL_LIBRARY.stableId,
			),
		)

		assertNotEquals(RankThemeId.ETERNAL_LIBRARY, model.theme)
		assertEquals(ReaderRank.NEWCOMER, model.rank)
	}

	@Test
	fun `share model is an aggregate-only whitelist independent of mature include mode`() {
		val model = source("kotlin/org/koitharu/kotatsu/stats/domain/ReaderProfileShareModel.kt")
		val renderer = source("kotlin/org/koitharu/kotatsu/stats/share/ReaderProfileShareCard.kt")
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(model.contains("val rank: ReaderRank"))
		assertTrue(model.contains("val level: Int"))
		assertTrue(model.contains("val lifetimeXp: Long"))
		assertTrue(model.contains("val theme: RankThemeId?"))
		assertFalse(model.contains("StatsMatureMode"))
		assertFalse(model.contains("Manga"))
		assertFalse(model.contains("StatsRecord"))
		assertFalse(model.contains("displayName:"))
		assertFalse(model.contains("selectedTitle:"))
		assertFalse(model.contains("showcase:"))
		assertFalse(renderer.contains("import org.koitharu.kotatsu.core.model.Manga"))
		assertFalse(renderer.contains("StatsMatureMode"))
		assertFalse(renderer.contains("StatsRecord"))
		assertTrue(screen.contains("ReaderProfileShareModel.from(stats.lifetimeXp,profile.cosmetics)"))
	}

	@Test
	fun `share path is explicit and uses the safe model in both Reader Journey hosts`() {
		val activity = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsActivity.kt")
		val fragment = source("kotlin/org/koitharu/kotatsu/stats/ui/ReaderJourneyFragment.kt")

		assertTrue(activity.contains("ReaderProfileShareCard.renderToShareUri"))
		assertTrue(fragment.contains("ReaderProfileShareCard.renderToShareUri"))
		assertTrue(activity.contains("onShareReaderProfile = ::shareReaderProfile"))
		assertTrue(fragment.contains("onShareReaderProfile = ::shareReaderProfile"))
	}

	private fun source(relativePath: String): String {
		return sequenceOf(
			File("src/main", relativePath),
			File("app/src/main", relativePath),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find production source: $relativePath")
	}
}
