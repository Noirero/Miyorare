package org.koitharu.kotatsu.core.fonts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontPackIndexParserTest {

	private fun indexWith(packs: String) = JSONObject("""{"schema":2,"packs":[$packs]}""")

	private fun packJson(
		id: String = "literata",
		files: String = """{"file":"Literata.ttf","url":"https://example.com/Literata.ttf","sha256":"${"a".repeat(64)}","sizeBytes":100,"family":"Literata","variable":true,"italic":false,"weights":[400,700]}""",
	) = """{"id":"$id","name":"Literata","version":"1.0.0","files":[$files]}"""

	@Test
	fun `parses a valid pack`() {
		val packs = FontPackIndexParser.parse(indexWith(packJson()))
		assertEquals(1, packs.size)
		assertEquals("literata", packs[0].id)
		assertEquals(100L, packs[0].totalSizeBytes)
		assertEquals(listOf(400, 700), packs[0].files[0].weights)
	}

	@Test
	fun `rejects a pack id with path segments`() {
		val packs = FontPackIndexParser.parse(indexWith(packJson(id = "../evil")))
		assertTrue(packs.isEmpty())
	}

	@Test
	fun `rejects a file name with path segments`() {
		val bad = """{"file":"../evil.ttf","url":"https://example.com/e.ttf","sha256":"${"a".repeat(64)}","sizeBytes":10}"""
		val packs = FontPackIndexParser.parse(indexWith(packJson(files = bad)))
		assertTrue(packs.isEmpty())
	}

	@Test
	fun `rejects a non-https url and a bad hash`() {
		val badUrl = """{"file":"A.ttf","url":"http://example.com/a.ttf","sha256":"${"a".repeat(64)}","sizeBytes":10}"""
		assertTrue(FontPackIndexParser.parse(indexWith(packJson(files = badUrl))).isEmpty())
		val badHash = """{"file":"A.ttf","url":"https://example.com/a.ttf","sha256":"xyz","sizeBytes":10}"""
		assertTrue(FontPackIndexParser.parse(indexWith(packJson(files = badHash))).isEmpty())
	}

	@Test
	fun `sha256 of known bytes`() {
		assertEquals(
			"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
			FontPackHashes.sha256(ByteArray(0)),
		)
	}
}
