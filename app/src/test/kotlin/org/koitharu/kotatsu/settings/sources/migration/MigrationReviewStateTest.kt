package org.koitharu.kotatsu.settings.sources.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.alternatives.domain.MigrationDiscovery
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchAvailability
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchScope
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.Manga

class MigrationReviewStateTest {

	private val seed = Manga(
		id = 1L,
		title = "Shared Story",
		altTitles = emptySet(),
		url = "/1",
		publicUrl = "https://example.org/1",
		rating = 0f,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = setOf("Writer"),
		source = MangaSource("OLD"),
		chapters = null,
		description = null,
	)

	@Test
	fun `changing scope invalidates prior search evidence but keeps stable context and chosen path`() {
		val availability = MigrationSearchAvailability(
			counts = mapOf(MigrationSearchScope.ALL to 4, MigrationSearchScope.UMA to 2),
		)
		val before = MigrationReviewState(
			loading = true,
			searching = true,
			discovery = MigrationDiscovery(
				seed = seed,
				sourceHealthy = true,
				incomplete = true,
				partialFailureCount = 2,
			),
			availability = availability,
			selectedScope = MigrationSearchScope.ALL,
			hasSearched = true,
			currentSourceChecked = true,
			manualSearchEnabled = true,
			checkFailed = true,
			searchFailed = true,
			failed = true,
			changed = true,
		)

		val after = before.withSearchScope(MigrationSearchScope.UMA)

		assertEquals(MigrationSearchScope.UMA, after.selectedScope)
		assertFalse(after.loading)
		assertFalse(after.hasSearched)
		assertFalse(after.searching)
		assertFalse(after.checkFailed)
		assertFalse(after.searchFailed)
		assertFalse(after.failed)
		assertFalse(after.changed)
		assertTrue(after.currentSourceChecked)
		assertTrue(after.manualSearchEnabled)
		assertNull(after.availability)
		assertNotNull(after.discovery)
		assertEquals(seed, after.discovery?.seed)
		assertTrue(after.discovery?.currentSourceReadable == true)
		assertFalse(after.discovery?.incomplete == true)
		assertEquals(0, after.discovery?.partialFailureCount)
		assertNull(after.discovery?.assessment)
	}

	@Test
	fun `initial local seed is not treated as a completed current source check`() {
		val state = MigrationReviewState(
			loading = false,
			discovery = MigrationDiscovery(seed),
		)

		assertFalse(state.currentSourceChecked)
		assertFalse(state.manualSearchEnabled)
		assertFalse(state.hasSearched)
		assertFalse(state.checkFailed)
	}

	@Test
	fun `reselecting current scope does not discard current evidence`() {
		val before = MigrationReviewState(
			loading = false,
			discovery = MigrationDiscovery(seed),
			selectedScope = MigrationSearchScope.ALL,
			hasSearched = true,
		)

		assertSame(before, before.withSearchScope(MigrationSearchScope.ALL))
	}
}
