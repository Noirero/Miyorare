package org.koitharu.kotatsu.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CustomVisualIdentityRegressionTest {

	@Test
	fun `adaptive custom background keeps authored identity for glass and navigation`() {
		val palette = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareViewPalette.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(palette.contains("customBackgroundPrimary?.let(MiyorareAppearance::parseAccentArgb)"))
		assertTrue(palette.contains("customBackgroundIdentity?:if(adaptivePalette==null)"))
	}

	@Test
	fun `floating navigation preserves neutral identity instead of relighting it`() {
		val navigation = source("kotlin/org/koitharu/kotatsu/main/ui/nav/FloatingNavBar.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(navigation.contains("resolveNeonGlassIdentityColor(identity,Int.MIN_VALUE)==identity"))
		assertTrue(navigation.contains("container=neutralCustomIdentity"))
		assertTrue(navigation.contains("selectedContainer=neutralCustomIdentity"))
		assertTrue(navigation.contains("selectedContent=Color.White.toArgb()"))
	}

	private fun source(relativePath: String): String =
		File("src/main/$relativePath").readText()
}
