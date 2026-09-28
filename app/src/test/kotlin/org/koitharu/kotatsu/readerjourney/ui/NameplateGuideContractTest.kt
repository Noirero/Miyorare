package org.koitharu.kotatsu.readerjourney.ui

import java.io.File
import java.security.MessageDigest
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
		assertEquals(12, specs.values.map { it.thumbnailRes }.distinct().size)
		assertEquals(12, specs.values.map { it.ambient }.distinct().size)
		specs.forEach { (theme, spec) ->
			assertTrue("$theme idle duration must follow the guide", spec.idleDurationMs in checkNotNull(expectedIdleRanges[theme]))
			assertTrue("$theme one-shot accent must remain restrained", spec.oneShotDurationMs in 180..300)
			assertTrue("$theme title safe area must remain inside the reference plate", spec.titleWidthFraction in 0.50f..0.55f)
			assertNotEquals("$theme must use a dedicated catalog thumbnail", spec.drawableRes, spec.thumbnailRes)
		}
	}

	@Test
	fun `approved Nameplate V2 runtime set is exact and old packaged sources are absent`() {
		val res = resourceRoot()
		val runtime = File(res, "drawable-nodpi")
		val expectedSha256 = mapOf(
			"nameplate_01_first_page_silver_base.webp" to "e78df5663e3c00e19560d19a1f3187ec641113c8839117a201f7512debf897e2",
			"nameplate_01_first_page_silver_thumb.webp" to "1acf454a34c6b6129815c33743d924a4b019ca636a3baf95123abff8d033c57b",
			"nameplate_02_first_light_blue_base.webp" to "28bc9084de9514ba91d15b39bf33cbbbdd34ba38da870550967954fe05927d23",
			"nameplate_02_first_light_blue_thumb.webp" to "d87c2d5b610172777ee26a1d3656c54f166c4f8a6079d5a12c33645663eaa673",
			"nameplate_03_cyan_orbit_base.webp" to "d3c6cf5e12a8bb0539a43b0ec8c15bbce80b66950bbc6340a31a168b0000c311",
			"nameplate_03_cyan_orbit_thumb.webp" to "2ea655cb3103c3e4bb79b0ba8602488dfc32670429d5ee1e944ece56a22fe071",
			"nameplate_04_emerald_pulse_base.webp" to "8d3feedf1b4f9d926a81baef8132cdd769c52287634ab7d8b01bcd392ee667e9",
			"nameplate_04_emerald_pulse_thumb.webp" to "019ce116cff4448a244aaaf329cde531e43217349d4c9d92dfa6d3d0fcfd8acb",
			"nameplate_05_arcane_scholar_base.webp" to "416663de64a71bd0fd58f21e1415792b569039aab14b80e55f3b94011ea9cff1",
			"nameplate_05_arcane_scholar_thumb.webp" to "d9d39781f99cd07f2fef8ff10f6aaa8834ecaa739b41fb856702cbdf083542ac",
			"nameplate_06_violet_halo_base.webp" to "9e8794d9efe795f00f07356547885a6638aa6fa85f01ac8423dee0f873564e27",
			"nameplate_06_violet_halo_thumb.webp" to "5100b9fdd306b61fcd53921bbde8b467b3bd7e3c56518e89f48ba6b43f367ef7",
			"nameplate_07_rose_nebula_base.webp" to "f913741fb20e721a0ae3fc3043a1188213ab6f1dbf3cdb36c4c641eacf77c51e",
			"nameplate_07_rose_nebula_thumb.webp" to "5e34574b00276f6da7d32ae5d44b9288c9585ae82833c9e4fe72fb6c57cf3c89",
			"nameplate_08_crimson_ember_base.webp" to "ffd5792f87a9667e56d23100738c36b669db157ea41f0db1b412272f3b6ff4c3",
			"nameplate_08_crimson_ember_thumb.webp" to "167eb797a6fe8c007ee60a3c6a377e7dbc9e71c16d80cc93d44ced6db1c1f390",
			"nameplate_09_amber_manuscript_base.webp" to "22668df14317ad29680cc8893c467067173b1679ff5732abb0d03f3776515694",
			"nameplate_09_amber_manuscript_thumb.webp" to "8a0a76b78fa89b1bbeca7bd3664914dd198df935ab8503f7a8850dfc92b994cb",
			"nameplate_10_golden_manuscript_deluxe_base.webp" to "dd00a7902db401f2d39068e8f48544a6a884e4b212405c4d318aaa86d161a97a",
			"nameplate_10_golden_manuscript_deluxe_thumb.webp" to "5871914b4a27d525245df6bed3e30ee1fd3ea18c076c1090d0e0ada5256c75fb",
			"nameplate_11_eternal_library_prism_base.webp" to "07fe7164533cf9d37728e023dc22e502a5cb1641fa9989a1ae18b74bc1353b19",
			"nameplate_11_eternal_library_prism_thumb.webp" to "f98247a3976bfc9a4d143a1f400d1eac66ddb1e21a0e33d977ac603f439db70c",
			"nameplate_12_celestial_infinity_base.webp" to "899b88c90fb88d30af080985d31993c82ce868325b3773b353bc3352a656d2fb",
			"nameplate_12_celestial_infinity_thumb.webp" to "6662db0bbfe8e490a29471cb41529defa807b5d3f1e7011f10e698b2565da5e2",
		)
		val actual = runtime.listFiles().orEmpty()
			.filter { it.isFile && it.name.startsWith("nameplate_") && it.extension == "webp" }
			.associateBy(File::getName)

		assertEquals(expectedSha256.keys, actual.keys)
		assertEquals(1_143_416L, actual.values.sumOf(File::length))
		expectedSha256.forEach { (name, sha) ->
			val file = checkNotNull(actual[name])
			assertEquals("Exact supplied bytes changed for $name", sha, file.sha256())
			val expectedSize = if (name.endsWith("_thumb.webp")) 320 to 123 else 640 to 245
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
			File(runtime, "nameplate_09_amber_manuscript_base.webp"),
			File(runtime, "nameplate_10_golden_manuscript_deluxe_base.webp"),
			File(runtime, "nameplate_11_eternal_library_prism_base.webp"),
			File(runtime, "nameplate_12_celestial_infinity_base.webp"),
		)
		files.forEach { assertTrue("$it is missing", it.isFile) }
		assertEquals(4, files.map { it.sha256() }.distinct().size)
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
		assertTrue("Runtime title must use warm ivory instead of flat white", renderer.contains("Color(0xFFF6E8D0)"))
		assertTrue("Runtime title must keep a subtle readability shadow", renderer.contains("blurRadius=3.6f"))
		assertTrue(
			"Title fitting must shrink font before touching letter spacing",
			renderer.indexOf("varfontSp=typography.preferredFontSp-0.5f") <
				renderer.indexOf("varletterSpacingSp=typography.preferredLetterSpacingSp-0.10f"),
		)
		assertTrue("Title fitting must measure real layout overflow", renderer.contains("textMeasurer.measure("))
		assertTrue("Title fitting must reject measured visual overflow", renderer.contains(").hasVisualOverflow"))
		assertFalse(
			"Catalog title fitting must not use repeated onTextLayout recomposition loops",
			renderer.contains("onTextLayout="),
		)
		assertTrue(
			"Ellipsis must remain a final fallback after clip-based fitting",
			renderer.contains("overflow=if(fitted.ellipsisFallback)TextOverflow.EllipsiselseTextOverflow.Clip"),
		)
		assertTrue("Runtime title must center across the authored safe area", renderer.contains("modifier=Modifier.fillMaxWidth()"))
		assertTrue("Catalog must render dedicated thumbnails", renderer.contains("painterResource(asset.thumbnailRes)"))
		assertTrue("Preview/profile must render approved base assets", renderer.contains("valimageRes=asset.drawableRes"))
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
