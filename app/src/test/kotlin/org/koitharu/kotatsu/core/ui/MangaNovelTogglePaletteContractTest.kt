package org.koitharu.kotatsu.core.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaNovelTogglePaletteContractTest {

	@Test
	fun `both History and Downloads use the resolved Modern palette rather than static Material colors`() {
		val downloads = source("download/ui/list/DownloadsActivity.kt")
		val history = source("history/ui/HistoryListFragment.kt")
		val theme = source("core/ui/MangaNovelTogglePalette.kt")
		assertTrue(downloads.contains("miyorareViewPaletteFromPreferences()?.let(typeToggle::applyMangaNovelTogglePalette)"))
		assertTrue(downloads.contains(".applyMangaNovelTogglePalette(palette)"))
		assertTrue(downloads.contains("viewBinding.modernDownloadsIcon.imageTintList = ColorStateList.valueOf(palette.primary)"))
		assertTrue(history.contains("toggle.applyMangaNovelTogglePalette(initialPalette)"))
		assertTrue(history.contains("visualEffectPreferences.level.observe(viewLifecycleOwner)"))
		assertTrue(theme.contains("palette.primaryContainer"))
		assertTrue(theme.contains("palette.onPrimaryContainer"))
		assertTrue(theme.contains("palette.onSurfaceVariant"))
		assertTrue(theme.contains("android.R.attr.state_checked"))
	}

	private fun source(relative: String): String = sequenceOf(
		File("src/main/kotlin/org/koitharu/kotatsu", relative),
		File("app/src/main/kotlin/org/koitharu/kotatsu", relative),
	).first(File::isFile).readText()
}
