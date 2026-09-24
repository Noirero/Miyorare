package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReferenceRankThemeVisualsRegressionTest {

	@Test
	fun `reference renderer stays static local first and progression independent`() {
		val renderer = source("kotlin/org/koitharu/kotatsu/readerjourney/ui/ReferenceRankThemeVisuals.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(renderer.contains("ReferenceRankThemeBadge("))
		assertTrue(renderer.contains("ReferenceRankThemeFrame("))
		assertTrue(renderer.contains("ReferenceRankThemeWallpaper("))
		assertTrue(renderer.contains("ReferenceRankThemeCard("))
		assertTrue(renderer.contains("ReferenceRankThemeProgress("))

		assertFalse(renderer.contains("rememberInfiniteTransition"))
		assertFalse(renderer.contains("InfiniteTransition"))
		assertFalse(renderer.contains("AsyncImage"))
		assertFalse(renderer.contains("ImageRequest"))
		assertFalse(renderer.contains("http://"))
		assertFalse(renderer.contains("https://"))
		assertFalse(renderer.contains("ReaderJourneyCollector"))
		assertFalse(renderer.contains("ReaderProfileStore"))
		assertFalse(renderer.contains("updateCosmetics("))
	}

	@Test
	fun `gallery uses the reference renderers only when a reference visual exists`() {
		val gallery = source("kotlin/org/koitharu/kotatsu/settings/developer/RankThemeGalleryFragment.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(gallery.contains("ReferenceRankThemeVisualRegistry.resolve(definition.id)"))
		assertTrue(gallery.contains("referenceVisual?.let"))
		assertTrue(gallery.contains("wallpaperEnabled"))
		assertTrue(gallery.contains("if(wallpaperEnabled)"))
		assertTrue(gallery.contains("ReferenceRankThemeWallpaper("))
		assertTrue(gallery.contains("ReferenceRankThemeBadge("))
		assertTrue(gallery.contains("ReferenceRankThemeFrame("))
		assertTrue(gallery.contains("ReferenceRankThemeCard("))
		assertTrue(gallery.contains("ReferenceRankThemeProgress("))
	}

	@Test
	fun `reference assets are covered by provenance manifest`() {
		val manifest = sequenceOf(
			File("docs/reader-journey-theme-assets.md"),
			File("../docs/reader-journey-theme-assets.md"),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find Reader Journey asset provenance manifest")

		ReferenceRankThemeVisualRegistry.all.forEach { spec ->
			listOf(spec.badgeId, spec.frameId, spec.wallpaperId, spec.cardId, spec.progressId).forEach { id ->
				assertTrue("Missing provenance for $id", manifest.contains(id))
			}
		}
		assertFalse(manifest.contains("Pinterest", ignoreCase = true) && manifest.contains("http"))
		assertFalse(manifest.contains("Google Images", ignoreCase = true) && manifest.contains("http"))
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
