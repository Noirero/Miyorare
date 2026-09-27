package org.koitharu.kotatsu.readerjourney.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId

class NameplateGuideContractTest {

	@Test
	fun `all twelve tiers keep distinct identity motion and golden-reference title safe areas`() {
		val expectedIdleRanges = mapOf(
			RankThemeId.FIRST_PAGE to 12_000..18_000,
			RankThemeId.FIRST_LIGHT to 8_000..12_000,
			RankThemeId.CYAN_CODEX to 12_000..18_000,
			RankThemeId.EMERALD_COMPASS to 8_000..12_000,
			RankThemeId.VIOLET_VAULT to 8_000..14_000,
			RankThemeId.ARCANE_SCHOLAR to 8_000..14_000,
			RankThemeId.NEON_ARCHIVE to 8_000..20_000,
			RankThemeId.CRIMSON_LIBRARY to 10_000..16_000,
			RankThemeId.EMBER_VETERAN to 12_000..18_000,
			RankThemeId.GOLDEN_MANUSCRIPT to 10_000..16_000,
			RankThemeId.IMPERIAL_AURORA to 10_000..16_000,
			RankThemeId.ETERNAL_LIBRARY to 12_000..20_000,
		)
		val specs = RankThemeId.entries.associateWith(NameplateAssetRegistry::resolve)

		assertEquals(12, specs.size)
		assertEquals(12, specs.values.map { it.drawableRes }.distinct().size)
		assertEquals(12, specs.values.map { it.ambient }.distinct().size)
		specs.forEach { (theme, spec) ->
			assertTrue("$theme idle duration must follow the guide", spec.idleDurationMs in checkNotNull(expectedIdleRanges[theme]))
			assertTrue("$theme one-shot accent must remain restrained", spec.oneShotDurationMs in 180..300)
			assertTrue("$theme title safe area must remain inside the reference plate", spec.titleWidthFraction in 0.50f..0.55f)
		}
		assertNotEquals(
			specs.getValue(RankThemeId.IMPERIAL_AURORA).drawableRes,
			specs.getValue(RankThemeId.ETERNAL_LIBRARY).drawableRes,
		)
	}

	@Test
	fun `asset architecture is fidelity driven with lightweight catalog thumbnails and rich runtime masters`() {
		val res = resourceRoot()
		val svgRichStems = listOf(
			"01_first_page_silver",
			"02_first_light_blue",
			"03_cyan_orbit",
			"04_emerald_pulse",
			"05_arcane_scholar",
			"06_violet_halo",
			"07_rose_nebula",
			"08_crimson_ember",
			"09_amber_manuscript",
			"10_golden_manuscript_deluxe",
			"12_celestial_infinity",
		)
		svgRichStems.forEach { stem ->
			val svg = File(res, "raw/nameplate_${stem}_master.svg")
			assertTrue("Missing transparent golden-reference runtime master for $stem", svg.isFile)
			val text = svg.readText()
			assertTrue(text.contains("viewBox=\"0 0 1200 460\""))
			assertTrue(
				"Runtime master must directly embed the supplied golden-reference raster material",
				text.contains("data:image/png;base64,"),
			)
			assertFalse("Runtime title must remain separate", text.contains("<text"))
			val payload = text.substringAfter("data:image/png;base64,").substringBefore("\"")
			val bytes = java.util.Base64.getDecoder().decode(payload)
			assertTrue("Embedded golden-reference raster is suspiciously small", bytes.size > 20_000)
			assertEquals(0x89, bytes[0].toInt() and 0xFF)
			assertEquals('P'.code, bytes[1].toInt() and 0xFF)
			assertEquals('N'.code, bytes[2].toInt() and 0xFF)
			assertEquals('G'.code, bytes[3].toInt() and 0xFF)
			assertTrue("Catalog must retain lightweight drawable thumbnail", File(res, "drawable/nameplate_${stem}_base.xml").isFile)
		}

		val prism = File(res, "drawable/nameplate_11_eternal_library_prism_normal.webp")
		assertRichPrismWebp(prism)
		assertFalse(
			"Flat SVG Prism candidate must not remain the production master",
			File(res, "raw/nameplate_11_eternal_library_prism_master.svg").exists(),
		)
		assertTrue(
			"Prism catalog must remain lightweight",
			File(res, "drawable/nameplate_11_eternal_library_prism_base.xml").isFile,
		)
	}

	@Test
	fun `09 10 11 and 12 remain structurally distinct before runtime animation`() {
		val res = resourceRoot()
		fun embedded(stem: String): ByteArray {
			val text = File(res, "raw/nameplate_${stem}_master.svg").readText()
			val payload = text.substringAfter("data:image/png;base64,").substringBefore("\"")
			return java.util.Base64.getDecoder().decode(payload)
		}
		val manuscript = embedded("09_amber_manuscript")
		val royal = embedded("10_golden_manuscript_deluxe")
		val celestial = embedded("12_celestial_infinity")
		val prism = File(res, "drawable/nameplate_11_eternal_library_prism_normal.webp")

		assertTrue("09 poster-derived manuscript master is missing material detail", manuscript.size > 30_000)
		assertTrue("10 poster-derived royal master is missing material detail", royal.size > 30_000)
		assertTrue("12 poster-derived celestial master is missing material detail", celestial.size > 30_000)
		assertFalse("09 and 10 must remain different static identities", manuscript.contentEquals(royal))
		assertFalse("10 and 12 must remain different static identities", royal.contentEquals(celestial))
		assertRichPrismWebp(prism)
	}

