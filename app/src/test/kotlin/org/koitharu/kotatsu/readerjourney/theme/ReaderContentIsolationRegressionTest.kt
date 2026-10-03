package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ReaderContentIsolationRegressionTest {

	@Test
	fun `rank theme does not enter manga content settings or color filter domain`() {
		val readerSettings = source("kotlin/org/koitharu/kotatsu/reader/ui/config/ReaderSettings.kt")
		val colorFilter = source("kotlin/org/koitharu/kotatsu/reader/domain/ReaderColorFilter.kt")
		val colorFilterVm = source("kotlin/org/koitharu/kotatsu/reader/ui/colorfilter/ColorFilterConfigViewModel.kt")

		listOf(readerSettings, colorFilter, colorFilterVm).forEach { source ->
			assertFalse(source.contains("readerjourney.theme"))
			assertFalse(source.contains("RankThemeTokens"))
			assertFalse(source.contains("RankThemeId"))
		}
	}

	@Test
	fun `rank theme does not override epub reading content preferences`() {
		val epubReader = source("kotlin/org/koitharu/kotatsu/reader/ui/epub/EpubReaderFragment.kt")
		val epubStore = source("kotlin/org/koitharu/kotatsu/reader/ui/epub/EpubBookSettingsStore.kt")

		listOf(epubReader, epubStore).forEach { source ->
			assertFalse(source.contains("readerjourney.theme"))
			assertFalse(source.contains("RankThemeTokens"))
			assertFalse(source.contains("RankThemeId"))
		}
	}

	@Test
	fun `reference visual renderer remains outside reader content package`() {
		val renderer = source("kotlin/org/koitharu/kotatsu/readerjourney/ui/ReferenceRankThemeVisuals.kt")

		assertFalse(renderer.contains("ReaderColorFilter"))
		assertFalse(renderer.contains("EpubBookSettingsStore"))
		assertFalse(renderer.contains("epubFont"))
		assertFalse(renderer.contains("epubTheme"))
		assertFalse(renderer.contains("brightness"))
		assertFalse(renderer.contains("contrast"))
		assertFalse(renderer.contains("saturation"))
		assertFalse(renderer.contains("gamma"))
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
