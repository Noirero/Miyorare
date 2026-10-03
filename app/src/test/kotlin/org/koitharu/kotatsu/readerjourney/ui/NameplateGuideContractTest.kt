package org.koitharu.kotatsu.readerjourney.ui

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId

class NameplateGuideContractTest {

	@Test
	fun `all twelve tiers keep distinct rank artwork and motion identity`() {
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
		}
	}

	@Test
	fun `final rank title WebP set is exact and old packaged sources are absent`() {
		val res = resourceRoot()
		val runtime = File(res, "drawable-nodpi")
		val expectedSha256 = mapOf(
			"nameplate_01_newcomer.webp" to "41b50b76377c2380d36ef0bc4285a38aa24edfdb5ea6848a382bc338ba721ba3",
			"nameplate_02_reader.webp" to "9a38ceeb5532b721ac7dbfa82c63a8b09323a83ac365a1c38fc604762feb3c5f",
			"nameplate_03_bookworm.webp" to "acf85d64ef7120c521d28bb621db45b958994ccd3cefb2b0f17abe3a51aac578",
			"nameplate_04_explorer.webp" to "7aec449a7fef1b1d34f25331ebaa481824bcc375daecd7b0d84992f691e8a0e4",
			"nameplate_05_collector.webp" to "ef034c8affafd9e9b3a12ab82dc66186e063aa23c154b5046db36eabf09e2c8b",
			"nameplate_06_scholar.webp" to "85a245846a5451b124568f841fc34b99baf4bd12e7a76910a4931195a28f1693",
			"nameplate_07_archivist.webp" to "a5b8e47a2a00d5fbc96fe2c8c40eb14cd207f3419d56afa0c40844c48bbedf1b",
			"nameplate_08_bibliophile.webp" to "b8e5eaea2332e547618882d34c7a147589e3cccc5311373c6fccbd2e51373420",
			"nameplate_09_veteran_reader.webp" to "137ee0cf86c2c04cb3a57616591931d15a124e793dced367176681403fd6602c",
			"nameplate_10_master_reader.webp" to "533203c672aebeca064378b06c119988de925d5099847c9b889ce63a8db130ce",
			"nameplate_11_grand_reader.webp" to "caff8ca65a77c9253a3f691e72341c0542123a1d01727b2ce78efffa21636af4",
			"nameplate_12_legend.webp" to "fc39daba80ad1a54981c868d69a5123356233263f184ab4600d36ca431b4db91",
		)
		val actual = runtime.listFiles().orEmpty()
			.filter { it.isFile && it.name.startsWith("nameplate_") && it.extension == "webp" }
			.associateBy(File::getName)

		assertEquals(expectedSha256.keys, actual.keys)
		assertEquals(912_122L, actual.values.sumOf(File::length))
		expectedSha256.forEach { (name, sha) ->
			val file = checkNotNull(actual[name])
			assertEquals("Exact supplied bytes changed for $name", sha, file.sha256())
			val expectedSize = 600 to 230
			assertEquals("$name dimensions changed", expectedSize, file.webpVp8xSize())
		}

		val drawable = File(res, "drawable")
		assertTrue(
			"Legacy Nameplate drawable resources must not remain packaged",
			drawable.listFiles().orEmpty().none {
				it.name.startsWith("nameplate_") &&
					(it.name.endsWith("_base.xml") || it.name.endsWith("_overlay.xml") || it.name.endsWith("_normal.webp"))
			},
		)
		val raw = File(res, "raw")
		assertTrue(
			"Legacy master SVGs must not remain packaged",
			raw.listFiles().orEmpty().none { it.name.startsWith("nameplate_") && it.name.endsWith("_master.svg") },
		)
	}

	@Test
	fun `09 10 11 and 12 remain structurally distinct before runtime animation`() {
		val runtime = File(resourceRoot(), "drawable-nodpi")
		val files = listOf(
			File(runtime, "nameplate_09_veteran_reader.webp"),
			File(runtime, "nameplate_10_master_reader.webp"),
			File(runtime, "nameplate_11_grand_reader.webp"),
			File(runtime, "nameplate_12_legend.webp"),
		)
		files.forEach { assertTrue("$it is missing", it.isFile) }
		assertEquals(4, files.map { it.sha256() }.distinct().size)
	}

	@Test
	fun `renderer preserves final artwork state motion fallback and selected-only ambient`() {
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
		assertFalse("Rank title is baked into the final image", renderer.contains("funExclusiveNameplateTitle("))
		assertFalse("No composable slot may paint Achievement text over the image", renderer.contains("content:@Composable"))
		assertFalse("No center scrim may change the supplied artwork", renderer.contains("titleScrimAlpha"))
		assertFalse("No Compose Text may overlay the supplied rank title", renderer.contains("Text("))
		assertTrue("Baked title must retain localized accessibility", renderer.contains("stringResource(spec.themeId.rank.titleRes)"))
		assertTrue("Catalog must render final artwork through the bounded bitmap cache", renderer.contains("NameplateCatalogBitmapCache.get(context,asset.drawableRes)"))
		assertTrue("Preview/profile must render approved final assets", renderer.contains("valimageRes=asset.drawableRes"))
		assertFalse("Legacy SVG runtime decoding must stay removed", renderer.contains("AsyncImage("))
		assertFalse("Legacy layered production assets must stay removed", renderer.contains("overlayRes"))
		assertTrue(
			"Catalog must not add a runtime radial-glow canvas on top of static thumbnails",
			renderer.contains("valruntimeGlowAlpha=if(usage==NameplateUsage.CATALOG){0f}else{"),
		)
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
		assertTrue("Achievement selection remains visible as separate profile text", profile.contains("text=titleText"))
		val adapter = source("kotlin/org/koitharu/kotatsu/readerjourney/ui/ReferenceRankThemeVisuals.kt")
			.substringAfter("fun ReferenceRankThemeNameplate(").substringBefore("fun ReferenceRankThemeCard(")
		assertFalse("Nameplate adapter must not accept an Achievement title", adapter.contains("title: String"))
		assertFalse("Nameplate adapter must not expose a content overlay", adapter.contains("content:"))
	}

	private fun File.sha256(): String =
		MessageDigest.getInstance("SHA-256").digest(readBytes()).joinToString("") { "%02x".format(it) }

	private fun File.webpVp8xSize(): Pair<Int, Int> {
		val bytes = readBytes()
		assertTrue("$name is truncated", bytes.size >= 30)
		assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
		assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
		assertEquals("VP8X", String(bytes, 12, 4, Charsets.US_ASCII))
		fun u24(offset: Int): Int =
			(bytes[offset].toInt() and 0xff) or
				((bytes[offset + 1].toInt() and 0xff) shl 8) or
				((bytes[offset + 2].toInt() and 0xff) shl 16)
		return (u24(24) + 1) to (u24(27) + 1)
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
