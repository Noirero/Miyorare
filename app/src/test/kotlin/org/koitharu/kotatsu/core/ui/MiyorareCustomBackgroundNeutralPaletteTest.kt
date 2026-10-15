package org.koitharu.kotatsu.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareCustomBackgroundIntensity

class MiyorareCustomBackgroundNeutralPaletteTest {

	@Test
	fun blackWhiteAndGrayStayNeutralWhileChromaticColorDoesNot() {
		assertTrue(MiyorareAppearance.isNeutralArgb(0xFF000000.toInt()))
		assertTrue(MiyorareAppearance.isNeutralArgb(0xFFFFFFFF.toInt()))
		assertTrue(MiyorareAppearance.isNeutralArgb(0xFF808080.toInt()))
		assertFalse(MiyorareAppearance.isNeutralArgb(0xFF5B6CFF.toInt()))
	}

	@Test
	fun neutralTuningPreservesDarkLightAndGrayFamilies() {
		val dark = MiyorareAppearance.tuneNeutralLightness(
			0xFF000000.toInt(),
			MiyorareCustomBackgroundIntensity.BALANCED,
		)
		val light = MiyorareAppearance.tuneNeutralLightness(
			0xFFFFFFFF.toInt(),
			MiyorareCustomBackgroundIntensity.BALANCED,
		)
		val gray = MiyorareAppearance.tuneNeutralLightness(
			0xFF808080.toInt(),
			MiyorareCustomBackgroundIntensity.BALANCED,
		)

		assertNeutral(dark)
		assertNeutral(light)
		assertNeutral(gray)
		assertTrue(channel(dark) < channel(gray))
		assertTrue(channel(gray) < channel(light))
		assertTrue(channel(dark) < 64)
		assertTrue(channel(light) > 191)
	}

	@Test
	fun predominantlyNeutralSamplesUseNeutralFallback() {
		assertTrue(MiyorareCustomBackgroundStore.shouldUseNeutralFallback(100, 0))
		assertTrue(MiyorareCustomBackgroundStore.shouldUseNeutralFallback(100, 19))
		assertFalse(MiyorareCustomBackgroundStore.shouldUseNeutralFallback(100, 20))
		assertFalse(MiyorareCustomBackgroundStore.shouldUseNeutralFallback(0, 0))
	}

	@Test
	fun neutralFallbackUsesActualOpaqueImageTone() {
		val black = MiyorareCustomBackgroundStore.averageOpaqueColor(
			intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt()),
		)
		val white = MiyorareCustomBackgroundStore.averageOpaqueColor(
			intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()),
		)
		val gray = MiyorareCustomBackgroundStore.averageOpaqueColor(
			intArrayOf(0xFF404040.toInt(), 0xFFC0C0C0.toInt()),
		)

		assertEquals(0xFF000000.toInt(), black)
		assertEquals(0xFFFFFFFF.toInt(), white)
		assertEquals(0xFF808080.toInt(), gray)
	}

	private fun assertNeutral(color: Int) {
		val red = color ushr 16 and 0xFF
		val green = color ushr 8 and 0xFF
		val blue = color and 0xFF
		assertEquals(red, green)
		assertEquals(green, blue)
	}

	private fun channel(color: Int): Int = color and 0xFF
}
