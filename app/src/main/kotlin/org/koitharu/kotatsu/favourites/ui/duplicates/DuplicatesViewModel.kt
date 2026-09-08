package org.koitharu.kotatsu.favourites.ui.duplicates

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.alternatives.domain.MigrateUseCase
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.DuplicatesUseCase
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.domain.MangaDuplicate
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

/**
 * Drives the duplicate sheet for a whole batch of manga at once.
 * Duplicate detection uses the active FavouriteSpace. Private replacement remains blocked whenever
 * the matched entry is also present in Normal, so a Private action cannot rewrite shared state.
 */
@HiltViewModel
class DuplicatesViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val duplicatesUseCase: DuplicatesUseCase,
	private val migrateUseCase: MigrateUseCase,
	private val favouritesRepository: FavouritesRepository,
	private val settings: AppSettings,
) : BaseViewModel() {

	private val input: List<Manga> = savedStateHandle
		.require<List<ParcelableManga>>(AppRouter.KEY_MANGA_LIST)
		.map { it.manga }

	private val favouriteSpace: FavouriteSpace = FavouriteSpace.fromArgument(
		savedStateHandle[EXTRA_FAVOURITE_SPACE] ?: FavouriteSpace.NORMAL.dbValue,
	)

	private val accepted = ArrayList<Manga>(input.size)
	private val queue = ArrayList<Clash>()
	private var chaptersJob: Job? = null

	private val _state = MutableStateFlow<DuplicatesState>(DuplicatesState.Checking)
	val state: StateFlow<DuplicatesState> = _state

	val onFinished = MutableEventFlow<List<Manga>>()
	val onMigrated = MutableEventFlow<MigrationResult>()

	init {
		launchJob(Dispatchers.Default) {
			if (!settings.isDuplicateCheckEnabled) {
				accepted.addAll(input)
				advance()
				return@launchJob
			}

			val existingFavouriteIds = favouritesRepository.getMemberships(favouriteSpace)
				.asSequence()
				.mapTo(HashSet()) { it.mangaId }
			val semaphore = Semaphore(DUPLICATE_CHECK_CONCURRENCY)
			val checked: List<CheckedManga> = coroutineScope {
				input.map { manga ->
					async {
						if (manga.id in existingFavouriteIds) {
							CheckedManga(manga, isAlreadyFavourite = true, duplicates = emptyList())
						} else {
							CheckedManga(
								manga = manga,
								isAlreadyFavourite = false,
								duplicates = semaphore.withPermit {
									duplicatesUseCase(manga, favouriteSpace)
								},
							)
						}
					}
				}.awaitAll()
			}
			for (result in checked) {
				if (result.isAlreadyFavourite || result.duplicates.isEmpty()) {
					accepted.add(result.manga)
				} else {
					queue.add(Clash(result.manga, result.duplicates))
				}
			}
			advance()
		}
	}

	fun disableDuplicateCheck() {
		if (isBusy()) return
		settings.isDuplicateCheckEnabled = false
		launchJob(Dispatchers.Default) {
			queue.forEach { accepted.add(it.manga) }
			queue.clear()
			advance()
		}
	}

	fun setProgressMigrated(value: Boolean) {
		if (isBusy()) return
		settings.isDuplicateProgressMigrated = value
		_state.update { current ->
			if (current is DuplicatesState.Ask) current.copy(isProgressMigrated = value) else current
		}
	}

	fun skip() {
		if (isBusy()) return
		queue.removeFirstOrNull()
		launchJob(Dispatchers.Default) { advance() }
	}

	fun addAnyway() {
		if (isBusy()) return
		queue.removeFirstOrNull()?.let { accepted.add(it.manga) }
		launchJob(Dispatchers.Default) { advance() }
	}

	fun replaceWith(existing: Manga) {
		if (isBusy()) return
		val current = queue.firstOrNull() ?: return
		val duplicate = current.duplicates.firstOrNull { it.manga.id == existing.id } ?: return
		if (!duplicate.canReplace) return
		setCardsBusy(existing.id)
		launchLoadingJob(Dispatchers.Default) {
			try {
				migrateUseCase(
					oldManga = existing,
					newManga = current.manga,
					migrateProgress = settings.isDuplicateProgressMigrated,
				)
			} catch (e: Throwable) {
				setCardsBusy(null)
				throw e
			}
			onMigrated.call(
				MigrationResult(
					title = current.manga.title,
					fromSource = existing.source,
					toSource = current.manga.source,
				),
			)
			queue.removeFirstOrNull()
			advance()
		}
	}

	private fun isBusy(): Boolean = (_state.value as? DuplicatesState.Ask)?.isMigrating == true

	private fun setCardsBusy(migratingId: Long?) {
		_state.update { current ->
			if (current !is DuplicatesState.Ask) {
				current
			} else {
				current.copy(
					cards = current.cards.map { card ->
						card.copy(
							isMigrating = card.manga.id == migratingId,
							isBlocked = migratingId != null,
						)
					},
				)
			}
		}
	}

	private suspend fun advance() {
		chaptersJob?.cancel()
		val next = queue.firstOrNull()
		if (next == null) {
			onFinished.call(accepted)
			return
		}
		val known = duplicatesUseCase.getLocalChaptersCount(next.manga)
		_state.value = DuplicatesState.Ask(
			incoming = next.manga,
			cards = next.duplicates.map { duplicate ->
				DuplicateCardModel(duplicate, known, isMigrating = false, isBlocked = false)
			},
			remaining = queue.size - 1,
			isProgressMigrated = settings.isDuplicateProgressMigrated,
		)
		if (known == null) {
			resolveIncomingChapters(next.manga)
		}
	}

	private fun resolveIncomingChapters(manga: Manga) {
		chaptersJob = launchJob(Dispatchers.Default + SkipErrors) {
			val count = duplicatesUseCase.fetchChaptersCount(manga) ?: return@launchJob
			_state.update { current ->
				if (current !is DuplicatesState.Ask || current.incoming.id != manga.id) {
					current
				} else {
					current.copy(
						cards = current.cards.map { card -> card.copy(incomingChapters = count) },
					)
				}
			}
		}
	}

	private data class CheckedManga(
		val manga: Manga,
		val isAlreadyFavourite: Boolean,
		val duplicates: List<MangaDuplicate>,
	)

	private data class Clash(
		val manga: Manga,
		val duplicates: List<MangaDuplicate>,
	)

	private companion object {
		const val DUPLICATE_CHECK_CONCURRENCY = 4
	}
}
