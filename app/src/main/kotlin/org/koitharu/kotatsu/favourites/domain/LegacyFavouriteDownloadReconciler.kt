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
 * existing favourites database; it never walks storage during ordinary list rendering. If that
 * persisted index is both stale and empty, the migration rebuilds it once before reconciliation so
 * startup ordering cannot permanently defer legacy ownership until Details is opened.
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

		var localSnapshot = localMangaIndex.getPersistedSnapshot()
		if (localSnapshot.isEmpty()) {
			// A cold process may start this migration before a stale Local index has been rebuilt. Do the
			// required maintenance here once instead of waiting for Details to discover the same download.
			localMangaIndex.rebuildIfRequired()
			localSnapshot = localMangaIndex.getPersistedSnapshot()
		}
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

		val favouritesBySpace = FavouriteSpace.entries.associateWith { space ->
			favouritesRepository.getAllManga(space)
		}
		val membershipBySpace = favouritesBySpace.mapValues { (_, mangas) ->
			mangas.mapTo(HashSet()) { it.id }
		}
		val canInferSpaceFromPath =
			downloadDestinationStore.privateUsesOwnRoot() && !downloadDestinationStore.rootsOverlap()
		if (!canInferSpaceFromPath) {
			repairSharedRootOwnership(membershipBySpace)
		}

		var allRootsReadable = true
		for (space in FavouriteSpace.entries) {
			// Use the same ownership boundary as interactive badges, including historical roots.
			val configured = downloadDestinationStore.readableRoots(space)
			val readable = configured.filter { it.isDirectory && it.canRead() }
			if (readable.size != configured.size || readable.isEmpty()) allRootsReadable = false
			val roots = readable.map { File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME) }
			if (roots.isEmpty()) continue
			val candidates = favouritesBySpace.getValue(space)
			val knownIds = downloadedContentClassifier.getKnownDownloadedIds(space, candidates.map { it.id })
			val otherSpace = if (space == FavouriteSpace.NORMAL) FavouriteSpace.PRIVATE else FavouriteSpace.NORMAL
			for (candidate in candidates) {
				// Current downloads already have authoritative ownership; never parse them during repair.
				if (candidate.id in knownIds || candidate.isLocal || !candidate.hasLegacyTitleCandidate(localTitles)) continue
				// A shared physical root contains no Normal/Private identity. If the same title belongs to
				// both spaces, assigning its legacy artifact to either side would be a guess, so leave it
				// unresolved until an explicit ownership-producing action occurs.
				if (!canInferSpaceFromPath && candidate.id in membershipBySpace.getValue(otherSpace)) continue
				val remote = mangaDataRepository.findMangaById(candidate.id, withChapters = true) ?: continue
				val linked = localMangaRepository.findSavedMangaIndexedByTitle(remote, roots) ?: continue
				// Persist verified ownership before publishing/marking completion, as DownloadWorker does.
				// SharedFlow delivery alone does not guarantee its asynchronous index collector has committed.
				database.getFavouriteDownloadIndexDao().upsert(listOf(
					FavouriteDownloadIndexEntity(remote.id, space.dbValue, linked.file.canonicalPath),
				))
				// Publish the verified remote identity through the normal storage pipeline so both the Local
				// index and favourite_download_index update and active Favorites screens invalidate naturally.
				// Match the canonical alias path so the Local index cannot overwrite remote metadata.
				localStorageChanges.emit(linked.copy(file = linked.file.canonicalFile))
			}
		}
		// Unavailable storage must not make a one-shot repair permanently forget its candidates.
		if (allRootsReadable) prefs.edit { putBoolean(KEY_COMPLETE, true) }
	}

	private suspend fun repairSharedRootOwnership(membershipBySpace: Map<FavouriteSpace, Set<Long>>) {
		val normalIds = membershipBySpace.getValue(FavouriteSpace.NORMAL)
		val privateIds = membershipBySpace.getValue(FavouriteSpace.PRIVATE)
		val relevantIds = normalIds + privateIds
		if (relevantIds.isEmpty()) return
		val dao = database.getFavouriteDownloadIndexDao()
		for (chunk in relevantIds.chunked(INDEX_QUERY_CHUNK_SIZE)) {
			for ((mangaId, entries) in dao.findEntries(chunk).groupBy { it.mangaId }) {
				if (entries.size < 2 || entries.map { it.path }.distinct().size != 1) continue
				val inNormal = mangaId in normalIds
				val inPrivate = mangaId in privateIds
				when {
					inNormal && !inPrivate -> dao.delete(FavouriteSpace.PRIVATE.dbValue, mangaId)
					inPrivate && !inNormal -> dao.delete(FavouriteSpace.NORMAL.dbValue, mangaId)
					inNormal && inPrivate -> {
						// Historical path-derived rows cannot tell which destination was selected. Keeping either
						// would preserve the leak, so make the ambiguous state unknown until an explicit action.
						dao.delete(FavouriteSpace.NORMAL.dbValue, mangaId)
						dao.delete(FavouriteSpace.PRIVATE.dbValue, mangaId)
					}
				}
			}
		}
	}

	private fun Manga.hasLegacyTitleCandidate(keys: Set<String>): Boolean =
		title.legacyTitleKey() in keys || altTitles.any { it.legacyTitleKey() in keys }

	private fun String.legacyTitleKey(): String =
		trim().lowercase().replace(WHITESPACE, " ")

	private companion object {
		const val PREFS_NAME = "legacy_favourite_download_reconcile"
		// v3 intentionally reruns the one-shot repair for installations where the earlier v2 pass
		// completed before the final indexed title/chapter-evidence compatibility path was available.
		const val KEY_COMPLETE = "v3_all_spaces_complete"
		const val INDEX_QUERY_CHUNK_SIZE = 500
		val WHITESPACE = Regex("\\s+")
	}
}
