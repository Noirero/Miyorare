package org.koitharu.kotatsu.explore.ui.adapter

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExplorePalettePropagationRegressionTest {

	@Test
	fun `Modern Explore XML surfaces are rebound through the resolved runtime palette`() {
		val delegates = source("kotlin/org/koitharu/kotatsu/explore/ui/adapter/ExploreAdapterDelegates.kt")
			.replace(Regex("\\s+"), "")
		val palette = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareViewPalette.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(delegates.contains("binding.applyModernResolvedPalette()"))
		assertTrue(delegates.contains("binding.applyModernResolvedPalette(isExternal)"))
		assertTrue(delegates.contains("root.context.miyorareViewPaletteFromPreferences()?:return"))
		assertTrue(delegates.contains("palette.surfaceContainerHigh"))
		assertTrue(delegates.contains("palette.surfaceContainerLow"))
		assertTrue(delegates.contains("palette.surfaceContainerHighest"))
		assertTrue(delegates.contains("palette.outlineVariant"))
		assertTrue(delegates.contains("createExploreSourceRowBackground("))
		assertTrue(delegates.contains("imageViewIcon.strokeColor=ColorStateList.valueOf(palette.outline)"))
		assertTrue(palette.contains("surfaceContainerLow=scheme.surfaceContainerLow.toArgb()"))
		assertTrue(palette.contains("surfaceContainerHighest=scheme.surfaceContainerHighest.toArgb()"))
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
