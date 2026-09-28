package org.koitharu.kotatsu.readerjourney.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId

class ProfileFrameGuideContractTest {

	@Test
	fun `all twelve themes keep distinct runtime mappings and guide timing ranges`() {
		val expectedIdleRanges = mapOf(
			RankThemeId.FIRST_PAGE to 12_000..18_000,
			RankThemeId.FIRST_LIGHT to 8_000..12_000,
			RankThemeId.CYAN_CODEX to 10_000..16_000,
			RankThemeId.EMERALD_COMPASS to 6_000..10_000,
			RankThemeId.VIOLET_VAULT to 7_000..12_000,
			RankThemeId.ARCANE_SCHOLAR to 8_000..12_000,
			RankThemeId.NEON_ARCHIVE to 8_000..14_000,
			RankThemeId.CRIMSON_LIBRARY to 8_000..12_000,
			RankThemeId.EMBER_VETERAN to 10_000..16_000,
			RankThemeId.GOLDEN_MANUSCRIPT to 10_000..16_000,
			RankThemeId.IMPERIAL_AURORA to 9_000..16_000,
			RankThemeId.ETERNAL_LIBRARY to 10_000..20_000,
		)
		val specs = RankThemeId.entries.associateWith(ProfileFrameAssetRegistry::resolve)

		assertEquals(12, specs.size)
		assertEquals(12, specs.values.map { it.drawableRes }.distinct().size)
		assertEquals(12, specs.values.map { it.ambient }.distinct().size)
		specs.forEach { (theme, spec) ->
			assertTrue("$theme idle duration must follow the guide", spec.idleDurationMs in checkNotNull(expectedIdleRanges[theme]))
			assertTrue("$theme one-shot accent must remain in 280-420ms", spec.oneShotDurationMs in 280..420)
			assertTrue("$theme avatar diameter must stay inside 280-310px of a 512px master", spec.avatarFraction in (280f / 512f)..(310f / 512f))
		}
	}

	@Test
	fun `wave one assets keep approved vector and transparent webp pipelines`() {
		val drawable = File(resourceRoot(), "drawable")

		val vectorBases = listOf(
			"profile_frame_01_first_page_silver_base.xml",
			"profile_frame_02_first_light_blue_base.xml",
			"profile_frame_04_emerald_pulse_base.xml",
			"profile_frame_05_arcane_scholar_base.xml",
			"profile_frame_06_violet_halo_base.xml",
			"profile_frame_07_rose_nebula_base.xml",
			"profile_frame_08_crimson_ember_base.xml",
			"profile_frame_09_amber_manuscript_base.xml",
			"profile_frame_10_golden_manuscript_deluxe_base.xml",
		)
		vectorBases.forEach { fileName ->
			val file = File(drawable, fileName)
			assertTrue("Missing vector Profile Frame asset: $fileName", file.isFile)
			val text = file.readText()
			assertTrue(
				"Vector Profile Frame must use a 512 viewport: $fileName",
				text.contains("android:viewportWidth=\"512\"") &&
					text.contains("android:viewportHeight=\"512\""),
			)
		}

		val vectorOverlays = vectorBases.map { it.removeSuffix("_base.xml") + "_overlay.xml" }
		vectorOverlays.forEach { fileName ->
			assertTrue("Missing vector Profile Frame overlay: $fileName", File(drawable, fileName).isFile)
		}

		val webps = listOf(
			"profile_frame_03_cyan_orbit_normal.webp",
			"profile_frame_11_eternal_library_prism_normal.webp",
			"profile_frame_12_celestial_infinity_normal.webp",
		)
		webps.forEach { fileName ->
			val file = File(drawable, fileName)
			assertTrue("Missing transparent WebP Profile Frame asset: $fileName", file.isFile)
			assertTransparent512Webp(file)
		}

		assertFalse(
			"Cyan Orbit Wave 1 must not fall back to the old vector base",
			File(drawable, "profile_frame_03_cyan_orbit_base.xml").exists(),
		)
		assertFalse(
			"Cyan Orbit Wave 1 must not fall back to the old vector overlay",
			File(drawable, "profile_frame_03_cyan_orbit_overlay.xml").exists(),
		)

		val legacy = listOf(
			"profile_frame_first_page_silver.xml",
			"profile_frame_first_light_blue.xml",
			"profile_frame_cyan_orbit.xml",
			"profile_frame_emerald_pulse.xml",
			"profile_frame_arcane_scholar.xml",
			"profile_frame_violet_halo.xml",
			"profile_frame_rose_nebula.xml",
			"profile_frame_crimson_ember.xml",
			"profile_frame_amber_manuscript.xml",
			"profile_frame_golden_manuscript_deluxe.xml",
			"profile_frame_eternal_library_prism.xml",
			"profile_frame_celestial_infinity.xml",
		)
		legacy.forEach { fileName ->
			assertFalse("Legacy simplified frame must not remain active: $fileName", File(drawable, fileName).exists())
		}
	}

