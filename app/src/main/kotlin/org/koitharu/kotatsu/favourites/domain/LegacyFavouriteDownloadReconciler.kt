package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject
import javax.inject.Singleton

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
	private val mangaDataRepository: MangaDataRepository,
	private val localMangaIndex: LocalMangaIndex,
	private val localMangaRepository: LocalMangaRepository,
	private val localStorageManager: LocalStorageManager,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
) {
	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	suspend fun reconcileOnce() {
		if (prefs.getBoolean(KEY_COMPLETE, false)) return

		val localSnapshot = localMangaIndex.getPersistedSnapshot()
		if (localSnapshot.isEmpty()) return
		val localTitles = localSnapshot.asSequence()
			.flatMap { sequenceOf(it.manga.title) + it.manga.altTitles.asSequence() }
			.map(String::legacyTitleKey)
			.filter(String::isNotEmpty)
			.toHashSet()
		if (localTitles.isEmpty()) {
			prefs.edit { putBoolean(KEY_COMPLETE, true) }
			return
		}

		val roots = localStorageManager.getReadableDirs()
		if (roots.isEmpty()) return
		for (candidate in favouritesRepository.getAllManga(FavouriteSpace.NORMAL)) {
			if (candidate.isLocal || !candidate.hasLegacyTitleCandidate(localTitles)) continue
			val remote = mangaDataRepository.findMangaById(candidate.id, withChapters = true) ?: continue
			val linked = localMangaRepository.findSavedMangaIndexedByTitle(remote, roots) ?: continue
			// Publish the verified remote identity through the normal storage pipeline so both the Local
			// index and favourite_download_index update and active Favorites screens invalidate naturally.
			localStorageChanges.emit(linked)
		}
		prefs.edit { putBoolean(KEY_COMPLETE, true) }
	}

	private fun Manga.hasLegacyTitleCandidate(keys: Set<String>): Boolean =
		title.legacyTitleKey() in keys || altTitles.any { it.legacyTitleKey() in keys }

	private fun String.legacyTitleKey(): String =
		trim().lowercase().replace(WHITESPACE, " ")

	private companion object {
		const val PREFS_NAME = "legacy_favourite_download_reconcile"
		const val KEY_COMPLETE = "v1_complete"
		val WHITESPACE = Regex("\\s+")
	}
}
