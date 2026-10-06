package org.koitharu.kotatsu.details.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.local.data.LegacyChapterDownloadCompat
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import java.io.File
import javax.inject.Inject

/* TODO: remove */
class DetailsInteractor @Inject constructor(
	private val favouritesRepository: FavouritesRepository,
	private val localMangaRepository: LocalMangaRepository,
	private val localMangaIndex: LocalMangaIndex,
	private val database: MangaDatabase,
	private val trackingRepository: TrackingRepository,
	private val settings: AppSettings,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
) {

	fun observeFavourite(mangaId: Long): Flow<Set<FavouriteCategory>> {
		return favouritesRepository.observeCategories(mangaId)
	}

	fun observeNewChapters(mangaId: Long): Flow<Int> {
		return settings.observeAsFlow(AppSettings.KEY_TRACKER_ENABLED) { isTrackerEnabled }
			.flatMapLatest { isEnabled ->
				if (isEnabled) {
					trackingRepository.observeNewChaptersCount(mangaId)
				} else {
					flowOf(0)
				}
			}
	}

	fun observeScrobblingInfo(mangaId: Long): Flow<List<ScrobblingInfo>> {
		return combine(
			scrobblers.map { it.observeScrobblingInfo(mangaId) },
		) { scrobblingInfo ->
			scrobblingInfo.filterNotNull()
		}
	}

	fun observeIncognitoMode(mangaFlow: Flow<Manga?>): Flow<TriStateOption> {
		return mangaFlow
			.filterNotNull()
			.distinctUntilChangedBy { it.isNsfw() }
			.combine(observeIncognitoMode()) { manga, globalIncognito ->
				when {
					globalIncognito -> TriStateOption.ENABLED
					manga.isNsfw() -> settings.incognitoModeForNsfw
					else -> TriStateOption.DISABLED
				}
			}
	}

	suspend fun updateLocal(subject: MangaDetails?, localManga: LocalManga): MangaDetails? {
		subject ?: return null
		if (subject.isLocal) {
			val isSameLocal = subject.id == localManga.manga.id || subject.local?.file?.samePathAs(localManga.file) == true
			return if (isSameLocal) subject.copy(manga = localManga.manga) else subject
		}

		val isDirectMatch = subject.id == localManga.manga.id || subject.local?.file?.samePathAs(localManga.file) == true
		val isIndexedMatch = if (isDirectMatch) {
			true
		} else {
			// DownloadWorker registers the remote -> physical Local alias before publishing the storage
			// event. Its path and any legacy remote-id row are explicit persisted identities. Do not
			// invoke the repository resolver here: it also probes expected paths and may request a
			// global index rebuild when no row exists.
			val aliasPath = localMangaIndex.getDownloadAliasPaths(listOf(subject.id))[subject.id]
			aliasPath?.let { File(it).samePathAs(localManga.file) } == true ||
				database.getLocalMangaIndexDao().findPath(subject.id)?.let { File(it).samePathAs(localManga.file) } == true
		}
		if (!isIndexedMatch) {
			return subject
		}

		val updatedLocal = if (localManga.manga.chapters != null) {
			// Concrete live snapshots can come from two producers: partial deletion already carries the
			// surviving physical chapter list, while DownloadWorker parses the freshly finished artifact
			// and can therefore carry filesystem-derived chapter ids. Canonicalize that in-memory snapshot
			// against the remote manga before exposing it to MangaDetails. This keeps the verified event
			// path/snapshot, avoids a second filesystem parse, and makes Reader select LocalMangaSource
			// immediately instead of falling back to the network until Details is refreshed.
			LegacyChapterDownloadCompat.linkToRemote(
				subject.sourceManga,
				localManga.copy(manga = localManga.manga.copy(chapters = localManga.manga.chapters?.toList())),
			)
		} else {
			// Download completion events without chapter details are hydrated only from the verified event
			// path and keep the repository's existing chapter/legacy compatibility mapping.
			localMangaRepository.findSavedMangaAtPath(subject.sourceManga, localManga.file, rememberIdentity = false)
		} ?: return subject
		return subject.copy(localManga = updatedLocal)
	}

	private fun File.samePathAs(other: File): Boolean =
		runCatching { canonicalFile == other.canonicalFile }.getOrDefault(absoluteFile == other.absoluteFile)

	suspend fun findRemote(seed: Manga) = localMangaRepository.getRemoteManga(seed)

	private fun observeIncognitoMode() = settings.observeAsFlow(AppSettings.KEY_INCOGNITO_MODE) {
		isIncognitoModeEnabled
	}
}