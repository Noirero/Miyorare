package org.koitharu.kotatsu.readerjourney.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVisualRegistry
import org.koitharu.kotatsu.readerjourney.theme.ReferenceBadgeStyle
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream

class ExclusiveBadgeGuideContractTest {

	@Test
	fun `all twelve badge identities stay unique and match the golden-reference taxonomy`() {
		val specs = RankThemeVisualRegistry.all
		assertEquals(12, specs.size)
		assertEquals(12, specs.map { it.badgeStyle }.distinct().size)
		assertEquals(
			listOf(
				ReferenceBadgeStyle.FIRST_PAGE_SILVER_BOOK,
				ReferenceBadgeStyle.FIRST_LIGHT_COMPASS,
				ReferenceBadgeStyle.CYAN_ORBIT,
				ReferenceBadgeStyle.EMERALD_CRYSTAL,
				ReferenceBadgeStyle.ARCANE_BOOK,
				ReferenceBadgeStyle.VIOLET_MOON,
				ReferenceBadgeStyle.ROSE_NEBULA,
				ReferenceBadgeStyle.CRIMSON_CRYSTAL,
				ReferenceBadgeStyle.AMBER_MANUSCRIPT,
				ReferenceBadgeStyle.GOLDEN_ROYAL,
				ReferenceBadgeStyle.ETERNAL_LIBRARY_PRISM,
				ReferenceBadgeStyle.CELESTIAL_INFINITY,
			),
			specs.map { it.badgeStyle },
		)
	}

	@Test
	fun `prism and celestial ultimate tiers remain structurally and materially distinct`() {
		val prism = RankThemeVisualRegistry.resolve(RankThemeId.IMPERIAL_AURORA)!!
		val celestial = RankThemeVisualRegistry.resolve(RankThemeId.ETERNAL_LIBRARY)!!
		assertEquals(ReferenceBadgeStyle.ETERNAL_LIBRARY_PRISM, prism.badgeStyle)
		assertEquals(ReferenceBadgeStyle.CELESTIAL_INFINITY, celestial.badgeStyle)
		assertNotEquals(prism.badgeStyle, celestial.badgeStyle)

		val assets = payloadEntries()
		val prismBytes = checkNotNull(assets["badge_11_eternal_library_prism_base.webp"])
		val celestialBytes = checkNotNull(assets["badge_12_celestial_infinity_base.webp"])
		assertTrue(prismBytes.size > 50_000)
		assertTrue(celestialBytes.size > 50_000)
		assertFalse(prismBytes.contentEquals(celestialBytes))
	}

	@Test
	fun `all twelve tiers use baked WebP foundations and dedicated thumbnails after the static fidelity gate`() {
		val assets = payloadEntries()
		val stems = listOf(
			"badge_01_first_page_silver",
			"badge_02_first_light_blue",
			"badge_03_cyan_orbit",
			"badge_04_emerald_pulse",
			"badge_05_arcane_scholar",
			"badge_06_violet_halo",
			"badge_07_rose_nebula",
			"badge_08_crimson_ember",
			"badge_09_amber_manuscript",
			"badge_10_golden_manuscript_deluxe",
			"badge_11_eternal_library_prism",
			"badge_12_celestial_infinity",
		)
		stems.forEachIndexed { index, stem ->
			val tier = index + 1
			val full = checkNotNull(assets["${stem}_base.webp"]) { "Missing full WebP for tier $tier" }
			val thumb = checkNotNull(assets["${stem}_thumb.webp"]) { "Missing thumbnail WebP for tier $tier" }
			assertTrue("Tier $tier full artwork is suspiciously small", full.size > 50_000)
			assertTrue("Tier $tier thumbnail is suspiciously small", thumb.size > 20_000)
			assertTrue("Tier $tier thumbnail must be lighter than full artwork", thumb.size < full.size)
		}
		assertTrue(assets.containsKey("exclusive_badge_golden_reference_sheet.webp"))
	}

	@Test
	fun `production payload contains exactly the unchanged V2 sources and test-only golden reference`() {
		val assets = payloadEntries()
		val sources = sourceFile("badge-assets/v2-source").listFiles()!!
			.filter { it.extension == "webp" }
		assertEquals(24, sources.size)
		assertEquals(sources.map { it.name }.toSet() + "exclusive_badge_golden_reference_sheet.webp", assets.keys)
		sources.forEach { file ->
			assertTrue("Payload must use unchanged V2 bytes: ${file.name}", file.readBytes().contentEquals(assets[file.name]!!))
		}
	}

