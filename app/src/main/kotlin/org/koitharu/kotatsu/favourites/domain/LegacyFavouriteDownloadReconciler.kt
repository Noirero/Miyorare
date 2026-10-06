package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga

/**
 * One-shot compatibility repair for downloads created before remote-id ownership was persisted.
 *
 * The migration is deliberately background-only. It starts from the persisted Local index and the
 * existing favourites database; it never walks storage. Only title-intersecting candidates are
 * verified, and [LocalMangaRepository.findSavedMangaIndexedByTitle] requires concrete chapter
 * evidence before it persists a remote -> physical Local alias. This keeps ordinary list rendering
 * index-only while removing the old requirement to open Details before a legacy download is known.
 */
@Singleton
class LegacyFavouriteDownloadReconciler @Inject constructor(
	@ApplicationContext context: Context,
	private val favouritesRepository: FavouritesRepository,
	private val database: MangaDatabase,
	private val downloadedContentClassifier: DownloadedContentClassifier,
	private val mangaDataRepository: MangaDataRepository,
	private val localMangaIndex: LocalMangaIndex,
	private val localMangaRepository: LocalMangaRepository,
	private val downloadDestinationStore: DownloadDestinationStore,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
) {
	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	suspend fun reconcileOnce() {
		if (prefs.getBoolean(KEY_COMPLETE, false)) return

		val localSnapshot = localMangaIndex.getPersistedSnapshot()
		if (localSnapshot.isEmpty()) return
		val localTitles = localSnapshot.asSequence()
			.flatMap { sequenceOf(it.manga.title) + it.manga.altTitles.asSequence() }
			.map { it.legacyTitleKey() }
			.filter(String::isNotEmpty)
			.toHashSet()
		if (localTitles.isEmpty()) {
			prefs.edit { putBoolean(KEY_COMPLETE, true) }
			return
		}

		var allRootsReadable = true
		for (space in FavouriteSpace.entries) {
			// Use the same ownership boundary as interactive badges, including historical roots.
			val configured = downloadDestinationStore.readableRoots(space)
			val readable = configured.filter { it.isDirectory && it.canRead() }
			if (readable.size != configured.size || readable.isEmpty()) allRootsReadable = false
			val roots = readable.map { File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME) }
			if (roots.isEmpty()) continue
			val candidates = favouritesRepository.getAllManga(space)
			val knownIds = downloadedContentClassifier.getKnownDownloadedIds(space, candidates.map { it.id })
			for (candidate in candidates) {
				// Current downloads already have authoritative ownership; never parse them during repair.
				if (candidate.id in knownIds || candidate.isLocal || !candidate.hasLegacyTitleCandidate(localTitles)) continue
				val remote = mangaDataRepository.findMangaById(candidate.id, withChapters = true) ?: continue
				val linked = localMangaRepository.findSavedMangaIndexedByTitle(remote, roots) ?: continue
				// Persist verified ownership before publishing/marking completion, as DownloadWorker does.
				// SharedFlow delivery alone does not guarantee its asynchronous index collector has committed.
				database.getFavouriteDownloadIndexDao().upsert(listOf(
					FavouriteDownloadIndexEntity(remote.id, space.dbValue, linked.file.canonicalPath),
				))
				// Publish the verified remote identity through the normal storage pipeline so both the Local
				// index and favourite_download_index update and active Favorites screens invalidate naturally.
				localStorageChanges.emit(linked)
			}
		}
		// Unavailable storage must not make a one-shot repair permanently forget its candidates.
		if (allRootsReadable) prefs.edit { putBoolean(KEY_COMPLETE, true) }
	}

	private fun Manga.hasLegacyTitleCandidate(keys: Set<String>): Boolean =
		title.legacyTitleKey() in keys || altTitles.any { it.legacyTitleKey() in keys }

	private fun String.legacyTitleKey(): String =
		trim().lowercase().replace(WHITESPACE, " ")

	private companion object {
		const val PREFS_NAME = "legacy_favourite_download_reconcile"
		const val KEY_COMPLETE = "v2_all_spaces_complete"
		val WHITESPACE = Regex("\\s+")
	}
}
