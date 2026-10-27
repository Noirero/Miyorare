package org.koitharu.kotatsu.settings.sources.migration

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchDiscoveryMode
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchOutcome
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchSnapshot
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchStatus
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchScope
import org.koitharu.kotatsu.alternatives.domain.MigrationSelection
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.Manga

class SourceMangaListStateTest {

	private fun manga(id: Long) = Manga(
		id = id,
		title = "Story $id",
		altTitles = emptySet(),
		url = "/$id",
		publicUrl = "https://fixture.invalid/$id",
		rating = 0f,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = MangaSource("OLD"),
	)

	private fun snapshot(
		id: Long,
		status: MigrationBatchStatus,
		scope: MigrationSearchScope,
		mode: MigrationBatchDiscoveryMode = MigrationBatchDiscoveryMode.CHECK_CURRENT,
	): MigrationBatchSnapshot = MigrationBatchSnapshot(
		mangaId = id,
		source = "OLD",
		generation = 1L,
		batchName = "batch",
		outcome = MigrationBatchOutcome(
			status,
			selection = if (status == MigrationBatchStatus.READY) MigrationSelection("NEW", 101L, "fingerprint") else null,
		),
		searchScope = scope,
		discoveryMode = mode,
	)

	@Test
	fun `scope dependent results become unchecked after scope changes`() {
		val item = manga(1L)
		val state = SourceMangaListState(
			isLoading = false,
			manga = listOf(item),
			searchScope = MigrationSearchScope.UMA,
			batchItems = mapOf(1L to snapshot(1L, MigrationBatchStatus.READY, MigrationSearchScope.ALL)),
		)

		assertEquals(MigrationBatchStatus.UNCHECKED, state.status(1L))
		assertEquals(0, state.readyCount)
	}

	@Test
	fun `ready result is actionable only in the scope and mode that produced it`() {
		val item = manga(1L)
		val directSnapshot = snapshot(
			1L,
			MigrationBatchStatus.READY,
			MigrationSearchScope.UMA,
			MigrationBatchDiscoveryMode.DIRECT_SEARCH,
		)
		val checkState = SourceMangaListState(
			isLoading = false,
			manga = listOf(item),
			searchScope = MigrationSearchScope.UMA,
			discoveryMode = MigrationBatchDiscoveryMode.CHECK_CURRENT,
			batchItems = mapOf(1L to directSnapshot),
		)
		val directState = checkState.copy(discoveryMode = MigrationBatchDiscoveryMode.DIRECT_SEARCH)

		assertEquals(MigrationBatchStatus.UNCHECKED, checkState.status(1L))
		assertEquals(0, checkState.readyCount)
		assertEquals(MigrationBatchStatus.READY, directState.status(1L))
		assertEquals(1, directState.readyCount)
	}

	@Test
	fun `healthy stays valid across scope changes only in check mode while migrated always stays valid`() {
		val items = listOf(manga(1L), manga(2L))
		val snapshots = mapOf(
			1L to snapshot(1L, MigrationBatchStatus.HEALTHY, MigrationSearchScope.ALL),
			2L to snapshot(2L, MigrationBatchStatus.MIGRATED, MigrationSearchScope.ALL),
		)
		val checkState = SourceMangaListState(
			isLoading = false,
			manga = items,
			searchScope = MigrationSearchScope.GEKKOUSHI,
			discoveryMode = MigrationBatchDiscoveryMode.CHECK_CURRENT,
			batchItems = snapshots,
		)
		val directState = checkState.copy(discoveryMode = MigrationBatchDiscoveryMode.DIRECT_SEARCH)

		assertEquals(MigrationBatchStatus.HEALTHY, checkState.status(1L))
		assertEquals(MigrationBatchStatus.MIGRATED, checkState.status(2L))
		assertEquals(MigrationBatchStatus.UNCHECKED, directState.status(1L))
		assertEquals(MigrationBatchStatus.MIGRATED, directState.status(2L))
	}
}
