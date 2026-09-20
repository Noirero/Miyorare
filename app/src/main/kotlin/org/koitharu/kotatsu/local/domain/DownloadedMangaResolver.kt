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
		// Space ownership is stronger than the global Local index for sidecar-free downloads.
		if (favouriteSpace != null) {
			val ownership = database.getFavouriteDownloadIndexDao().findEntry(favouriteSpace.dbValue, manga.id)
			if (ownership != null) {
				val ownedFile = File(ownership.path)
				val belongsToSpace = downloadDestinationStore.readableRoots(favouriteSpace)
					.any { ownedFile.isInside(it) }
				if (belongsToSpace) {
					localMangaRepository.findSavedMangaAtPath(manga, ownedFile, withDetails = true)?.let {
						return it
					}
				}
			}
		}

		// Primary path: deterministic/indexed lookup only. Broad reconnect scanning is intentionally
		// not available here; legacy recovery is handled by the persisted title/chapter-evidence bridge.
		val indexed = localMangaRepository.findSavedMangaIndexed(manga)
			?: favouriteSpace?.let { space ->
				localMangaRepository.findSavedMangaIndexedByTitle(
					remoteManga = manga,
					roots = downloadDestinationStore.readableRoots(space),
				)
			}
			?: if (favouriteSpace == null) {
				localMangaRepository.findSavedMangaIndexedByTitle(
					remoteManga = manga,
					roots = downloadDestinationStore.allReadableRoots(),
				)
			} else {
				null
			}
			?: return null
		if (favouriteSpace == FavouriteSpace.PRIVATE) {
			val inPrivate = downloadDestinationStore.readableRoots(FavouriteSpace.PRIVATE)
				.any { indexed.file.isInside(it) }
			if (!inPrivate) return null
		}
		if (favouriteSpace == FavouriteSpace.NORMAL && downloadDestinationStore.privateUsesOwnRoot()) {
			val inNormal = downloadDestinationStore.readableRoots(FavouriteSpace.NORMAL)
				.any { indexed.file.isInside(it) }
			val inPrivate = downloadDestinationStore.readableRoots(FavouriteSpace.PRIVATE)
				.any { indexed.file.isInside(it) }
			if (inPrivate && !inNormal) return null
		}
		if (favouriteSpace != null) {
			rememberFavouriteDownloadOwnership(favouriteSpace, manga.id, indexed.file)
		}
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
