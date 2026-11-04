package org.koitharu.kotatsu.alternatives.domain

import dagger.Reusable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

enum class MigrationExecutionMode { MIGRATE, COPY }

/**
 * Execution authority for the Yokai-style migration flow.
 *
 * Search/manual review chooses and persists a stable source/id. Execution never searches for that
 * candidate again. It reloads the exact persisted identity, refreshes details from that exact source,
 * verifies the persisted identity, then uses the application's data-transfer boundary.
 */
@Reusable
class YokaiStyleMigrationExecutionUseCase @Inject constructor(
	private val mangaDataRepository: MangaDataRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val migrateUseCase: MigrateUseCase,
	private val copyUseCase: LosslessMigrationCopyUseCase,
    private val simpleMigrationTransfer: SimpleMigrationTransfer,
    private val database: MangaDatabase,
) {
	suspend operator fun invoke(
		mangaId: Long,
		selection: MigrationSelection,
		mode: MigrationExecutionMode = MigrationExecutionMode.MIGRATE,
	): Manga {
		val old = checkNotNull(mangaDataRepository.findMangaById(mangaId, withChapters = true)) {
			"Source manga $mangaId no longer exists"
		}
		val storedCandidate = checkNotNull(mangaDataRepository.findMangaById(selection.id, withChapters = false)) {
			"Selected replacement ${selection.id} is no longer available locally"
		}
		check(storedCandidate.source.name == selection.source) {
			"Selected replacement source changed"
		}
		check(old.id != storedCandidate.id) {
			"Migration destination must be a different manga identity"
		}

		val repository = mangaRepositoryFactory.create(storedCandidate.source)
		val details = try {
            withTimeoutOrNull(90_000L) { repository.getDetails(storedCandidate) }
                ?: error("Destination source detail request timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw MigrationExecutionStageException(MigrationFailureStage.DESTINATION_DETAILS, failure)
        }
		check(details.id == selection.id && details.source.name == selection.source) {
			"Selected replacement identity changed while refreshing details"
		}
		try {
            mangaDataRepository.storeManga(
                manga = details,
                replaceExisting = true,
                stripAppliedOverride = false,
                detailsFetched = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw MigrationExecutionStageException(MigrationFailureStage.DESTINATION_STORAGE, failure)
        }
		try {
        when (mode) {
			MigrationExecutionMode.MIGRATE -> migrateUseCase(
				oldManga = old,
				newManga = details,
				lossless = true,
				requireLibraryEntry = true,
			)
			MigrationExecutionMode.COPY -> copyUseCase(old, details)
        }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (changed: MigrateUseCase.SourceChangedException) {
            throw changed
        } catch (failure: Exception) {
            throw MigrationExecutionStageException(
                if (failure is LosslessMigrationConflictException) MigrationFailureStage.DATA_CONFLICT
                else MigrationFailureStage.DATA_TRANSFER,
                failure,
            )
        }
		return details
	}
    /** Explicit simple-migration execution, isolated from legacy strict and Copy paths. */
    suspend fun migrateSimple(mangaId: Long, selection: MigrationSelection): MigrationTransferResult {
        // Revalidate at execution time, before any destination details are stored.
        if (!database.getMangaDao().isNormalMigrationFavourite(mangaId)) {
            throw MigrateUseCase.SourceChangedException()
        }
        val old = checkNotNull(mangaDataRepository.findMangaById(mangaId, withChapters = true)) {
            "Source manga is no longer available"
        }
        val storedCandidate = checkNotNull(mangaDataRepository.findMangaById(selection.id, withChapters = false)) {
            "Selected destination is no longer available"
        }
        check(storedCandidate.source.name == selection.source && old.id != storedCandidate.id) {
            "Selected destination identity changed"
        }
        val details = try {
            withTimeoutOrNull(90_000L) {
                mangaRepositoryFactory.create(storedCandidate.source).getDetails(storedCandidate)
            } ?: error("Destination source detail request timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw MigrationExecutionStageException(MigrationFailureStage.DESTINATION_DETAILS, failure)
        }
        check(details.id == selection.id && details.source.name == selection.source) {
            "Destination identity changed while refreshing details"
        }
        try {
            mangaDataRepository.storeManga(
                manga = details, replaceExisting = true,
                stripAppliedOverride = false, detailsFetched = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw MigrationExecutionStageException(MigrationFailureStage.DESTINATION_STORAGE, failure)
        }
        val preserved = try {
            // Private-only data must remain behind its original isolation boundary.
            // Until preserved Private entries have a secure archival visibility route,
            // use the existing strict transfer rather than exposing retained history.
            val privateMembership = database.getPrivateFavouritesDao().findAllRaw(old.id).isNotEmpty() &&
                database.getFavouritesDao().findAllRaw(old.id).isEmpty()
            if (privateMembership) {
                migrateUseCase(old, details, lossless = true, requireLibraryEntry = true)
                false
            } else simpleMigrationTransfer(old, details)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (changed: MigrateUseCase.SourceChangedException) {
            throw changed
        } catch (failure: Exception) {
            throw MigrationExecutionStageException(
                if (failure is LosslessMigrationConflictException) MigrationFailureStage.DATA_CONFLICT
                else MigrationFailureStage.DATA_TRANSFER, failure,
            )
        }
        return MigrationTransferResult(details, preserved)
    }

}
