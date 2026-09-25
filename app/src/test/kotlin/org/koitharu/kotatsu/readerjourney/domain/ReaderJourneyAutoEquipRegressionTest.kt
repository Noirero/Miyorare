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
	fun `auto equip remains off by default and customizer never forces it on`() {
		val journey = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourney.kt")
			.replace(Regex("\\s+"), "")
		val exclusive = source("kotlin/org/koitharu/kotatsu/stats/ui/ReaderJourneyExclusiveCollection.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(journey.contains("autoEquipNewRankTheme:Boolean=false"))
		assertTrue(exclusive.contains("ReaderJourneyCosmeticPolicy.sanitizeForRank("))
		assertTrue(exclusive.contains("loadout.copy("))
		assertFalse(exclusive.contains("autoEquipNewRankTheme=true"))
		assertFalse(exclusive.contains("autoEquipNewRankTheme=enabled"))
	}

	@Test
	fun `rank up celebration offers a non blocking path to preview and customize`() {
		val reader = source("kotlin/org/koitharu/kotatsu/reader/ui/ReaderActivity.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(reader.contains("if(event.isRankUp){snackbar.setAction(R.string.reader_journey_preview)"))
		assertTrue(reader.contains("router.openStatistic()"))
		assertFalse(reader.contains("setCancelable(false)"))
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
