package org.koitharu.kotatsu.settings.sources.migration

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchDiscoveryMode
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchOutcome
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchSnapshot
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchStatus
import org.koitharu.kotatsu.alternatives.domain.MigrationSelection
import org.koitharu.kotatsu.alternatives.domain.MigrationSourceStrategy
import org.koitharu.kotatsu.alternatives.domain.migrationConfigKey
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.Manga

class MigrationBatchControlsTest {
	@get:Rule val compose = createComposeRule()
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val destination = MangaSource("NEW")
	private val configKey = migrationConfigKey(listOf(destination.name), MigrationSourceStrategy.FIRST_MATCH, null)

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

	private val selection = MigrationSelection("NEW", 101, "")

	private fun state() = SourceMangaListState(
		isLoading = false,
		manga = listOf(manga(1), manga(2), manga(3)),
		availableSources = listOf(destination),
		selectedSourceNames = listOf(destination.name),
		sourceStrategy = MigrationSourceStrategy.FIRST_MATCH,
		batchItems = listOf(
			MigrationBatchStatus.READY,
			MigrationBatchStatus.NEEDS_REVIEW,
			MigrationBatchStatus.NO_REPLACEMENT,
		).mapIndexed { index, status ->
			MigrationBatchSnapshot(
				mangaId = index + 1L,
				source = "OLD",
				generation = 1,
				batchName = "batch",
				outcome = MigrationBatchOutcome(status, selection),
				discoveryMode = MigrationBatchDiscoveryMode.YOKAI,
				configKey = configKey,
			)
		}.associateBy { it.mangaId },
	)

	private fun setControls(
		state: SourceMangaListState = state(),
		onSearch: () -> Unit = {},
		onMigrateReady: () -> Unit = {},
		onCopyReady: () -> Unit = {},
		onCancel: () -> Unit = {},
		onFilter: (MigrationBatchStatus?) -> Unit = {},
	) {
		compose.setContent {
			MaterialTheme {
				LazyColumn {
					item {
						MigrationBatchControls(
							state = state,
							onSearch = onSearch,
							onMigrateReady = onMigrateReady,
							onCopyReady = onCopyReady,
							onCancel = onCancel,
							onFilter = onFilter,
							onToggleSource = {},
							onSelectAllSources = {},
							onClearSources = {},
							onMoveSource = { _, _ -> },
							onStrategy = {},
							onExtraSearch = {},
							onSkipSetup = {},
							onRetrySources = {},
						)
					}
				}
			}
		}
	}

	@Test
	fun summaryAndReadyActionsReachTheUser() {
		var migrations = 0
		var copies = 0
		setControls(
			onMigrateReady = { migrations++ },
			onCopyReady = { copies++ },
		)
		compose.onNodeWithText(context.getString(R.string.migration_batch_summary, 1, 1, 1)).fetchSemanticsNode()
		compose.onNodeWithTag("migration-batch-migrate").performScrollTo().performClick()
		compose.onNodeWithTag("migration-batch-copy").performScrollTo().performClick()
		assertEquals(1, migrations)
		assertEquals(1, copies)
	}

	@Test
	fun reviewAndUnresolvedAreFilterableWithoutMigration() {
		var filter: MigrationBatchStatus? = null
		setControls(onFilter = { filter = it })
		compose.onNodeWithTag("migration-batch-filter").performScrollTo().performClick()
		compose.onNodeWithText(
			context.getString(
				R.string.migration_batch_filter_count,
				context.getString(R.string.migration_batch_review),
				1,
			),
		).performClick()
		assertEquals(MigrationBatchStatus.NEEDS_REVIEW, filter)
		assertEquals(listOf(2L), state().copy(filter = filter).visibleManga.map { it.id })
	}

	@Test
	fun activeBatchCanCancelAndCannotScheduleAnotherExecution() {
		var cancellations = 0
		setControls(
			state = state().copy(batchBusy = true, ownedBatchName = "batch", batchCompleted = 1, batchTotal = 3),
			onCancel = { cancellations++ },
		)
		compose.onNodeWithTag("migration-batch-search").performScrollTo().assertIsNotEnabled()
		compose.onNodeWithTag("migration-batch-migrate").performScrollTo().assertIsNotEnabled()
		compose.onNodeWithTag("migration-batch-copy").performScrollTo().assertIsNotEnabled()
		compose.onNodeWithTag("migration-batch-cancel").performScrollTo().performClick()
		assertEquals(1, cancellations)
	}
}
