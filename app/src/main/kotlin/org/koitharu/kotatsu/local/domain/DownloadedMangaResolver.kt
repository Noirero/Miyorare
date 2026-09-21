package org.koitharu.kotatsu.local.domain

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.MangaSource as ResolveMangaSource
import org.koitharu.kotatsu.core.model.isBroken
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the durable relationship between a remote title and downloaded/local artifacts.
 *
 * This is intentionally separate from Details loading. Details decides when a lookup may run;
 * this resolver owns where to look, FavouriteSpace isolation, canonical Local -> remote identity,
 * and persistence of recovered ownership. Indexed lookups never perform the broad reconnect scan.
 */
@Singleton
class DownloadedMangaResolver @Inject constructor(
	private val database: MangaDatabase,
	private val mangaDataRepository: MangaDataRepository,
	private val localMangaRepository: LocalMangaRepository,
	private val localMangaIndex: LocalMangaIndex,
	private val downloadDestinationStore: DownloadDestinationStore,
	private val mihonExtensionManager: MihonExtensionManager,
) {

	suspend fun resolveCanonicalManga(manga: Manga): Manga {
		if (!manga.isLocal) return manga
		val remoteId = localMangaIndex.getCanonicalRemoteIds(listOf(manga.id))[manga.id] ?: return manga
		var remote = mangaDataRepository.findMangaById(remoteId, withChapters = true) ?: return manga
		if (remote.source.isBroken && remote.source.name.startsWith("MIHON_")) {
			// Avoid treating the normal extension startup race as a permanently missing source.
			mihonExtensionManager.ensureReady(forceRefresh = false)
			remote = remote.copy(source = ResolveMangaSource(remote.source.name))
		}
		// A genuinely missing/removed extension must never make downloaded content unusable offline.
		return if (remote.source.isBroken) manga else remote
	}

	suspend fun findSavedManga(
		manga: Manga,
		favouriteSpace: FavouriteSpace?,
	): LocalManga? {
		// Global callers have no FavouriteSpace ownership boundary, so the repository's deterministic
		// path is the single source of truth: expected path -> persisted Local index -> bounded
		// title/chapter-evidence compatibility bridge. It never performs the old broad reconnect scan.
		if (favouriteSpace == null) {
			return localMangaRepository.findSavedManga(manga, withDetails = true)
		}

		val roots = downloadDestinationStore.readableRoots(favouriteSpace)

		// Space ownership is stronger than the global Local index for sidecar-free downloads.
		val ownership = database.getFavouriteDownloadIndexDao().findEntry(favouriteSpace.dbValue, manga.id)
		if (ownership != null) {
			val ownedFile = File(ownership.path)
			if (roots.any { ownedFile.isInside(it) }) {
				localMangaRepository.findSavedMangaAtPath(manga, ownedFile, withDetails = true)?.let {
					return it
				}
			}
		}

		// The global Local index can point at a copy owned by the other favourites space. Validate it
		// before accepting it; if it is outside this space, continue to the space-scoped compatibility
		// bridge instead of returning null and hiding a valid copy in the requested destination.
		val indexed = localMangaRepository.findSavedMangaIndexed(manga)
			?.takeIf { candidate ->
				when (favouriteSpace) {
					FavouriteSpace.PRIVATE -> roots.any { candidate.file.isInside(it) }
					FavouriteSpace.NORMAL -> if (downloadDestinationStore.privateUsesOwnRoot()) {
						val inNormal = roots.any { candidate.file.isInside(it) }
						val inPrivate = downloadDestinationStore.readableRoots(FavouriteSpace.PRIVATE)
							.any { candidate.file.isInside(it) }
						inNormal || !inPrivate
					} else {
						true
					}
				}
			}
			?: localMangaRepository.findSavedMangaIndexedByTitle(
				remoteManga = manga,
				roots = roots,
			)
			?: return null

		rememberFavouriteDownloadOwnership(favouriteSpace, manga.id, indexed.file)
		return indexed
	}

	private suspend fun rememberFavouriteDownloadOwnership(
		space: FavouriteSpace,
		mangaId: Long,
		file: File,
	) {
		val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
		val dao = database.getFavouriteDownloadIndexDao()
		if (dao.findEntry(space.dbValue, mangaId)?.path == path) return
		dao.upsert(
			FavouriteDownloadIndexEntity(
				mangaId = mangaId,
				space = space.dbValue,
				path = path,
			),
		)
	}

	private fun File.isInside(root: File): Boolean {
		val rootPath = runCatching { root.canonicalFile }.getOrDefault(root.absoluteFile)
			.path.trimEnd(File.separatorChar)
		val filePath = runCatching { canonicalFile }.getOrDefault(absoluteFile).path
		return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
	}
}
