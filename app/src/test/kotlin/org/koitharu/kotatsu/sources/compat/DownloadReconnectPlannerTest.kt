package org.koitharu.kotatsu.sources.compat

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadReconnectPlannerTest {

	@Test
	fun `unique strongest evidence is selected automatically`() {
		val result = DownloadReconnectPlanner.select(
			listOf(
				DownloadedContentMatch.NONE,
				DownloadedContentMatch.LEGACY_SOURCE_PATH,
				DownloadedContentMatch.SOURCE_ALIAS_AND_CONTENT_URL,
				DownloadedContentMatch.PUBLIC_URL,
			),
		)
		assertEquals(
			DownloadReconnectSelection.Automatic(3, DownloadedContentMatch.PUBLIC_URL),
			result,
		)
	}

	@Test
	fun `same strongest evidence is ambiguous`() {
		val result = DownloadReconnectPlanner.select(
			listOf(
				DownloadedContentMatch.PUBLIC_URL,
				DownloadedContentMatch.SOURCE_ALIAS_AND_CONTENT_URL,
				DownloadedContentMatch.PUBLIC_URL,
			),
		)
		assertEquals(
			DownloadReconnectSelection.Ambiguous(
				DownloadedContentMatch.PUBLIC_URL,
				listOf(0, 2),
			),
			result,
		)
	}

	@Test
	fun `two legacy path candidates remain ambiguous`() {
		val result = DownloadReconnectPlanner.select(
			listOf(
				DownloadedContentMatch.LEGACY_SOURCE_PATH,
				DownloadedContentMatch.LEGACY_SOURCE_PATH,
			),
		)
		assertEquals(
			DownloadReconnectSelection.Ambiguous(
				DownloadedContentMatch.LEGACY_SOURCE_PATH,
				listOf(0, 1),
			),
			result,
		)
	}

	@Test
	fun `weaker duplicates do not make a stronger candidate ambiguous`() {
		val result = DownloadReconnectPlanner.select(
			listOf(
				DownloadedContentMatch.PUBLIC_URL,
				DownloadedContentMatch.LEGACY_SOURCE_PATH,
				DownloadedContentMatch.LEGACY_SOURCE_PATH,
			),
		)
		assertEquals(
			DownloadReconnectSelection.Automatic(0, DownloadedContentMatch.PUBLIC_URL),
			result,
		)
	}

	@Test
	fun `no identity evidence never auto reconnects`() {
		val result = DownloadReconnectPlanner.select(
			listOf(
				DownloadedContentMatch.NONE,
				DownloadedContentMatch.NONE,
			),
		)
		assertEquals(DownloadReconnectSelection.NoSafeMatch, result)
	}
}
