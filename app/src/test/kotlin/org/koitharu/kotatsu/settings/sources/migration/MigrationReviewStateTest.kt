package org.koitharu.kotatsu.settings.sources.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.alternatives.domain.MigrationDiscovery
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
	fun `changing category only filters source picker and preserves selections and evidence`() {
		val before = MigrationReviewState(
			loading = false,
			discovery = MigrationDiscovery(seed = seed, sourceHealthy = true, incomplete = true, partialFailureCount = 2),
			selectedScope = MigrationSearchScope.ALL,
			hasSearched = true,
			currentSourceChecked = true,
			manualSearchEnabled = true,
			selectedSourceNames = setOf("A", "B"),
			searchFailed = true,
			changed = true,
		)

		val after = before.withSearchScope(MigrationSearchScope.UMA)

		assertEquals(MigrationSearchScope.UMA, after.selectedScope)
		assertEquals(before.copy(selectedScope = MigrationSearchScope.UMA), after)
		assertEquals(setOf("A", "B"), after.selectedSourceNames)
		assertTrue(after.hasSearched)
		assertTrue(after.searchFailed)
		assertTrue(after.changed)
		assertSame(before.discovery, after.discovery)
	}

	@Test
	fun `initial local seed is not treated as a completed current source check or search`() {
		val state = MigrationReviewState(
			loading = false,
			discovery = MigrationDiscovery(seed),
		)

		assertFalse(state.currentSourceChecked)
		assertFalse(state.manualSearchEnabled)
		assertFalse(state.hasSearched)
		assertFalse(state.checkFailed)
		assertFalse(state.sourceSelectionLoading)
		assertTrue(state.selectedSourceNames.isEmpty())
	}

	@Test
	fun `reselecting current category returns identical state`() {
		val before = MigrationReviewState(
			loading = false,
			discovery = MigrationDiscovery(seed),
			selectedScope = MigrationSearchScope.ALL,
			hasSearched = true,
		)

		assertSame(before, before.withSearchScope(MigrationSearchScope.ALL))
	}
}
