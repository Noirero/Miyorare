package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.discovery.domain.CatalogFailureKind
import org.koitharu.kotatsu.discovery.domain.CatalogFilter
import org.koitharu.kotatsu.discovery.domain.CatalogSectionType

class MALCatalogHomeTimeoutTest {
	private fun response(request: Request): Response = Response.Builder()
		.request(request)
		.protocol(Protocol.HTTP_1_1)
		.code(200)
		.message("fixture")
		.body(
			"""{"data":[{"node":{"id":13,"title":"Manga","media_type":"manga","nsfw":"white","mean":8.7}}],"paging":{"next":"https://ignored.invalid"}}"""
				.toResponseBody(),
		)
		.build()

	@Test
	fun `slow MAL Home rails time out without hiding a fast successful rail`() = runBlocking {
		val http = CatalogHttp(
			OkHttpClient.Builder().addInterceptor { chain ->
				val request = chain.request()
				if (request.url.queryParameter("ranking_type") != "manga") {
					Thread.sleep(250)
				}
				response(request)
			}.build(),
		)
		val provider = MALCatalogProvider(http, "fixture-public-id", homeSectionTimeoutMs = 75L)

		val home = withTimeout(2_000) { provider.home(CatalogFilter.MANGA, false) }

		assertEquals(3, home.sections.size)
		val top = home.sections.single { it.key.type == CatalogSectionType.TOP_RANKED }
		assertNull(top.failure)
		assertEquals("13", top.items.single().key.remoteId)
		val timedOut = home.sections.filter { it.key.type != CatalogSectionType.TOP_RANKED }
		assertEquals(setOf(CatalogSectionType.POPULAR, CatalogSectionType.MOST_FAVORITED), timedOut.map { it.key.type }.toSet())
		assertTrue(timedOut.all { it.items.isEmpty() && it.failure?.kind == CatalogFailureKind.PROVIDER })
	}
}
