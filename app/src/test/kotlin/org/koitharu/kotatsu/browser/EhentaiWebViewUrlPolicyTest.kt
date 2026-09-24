package org.koitharu.kotatsu.browser

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EhentaiWebViewUrlPolicyTest {

	@Test
	fun `search URL bypasses account filters without losing query`() {
		val result = EhentaiWebViewUrlPolicy.withoutAccountFilters(
			"https://exhentai.org/?f_search=Gragas&f_cats=1017",
		).toHttpUrl()

		assertEquals("Gragas", result.queryParameter("f_search"))
		assertEquals("1017", result.queryParameter("f_cats"))
		assertEquals("1", result.queryParameter("advsearch"))
		assertEquals("on", result.queryParameter("f_sfl"))
		assertEquals("on", result.queryParameter("f_sfu"))
		assertEquals("on", result.queryParameter("f_sft"))
	}

	@Test
	fun `policy is idempotent for already unfiltered search`() {
		val original =
			"https://exhentai.org/?f_search=Gragas&advsearch=1&f_sfl=on&f_sfu=on&f_sft=on"
		assertEquals(original, EhentaiWebViewUrlPolicy.withoutAccountFilters(original))
	}

	@Test
	fun `e hentai front page also bypasses account filters`() {
		val result = EhentaiWebViewUrlPolicy.withoutAccountFilters("https://e-hentai.org/").toHttpUrl()
		assertEquals("1", result.queryParameter("advsearch"))
		assertEquals("on", result.queryParameter("f_sfl"))
		assertEquals("on", result.queryParameter("f_sfu"))
		assertEquals("on", result.queryParameter("f_sft"))
	}

	@Test
	fun `gallery detail URL remains untouched`() {
		val original = "https://exhentai.org/g/123456/abcdef1234/"
		assertEquals(original, EhentaiWebViewUrlPolicy.withoutAccountFilters(original))
	}

	@Test
	fun `unrelated site remains untouched`() {
		val original = "https://example.com/?f_search=Gragas"
		assertEquals(original, EhentaiWebViewUrlPolicy.withoutAccountFilters(original))
	}

	@Test
	fun `existing search parameters are preserved`() {
		val result = EhentaiWebViewUrlPolicy.withoutAccountFilters(
			"https://exhentai.org/?f_search=Gragas&next=12345&f_sh=on",
		).toHttpUrl()
		assertEquals("12345", result.queryParameter("next"))
		assertEquals("on", result.queryParameter("f_sh"))
		assertTrue(result.queryParameterNames.containsAll(setOf("f_sfl", "f_sfu", "f_sft")))
	}
}