	@Test
	fun `legacy flat vector foundations are removed for all twelve tiers`() {
		val legacy = listOf(
			"badge_01_first_page_silver_base.xml",
			"badge_02_first_light_blue_base.xml",
			"badge_03_cyan_orbit_base.xml",
			"badge_04_emerald_pulse_base.xml",
			"badge_05_arcane_scholar_base.xml",
			"badge_06_violet_halo_base.xml",
			"badge_07_rose_nebula_base.xml",
			"badge_08_crimson_ember_base.xml",
			"badge_09_amber_manuscript_base.xml",
			"badge_10_golden_manuscript_deluxe_base.xml",
			"badge_11_eternal_library_prism_base.xml",
			"badge_12_celestial_infinity_base.xml",
		)
		legacy.forEach { name ->
			assertFalse("Golden-reference badge must not regress to the rejected flat vector: $name", sourceFile("res/drawable/$name").isFile)
		}
	}

	@Test
	fun `badge engine implements locked preview equipped pressed and low-motion policies`() {
		val engine = source("kotlin/org/koitharu/kotatsu/readerjourney/ui/ExclusiveBadge.kt")
			.replace(Regex("\\s+"), "")
		assertTrue(engine.contains("enumclassBadgeState{LOCKED,UNLOCKED,PREVIEWING,EQUIPPED,PRESSED"))
		assertTrue(engine.contains("KEY_RANK_THEME_REDUCE_MOTION"))
		assertTrue(engine.contains("PowerManager.ACTION_POWER_SAVE_MODE_CHANGED"))
		assertTrue(engine.contains("setToSaturation(0.45f)"))
		assertTrue(engine.contains("targetValue=if(pressed)0.975felse1f"))
		assertTrue(engine.contains("durationMillis=if(pressed)90else135"))
		assertTrue(engine.contains("if(idleEnabled){BadgeAmbientOverlay("))
		assertTrue(engine.contains("effectiveQuality!=BadgeQualityMode.BATTERY_SAVER"))
		assertTrue(engine.contains("!reduceMotion&&!powerSaveMode"))
		assertTrue(engine.contains("badge_01_first_page_silver_thumb"))
		assertTrue(engine.contains("badge_02_first_light_blue_thumb"))
		assertTrue(engine.contains("badge_03_cyan_orbit_thumb"))
		assertTrue(engine.contains("badge_04_emerald_pulse_thumb"))
		assertTrue(engine.contains("badge_11_eternal_library_prism_thumb"))
		assertTrue(engine.contains("badge_12_celestial_infinity_thumb"))
	}

	@Test
	fun `catalog stays static while large preview owns the ambient animation`() {
		val collection = source("kotlin/org/koitharu/kotatsu/stats/ui/ReaderJourneyExclusiveCollection.kt")
			.replace(Regex("\\s+"), "")
		assertTrue(collection.contains("modifier=Modifier.size(148.dp)"))
		assertTrue(collection.contains("state=BadgeState.PREVIEWING,animate=true,qualityMode=BadgeQualityMode.NORMAL,useThumbnail=false"))
		assertTrue(collection.contains("state=if(pressed)BadgeState.PRESSEDelseBadgeState.UNLOCKED,animate=false,qualityMode=BadgeQualityMode.REDUCED,useThumbnail=true"))
		assertFalse(collection.contains("animate=selected&&!pressed"))
	}

	@Test
	fun `mini profile badge cannot compete with profile frame animation`() {
		val profile = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")
		assertTrue(profile.contains("state=BadgeState.EQUIPPED"))
		assertTrue(profile.contains("animate=false"))
		assertTrue(profile.contains("useThumbnail=true"))
		assertTrue(profile.contains("profileMode=true"))
		assertTrue(profile.contains(".size(34.dp)"))
	}

	private fun payloadEntries(): Map<String, ByteArray> {
		val payload = sourceFile("badge-assets/exclusive_badge_material_payload.b64")
		val decoded = Base64.getMimeDecoder().decode(payload.readText())
		val result = linkedMapOf<String, ByteArray>()
		ZipInputStream(ByteArrayInputStream(decoded)).use { zip ->
			var entry = zip.nextEntry
			while (entry != null) {
				if (!entry.isDirectory) {
					result[entry.name] = zip.readBytes()
				}
				zip.closeEntry()
				entry = zip.nextEntry
			}
		}
		return result
	}

	private fun source(relativePath: String): String = sourceFile(relativePath).readText()

	private fun sourceFile(relativePath: String): File {
		return sequenceOf(
			File("src/main", relativePath),
			File("app/src/main", relativePath),
		)
			.firstOrNull(File::exists)
			?: File("app/src/main", relativePath)
	}
}

