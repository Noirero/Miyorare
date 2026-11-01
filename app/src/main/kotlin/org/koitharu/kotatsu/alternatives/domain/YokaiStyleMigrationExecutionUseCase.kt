package org.koitharu.kotatsu.alternatives.domain

import dagger.Reusable
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
		val details = repository.getDetails(storedCandidate)
		check(details.id == selection.id && details.source.name == selection.source) {
			"Selected replacement identity changed while refreshing details"
		}
		mangaDataRepository.storeManga(
			manga = details,
			replaceExisting = true,
			stripAppliedOverride = false,
			detailsFetched = true,
		)
		when (mode) {
			MigrationExecutionMode.MIGRATE -> migrateUseCase(
				oldManga = old,
				newManga = details,
				lossless = true,
				requireLibraryEntry = true,
			)
			MigrationExecutionMode.COPY -> copyUseCase(old, details)
		}
		return details
	}
}
