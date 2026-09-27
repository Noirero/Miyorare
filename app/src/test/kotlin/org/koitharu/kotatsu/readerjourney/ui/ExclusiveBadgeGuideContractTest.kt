package org.koitharu.kotatsu.readerjourney.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVisualRegistry
import org.koitharu.kotatsu.readerjourney.theme.ReferenceBadgeStyle
import java.io.File

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
	fun `prism and celestial ultimate tiers remain structurally distinct`() {
		val prism = RankThemeVisualRegistry.resolve(RankThemeId.IMPERIAL_AURORA)!!
		val celestial = RankThemeVisualRegistry.resolve(RankThemeId.ETERNAL_LIBRARY)!!
		assertEquals(ReferenceBadgeStyle.ETERNAL_LIBRARY_PRISM, prism.badgeStyle)
		assertEquals(ReferenceBadgeStyle.CELESTIAL_INFINITY, celestial.badgeStyle)
		assertNotEquals(prism.badgeStyle, celestial.badgeStyle)

		val prismDrawable = source("res/drawable/badge_11_eternal_library_prism_base.xml")
		val celestialDrawable = source("res/drawable/badge_12_celestial_infinity_base.xml")
		assertTrue(prismDrawable.contains("L256,54"))
		assertTrue(prismDrawable.contains("L383,190"))
		assertTrue(celestialDrawable.contains("C142,139"))
		assertTrue(celestialDrawable.contains("C370,139"))
		assertFalse(celestialDrawable.contains("L383,190"))
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
	}

	@Test
	fun `catalog stays static while large preview owns the ambient animation`() {
		val collection = source("kotlin/org/koitharu/kotatsu/stats/ui/ReaderJourneyExclusiveCollection.kt")
			.replace(Regex("\\s+"), "")
		assertTrue(collection.contains("modifier=Modifier.size(136.dp)"))
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

	@Test
	fun `static foundations cover all twelve badges and are not generic color swaps`() {
		val names = listOf(
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
		val bodies = names.map { source("res/drawable/$it").replace(Regex("\\s+"), "") }
		assertEquals(12, bodies.size)
		assertEquals(12, bodies.distinct().size)
		assertTrue(bodies.all { it.contains("<vector") })
		assertTrue(bodies.all { it.count { ch -> ch == '<' } >= 12 })
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
