package org.koitharu.kotatsu.sources.compat

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadReconnectPlannerTest {

	private val planner = DownloadReconnectPlanner(
		matcher = error("Matcher is not used by pure selection tests"),
	)

	@Test
	fun `unique strongest evidence is selected automatically`() {
		val result = planner.select(
			listOf(
				DownloadedContentMatch.NONE,
				DownloadedContentMatch.SOURCE_ALIAS_AND_CONTENT_URL,
				DownloadedContentMatch.PUBLIC_URL,
			),
		)
		assertEquals(
			DownloadReconnectSelection.Automatic(2, DownloadedContentMatch.PUBLIC_URL),
			result,
		)
	}

	@Test
	fun `same strongest evidence is ambiguous`() {
		val result = planner.select(
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
	fun `weaker duplicates do not make a stronger candidate ambiguous`() {
		val result = planner.select(
			listOf(
				DownloadedContentMatch.PUBLIC_URL,
				DownloadedContentMatch.SOURCE_ALIAS_AND_CONTENT_URL,
				DownloadedContentMatch.SOURCE_ALIAS_AND_CONTENT_URL,
			),
		)
		assertEquals(
			DownloadReconnectSelection.Automatic(0, DownloadedContentMatch.PUBLIC_URL),
			result,
		)
	}

	@Test
	fun `no identity evidence never auto reconnects`() {
		val result = planner.select(
			listOf(
				DownloadedContentMatch.NONE,
				DownloadedContentMatch.NONE,
			),
		)
		assertEquals(DownloadReconnectSelection.NoSafeMatch, result)
	}
}
