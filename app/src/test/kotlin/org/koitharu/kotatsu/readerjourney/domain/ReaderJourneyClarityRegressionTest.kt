package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderJourneyClarityRegressionTest {

	@Test
	fun `overview explains verified progression without mixing period active days`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")
		val grid = screen
			.substringAfter("privatefunReaderJourneyOverviewGrid(")
			.substringBefore("privatefunReaderJourneyOverviewMetric(")

		assertTrue(grid.contains("reader_journey_profile_verified_titles"))
		assertTrue(grid.contains("reader_journey_profile_verified_chapters"))
		assertTrue(grid.contains("reader_journey_profile_verified_breakdown"))
		assertTrue(grid.contains("stats.journeyMangaChapters"))
		assertTrue(grid.contains("stats.journeyNovelChapters"))
		assertTrue(grid.contains("reader_journey_profile_xp_to_level"))
		assertFalse(grid.contains("stats.activeDays"))
	}

	@Test
	fun `xp guide mirrors current verified award rules`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")
		val guide = screen
			.substringAfter("privatefunReaderJourneyXpGuideCard(")
			.substringBefore("privatefunReaderJourneyXpRuleRow(")

		assertTrue(guide.contains("ReaderJourneyRules.MANGA_COMPLETION_XP"))
		assertTrue(guide.contains("ReaderJourneyRules.novelCompletionXp(0)"))
		assertTrue(guide.contains("ReaderJourneyRules.REREAD_XP"))
		assertTrue(guide.contains("ReaderJourneyRules.MAX_REREAD_AWARDS"))
	}

	@Test
	fun `weekly completion header never renders progress above its three task target`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(
			screen.contains(
				"snapshot.completedTaskCount.coerceAtMost(ReaderJourneyRules.WEEKLY_TASKS_FOR_BONUS).toString()+\"/\"+ReaderJourneyRules.WEEKLY_TASKS_FOR_BONUS",
			),
		)
	}

	@Test
	fun `XP history uses ledger context instead of misleading generic labels`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(screen.contains("\"DIVERSE_5\"->\"5chapter·beberapajudul\""))
		assertTrue(screen.contains("\"Weekly·\"+weeklyTaskTitle(task)"))
		assertTrue(screen.contains("\"Achievement·\"+stringResource(achievement.titleRes)"))
	}

	@Test
	fun `completion feedback does not truncate XP source breakdown`() {
		val reader = source("kotlin/org/koitharu/kotatsu/reader/ui/ReaderActivity.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(reader.contains("valdetail=breakdownParts.joinToString(\"·\")"))
		assertFalse(reader.contains("breakdownParts.take(3)"))
	}

	@Test
	fun `no loss XP floor is explained separately from earned event history`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")
		val progression = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourneyProgression.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(progression.contains("preservedXp=dao.getProfile()?.xpFloorAdjustment?:0L"))
		assertTrue(screen.contains("preservedXp=progression.preservedXp"))
		assertTrue(screen.contains("Progresdipertahankan"))
		assertTrue(screen.contains("InibukanXPbaru"))
	}

	@Test
	fun `rank one hundred does not render next level quarter milestones`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(
			screen.contains(
				"if(progress.xpForNextLevel!=null){ReaderJourneyProgressMilestones(progress.levelFraction)}",
			),
		)
	}

	@Test
	fun `annual summary uses explicit annual labels and theme stays secondary`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")
		val overview = screen
			.substringAfter("ReaderJourneySection.OVERVIEW->{")
			.substringBefore("ReaderJourneySection.STATISTICS->")

		assertTrue(overview.contains("item(\"journey-xp-guide\")"))
		assertTrue(overview.indexOf("item(\"year-in-review\")") < overview.indexOf("item(\"journey-current-theme\")"))
		assertTrue(screen.contains("reader_journey_year_statistics_label"))
		assertTrue(screen.contains("reader_journey_year_manga_chapters"))
		assertTrue(screen.contains("reader_journey_year_novel_chapters"))
	}

	@Test
	fun `indonesian journey labels avoid mixed english dashboard copy`() {
		val strings = File("src/main/res/values-in/strings.xml")
			.takeIf(File::isFile)
			?.readText()
			?: File("app/src/main/res/values-in/strings.xml").readText()

		assertTrue(strings.contains("name=\"stats_read_time\">Waktu membaca<"))
		assertTrue(strings.contains("name=\"stats_reading_heatmap\">Peta Aktivitas Membaca<"))
		assertTrue(strings.contains("name=\"reader_journey_active_title_label\">Gelar Aktif<"))
		assertTrue(strings.contains("name=\"reader_journey_xp_guide_title\">Cara mendapatkan XP<"))
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
