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

		assertTrue(navItem.contains("UPDATED(R.id.nav_updated,R.string.updated,R.drawable.ic_updated_selector,R.string.updated_nav)"))
		assertTrue(navItem.contains("READER_JOURNEY(R.id.nav_reader_journey,R.string.reader_journey,R.drawable.ic_auto_stories,R.string.reader_journey_nav)"))
		assertTrue(strings.contains("name=\"reader_journey_nav\">Journey<"))
		assertTrue(strings.contains("name=\"updated_nav\">Updates<"))
		val navView = source("kotlin/org/koitharu/kotatsu/core/ui/widgets/FloatingBottomNavigationView.kt")
			.replace(Regex("\\s+"), "")
		assertTrue(navView.contains("titleRes=item.navTitle"))
		assertTrue(legacy.contains("compactLabel=visibleItems.size>=MAX_LEGACY_ITEMS"))
		assertTrue(legacy.contains("compactLabel->11.sp"))
	}

	@Test
	fun `exclusive rank theme refreshes legacy activity chrome when runtime changes`() {
		val baseActivity = source("kotlin/org/koitharu/kotatsu/core/ui/BaseActivity.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(baseActivity.contains("observeExclusiveRankThemeChanges(settings)"))
		assertTrue(baseActivity.contains("runtime.state.collect{currentState->"))
		assertTrue(baseActivity.contains("currentState.ledgerReady&&settings.isRankThemeEnabled"))
		assertTrue(baseActivity.contains("ActivityCompat.recreate(this@BaseActivity)"))
	}

	@Test
	fun `profile rank identity and collection use collectible visual primitives`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(screen.contains("valrankBadgeSpec=remember(progress.rank)"))
		assertTrue(screen.contains("ReferenceRankThemeBadge(spec=rankBadgeSpec"))
		val collection = screen
			.substringAfter("privatefunRankThemeCollectionCard(")
			.substringBefore("privatefunCustomThemeComponentPicker(")
		assertTrue(collection.contains("ReferenceRankThemeCard("))
		assertTrue(collection.contains("ReferenceRankThemeWallpaper("))
		assertTrue(collection.contains("ReferenceRankThemeProgress("))
		assertTrue(collection.contains("height(150.dp)"))
	}


	// Synchronization marker: this regression test is the CI contract for the standalone Lv100 pass.
	@Test
	fun `final rank signatures reach global navigation favourites settings and details`() {
		val palette = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareColorScheme.kt")
			.replace(Regex("\\s+"), "")
		val surface = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareSurface.kt")
			.replace(Regex("\\s+"), "")
		val nav = source("kotlin/org/koitharu/kotatsu/main/ui/nav/FloatingNavBar.kt")
			.replace(Regex("\\s+"), "")
		val rootSettings = source("kotlin/org/koitharu/kotatsu/settings/RootSettingsFragment.kt")
			.replace(Regex("\\s+"), "")
		val grid = source("kotlin/org/koitharu/kotatsu/list/ui/adapter/MangaGridItemAD.kt")
			.replace(Regex("\\s+"), "")
		val details = listOf(
			source("kotlin/org/koitharu/kotatsu/details/ui/DetailsExpressiveScreen.kt"),
			source("kotlin/org/koitharu/kotatsu/details/ui/HeroSectionComponents.kt"),
			source("kotlin/org/koitharu/kotatsu/details/ui/DetailsCommonComponents.kt"),
			source("kotlin/org/koitharu/kotatsu/details/ui/DetailsChapterComponents.kt"),
			source("kotlin/org/koitharu/kotatsu/details/ui/DetailsContentComponents.kt"),
		).joinToString("\n").replace(Regex("\\s+"), "")

		assertTrue(palette.contains("RankThemeId.IMPERIAL_AURORA.stableId->RankThemeId.IMPERIAL_AURORA"))
		assertTrue(palette.contains("RankThemeId.ETERNAL_LIBRARY.stableId->RankThemeId.ETERNAL_LIBRARY"))
		assertTrue(palette.contains("rankBorderGradient=activeFinalRankSignature?.borderStops"))
		assertTrue(palette.contains("rankSelectedGradient=activeFinalRankSignature?.selectedStops"))
		assertTrue(surface.contains("RankThemeId.ETERNAL_LIBRARY.stableId"))
		assertTrue(surface.contains("signatureBorderBrush("))
		assertTrue(surface.contains("signatureSelectedBrush("))
		assertTrue(nav.contains("RankThemeId.ETERNAL_LIBRARY.stableId"))
		assertTrue(nav.contains("palette.rankBorderGradient"))
		assertTrue(nav.contains("palette.rankSelectedGradient"))
		assertTrue(rootSettings.contains("eternalLibrary&&palette.rankBorderGradient.isNotEmpty()"))
		assertTrue(rootSettings.contains("palette.rankBorderGradient[groupIndex%palette.rankBorderGradient.size]"))
		assertTrue(grid.contains("RankThemeId.ETERNAL_LIBRARY"))
		assertTrue(grid.contains("RankSignatureCoverBorderDrawable("))
		assertTrue(details.contains("signatureBorderBrush("))
		assertTrue(details.contains("signatureSelectedBrush("))
	}



	@Test
	fun `eternal library favourites and navigation keep full celestial prism`() {
		val neon = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareNeonGlass.kt")
			.replace(Regex("\\s+"), "")
		val header = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareHeaderLayouts.kt")
			.replace(Regex("\\s+"), "")
		val quickFilter = source("kotlin/org/koitharu/kotatsu/list/ui/adapter/QuickFilterAD.kt")
			.replace(Regex("\\s+"), "")
		val nav = source("kotlin/org/koitharu/kotatsu/main/ui/nav/FloatingNavBar.kt")
			.replace(Regex("\\s+"), "")
		val legacyNav = source("kotlin/org/koitharu/kotatsu/main/ui/nav/LegacyGlowNavBar.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(neon.contains("rankThemeId==RankThemeId.ETERNAL_LIBRARY.stableId"))
		assertTrue(neon.contains("RankThemeSignatureRegistry.resolve(RankThemeId.ETERNAL_LIBRARY)"))
		assertTrue(header.contains("celestialBorderStops"))
		assertTrue(header.contains("celestialSelectedStops"))
		assertTrue(header.contains("PrismStrokeDrawable("))
		assertTrue(quickFilter.contains("celestialBorderStops"))
		assertTrue(quickFilter.contains("celestialSelectedStops"))
		assertTrue(quickFilter.contains("QuickFilterPrismStrokeDrawable("))
		assertTrue(nav.contains("eternalFullPrism"))
		assertTrue(nav.contains("RankThemeSignatureRegistry.resolve(RankThemeId.ETERNAL_LIBRARY)"))
		assertTrue(nav.contains("Fullcelestialspectrumisintentionallyusedhere"))
		assertTrue(legacyNav.contains("RankThemeId.ETERNAL_LIBRARY.stableId"))
		assertTrue(legacyNav.contains("eternalFullPrism"))
		assertTrue(legacyNav.contains("Brush.horizontalGradient(eternalFullPrism)"))
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