	@Test
	fun `renderer keeps state animation fallback and wave one pipeline contract`() {
		assertEquals(
			setOf(ProfileFrameState.LOCKED, ProfileFrameState.UNLOCKED, ProfileFrameState.EQUIPPED, ProfileFrameState.PREVIEWING),
			ProfileFrameState.entries.toSet(),
		)
		assertEquals(
			setOf(ProfileFrameQualityMode.NORMAL, ProfileFrameQualityMode.REDUCED, ProfileFrameQualityMode.BATTERY_SAVER),
			ProfileFrameQualityMode.entries.toSet(),
		)

		val source = source("kotlin/org/koitharu/kotatsu/readerjourney/ui/ExclusiveProfileFrame.kt")
			.replace(Regex("\\s+"), "")
		assertTrue(source.contains("R.drawable.profile_frame_01_first_page_silver_base"))
		assertTrue(source.contains("R.drawable.profile_frame_01_first_page_silver_overlay"))
		assertTrue(source.contains("drawableRes=R.drawable.profile_frame_03_cyan_orbit_normal"))
		assertFalse(source.contains("R.drawable.profile_frame_03_cyan_orbit_base"))
		assertFalse(source.contains("R.drawable.profile_frame_03_cyan_orbit_overlay"))
		assertTrue(source.contains("R.drawable.profile_frame_11_eternal_library_prism_normal"))
		assertTrue(source.contains("R.drawable.profile_frame_12_celestial_infinity_normal"))

		assertTrue(source.contains("valframeScale=0.96f+0.04f*reveal.value"))
		assertTrue(source.contains("valbadgeScale=0.92f+badgeReveal.value*0.08f"))
		assertTrue(source.contains("targetValue=0.8f"))
		assertTrue(source.contains("durationMillis=280"))
		assertTrue(source.contains("setToSaturation(0.45f)"))
		assertTrue(source.contains("valeffectiveAnimate=animate&&!reduceMotion&&!powerSaveMode"))
		assertTrue(source.contains("ProfileFrameQualityMode.BATTERY_SAVER"))
		assertTrue(source.contains("state==ProfileFrameState.EQUIPPED||state==ProfileFrameState.PREVIEWING"))
		assertTrue(source.contains("ProfileFrameAmbient.ORBIT"))
		assertTrue(source.contains("ProfileFrameAmbient.PRISM"))
		assertTrue(source.contains("ProfileFrameAmbient.CELESTIAL"))
	}

	private fun assertTransparent512Webp(file: File) {
		val bytes = file.readBytes()
		assertTrue("WebP asset is unexpectedly small: ${file.name}", bytes.size > 10_000)
		assertTrue("Missing RIFF header: ${file.name}", ascii(bytes, 0, 4) == "RIFF")
		assertTrue("Missing WEBP header: ${file.name}", ascii(bytes, 8, 4) == "WEBP")
		assertTrue("Wave 1 WebP must use VP8X extended header: ${file.name}", ascii(bytes, 12, 4) == "VP8X")
		val flags = bytes[20].toInt() and 0xFF
		assertTrue("Wave 1 WebP must carry an alpha channel: ${file.name}", flags and 0x10 != 0)
		val width = 1 + uint24(bytes, 24)
		val height = 1 + uint24(bytes, 27)
		assertEquals("Wave 1 WebP width must be 512: ${file.name}", 512, width)
		assertEquals("Wave 1 WebP height must be 512: ${file.name}", 512, height)
	}

	private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
		bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

	private fun uint24(bytes: ByteArray, offset: Int): Int =
		(bytes[offset].toInt() and 0xFF) or
			((bytes[offset + 1].toInt() and 0xFF) shl 8) or
			((bytes[offset + 2].toInt() and 0xFF) shl 16)

	private fun resourceRoot(): File = sequenceOf(
		File("src/main/res"),
		File("app/src/main/res"),
	).firstOrNull(File::isDirectory) ?: error("Cannot find Android resources")

	private fun source(relativePath: String): String = sequenceOf(
		File("src/main", relativePath),
		File("app/src/main", relativePath),
	).firstOrNull(File::isFile)?.readText() ?: error("Cannot find production source: $relativePath")
}
