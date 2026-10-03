package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderProfileFinalRegressionTest {

	@Test
	fun `reader profile stays device local and showcase stays bounded`() {
		val store = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderProfileStore.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(store.contains("getSharedPreferences(PREFS_NAME,Context.MODE_PRIVATE)"))
		assertTrue(store.contains("constvalMAX_SHOWCASE=3"))
		assertTrue(store.contains("showcase.distinct().take(MAX_SHOWCASE)"))
		assertFalse(store.contains("importorg.koitharu.kotatsu.core.prefs.AppSettings"))
		assertFalse(store.contains("privatevalsettings:AppSettings"))
	}

	@Test
	fun `reader title and showcase can only use unlocked achievements`() {
		val viewModel = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsViewModel.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(viewModel.contains("selectedTitle=selectedTitle?.takeIf{itinunlocked}"))
		assertTrue(viewModel.contains("showcase=showcase.filter{itinunlocked}"))
	}

	@Test
	fun `profile exposes verified journey stats without duplicate overview hero`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")
		val overview = screen
			.substringAfter("ReaderJourneySection.OVERVIEW->{")
			.substringBefore("ReaderJourneySection.STATISTICS->{")

		assertTrue(screen.contains("stats.journeyCompletedChapters"))
		assertTrue(screen.contains("stats.journeyTitleCount"))
		assertTrue(screen.contains("stats.journeyMangaChapters"))
		assertTrue(screen.contains("stats.journeyNovelChapters"))
		assertTrue(screen.contains("progress.levelFraction"))
		assertFalse(overview.contains("ReaderJourneyHero(stats)"))
	}

	@Test
	fun `profile cosmetics keep independent authored identities`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(screen.contains("effectiveCosmetics.selectedFrameId?.let"))
		assertTrue(screen.contains("effectiveCosmetics.selectedNameplateId?.let"))
		assertTrue(screen.contains("tokens=frameTokens"))
		assertTrue(screen.contains("tokens=nameplateTokens"))
		assertTrue(screen.contains("ReferenceRankThemeBadge("))
		assertTrue(screen.contains("tokens=badgeTokens"))
		assertTrue(screen.contains("ReferenceRankThemeProgress("))
		assertTrue(screen.contains("tokens=progressTokens"))
	}


	@Test
	fun `reader avatar is app private bounded and removable`() {
		val store = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderProfileStore.kt")
			.replace(Regex("\\s+"), "")
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(store.contains("File(context.filesDir,AVATAR_DIRECTORY)"))
		assertTrue(store.contains("AVATAR_MAX_EDGE=512"))
		assertTrue(store.contains("funremoveAvatar()"))
		assertTrue(screen.contains("ActivityResultContracts.PickVisualMedia()"))
		assertTrue(screen.contains("contentScale=ContentScale.Crop"))
	}

	@Test
	fun `profile editing keeps one entry flow and preserves drafts while avatar changes`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(screen.contains("onAvatarClick={showProfileEditor=true}"))
		assertTrue(screen.contains("varshowProfileEditorbyrememberSaveable{mutableStateOf(false)}"))
		assertTrue(screen.contains("vardisplayNamebyremember{mutableStateOf(profile.displayName)}"))
		assertTrue(screen.contains("varselectedTitlebyremember{mutableStateOf(profile.selectedTitle?.takeIf{itinunlockedAchievements})}"))
		assertTrue(screen.contains("varshowcasebyremember{mutableStateOf(profile.showcase.filter{itinunlockedAchievements}.take(3))}"))
		assertFalse(screen.contains("vardisplayNamebyremember(profile)"))
		assertFalse(screen.contains("varselectedTitlebyremember(profile)"))
		assertFalse(screen.contains("varshowcasebyremember(profile)"))
	}

	@Test
	fun `reading heatmap exposes legend selection and real activity detail`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(screen.contains("HeatmapLegend()"))
		assertTrue(screen.contains("selectedEpochDay"))
		assertTrue(screen.contains("onDaySelected"))
		assertTrue(screen.contains("selectedStats?.duration"))
		assertTrue(screen.contains("selectedStats?.sessions"))
		assertFalse(screen.contains("Random("))
	}

	@Test
	fun `achievement identities stay generic and mature safe`() {
		val forbidden = listOf("hentai", "nsfw", "adult", "source", "genre", "tag")
		ReaderAchievementId.entries.forEach { id ->
			val normalized = id.name.lowercase()
			forbidden.forEach { term ->
				assertFalse("Achievement identity must stay generic: ${id.name}", term in normalized)
			}
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
