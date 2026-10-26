package org.koitharu.kotatsu.settings.sources.migration

import org.junit.Assert.assertEquals
import org.junit.Test
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
	fun `ready result is actionable only in the scope that produced it`() {
		val item = manga(1L)
		val state = SourceMangaListState(
			isLoading = false,
			manga = listOf(item),
			searchScope = MigrationSearchScope.UMA,
			batchItems = mapOf(1L to snapshot(1L, MigrationBatchStatus.READY, MigrationSearchScope.UMA)),
		)

		assertEquals(MigrationBatchStatus.READY, state.status(1L))
		assertEquals(1, state.readyCount)
	}

	@Test
	fun `healthy and migrated outcomes stay valid across scope changes`() {
		val state = SourceMangaListState(
			isLoading = false,
			manga = listOf(manga(1L), manga(2L)),
			searchScope = MigrationSearchScope.GEKKOUSHI,
			batchItems = mapOf(
				1L to snapshot(1L, MigrationBatchStatus.HEALTHY, MigrationSearchScope.ALL),
				2L to snapshot(2L, MigrationBatchStatus.MIGRATED, MigrationSearchScope.ALL),
			),
		)

		assertEquals(MigrationBatchStatus.HEALTHY, state.status(1L))
		assertEquals(MigrationBatchStatus.MIGRATED, state.status(2L))
	}
}
