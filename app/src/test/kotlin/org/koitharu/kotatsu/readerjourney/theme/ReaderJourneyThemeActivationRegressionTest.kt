package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderJourneyThemeActivationRegressionTest {

	@Test
	fun `runtime derives presentation from profile snapshot plus Reader Journey ledger`() {
		val runtime = source("kotlin/org/koitharu/kotatsu/readerjourney/theme/ReaderJourneyThemeRuntime.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(runtime.contains("combine(profileStore.profile,dao.observeProfile()"))
		assertTrue(runtime.contains("loadout=profile.cosmetics"))
		assertTrue(runtime.contains("lifetimeXp=journey?.totalXp?:0L"))
		assertTrue(runtime.contains("ledgerReady=true"))
		assertTrue(runtime.contains("if(!ledgerReady)returnnull"))
		assertTrue(runtime.contains("processLifecycleScope"))
		assertFalse(runtime.contains("putString("))
		assertFalse(runtime.contains("putInt("))
		assertFalse(runtime.contains("currentRank="))
	}

	@Test
	fun `Compose theme consumes one resolved rank token source`() {
		val theme = source("kotlin/org/koitharu/kotatsu/settings/compose/SettingsTheme.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(theme.contains("readerJourneyThemeRuntimeOrNull()"))
		assertTrue(theme.contains("journeyThemeRuntime?.state?.collectAsState()"))
		assertTrue(theme.contains("journeyThemeRuntimeState.resolveTokens("))
		assertTrue(theme.contains("explicitCustomAppearance=themePreset==MiyorareThemePreset.CUSTOM"))
		assertTrue(theme.contains("rankThemeTokens=rankThemeTokens"))
	}

	@Test
	fun `legacy View palette consumes same runtime and preserves explicit Private visual precedence`() {
		val view = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareViewPalette.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(view.contains("rankThemeState=readerJourneyThemeRuntimeOrNull()?.state?.value"))
		assertTrue(view.contains("allowRankTheme=privateSpec==null"))
		assertTrue(view.contains("rankThemeState?.resolveTokens("))
		assertTrue(view.contains("explicitCustomAppearance=preset==MiyorareThemePreset.CUSTOM"))
		assertTrue(view.contains("rankThemeTokens=rankThemeTokens"))
	}

	@Test
	fun `exclusive rank theme is opt in and normal app palettes win while off`() {
		val settings = source("kotlin/org/koitharu/kotatsu/core/prefs/AppSettings.kt")
			.replace(Regex("\\s+"), "")
		val theme = source("kotlin/org/koitharu/kotatsu/settings/compose/SettingsTheme.kt")
			.replace(Regex("\\s+"), "")
		val view = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareViewPalette.kt")
			.replace(Regex("\\s+"), "")
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(settings.contains("putBoolean(KEY_RANK_THEME_ENABLED,false)"))
		assertTrue(settings.contains("KEY_RANK_THEME_ENABLED=\"rank_theme_enabled\""))
		assertTrue(settings.contains("getBoolean(KEY_RANK_THEME_ENABLED,false)"))
		assertTrue(theme.contains("rememberBooleanPref(AppSettings.KEY_RANK_THEME_ENABLED,false)"))
		assertTrue(theme.contains("if(rankThemeEnabled){journeyThemeRuntimeState.resolveTokens("))
		assertTrue(view.contains("allowRankTheme=privateSpec==null&&settings.isRankThemeEnabled"))
		assertTrue(view.contains("prefs.getBoolean(AppSettings.KEY_RANK_THEME_ENABLED,false)"))
		assertTrue(screen.contains("checked=rankThemeEnabled"))
		assertTrue(screen.contains("onCheckedChange={rankThemeEnabled=it}"))
	}

	@Test
	fun `activation does not enter reader content preference domain`() {
		val readerSettings = source("kotlin/org/koitharu/kotatsu/reader/ui/config/ReaderSettings.kt")
		val epubStore = source("kotlin/org/koitharu/kotatsu/reader/ui/epub/EpubBookSettingsStore.kt")

		listOf(readerSettings, epubStore).forEach { text ->
			assertFalse(text.contains("ReaderJourneyThemeRuntime"))
			assertFalse(text.contains("RankThemeTokens"))
			assertFalse(text.contains("ReaderJourneyThemePresentationResolver"))
		}
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
