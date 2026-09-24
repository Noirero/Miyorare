package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderJourneyFinalVisualRegressionTest {

	@Test
	fun `year in review remains available when gamification is disabled`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		val overview = screen
			.substringAfter("ReaderJourneySection.OVERVIEW->{")
			.substringBefore("ReaderJourneySection.STATISTICS->")

		assertTrue(overview.contains("item(\"year-in-review\")"))
		val gamificationBlock = overview
			.substringAfter("if(stats.isJourneyEnabled){")
			.substringBefore("item(\"year-in-review\")")
		assertTrue(gamificationBlock.contains("item(\"profile\")"))
		assertFalse(gamificationBlock.contains("YearInReviewCard("))
	}

	@Test
	fun `reader journey supplies semantic foreground for translucent dark glass`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(
			screen.contains(
				"CompositionLocalProvider(LocalContentColorprovidesMaterialTheme.colorScheme.onSurface)",
			),
		)
	}

	@Test
	fun `three section selector uses compact typography instead of ellipsizing large labels`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")
		val selector = screen
			.substringAfter("privatefunReaderJourneySectionSelector(")
			.substringBefore("privatevalReaderJourneySection.titleRes")

		assertTrue(selector.contains("visibleEntries.size>=3"))
		assertTrue(selector.contains("MaterialTheme.typography.labelMedium"))
	}

	@Test
	fun `five item navigation uses compact labels and short journey title`() {
		val navItem = source("kotlin/org/koitharu/kotatsu/core/prefs/NavItem.kt")
			.replace(Regex("\\s+"), "")
		val legacy = source("kotlin/org/koitharu/kotatsu/main/ui/nav/LegacyGlowNavBar.kt")
			.replace(Regex("\\s+"), "")
		val strings = File("src/main/res/values/strings.xml")
			.takeIf(File::isFile)
			?.readText()
			?: File("app/src/main/res/values/strings.xml").readText()

		assertTrue(navItem.contains("READER_JOURNEY(R.id.nav_reader_journey,R.string.reader_journey_nav"))
		assertTrue(strings.contains("name=\"reader_journey_nav\">Journey<"))
		assertTrue(legacy.contains("compactLabel=visibleItems.size>=MAX_LEGACY_ITEMS"))
		assertTrue(legacy.contains("compactLabel->11.sp"))
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
