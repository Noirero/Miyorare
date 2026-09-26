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


	@Test
	fun `exclusive theme global consumers use resolved semantic roles`() {
		val contract = source("kotlin/org/koitharu/kotatsu/readerjourney/theme/ExclusiveThemeContract.kt")
			.replace(Regex("\\s+"), "")
		val runtime = source("kotlin/org/koitharu/kotatsu/readerjourney/theme/ReaderJourneyThemeRuntime.kt")
			.replace(Regex("\\s+"), "")
		val composePalette = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareColorScheme.kt")
			.replace(Regex("\\s+"), "")
		val viewPalette = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareViewPalette.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(contract.contains("data class ResolvedExclusiveTheme("))
		assertTrue(contract.contains("val navigation:ResolvedExclusiveThemeComponent"))
		assertTrue(contract.contains("val favourites:ResolvedExclusiveThemeComponent"))
		assertTrue(contract.contains("val settings:ResolvedExclusiveThemeComponent"))
		assertTrue(contract.contains("val details:ResolvedExclusiveThemeComponent"))
		assertTrue(contract.contains("fun resolve("))
		assertTrue(runtime.contains("fun resolveExclusiveTheme("))
		assertTrue(runtime.contains("ExclusiveThemeContractResolver.resolve("))
		assertTrue(composePalette.contains("val exclusiveTheme:ResolvedExclusiveThemePalette?=null"))
		assertTrue(viewPalette.contains("val exclusiveTheme:MiyorareViewExclusiveTheme?=null"))
	}

	@Test
	fun `theme consumers do not branch on concrete rank identity`() {
		val consumers = listOf(
			"kotlin/org/koitharu/kotatsu/core/ui/MiyorareNeonGlass.kt",
			"kotlin/org/koitharu/kotatsu/core/ui/MiyorareHeaderLayouts.kt",
			"kotlin/org/koitharu/kotatsu/core/ui/MiyorareSurface.kt",
			"kotlin/org/koitharu/kotatsu/list/ui/adapter/QuickFilterAD.kt",
			"kotlin/org/koitharu/kotatsu/list/ui/adapter/MangaGridItemAD.kt",
			"kotlin/org/koitharu/kotatsu/main/ui/nav/FloatingNavBar.kt",
			"kotlin/org/koitharu/kotatsu/main/ui/nav/LegacyGlowNavBar.kt",
			"kotlin/org/koitharu/kotatsu/details/ui/DetailsExpressiveScreen.kt",
			"kotlin/org/koitharu/kotatsu/settings/RootSettingsFragment.kt",
			"kotlin/org/koitharu/kotatsu/settings/compose/SettingsItem.kt",
		)
		consumers.forEach { path ->
			val screen = source(path)
			assertFalse("$path must not branch on a concrete rank", screen.contains("RankThemeId."))
			assertFalse("$path must not resolve signature registry directly", screen.contains("RankThemeSignatureRegistry"))
		}

		val nav = source("kotlin/org/koitharu/kotatsu/main/ui/nav/FloatingNavBar.kt")
			.replace(Regex("\\s+"), "")
		val legacyNav = source("kotlin/org/koitharu/kotatsu/main/ui/nav/LegacyGlowNavBar.kt")
			.replace(Regex("\\s+"), "")
		val favourites = listOf(
			source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareNeonGlass.kt"),
			source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareHeaderLayouts.kt"),
			source("kotlin/org/koitharu/kotatsu/list/ui/adapter/QuickFilterAD.kt"),
			source("kotlin/org/koitharu/kotatsu/list/ui/adapter/MangaGridItemAD.kt"),
		).joinToString("\n").replace(Regex("\\s+"), "")
		val details = source("kotlin/org/koitharu/kotatsu/details/ui/DetailsExpressiveScreen.kt")
			.replace(Regex("\\s+"), "")
		val settings = listOf(
			source("kotlin/org/koitharu/kotatsu/settings/RootSettingsFragment.kt"),
			source("kotlin/org/koitharu/kotatsu/settings/compose/SettingsItem.kt"),
		).joinToString("\n").replace(Regex("\\s+"), "")

		assertTrue(nav.contains("palette.exclusiveTheme?.navigation"))
		assertTrue(legacyNav.contains("palette.exclusiveTheme?.navigation"))
		assertTrue(favourites.contains("exclusiveTheme?.favourites"))
		assertTrue(details.contains("palette.exclusiveTheme?.details?.interactiveText"))
		assertTrue(settings.contains("palette.exclusiveTheme?.settings"))
	}

	@Test
	fun `details components consume details role rather than compatibility rank aliases`() {
		val details = listOf(
			source("kotlin/org/koitharu/kotatsu/details/ui/HeroSectionComponents.kt"),
			source("kotlin/org/koitharu/kotatsu/details/ui/DetailsCommonComponents.kt"),
			source("kotlin/org/koitharu/kotatsu/details/ui/DetailsChapterComponents.kt"),
			source("kotlin/org/koitharu/kotatsu/details/ui/DetailsContentComponents.kt"),
		).joinToString("\n")

		assertFalse(details.contains("rankBorderGradient"))
		assertFalse(details.contains("rankSelectedGradient"))
		assertFalse(details.contains("signatureBorderBrush"))
		assertFalse(details.contains("signatureSelectedBrush"))
		assertTrue(details.contains("detailsBorderBrush"))
	}

	@Test
	fun `favourites card border and navigation are authored semantic roles`() {
		val definition = source("kotlin/org/koitharu/kotatsu/readerjourney/theme/RankTheme.kt")
			.replace(Regex("\\s+"), "")
		val grid = source("kotlin/org/koitharu/kotatsu/list/ui/adapter/MangaGridItemAD.kt")
			.replace(Regex("\\s+"), "")
		val nav = source("kotlin/org/koitharu/kotatsu/main/ui/nav/FloatingNavBar.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(definition.contains("navigation=ExclusiveThemeComponentAuthoring("))
		assertTrue(definition.contains("favourites=ExclusiveThemeComponentAuthoring("))
		assertTrue(definition.contains("cardBorderStops="))
		assertTrue(grid.contains("exclusiveTheme?.favourites?.cardBorderStops"))
		assertTrue(nav.contains("exclusiveNavigation.selectedStops"))
		assertTrue(nav.contains("exclusiveNavigation.selectedMix"))
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
