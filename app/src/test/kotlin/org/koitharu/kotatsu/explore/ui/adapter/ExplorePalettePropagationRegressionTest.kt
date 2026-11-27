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

		assertTrue(delegates.contains("valmodernPalette=privatePalette?:context.miyorareViewPaletteFromPreferences()"))
		assertTrue(delegates.contains("binding.applyModernResolvedPalette(modernPalette)"))
		assertTrue(delegates.contains("binding.applyModernResolvedPalette(modernPalette,isExternal,preserveOriginIdentity=privatePalette!=null)"))
		assertTrue(delegates.contains("palette.surfaceContainerHigh"))
		assertTrue(delegates.contains("palette.surfaceContainerLow"))
		assertTrue(delegates.contains("palette.surfaceContainerHighest"))
		assertTrue(delegates.contains("palette.outlineVariant"))
		assertTrue(delegates.contains("createExploreSourceRowBackground("))
		assertTrue(delegates.contains("imageViewIcon.strokeColor=ColorStateList.valueOf(palette.outline)"))
		assertTrue(palette.contains("surfaceContainerLow=scheme.surfaceContainerLow.toArgb()"))
		assertTrue(palette.contains("surfaceContainerHighest=scheme.surfaceContainerHighest.toArgb()"))
	}

	@Test
	fun `Private Explore opts in without changing the Normal or Classic palette entry point`() {
		val palette = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareViewPalette.kt").replace(Regex("\\s+"), "")
		val header = source("kotlin/org/koitharu/kotatsu/explore/ui/MiyorareExploreHeaderLayout.kt").replace(Regex("\\s+"), "")
		val fragment = source("kotlin/org/koitharu/kotatsu/explore/ui/ExploreFragment.kt").replace(Regex("\\s+"), "")
		assertTrue(palette.contains("privateFavourites:Boolean=false"))
		assertTrue(palette.contains("if(!isPrivateFavouritesHost()||privateFavouritesThemeFromPreferences()==PrivateFavouritesThemePreset.FOLLOW_NORMAL)returnnull"))
		assertTrue(palette.contains("returnmiyorareViewPaletteFromPreferences(privateFavourites=true)"))
		assertTrue(header.contains("valpalette=privatePalette?:context.miyorareViewPaletteFromPreferences()?:return"))
		assertTrue(fragment.contains("context.explicitPrivateViewPaletteFromPreferences()?:context.miyorareViewPaletteFromPreferences()"))
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
