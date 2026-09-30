package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveBottomNavigationOrnamentRegistry
import java.io.File
import java.security.MessageDigest

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

	@Test
	fun `exclusive navigation uses approved runtime ornament assets without bitmap UI`() {
		val expected = listOf(
			"navigation/themes/01_First_Page_Silver.webp",
			"navigation/themes/02_First_Light_Blue.webp",
			"navigation/themes/03_Cyan_Orbit.webp",
			"navigation/themes/04_Emerald_Pulse.webp",
			"navigation/themes/05_Arcane_Scholar.webp",
			"navigation/themes/06_Violet_Halo.webp",
			"navigation/themes/07_Rose_Nebula.webp",
			"navigation/themes/08_Crimson_Ember.webp",
			"navigation/themes/09_Amber_Manuscript.webp",
			"navigation/themes/10_Golden_Manuscript_Deluxe.webp",
			"navigation/themes/11_Eternal_Library_Prism.webp",
			"navigation/themes/12_Celestial_Infinity.webp",
		)
		assertEquals(expected, ExclusiveBottomNavigationOrnamentRegistry.presets.map { it.assetPath })
		assertTrue(ExclusiveBottomNavigationOrnamentRegistry.validate().isEmpty())

		val expectedSha256 = mapOf(
			"navigation/themes/01_First_Page_Silver.webp" to "bf44fc66e24861f6eb636b89b33a00b09ae0abfd0151513e8253777a846eb24e",
			"navigation/themes/02_First_Light_Blue.webp" to "3ae478d0939627901ac66300d857ead168d564fa011d5b44780749f70940de45",
			"navigation/themes/03_Cyan_Orbit.webp" to "8da2044ab761ec70e1af6313faa1b8712956c161672059253817d8efd93e9dae",
			"navigation/themes/04_Emerald_Pulse.webp" to "673a9397661f6db0e975affe562b9073942498f3c55d46c5cfa96bba60ffdfc8",
			"navigation/themes/05_Arcane_Scholar.webp" to "70f2cf5ef386be5cfb26a474d97367ddb7e031c3ce9cfe172f3093d3bd0b4066",
			"navigation/themes/06_Violet_Halo.webp" to "7ff92591260d02e4a9a51d92f1a60238106e1c40c975c7920a9a4966152987c1",
			"navigation/themes/07_Rose_Nebula.webp" to "49a3fce5cb1a6ce37703500b27d824e922ec32ce0af97af3402044ee815f1455",
			"navigation/themes/08_Crimson_Ember.webp" to "5fcf84f3def0f45670a1c309a4128e81ad944c33a4bc232904a86ca58b047682",
			"navigation/themes/09_Amber_Manuscript.webp" to "986b12e9dc2a84001ea86035dfc94cca1b47eb60fe3ed315014ac899bcb6d67f",
			"navigation/themes/10_Golden_Manuscript_Deluxe.webp" to "df414d1105c844fe2d7727703286e1d2360f985ecbb11ea3a5f00d6edc31d0c5",
			"navigation/themes/11_Eternal_Library_Prism.webp" to "809ffddf408b1a0a10638275968c1cb69b9e5561e13116def588bfd5f06b4c26",
			"navigation/themes/12_Celestial_Infinity.webp" to "8b2610d49d69f8d2618ac88cbfbd3238d3986971b5b031493936c8de69e2069f",
		)
		expected.forEach { assetPath ->
			val asset = sequenceOf(
				File("src/main/assets", assetPath),
				File("app/src/main/assets", assetPath),
			).firstOrNull(File::isFile)
			assertTrue("Missing approved runtime ornament: $assetPath", asset != null && asset.length() > 0L)
			val digest = MessageDigest.getInstance("SHA-256")
				.digest(requireNotNull(asset).readBytes())
				.joinToString("") { "%02x".format(it) }
			assertEquals("Approved ornament bytes changed: $assetPath", expectedSha256[assetPath], digest)
		}

		val renderer = source("kotlin/org/koitharu/kotatsu/main/ui/nav/ExclusiveBottomNavigation.kt")
			.replace(Regex("\\s+"), "")
		assertTrue(renderer.contains("contentScale=ContentScale.Fit"))
		assertTrue(renderer.contains("BoxWithConstraints("))
		assertTrue(renderer.contains("modifier.height(navigationHeight)"))
		assertTrue(renderer.contains("maxWidth/ExclusiveBottomNavigationOrnamentRegistry.ASPECT_RATIO"))
		assertTrue(renderer.contains("navigationHeight/ornament.visibleHeightFraction"))
		assertTrue(renderer.contains("ornament.contentInsetStartFraction"))
		assertTrue(renderer.contains("ornament.contentWidthFraction"))
		assertTrue(renderer.contains("MIN_TOUCH_TARGET_DP.dp"))
		assertTrue(renderer.contains("requiredWidth(ornamentWidth)"))
		assertTrue(renderer.contains("offset(x=contentStart,y=touchTop)"))
		assertFalse(renderer.contains("visualCanvasHeight"))
		assertFalse(renderer.contains("modifier.aspectRatio(ExclusiveBottomNavigationOrnamentRegistry.ASPECT_RATIO)"))
		assertTrue(renderer.contains("NavIcon("))
		assertTrue(renderer.contains("Text(text=title"))
		assertTrue(renderer.contains("Role.Tab"))
		assertFalse(renderer.contains("ContentScale.Crop"))
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
