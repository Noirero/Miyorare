package org.koitharu.kotatsu.settings.sources.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchDiscoveryMode
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchOutcome
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchSnapshot
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchStatus
import org.koitharu.kotatsu.alternatives.domain.MigrationSelection
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.core.model.MangaSource

class SourceMangaListStateCandidateTest {
	private fun manga(id: Long, source: String) = Manga(
		id = id,
		title = "Story $id",
		altTitles = emptySet(),
		url = "/$id",
		publicUrl = "https://fixture.invalid/$id",
		rating = 0f,
		contentRating = null,
		coverUrl = "https://fixture.invalid/cover-$id.jpg",
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = MangaSource(source),
	)

	@Test
	fun `ready target is shown only for the currently selected search configuration`() {
		val original = manga(1, "OLD")
		val candidate = manga(2, "NEW")
		val snapshot = MigrationBatchSnapshot(
			mangaId = original.id,
			source = original.source.name,
			generation = 1,
			batchName = "candidate",
			outcome = MigrationBatchOutcome(
				status = MigrationBatchStatus.READY,
				selection = MigrationSelection(candidate.source.name, candidate.id, "fingerprint"),
			),
			discoveryMode = MigrationBatchDiscoveryMode.YOKAI,
			configKey = org.koitharu.kotatsu.alternatives.domain.migrationConfigKey(
				listOf(candidate.source.name),
				org.koitharu.kotatsu.alternatives.domain.MigrationSourceStrategy.FIRST_MATCH,
				null,
			),
		)
		val state = SourceMangaListState(
			isLoading = false,
			manga = listOf(original),
			selectedSourceNames = listOf(candidate.source.name),
			batchItems = mapOf(original.id to snapshot),
			targetManga = mapOf(original.id to candidate),
		)
		assertEquals(candidate, state.readyTarget(original.id))
		assertNull(state.copy(selectedSourceNames = listOf("DIFFERENT")).readyTarget(original.id))
		assertNull(state.copy(targetManga = mapOf(original.id to manga(2, "OTHER"))).readyTarget(original.id))
		assertNull(state.copy(batchItems = mapOf(original.id to snapshot.copy(running = true))).readyTarget(original.id))
		assertNull(state.copy(batchItems = mapOf(original.id to snapshot.copy(
			outcome = MigrationBatchOutcome(MigrationBatchStatus.NEEDS_REVIEW, selection = snapshot.outcome.selection)
		))).readyTarget(original.id))
	}
	@Test
	fun `completed result resolves only exact destination independent of search config`() {
		val original = manga(1, "OLD")
		val destination = manga(2, "NEW")
		val snapshot = MigrationBatchSnapshot(
			mangaId = original.id, source = "OLD", generation = 1, batchName = "done",
			outcome = MigrationBatchOutcome(MigrationBatchStatus.MIGRATED,
				destinationId = destination.id, destinationSource = destination.source.name),
		)
		val state = SourceMangaListState(isLoading = false, manga = listOf(original),
			selectedSourceNames = listOf("DIFFERENT"), batchItems = mapOf(original.id to snapshot),
			targetManga = mapOf(original.id to destination))
		assertEquals(destination, state.completedTarget(original.id))
		assertEquals(destination, state.copy(manga = emptyList()).completedTarget(original.id))
		assertNull(state.copy(targetManga = mapOf(original.id to manga(2, "OTHER"))).completedTarget(original.id))
		assertNull(state.copy(targetManga = mapOf(original.id to manga(3, "NEW"))).completedTarget(original.id))
		assertNull(state.copy(batchItems = mapOf(original.id to snapshot.copy(running = true))).completedTarget(original.id))
		assertNull(state.copy(batchItems = mapOf(original.id to snapshot.copy(
			outcome = snapshot.outcome.copy(status = MigrationBatchStatus.ERROR)))).completedTarget(original.id))
		assertEquals(destination, state.copy(batchItems = mapOf(original.id to snapshot.copy(
			outcome = snapshot.outcome.copy(status = MigrationBatchStatus.COPIED)))).completedTarget(original.id))
	}

}
