package org.koitharu.kotatsu.settings.sources.migration

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
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

class MigrationFlowScreenTest {
	@get:Rule val compose = createComposeRule()
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val loader = ImageLoader.Builder(context).build()
	private val destination = MangaSource("NEW")
	private val configKey = migrationConfigKey(listOf(destination.name), MigrationSourceStrategy.FIRST_MATCH, null)

	@After fun close() { loader.shutdown() }

	private fun manga(id: Long) = Manga(
		id = id, title = "Story $id", altTitles = emptySet(), url = "/$id",
		publicUrl = "https://fixture.invalid/$id", rating = 0f,
		contentRating = null, coverUrl = null, tags = emptySet(),
		state = null, authors = emptySet(), source = MangaSource("OLD"),
	)

	private fun screenState(vararg statuses: MigrationBatchStatus): SourceMangaListState =
		SourceMangaListState(
			isLoading = false,
			manga = statuses.indices.map { manga(it.toLong() + 1L) },
			availableSources = listOf(destination),
			selectedSourceNames = listOf(destination.name),
			batchItems = statuses.mapIndexed { index, status ->
				MigrationBatchSnapshot(
					mangaId = index.toLong() + 1L,
					source = "OLD",
					generation = 1L,
					batchName = "test-batch",
					outcome = MigrationBatchOutcome(
						status = status,
						selection = if (status == MigrationBatchStatus.READY)
							MigrationSelection("NEW", 100L + index, "fixture") else null,
						destinationTitle = if (status == MigrationBatchStatus.READY) "Replacement" else null,
						destinationSource = if (status == MigrationBatchStatus.READY) "NEW" else null,
					),
					discoveryMode = MigrationBatchDiscoveryMode.YOKAI,
					configKey = configKey,
				)
			}.associateBy { it.mangaId },
		)

	private fun show(
		state: SourceMangaListState,
		onMigrate: () -> Unit = {},
		onCopy: () -> Unit = {},
		onOpenManual: (Manga) -> Unit = {},
		onMigrateOne: (Long) -> Unit = {},
		onCopyOne: (Long) -> Unit = {},
	) {
		compose.setContent {
			MaterialTheme {
				MigrationFlowScreen(
					state = state, imageLoader = loader,
					onRetry = {}, onSearch = {},
					onMigrateReady = onMigrate, onCopyReady = onCopy,
					onCancel = {}, onFilter = {},
					onToggleSource = {}, onSelectAllSources = {}, onClearSources = {},
					onMoveSource = { _, _ -> }, onStrategy = {},
					onExtraSearch = {}, onSkipSetup = {}, onRetrySources = {},
					onMangaClick = onOpenManual, onMangaLongClick = {},
					onMigrateOne = onMigrateOne, onCopyOne = onCopyOne,
				)
			}
		}
	}

	@Test
	fun readyReviewRequiresExplicitConfirmationBeforeMigration() {
		var invoked = 0
		show(screenState(MigrationBatchStatus.READY), onMigrate = { invoked++ })
		compose.onNodeWithTag("migration-batch-search").performClick()
		compose.onNodeWithTag("migration-batch-migrate").performClick()
		assertEquals(0, invoked)
		compose.onNodeWithTag("migration-batch-confirm").performClick()
		assertEquals(1, invoked)
		compose.onNodeWithText("Hasil pencarian").assertExists()
	}

	@Test
	fun copyAndMigrateUseTheSameConfirmationPath() {
		var copies = 0
		show(screenState(MigrationBatchStatus.READY), onCopy = { copies++ })
		compose.onNodeWithTag("migration-batch-search").performClick()
		compose.onNodeWithTag("migration-batch-copy").performClick()
		assertEquals(0, copies)
		compose.onNodeWithTag("migration-batch-confirm").performClick()
		assertEquals(1, copies)
	}

	@Test
	fun ambiguousAndMissingItemsRemainManualAndNotAutomaticallyExecutable() {
		var reviewed = 0
		show(screenState(MigrationBatchStatus.NEEDS_REVIEW, MigrationBatchStatus.NO_REPLACEMENT),
			onOpenManual = { reviewed++ })
		compose.onNodeWithTag("migration-batch-search").performClick()
		compose.onNodeWithTag("migration-batch-migrate").assertDoesNotExist()
		compose.onNodeWithText("Story 1").performClick()
		assertEquals(1, reviewed)
	}


	@Test
	fun singleMigrateExecutesFromTheCandidateMenuWithoutAnotherReviewScreen() {
		var executed = 0L
		show(screenState(MigrationBatchStatus.READY), onMigrateOne = { executed = it })
		compose.onNodeWithTag("migration-batch-search").performClick()
		compose.onNodeWithText("⋮").performClick()
		compose.onNodeWithText("Migrasikan").performClick()
		assertEquals(1L, executed)
	}

	@Test
	fun singleCopyUsesTheSameCandidateMenuWithoutMigrating() {
		var copied = 0L
		show(screenState(MigrationBatchStatus.READY), onCopyOne = { copied = it })
		compose.onNodeWithTag("migration-batch-search").performClick()
		compose.onNodeWithText("⋮").performClick()
		compose.onNodeWithText("Salin").performClick()
		assertEquals(1L, copied)
	}

	@Test
	fun sourceSelectionIsVisibleWithoutExtraFilterTabs() {
		show(screenState(MigrationBatchStatus.UNCHECKED))
		compose.onNodeWithText("Pilih source tujuan").assertExists()
		compose.onNodeWithTag("migration-batch-search").assertExists()
	}


	@Test
	fun aDifferentConfigMakesOldReadyStatusNonActionable() {
		val stale = screenState(MigrationBatchStatus.READY).copy(
			sourceStrategy = MigrationSourceStrategy.MOST_CHAPTERS,
		)
		show(stale)
		compose.onNodeWithTag("migration-batch-search").performClick()
		compose.onNodeWithTag("migration-batch-migrate").assertDoesNotExist()
	}
	@Test
	fun readyStatusWithoutAValidSelectionCannotBeExecuted() {
		val original = screenState(MigrationBatchStatus.READY)
		val snapshot = checkNotNull(original.batchItems[1L])
		val malformed = original.copy(batchItems = mapOf(
			1L to snapshot.copy(outcome = snapshot.outcome.copy(selection = null)),
		))
		show(malformed)
		compose.onNodeWithTag("migration-batch-search").performClick()
		compose.onNodeWithTag("migration-batch-migrate").assertDoesNotExist()
	}

}