	@Test
	fun `renderer preserves runtime title state motion fallback and selected-only ambient`() {
		assertEquals(
			setOf(NameplateState.LOCKED, NameplateState.UNLOCKED, NameplateState.PREVIEWING, NameplateState.EQUIPPED),
			NameplateState.entries.toSet(),
		)
		assertEquals(
			setOf(NameplateQualityMode.NORMAL, NameplateQualityMode.REDUCED, NameplateQualityMode.BATTERY_SAVER),
			NameplateQualityMode.entries.toSet(),
		)

		val renderer = source("kotlin/org/koitharu/kotatsu/readerjourney/ui/ExclusiveNameplate.kt")
			.replace(Regex("\\s+"), "")
		assertTrue(renderer.contains("ContentScale.Fit"))
		assertTrue(renderer.contains("0.97f+(0.03f*reveal.value)"))
		assertTrue(renderer.contains("durationMillis=140"))
		assertTrue(renderer.contains("translationY=if(normalMotion)(1f-textReveal.value)*textOffsetPxelse0f"))
		assertTrue(renderer.contains("1f-(0.02f*pressProgress)"))
		assertTrue(renderer.contains("0.045f*pressProgress"))
		assertTrue(renderer.contains("setToSaturation(0.60f)"))
		assertTrue(renderer.contains("effectiveQualityMode!=NameplateQualityMode.BATTERY_SAVER"))
		assertTrue(renderer.contains("state==NameplateState.EQUIPPED||state==NameplateState.PREVIEWING"))
		assertTrue(renderer.contains("animate&&!reduceMotion&&!powerSaveMode"))
		assertTrue(renderer.contains("NameplateQualityMode.BATTERY_SAVER"))
		assertTrue(renderer.contains("NameplateAmbient.PRISM"))
		assertTrue(renderer.contains("NameplateAmbient.CELESTIAL_INFINITY"))
		assertTrue("Prism shimmer must leave the title band untouched", renderer.contains("h*0.34f") && renderer.contains("h*0.66f"))
		assertTrue("Celestial light must stay on separate outer lobes", renderer.contains("valleftLoop=phase<0.5f"))
		assertTrue(renderer.contains("funExclusiveNameplateTitle("))
		assertTrue(renderer.contains("FontFamily.Serif"))
		assertTrue(
			"Catalog must keep lightweight drawable thumbnails",
			renderer.contains("usage==NameplateUsage.CATALOG->asset.thumbnailRes"),
		)
		assertTrue(
			"Catalog must not add a runtime radial-glow canvas on top of static thumbnails",
			renderer.contains("valruntimeGlowAlpha=if(usage==NameplateUsage.CATALOG){0f}else{"),
		)
		assertTrue(
			"SVG masters must be runtime-only and skipped when a rich drawable is selected",
			renderer.contains("valuseRawMaster=usage!=NameplateUsage.CATALOG&&!useRichDrawable&&rawMaster!=null"),
		)
		assertTrue(
			"Rich Prism WebP must bypass SVG decoding",
			renderer.contains("valuseRichDrawable=usage!=NameplateUsage.CATALOG&&richDrawable!=null") &&
				renderer.contains("useRichDrawable->checkNotNull(richDrawable)"),
		)
		assertTrue("Preview/profile SVG masters must use the configured decoder", renderer.contains("AsyncImage("))
	}

	@Test
	fun `legacy generic renderer is removed and catalog remains static`() {
		val visual = source("kotlin/org/koitharu/kotatsu/readerjourney/ui/ReferenceRankThemeVisuals.kt")
		assertFalse("Nameplates must not share the old generic body()", visual.contains("fun body(notch:Float=.08f"))
		assertTrue("Reference renderer must delegate to asset-first engine", visual.contains("ExclusiveNameplate("))

		val collection = source("kotlin/org/koitharu/kotatsu/stats/ui/ReaderJourneyExclusiveCollection.kt")
			.replace(Regex("\\s+"), "")
		assertTrue(collection.contains("usage=NameplateUsage.PREVIEW"))
		assertTrue(collection.contains("state=NameplateState.PREVIEWING"))
		assertTrue(collection.contains("usage=NameplateUsage.CATALOG"))
		assertTrue(collection.contains("animate=false"))
		assertTrue(collection.contains("ReaderJourneyCollectionFilter.NAMEPLATES"))
		assertTrue(collection.contains("NameplateState.LOCKED"))
		assertTrue(collection.contains("selectedNameplateId"))
		assertTrue(collection.contains("collectIsPressedAsState()"))
		assertTrue(collection.contains("pressed=pressed"))

		val profile = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")
		assertTrue(profile.contains("state=NameplateState.EQUIPPED"))
		assertTrue(profile.contains("usage=NameplateUsage.PROFILE"))
	}

	private fun assertRichPrismWebp(prism: File) {
		assertTrue("Prism must use baked transparent WebP material", prism.isFile)
		val bytes = prism.readBytes()
		assertTrue("Prism WebP must be a non-trivial poster-derived material asset", bytes.size > 50_000)
		assertTrue("Prism WebP is truncated", bytes.size >= 12)
		assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
		assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
	}

	private fun String.countOccurrences(needle: String): Int =
		windowedSequence(needle.length, 1).count { it == needle }

	private fun resourceRoot(): File = sequenceOf(
		File("src/main/res"),
		File("app/src/main/res"),
	).firstOrNull(File::isDirectory) ?: error("Cannot find Android resources")

	private fun source(relativePath: String): String = sequenceOf(
		File("src/main", relativePath),
		File("app/src/main", relativePath),
	).firstOrNull(File::isFile)?.readText() ?: error("Cannot find production source: $relativePath")
}
