package org.koitharu.kotatsu.explore.ui.adapter

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExplorePalettePropagationRegressionTest {

	@Test
	fun `Modern Explore XML surfaces are rebound through the resolved runtime palette`() {
		val source = source("kotlin/org/koitharu/kotatsu/explore/ui/adapter/ExploreAdapterDelegates.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(source.contains("binding.applyModernResolvedPalette()"))
		assertTrue(source.contains("binding.applyModernResolvedPalette(isExternal)"))
		assertTrue(source.contains("context.miyorareViewPaletteFromPreferences()==null"))
		assertTrue(source.contains("getThemeColor(materialR.attr.colorSurfaceContainerHigh)"))
		assertTrue(source.contains("getThemeColor(materialR.attr.colorSurfaceContainerLow)"))
		assertTrue(source.contains("getThemeColor(materialR.attr.colorSurfaceContainerHighest)"))
		assertTrue(source.contains("getThemeColor(materialR.attr.colorOutlineVariant)"))
		assertTrue(source.contains("createExploreSourceRowBackground("))
		assertTrue(source.contains("imageViewIcon.strokeColor=ColorStateList.valueOf(outline)"))
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
