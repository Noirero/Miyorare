package org.koitharu.kotatsu.scrobbling.shikimori.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.CurlLoggingInterceptor
import org.koitharu.kotatsu.scrobbling.common.domain.model.*

class ShikimoriTrackerDetailsTest {
	private val target = TrackerTarget(ScrobblerService.SHIKIMORI, "2")
	private fun root(body: String) = Json.parseToJsonElement(body).jsonObject

	@Test fun `character and person namespaces stay in separate sections`() {
		val json = root("""{"data":{"mangas":[{"id":"2",
			"characterRoles":[{"rolesEn":["Main"],"character":{"id":"8","name":"C","poster":null}}],
			"personRoles":[{"rolesEn":["Story","Art"],"person":{"id":"8","name":"S","poster":{"originalUrl":"/uploads/person.jpg"}}}]
		}]}}""")
		val chars = shikimoriPeople(json, target, TrackerContent.CHARACTERS) as TrackerResult.Success
		val staff = shikimoriPeople(json, target, TrackerContent.STAFF) as TrackerResult.Success
		assertEquals("C", chars.items.single().name)
		assertNull(chars.items.single().image)
		assertEquals(listOf("Main"), chars.items.single().roles)
		assertEquals("S", staff.items.single().name)
		assertEquals(listOf("Story", "Art"), staff.items.single().roles)
		assertEquals("https://shikimori.io/uploads/person.jpg", staff.items.single().image)
	}

	@Test fun `similar manga keeps honest provenance and absolute URLs`() {
		val result = shikimoriSimilar(Json.parseToJsonElement("""[{"id":7,"name":"R","url":"/mangas/7-r","image":{"preview":"/system/7.jpg"}}]""").jsonArray) as TrackerResult.Success
		assertEquals(TrackerRecommendationKind.SIMILAR_MANGA, result.items.single().kind)
		assertEquals(TrackerTarget(ScrobblerService.SHIKIMORI, "7", "https://shikimori.io/mangas/7-r"), result.items.single().target)
		assertEquals("https://shikimori.io/system/7.jpg", result.items.single().image)
	}

	@Test fun `missing optional roles and images stay absent`() {
		val result = shikimoriPeople(root("""{"data":{"mangas":[{"id":"2","personRoles":[{"person":{"name":"S"}}]}]}}"""), target, TrackerContent.STAFF) as TrackerResult.Success
		assertNull(result.items.single().id)
		assertNull(result.items.single().image)
		assertTrue(result.items.single().roles.isEmpty())
	}

	@Test fun `partial GraphQL data survives errors`() {
		val result = shikimoriPeople(root("""{"errors":[{}],"data":{"mangas":[{"id":"2","personRoles":[{"person":{"name":"S"}},null]}]}}"""), target, TrackerContent.STAFF) as TrackerResult.Partial
		assertEquals("S", result.items.single().name)
		assertTrue(TrackerPartialReason.PROVIDER_ERROR in result.reasons)
		assertTrue(TrackerPartialReason.MISSING_RECORDS in result.reasons)
	}

	@Test fun `nonpaginated collections are bounded and truncation is visible`() {
		val rows = (1..26).joinToString(",") { """{"id":$it,"name":"R$it"}""" }
		val result = shikimoriSimilar(Json.parseToJsonElement("[$rows]").jsonArray) as TrackerResult.Partial
		assertEquals(25, result.items.size)
		assertEquals(setOf(TrackerPartialReason.TRUNCATED), result.reasons)
		assertNull(result.next)
	}

	@Test fun `invalid URL schemes and embedded credentials are rejected`() {
		assertNull(shikimoriUrl("javascript:alert(1)"))
		assertNull(shikimoriUrl("https://user:pass@host/path"))
	}

	@Test fun `public client preserves DNS and proxy while excluding cookies auth and redirects`() {
		val dns = Dns { emptyList() }
		val base = OkHttpClient.Builder().dns(dns).authenticator { _, _ -> null }
			.addInterceptor(CurlLoggingInterceptor()).build()
		val client = shikimoriDetailsClient(base)
		assertSame(dns, client.dns)
		assertSame(base.proxySelector, client.proxySelector)
		assertSame(base.proxyAuthenticator, client.proxyAuthenticator)
		assertSame(Authenticator.NONE, client.authenticator)
		assertSame(CookieJar.NO_COOKIES, client.cookieJar)
		assertNull(client.cache)
		assertFalse(client.followRedirects)
		assertFalse(client.followSslRedirects)
		assertTrue(client.callTimeoutMillis > 0)
		assertTrue(client.interceptors.none { it is CurlLoggingInterceptor })
	}
}
