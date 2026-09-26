package org.koitharu.kotatsu.readerjourney.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId

class ProfileFrameGuideContractTest {

	@Test
	fun `all twelve themes use distinct golden reference assets and guide timing ranges`() {
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
	fun `golden assets are 512 class transparent ornamental resources and simplified legacy set is removed`() {
		val res = resourceRoot()
		val expected = listOf(
			"profile_frame_01_first_page_silver_base.xml",
			"profile_frame_02_first_light_blue_base.xml",
			"profile_frame_03_cyan_orbit_base.xml",
			"profile_frame_04_emerald_pulse_base.xml",
			"profile_frame_05_arcane_scholar_base.xml",
			"profile_frame_06_violet_halo_base.xml",
			"profile_frame_07_rose_nebula_base.xml",
			"profile_frame_08_crimson_ember_base.xml",
			"profile_frame_09_amber_manuscript_base.xml",
			"profile_frame_10_golden_manuscript_deluxe_base.xml",
			"profile_frame_11_eternal_library_prism_base.xml",
			"profile_frame_12_celestial_infinity_base.xml",
		)
		expected.forEach { fileName ->
			val file = File(res, "drawable/$fileName")
			assertTrue("Missing golden profile-frame asset: $fileName", file.isFile)
			val text = file.readText()
			assertTrue("Profile-frame master must use a 512 viewport: $fileName", text.contains("android:viewportWidth=\"512\"") && text.contains("android:viewportHeight=\"512\""))
			assertTrue("Profile-frame asset must be ornamental rather than a tiny placeholder: $fileName", file.length() > 1_800L)
		}

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
			assertFalse("Legacy simplified vector must not remain active: $fileName", File(res, "drawable/$fileName").exists())
		}
	}

	@Test
	fun `renderer keeps the required reveal badge glow state animation and fallback contract`() {
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
		assertTrue(source.contains("valframeScale=0.96f+0.04f*reveal.value"))
		assertTrue(source.contains("durationMillis=230"))
		assertTrue(source.contains("valbadgeScale=0.92f+badgeReveal.value*0.08f"))
		assertTrue(source.contains("durationMillis=190"))
		assertTrue(source.contains("targetValue=0.8f"))
		assertTrue(source.contains("durationMillis=280"))
		assertTrue(source.contains("setToSaturation(0.18f)"))
		assertTrue(source.contains("effectiveQualityMode==ProfileFrameQualityMode.NORMAL"))
		assertTrue(source.contains("animate&&!reduceMotion&&!powerSaveMode"))
		assertTrue(source.contains("ProfileFrameQualityMode.BATTERY_SAVER"))
		assertTrue(source.contains("state==ProfileFrameState.EQUIPPED||state==ProfileFrameState.PREVIEWING"))
		assertTrue(source.contains("ProfileFrameAmbient.ORBIT"))
		assertTrue(source.contains("ProfileFrameAmbient.LAUREL"))
		assertTrue(source.contains("ProfileFrameAmbient.PRISM"))
		assertTrue(source.contains("ProfileFrameAmbient.CELESTIAL"))
	}

	private fun resourceRoot(): File = sequenceOf(
		File("src/main/res"),
		File("app/src/main/res"),
	).firstOrNull(File::isDirectory) ?: error("Cannot find Android resources")

	private fun source(relativePath: String): String = sequenceOf(
		File("src/main", relativePath),
		File("app/src/main", relativePath),
	).firstOrNull(File::isFile)?.readText() ?: error("Cannot find production source: $relativePath")
}
