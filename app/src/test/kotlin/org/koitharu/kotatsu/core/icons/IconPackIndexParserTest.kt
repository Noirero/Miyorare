package org.koitharu.kotatsu.core.icons

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconPackIndexParserTest {

	private fun indexWith(packs: String) = JSONObject("""{"schema":1,"packs":[$packs]}""")

	private fun fileJson(
		key: String = "ic_appearance",
		url: String = "https://example.com/icons/ic_appearance.svg",
		sha256: String = "a".repeat(64),
		sizeBytes: Long = 262,
	) = """{"key":"$key","url":"$url","sha256":"$sha256","sizeBytes":$sizeBytes}"""

	private fun packJson(
		id: String = "rounded",
		files: String = fileJson(),
	) = """{"id":"$id","name":"Rounded","version":"1.0.0","license":"MIT","files":[$files]}"""

	@Test
	fun `parses a valid pack`() {
		val packs = IconPackIndexParser.parse(indexWith(packJson()))
		assertEquals(1, packs.size)
		assertEquals("rounded", packs[0].id)
		assertEquals("ic_appearance", packs[0].files[0].key)
		assertEquals(262L, packs[0].totalSizeBytes)
		assertEquals("ic_appearance.svg", packs[0].files[0].svgFileName)
	}

	@Test
	fun `rejects a pack id with path segments`() {
		val packs = IconPackIndexParser.parse(indexWith(packJson(id = "../evil")))
		assertTrue(packs.isEmpty())
	}

	@Test
	fun `rejects a glyph key that is not a plain drawable name`() {
		val packs = IconPackIndexParser.parse(indexWith(packJson(files = fileJson(key = "../ic_evil"))))
		assertTrue(packs.isEmpty())
		val packs2 = IconPackIndexParser.parse(indexWith(packJson(files = fileJson(key = "ic_evil.svg"))))
		assertTrue(packs2.isEmpty())
	}

	@Test
	fun `rejects a non-https url and a bad hash`() {
		val http = IconPackIndexParser.parse(
			indexWith(packJson(files = fileJson(url = "http://example.com/ic_appearance.svg"))),
		)
		assertTrue(http.isEmpty())
		val badHash = IconPackIndexParser.parse(
			indexWith(packJson(files = fileJson(sha256 = "xyz"))),
		)
		assertTrue(badHash.isEmpty())
	}

	@Test
	fun `skips an invalid pack but keeps valid ones`() {
		val packs = IconPackIndexParser.parse(indexWith(packJson(id = "BAD ID") + "," + packJson()))
		assertEquals(1, packs.size)
		assertEquals("rounded", packs[0].id)
	}
}

class IconPackSvgTest {

	private val cleanGlyph = """
		<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8.6"/></svg>
	""".trimIndent()

	@Test
	fun `accepts a clean static glyph`() {
		assertTrue(IconPackSvg.isSafeGlyph(cleanGlyph))
	}

	@Test
	fun `rejects a glyph with a script`() {
		assertFalse(IconPackSvg.isSafeGlyph(cleanGlyph.replace("</svg>", "<script>alert(1)</script></svg>")))
	}

	@Test
	fun `rejects a glyph with an external reference`() {
		assertFalse(IconPackSvg.isSafeGlyph(cleanGlyph.replace("</svg>", "<image href=\"https://evil.example/x.png\"/></svg>")))
	}

	@Test
	fun `rejects a glyph without the 24 by 24 viewBox`() {
		assertFalse(IconPackSvg.isSafeGlyph(cleanGlyph.replace("0 0 24 24", "0 0 48 48")))
	}
}
