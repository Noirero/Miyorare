package org.koitharu.kotatsu.favourites.domain

import dagger.Reusable
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.isNovelContentPath
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject

/** Lightweight DB/path classifier for entries that belong to the virtual Downloaded shelf. */
@Reusable
class DownloadedContentClassifier @Inject constructor(
	private val db: MangaDatabase,
	private val storageManager: LocalStorageManager,
	private val destinationStore: DownloadDestinationStore,
) {
	/**
	 * Returns indexed downloaded ids inside the requested favourites space. This only reads the local
	 * index and compares paths; it never walks manga folders per item, so separating Normal/Private
	 * does not add UI-time disk scans.
	 *
	 * A null [space] preserves the old global classification for callers that intentionally operate
	 * across every configured local root.
	 */
	suspend fun getLocalDownloadedIds(space: FavouriteSpace? = null): Set<Long> {
		return db.getLocalMangaIndexDao().findAllEntries()
			.filterToDownloadRoots(downloadRoots(space))
			.mapTo(HashSet()) { it.mangaId }
	}

	/**
	 * Space-aware downloaded state for actual manga models. `local_index` intentionally has one row
	 * per manga id, so when the same title is downloaded in both Normal and Private the indexed path
	 * can represent only one physical copy. The common case is a cheap DB/path comparison; only when
	 * that one indexed path points outside [space] do we probe the known roots directly to detect the
	 * second copy. This avoids a database migration and avoids recursive/per-item directory scans.
	 */
	suspend fun getDownloadedIds(items: Collection<Manga>, space: FavouriteSpace): Set<Long> {
		if (items.isEmpty()) return emptySet()
		val candidateIds = items.mapTo(HashSet(items.size)) { it.id }
		val entriesById = db.getLocalMangaIndexDao().findAllEntries()
			.asSequence()
			.filter { it.mangaId in candidateIds }
			.associateBy { it.mangaId }
		val roots = destinationStore.readableRoots(space)
		val downloadRoots = roots.map { File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME) }
		val result = HashSet<Long>()
		for (manga in items) {
			val indexed = entriesById[manga.id] ?: continue
			if (indexed.path.isInsideAny(downloadRoots) || hasExistingOutput(manga, roots)) {
				result += manga.id
			}
		}
		return result
	}

	suspend fun getLocalNovelIds(space: FavouriteSpace? = null): Set<Long> {
		val result = HashSet<Long>()
		for (entry in db.getLocalMangaIndexDao().findAllEntries().filterToDownloadRoots(downloadRoots(space))) {
			if (entry.path.isNovelContentPath()) {
				result += entry.mangaId
			}
		}
		return result
	}

	private suspend fun hasExistingOutput(manga: Manga, roots: List<File>): Boolean = runCatchingCancellable {
		for (root in roots) {
			val output = LocalMangaOutput.get(root, manga) ?: continue
			runCatching { output.close() }
			return@runCatchingCancellable true
		}
		false
	}.getOrDefault(false)

	private suspend fun downloadRoots(space: FavouriteSpace?): List<File> {
		val roots = if (space == null) {
			storageManager.getConfiguredDirs()
		} else {
			destinationStore.readableRoots(space)
		}
		return roots.map { File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME) }
	}

	private fun List<org.koitharu.kotatsu.local.data.index.LocalMangaIndexEntity>.filterToDownloadRoots(
		downloadRoots: List<File>,
	) = filter { entry -> entry.path.isInsideAny(downloadRoots) }

	private fun String.isInsideAny(roots: List<File>): Boolean {
		val path = File(this).absolutePath
		return roots.any { root ->
			val rootPath = root.absolutePath.trimEnd(File.separatorChar)
			path == rootPath || path.startsWith(rootPath + File.separator)
		}
	}
}
