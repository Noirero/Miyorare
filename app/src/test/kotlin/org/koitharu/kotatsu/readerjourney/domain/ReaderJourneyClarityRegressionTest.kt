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
	fun `journey tab reserves actual floating navigation height`() {
		val fragment = source("kotlin/org/koitharu/kotatsu/stats/ui/ReaderJourneyFragment.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(fragment.contains("WindowInsetsCompat.Type.systemBars()"))
		assertTrue(fragment.contains("systemBottomInset.intValue=insets.getInsets"))
		assertTrue(fragment.contains("(requireActivity()as?BottomNavOwner)?.bottomNav?.let"))
		assertTrue(fragment.contains("bottomNavHeight.intValue=nav.height"))
		assertTrue(fragment.contains("valbottomClearance=maxOf(systemBottomInset.intValue,bottomNavHeight.intValue)"))
		assertTrue(fragment.contains("bottomInset=with(density){bottomClearance.toDp()}"))
		assertFalse(fragment.contains("bottomInset=0.dp"))
	}

	@Test
	fun `xp guide stacks long descriptions instead of squeezing two columns`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")
		val row = screen
			.substringAfter("privatefunReaderJourneyXpRuleRow(")
			.substringBefore("privatefunReaderJourneyThemeCard(")

		assertTrue(row.contains("Column("))
		assertTrue(row.contains("MaterialTheme.typography.bodySmall"))
		assertFalse(row.contains("modifier=Modifier.weight(1f)"))
	}


	@Test
	fun `exclusive customizer previews all themes but only applies owned selections`() {
		val customizer = source("kotlin/org/koitharu/kotatsu/stats/ui/ReaderJourneyExclusiveCollection.kt")
			.replace(Regex("\\s+"), "")
			.substringAfter("internalfunReaderJourneyExclusiveCustomizerDialog(")
			.substringBefore("privatefunseedExclusiveCustomLoadout(")

		assertTrue(customizer.contains("valallSpecs=remember(collection){collection.map{it.visualSpec}}"))
		assertTrue(customizer.contains("specs=allSpecs"))
		assertFalse(customizer.contains("specs=unlockedSpecs"))
		assertTrue(
			customizer.contains(
				"valcanApplyDraft=remember(draft,accessRank){ReaderJourneyCosmeticPolicy.sanitizeForRank(draft,accessRank)==draft}",
			),
		)
		assertTrue(customizer.contains("if(canApplyDraft){item(\"apply\")"))
		assertTrue(customizer.contains("item(\"locked-preview\")"))
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
