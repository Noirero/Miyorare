package org.koitharu.kotatsu.settings.sources.migration

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchDiscoveryMode
import org.koitharu.kotatsu.alternatives.domain.MigrationFailureStage
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchOutcome
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchSnapshot
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchStatus
import org.koitharu.kotatsu.alternatives.domain.MigrationSelection
import org.koitharu.kotatsu.alternatives.domain.MigrationSourceStrategy
import org.koitharu.kotatsu.alternatives.domain.migrationConfigKey
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiCompatibilityStatus
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiPluginDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import org.koitharu.kotatsu.tsuki.model.TsukiPluginState
import org.koitharu.kotatsu.tsuki.model.TsukiSourceDescriptor
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
			installedSources = listOf(destination),
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
		onOpenTarget: (Manga) -> Unit = {},
		onMigrateOne: (Long) -> Unit = {},
		onCopyOne: (Long) -> Unit = {},
		onCancel: (String) -> Unit = {},
		stateProvider: () -> SourceMangaListState = { state },
	) {
		compose.setContent {
			MaterialTheme {
				MigrationFlowScreen(
					state = stateProvider(), imageLoader = loader,
					onRetry = {}, onSearch = {},
					onMigrateReady = onMigrate, onCopyReady = onCopy,
					onCancel = onCancel, onFilter = {},
					onToggleSource = {}, onSelectAllSources = {}, onClearSources = {},
					onMoveSource = { _, _ -> }, onStrategy = {},
					onExtraSearch = {}, onSkipSetup = {}, onRetrySources = {},
					onMangaClick = onOpenManual, onMangaLongClick = {},
					onOpenTarget = onOpenTarget,
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
	fun destinationTabsCountInstalledLogicalGroups() {
		show(screenState(MigrationBatchStatus.UNCHECKED))
		compose.onNodeWithText("Semua (1)").assertExists()
		compose.onNodeWithText("Other (1)").assertExists()
		compose.onNodeWithText("A–Z").performClick()
		compose.onNodeWithText("Z–A").assertExists()
	}

	@Test
	fun installedInventoryWithoutCompatibleSourcesStillShowsCountedTabs() {
		val state = screenState(MigrationBatchStatus.UNCHECKED).copy(availableSources = emptyList())
		show(state)
		compose.onNodeWithText("Semua (1)").assertExists()
		compose.onNodeWithText("Tidak ada source kompatibel untuk judul yang dipilih.").assertExists()
		compose.onNodeWithTag("migration-batch-search").assertIsNotEnabled()
	}

	@Test
	fun selectedSourcePriorityIsVisibleIndependentlyOfAlphabeticalSort() {
		val other = MangaSource("ALPHA")
		val state = screenState(MigrationBatchStatus.UNCHECKED).copy(
			installedSources = listOf(other, destination),
			availableSources = listOf(other, destination),
			selectedSourceNames = listOf(destination.name, other.name),
		)
		show(state)
		compose.onNodeWithText("Prioritas 1").assertExists()
		compose.onNodeWithText("Prioritas 2").assertExists()
		compose.onNodeWithText("A–Z").performClick()
		compose.onNodeWithText("Z–A").assertExists()
		compose.onNodeWithText("Prioritas 1").assertExists()
	}

	@Test
	fun sourceSelectionKeepsSearchActionAvailable() {
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

	private fun tsuki(provider: TsukiPluginProvider, lang: String, name: String): TsukiMangaSource {
		val descriptor = TsukiSourceDescriptor(name = name, title = name, locale = lang, contentType = "MANGA")
		return TsukiMangaSource(
			plugin = TsukiPluginDescriptor(
				pluginId = provider.wireName + "-" + name,
				displayName = name,
				provider = provider,
				version = "1",
				origin = "",
				sha256 = "",
				fileSize = 0L,
				lastModified = 0L,
				requiredApi = null,
				compatibility = TsukiCompatibilityStatus.COMPATIBLE,
				state = TsukiPluginState.ENABLED,
				sources = listOf(descriptor),
			),
			descriptor = descriptor,
		)
	}

	@Test
	fun tsukiDestinationsGroupByProviderAndLanguageWithoutChangingSourceIdentity() {
		val asura = tsuki(TsukiPluginProvider.MIYORARE, "id", "Asura")
		val doujindi = tsuki(TsukiPluginProvider.MIYORARE, "id-ID", "Doujindi")
		val english = tsuki(TsukiPluginProvider.MIYORARE, "en", "Bitcave")
		val global = tsuki(TsukiPluginProvider.MIYORARE, "other", "Global")
		val uma = tsuki(TsukiPluginProvider.UMA, "id", "UmaComic")
		val groups = destinationGroups(listOf(asura, doujindi, english, global, uma), context)
		assertEquals(4, groups.size)
		assertEquals(listOf(asura.name, doujindi.name).toSet(),
			groups.single { it.label == "🇮🇩 Indonesia" && it.origin?.name == "MIYORARE_PACK" }
				.sources.map { it.name }.toSet())
		assertEquals(1, groups.single { it.label == "🇬🇧 English" }.sources.size)
		assertEquals(1, groups.single { it.label == "🏳️ Other / Global" }.sources.size)
		assertEquals(1, groups.single { it.origin?.name == "UMA" }.sources.size)

		val state = screenState(MigrationBatchStatus.UNCHECKED).copy(
			installedSources = listOf(asura, doujindi, english, global, uma),
			availableSources = listOf(asura, doujindi, english, global, uma),
			selectedSourceNames = listOf(english.name),
		)
		show(state)
		compose.onNodeWithText("Miyorare (4)").performClick()
		compose.onNodeWithText("🇮🇩 Indonesia (2)").assertExists()
		compose.onNodeWithText("🇬🇧 English (1)").assertExists()
		compose.onNodeWithText("🏳️ Other / Global (1)").assertExists()
		compose.onNodeWithText("🇮🇩 Indonesia (2)").performClick()
		compose.onNodeWithText("Asura").assertExists()
		compose.onNodeWithText("Doujindi").assertExists()
	}

	@Test
	fun internalSourceIdIsNotShownAsCandidateTitleOrSubtitle() {
		val original = screenState(MigrationBatchStatus.READY)
		val snapshot = checkNotNull(original.batchItems[1L])
		val state = original.copy(batchItems = mapOf(1L to snapshot.copy(
			outcome = snapshot.outcome.copy(destinationSource = "MIHON_26770799414")
		)))
		show(state)
		compose.onNodeWithTag("migration-batch-search").performClick()
		compose.onNodeWithText("MIHON_26770799414").assertDoesNotExist()
		compose.onNodeWithText("Replacement").assertExists()
	}

	@Test
	fun targetTitleTapOpensDetailsAndLongPressOpensCandidateSelection() {
		val base = screenState(MigrationBatchStatus.READY)
		val target = manga(100L).copy(title = "Replacement", source = destination,
			coverUrl = "https://fixture.invalid/cover.jpg")
		val state = base.copy(targetManga = mapOf(1L to target))
		var details = 0
		var manual = 0
		show(state, onOpenManual = { manual++ }, onOpenTarget = { details++ })
		compose.onNodeWithTag("migration-batch-search").performClick()
		compose.onNodeWithTag("migration-target-title-1").performClick()
		assertEquals(1, details)
		assertEquals(0, manual)
		compose.onNodeWithTag("migration-target-title-1").performTouchInput { longClick() }
		assertEquals(1, manual)
		compose.onNodeWithTag("migration-target-cover-1").performClick()
		assertEquals(2, details)
		compose.onNodeWithTag("migration-target-cover-1").performTouchInput { longClick() }
		assertEquals(2, manual)
	}


    @Test
    fun foreignBatchCanBeCancelledFromSourceSetupWithConfirmation() {
        val cancelled = mutableListOf<String>()
        val state = screenState(MigrationBatchStatus.UNCHECKED).copy(
            batchBusy = true,
            ownedBatchName = null,
            activeBatchName = "migration_batch:foreign-session",
            batchCompleted = 0,
            batchTotal = 0,
        )
        show(state, onCancel = { cancelled += it })
        compose.onNodeWithText("Batch migrasi lain sedang berjalan").assertExists()
        compose.onNodeWithText("Batalkan").performClick()
        assertEquals(emptyList<String>(), cancelled)
        compose.onNodeWithText("Hentikan").performClick()
        assertEquals(listOf("migration_batch:foreign-session"), cancelled)
    }

    @Test
    fun foreignBatchReviewDoesNotShowMisleadingZeroProgressAndCanCancel() {
        var cancellations = 0
        val state = screenState(MigrationBatchStatus.UNCHECKED).copy(
            batchBusy = true,
            ownedBatchName = null,
            activeBatchName = "migration_batch:foreign-session",
            batchTotal = 0,
        )
        show(state, onCancel = { cancellations++ })
        compose.onNodeWithText("Lihat pencarian").performClick()
        compose.onNodeWithText("Memproses 0/0").assertDoesNotExist()
        compose.onNodeWithText("Batch migrasi lain sedang berjalan").assertExists()
        compose.onNodeWithText("Batalkan").performClick()
        compose.onNodeWithText("Hentikan").performClick()
        assertEquals(1, cancellations)
    }

    @Test
    fun changedActiveBatchCannotBeCancelledUsingPreviousConfirmation() {
        val first = "migration_batch:original"
        val replacement = "migration_batch:replacement"
        val liveState = mutableStateOf(screenState(MigrationBatchStatus.UNCHECKED).copy(
            batchBusy = true,
            ownedBatchName = null,
            activeBatchName = first,
        ))
        val cancelled = mutableListOf<String>()
        show(liveState.value, onCancel = { cancelled += it }, stateProvider = { liveState.value })
        compose.onNodeWithText("Batalkan").performClick()
        compose.runOnUiThread {
            liveState.value = liveState.value.copy(activeBatchName = replacement)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Hentikan").performClick()
        assertEquals(emptyList<String>(), cancelled)
    }

    @Test
    fun terminalErrorShowsStageRatherThanSearchingPlaceholder() {
        val original = screenState(MigrationBatchStatus.ERROR)
        val failed = checkNotNull(original.batchItems[1L])
        val state = original.copy(batchItems = mapOf(
            1L to failed.copy(outcome = failed.outcome.copy(
                failureStage = MigrationFailureStage.DESTINATION_DETAILS,
            )),
        ))
        show(state)
        compose.onNodeWithTag("migration-batch-search").performClick()
        compose.onNodeWithText("Migrasi gagal").assertExists()
        compose.onNodeWithText("Mencari...").assertDoesNotExist()
        compose.onNodeWithText(
            "Gagal membaca detail source tujuan; periksa jaringan/source, lalu coba lagi.",
        ).assertExists()
    }
	@Test
	fun migratedCoverAndTitleOpenDestinationDetails() {
		val base = screenState(MigrationBatchStatus.MIGRATED)
		val target = manga(100L).copy(title = "Completed target", source = destination,
			coverUrl = "https://fixture.invalid/completed.jpg")
		val original = checkNotNull(base.batchItems[1L])
		val state = base.copy(
			targetManga = mapOf(1L to target),
			batchItems = mapOf(1L to original.copy(outcome = original.outcome.copy(
				destinationId = target.id, destinationSource = target.source.name,
				destinationTitle = target.title))))
		val opened = mutableListOf<Manga>()
		show(state, onOpenTarget = { opened += it })
		compose.onNodeWithTag("migration-batch-search").performClick()
		compose.onNodeWithTag("migration-target-title-1").performClick()
		compose.onNodeWithTag("migration-target-cover-1").performClick()
		assertEquals(listOf(target, target), opened)
	}

	@Test
	fun historyCoverAndTitleOpenDestinationAfterOriginalLeavesSourceList() {
		val base = screenState(MigrationBatchStatus.MIGRATED)
		val target = manga(200L).copy(title = "History target", source = destination,
			coverUrl = "https://fixture.invalid/history.jpg")
		val original = checkNotNull(base.batchItems[1L])
		val state = base.copy(
			manga = listOf(manga(99L)),
			targetManga = mapOf(1L to target),
			batchItems = mapOf(1L to original.copy(outcome = original.outcome.copy(
				destinationId = target.id, destinationSource = target.source.name))))
		val opened = mutableListOf<Manga>()
		show(state, onOpenTarget = { opened += it })
		compose.onNodeWithTag("migration-batch-search").performClick()
		compose.onNodeWithTag("migration-completed-title-1").performClick()
		compose.onNodeWithTag("migration-completed-cover-1").performClick()
		assertEquals(listOf(target, target), opened)
	}

}
