package org.koitharu.kotatsu.settings.developer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RankThemeGalleryRegressionTest {

	@Test
	fun `theme gallery is debug gated and read only`() {
		val tools = source("kotlin/org/koitharu/kotatsu/settings/developer/DeveloperToolsFragment.kt")
			.replace(Regex("\\s+"), "")
		val gallery = source("kotlin/org/koitharu/kotatsu/settings/developer/RankThemeGalleryFragment.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(tools.contains("if(BuildConfig.DEBUG)"))
		assertTrue(tools.contains("RankThemeGalleryFragment::class.java"))
		assertTrue(gallery.contains("RankThemeRegistry.definitions"))
		assertTrue(gallery.contains("RankThemeVariant.entries"))
		assertTrue(gallery.contains("rankThemeTokens=tokens"))
		assertTrue(gallery.contains("miyorareThemeColors("))

		assertFalse(gallery.contains("ReaderProfileStore"))
		assertFalse(gallery.contains("updateCosmetics("))
		assertFalse(gallery.contains("ReaderJourneyCollector"))
		assertFalse(gallery.contains("SharedPreferences"))
		assertFalse(gallery.contains(".edit{"))
	}

	@Test
	fun `gallery previews representative semantic states`() {
		val gallery = source("kotlin/org/koitharu/kotatsu/settings/developer/RankThemeGalleryFragment.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(gallery.contains("LinearProgressIndicator("))
		assertTrue(gallery.contains("visualPalette.success"))
		assertTrue(gallery.contains("visualPalette.warning"))
		assertTrue(gallery.contains("visualPalette.error"))
		assertTrue(gallery.contains("visualPalette.glow"))
		assertTrue(gallery.contains("borderHighlight"))
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
