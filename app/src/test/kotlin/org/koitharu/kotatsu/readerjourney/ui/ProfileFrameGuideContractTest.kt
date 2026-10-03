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
	fun `production profile frames use exactly twelve transparent 512 webp foundations`() {
		val drawable = File(resourceRoot(), "drawable")
		val webps = listOf(
			"profile_frame_01_first_page_silver_normal.webp",
			"profile_frame_02_first_light_blue_normal.webp",
			"profile_frame_03_cyan_orbit_normal.webp",
			"profile_frame_04_emerald_pulse_normal.webp",
			"profile_frame_05_arcane_scholar_normal.webp",
			"profile_frame_06_violet_halo_normal.webp",
			"profile_frame_07_rose_nebula_normal.webp",
			"profile_frame_08_crimson_ember_normal.webp",
			"profile_frame_09_amber_manuscript_normal.webp",
			"profile_frame_10_golden_manuscript_deluxe_normal.webp",
			"profile_frame_11_eternal_library_prism_normal.webp",
			"profile_frame_12_celestial_infinity_normal.webp",
		)
		webps.forEach { fileName ->
			val file = File(drawable, fileName)
			assertTrue("Missing production Profile Frame WebP: $fileName", file.isFile)
			assertTransparent512Webp(file)
		}

		val superseded = drawable.listFiles().orEmpty().filter {
			it.name.startsWith("profile_frame_") &&
				(it.name.endsWith("_base.xml") || it.name.endsWith("_overlay.xml"))
		}
		assertTrue(
			"Superseded Profile Frame vector resources must be removed: ${superseded.map(File::getName)}",
			superseded.isEmpty(),
		)
	}

	@Test
	fun `renderer keeps state animation fallback and accepted pipeline contracts`() {
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
		val runtimeResources = listOf(
			"profile_frame_01_first_page_silver_normal",
			"profile_frame_02_first_light_blue_normal",
			"profile_frame_03_cyan_orbit_normal",
			"profile_frame_04_emerald_pulse_normal",
			"profile_frame_05_arcane_scholar_normal",
			"profile_frame_06_violet_halo_normal",
			"profile_frame_07_rose_nebula_normal",
			"profile_frame_08_crimson_ember_normal",
			"profile_frame_09_amber_manuscript_normal",
			"profile_frame_10_golden_manuscript_deluxe_normal",
			"profile_frame_11_eternal_library_prism_normal",
			"profile_frame_12_celestial_infinity_normal",
		)
		runtimeResources.forEach { resource ->
			assertTrue("Renderer missing final WebP mapping: $resource", source.contains("R.drawable.$resource"))
		}
		assertFalse(source.contains("_base"))
		assertFalse(source.contains("_overlay"))

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
		assertTrue("Production WebP must use VP8X extended header: ${file.name}", ascii(bytes, 12, 4) == "VP8X")
		val flags = bytes[20].toInt() and 0xFF
		assertTrue("Production WebP must carry an alpha channel: ${file.name}", flags and 0x10 != 0)
		val width = 1 + uint24(bytes, 24)
		val height = 1 + uint24(bytes, 27)
		assertEquals("Production WebP width must be 512: ${file.name}", 512, width)
		assertEquals("Production WebP height must be 512: ${file.name}", 512, height)
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
