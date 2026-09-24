package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RankThemeVisualsRegressionTest {

	@Test
	fun `one shared renderer covers all visual primitives without heavy runtime dependencies`() {
		val renderer = source("kotlin/org/koitharu/kotatsu/readerjourney/ui/ReferenceRankThemeVisuals.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(renderer.contains("when(spec.badgeStyle)"))
		assertTrue(renderer.contains("when(spec.wallpaperStyle)"))
		assertTrue(renderer.contains("ReferenceRankThemeFrame("))
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
	fun `developer gallery resolves full visual registry for every rank theme`() {
		val gallery = source("kotlin/org/koitharu/kotatsu/settings/developer/RankThemeGalleryFragment.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(gallery.contains("RankThemeVisualRegistry.resolve(definition.id)"))
		assertTrue(gallery.contains("ReferenceRankThemeBadge("))
		assertTrue(gallery.contains("ReferenceRankThemeFrame("))
		assertTrue(gallery.contains("ReferenceRankThemeWallpaper("))
		assertTrue(gallery.contains("ReferenceRankThemeCard("))
		assertTrue(gallery.contains("ReferenceRankThemeProgress("))
		assertTrue(gallery.contains("if(wallpaperEnabled)"))
	}

	@Test
	fun `all 60 stable visual ids are covered by provenance manifest`() {
		val manifest = sequenceOf(
			File("docs/reader-journey-theme-assets.md"),
			File("../docs/reader-journey-theme-assets.md"),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find Reader Journey asset provenance manifest")

		RankThemeVisualRegistry.all.forEach { spec ->
			listOf(spec.badgeId, spec.frameId, spec.wallpaperId, spec.cardId, spec.progressId).forEach { id ->
				assertTrue("Missing provenance for $id", manifest.contains(id))
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
