package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderJourneyAutoEquipRegressionTest {

	@Test
	fun `auto equip is opt in and only runs on a real rank transition`() {
		val collector = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourneyCollector.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(collector.contains("if(after.level>before.level){"))
		assertTrue(collector.contains("if(after.rank.minLevel>before.rank.minLevel){"))
		assertTrue(collector.contains("if(loadout.autoEquipNewRankTheme){"))
		assertTrue(collector.contains("ReaderJourneyCosmeticPolicy.equipFullSet("))
		assertTrue(collector.contains("theme=RankThemeId.forRank(after.rank)"))
		assertFalse(collector.contains("autoEquipNewRankTheme=true"))
	}

	@Test
	fun `auto equip preference is user controlled in Collection and remains off by default`() {
		val journey = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourney.kt")
			.replace(Regex("\\s+"), "")
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(journey.contains("autoEquipNewRankTheme:Boolean=false"))
		assertTrue(screen.contains("checked=draft.autoEquipNewRankTheme"))
		assertTrue(screen.contains("draft=draft.copy(autoEquipNewRankTheme=enabled)"))
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
