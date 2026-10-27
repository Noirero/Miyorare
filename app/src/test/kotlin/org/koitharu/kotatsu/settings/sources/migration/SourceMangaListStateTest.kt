package org.koitharu.kotatsu.settings.sources.migration

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchDiscoveryMode
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchOutcome
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchSnapshot
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchStatus
import org.koitharu.kotatsu.alternatives.domain.MigrationSelection
import org.koitharu.kotatsu.alternatives.domain.MigrationSourceStrategy
import org.koitharu.kotatsu.alternatives.domain.migrationConfigKey
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
		configKey: String,
		mode: MigrationBatchDiscoveryMode = MigrationBatchDiscoveryMode.YOKAI,
	): MigrationBatchSnapshot = MigrationBatchSnapshot(
		mangaId = id,
		source = "OLD",
		generation = 1L,
		batchName = "batch",
		outcome = MigrationBatchOutcome(
			status,
			selection = if (status == MigrationBatchStatus.READY) MigrationSelection("NEW", 101L, "") else null,
		),
		discoveryMode = mode,
		configKey = configKey,
	)

	@Test
	fun `search evidence becomes unchecked after ordered source config changes`() {
		val item = manga(1L)
		val original = migrationConfigKey(listOf("A", "B"), MigrationSourceStrategy.FIRST_MATCH, null)
		val state = SourceMangaListState(
			isLoading = false,
			manga = listOf(item),
			selectedSourceNames = listOf("B", "A"),
			sourceStrategy = MigrationSourceStrategy.FIRST_MATCH,
			batchItems = mapOf(1L to snapshot(1L, MigrationBatchStatus.READY, original)),
		)

		assertEquals(MigrationBatchStatus.UNCHECKED, state.status(1L))
		assertEquals(0, state.readyCount)
	}

	@Test
	fun `ready result is actionable only for the Yokai config that produced it`() {
		val item = manga(1L)
		val sourceOrder = listOf("A", "B")
		val matching = migrationConfigKey(sourceOrder, MigrationSourceStrategy.MOST_CHAPTERS, "uncensored")
		val ready = snapshot(1L, MigrationBatchStatus.READY, matching)
		val state = SourceMangaListState(
			isLoading = false,
			manga = listOf(item),
			selectedSourceNames = sourceOrder,
			sourceStrategy = MigrationSourceStrategy.MOST_CHAPTERS,
			extraSearchParams = "uncensored",
			batchItems = mapOf(1L to ready),
		)
		val changedStrategy = state.copy(sourceStrategy = MigrationSourceStrategy.FIRST_MATCH)

		assertEquals(MigrationBatchStatus.READY, state.status(1L))
		assertEquals(1, state.readyCount)
		assertEquals(MigrationBatchStatus.UNCHECKED, changedStrategy.status(1L))
		assertEquals(0, changedStrategy.readyCount)
	}

	@Test
	fun `legacy discovery evidence is not actionable in Yokai session`() {
		val item = manga(1L)
		val config = migrationConfigKey(listOf("NEW"), MigrationSourceStrategy.FIRST_MATCH, null)
		val state = SourceMangaListState(
			isLoading = false,
			manga = listOf(item),
			selectedSourceNames = listOf("NEW"),
			sourceStrategy = MigrationSourceStrategy.FIRST_MATCH,
			batchItems = mapOf(
				1L to snapshot(
					1L,
					MigrationBatchStatus.READY,
					config,
					MigrationBatchDiscoveryMode.DIRECT_SEARCH,
				),
			),
		)

		assertEquals(MigrationBatchStatus.UNCHECKED, state.status(1L))
		assertEquals(0, state.readyCount)
	}

	@Test
	fun `migrated stays final when migration setup changes`() {
		val item = manga(1L)
		val oldConfig = migrationConfigKey(listOf("A", "B"), MigrationSourceStrategy.FIRST_MATCH, null)
		val state = SourceMangaListState(
			isLoading = false,
			manga = listOf(item),
			selectedSourceNames = listOf("B", "A"),
			sourceStrategy = MigrationSourceStrategy.MOST_CHAPTERS,
			extraSearchParams = "different",
			batchItems = mapOf(1L to snapshot(1L, MigrationBatchStatus.MIGRATED, oldConfig)),
		)

		assertEquals(MigrationBatchStatus.MIGRATED, state.status(1L))
	}
}
