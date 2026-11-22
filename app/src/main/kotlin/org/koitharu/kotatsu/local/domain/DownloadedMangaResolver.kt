package org.koitharu.kotatsu.local.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.logs.LegacyCbzDiagnosticExport
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.MangaSource as ResolveMangaSource
import org.koitharu.kotatsu.core.model.isBroken
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.ExhentaiDownloadedCopy
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.sources.compat.EhentaiLegacyDownloadResolver
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
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
		if (!BuildConfig.DEBUG || !ExhentaiDownloadedCopy.isOfficial(manga)) {
			return resolveSavedManga(manga, favouriteSpace)
		}
		val trace = LegacyCbzTrace()
		return withContext(trace) {
			val saved = resolveSavedManga(manga, favouriteSpace)
			val result = when {
				saved == null && trace.hasCandidate -> "REJECTED"
				saved == null -> "NOT_FOUND"
				saved.manga.chapters.isNullOrEmpty() -> "CHAPTER_NOT_MAPPED"
				else -> "LOCAL_CHAPTER_AVAILABLE"
			}
			LegacyCbzTrace.log("LOOKUP_${favouriteSpace?.name ?: "BROWSE"} $result ${trace.summary()}")
			saved
		}
	}

	private suspend fun resolveSavedManga(
		manga: Manga,
		favouriteSpace: FavouriteSpace?,
	): LocalManga? {
		val officialExhentai = ExhentaiDownloadedCopy.isOfficial(manga)
		val titleFallback = EhentaiSourceFamily.supportsTitleMatchedDownloads(manga.source.name)
		// ExHentai browse always resolves Normal; never scan an unscoped pool of Private files.
		val space = favouriteSpace ?: if (titleFallback) FavouriteSpace.NORMAL else null
		if (space == null) {
			return localMangaRepository.findSavedManga(manga, withDetails = true)
		}

		val roots = downloadDestinationStore.readableRoots(space)

		// Space ownership is stronger than the global Local index for sidecar-free downloads.
		val ownership = database.getFavouriteDownloadIndexDao().findEntry(space.dbValue, manga.id)
		if (ownership != null) {
			val ownedFile = File(ownership.path)
			val inKnownRoot = roots.any { ownedFile.isInside(it) }
			if (officialExhentai) {
				val match = localMangaRepository.findExhentaiAtPath(
					manga, ownedFile, withDetails = true, space = space,
					trustedOwnedPath = inKnownRoot,
				)
				if (match != null && (inKnownRoot || (
						match.verified && match.receipt?.space == space &&
							match.local.file.canonicalFile == ownedFile.canonicalFile
						))
				) return match.local
			} else if (inKnownRoot) {
				localMangaRepository.findSavedMangaAtPath(manga, ownedFile, withDetails = true)?.let { return it }
			}
		}

		// When Normal and Private share a physical destination, path alone cannot identify which space
		// owns an unindexed legacy artifact. Do not let opening Details manufacture ownership for the
		// active space; the background legacy reconciler is the only compatibility path allowed to
		// persist ownership after checking favourite membership and chapter evidence.
		if (officialExhentai) {
			return localMangaRepository.findExhentaiDestinationCopy(manga, space)
				?: localMangaRepository.findExhentaiInRoots(manga, roots, withDetails = true, space = space)
				?: findTitleMatchedChapter(manga, space, roots)
		}
		if (!canInferSpaceFromPath()) {
			return if (titleFallback) findTitleMatchedChapter(manga, space, roots) else null
		}

		// With distinct roots the global Local index is safe as a compatibility hint, but it still has
		// to live inside the requested space before it can be accepted and persisted as ownership.
		val indexed = localMangaRepository.findSavedMangaIndexed(manga)
			?.takeIf { candidate -> roots.any { candidate.file.isInside(it) } }
			?: localMangaRepository.findSavedMangaIndexedByTitle(
				remoteManga = manga,
				roots = roots,
			)
		if (indexed != null) {
			rememberFavouriteDownloadOwnership(space, manga.id, indexed.file)
			return indexed
		}
		return if (titleFallback) findTitleMatchedChapter(manga, space, roots) else null
	}

	/**
	 * Only Details/Reader may use this weak association. It is not registered in Local indexes or
	 * favourite_download_index, so worker completion and destructive operations keep their guards.
	 */
	private suspend fun findTitleMatchedChapter(
		manga: Manga,
		space: FavouriteSpace,
		roots: Collection<File>,
	): LocalManga? {
		if (manga.chapters?.size != 1) return null
		val candidates = LinkedHashSet<File>()
		for (root in roots) {
			for (file in EhentaiLegacyDownloadResolver.findTitleMatchedChapters(root, manga.title)) {
				if (!file.isInside(root)) continue
				candidates += file.canonicalFile
				// More than one physical copy is ambiguous, even if names differ only by _1.
				if (candidates.size > 1) return null
			}
		}
		val file = candidates.singleOrNull() ?: return null
		val evidence = ExhentaiChapterOwnership.inspectDestination(file)
		if (evidence == ExhentaiChapterOwnership.Evidence.Unusable) return null
		val receiptSpace = (evidence as? ExhentaiChapterOwnership.Evidence.Owned)?.receipt?.space
		if (receiptSpace != null && receiptSpace != space) return null

		val indexedPaths = listOf(file.canonicalPath, file.parentFile.canonicalPath)
		val existingOwners = database.getFavouriteDownloadIndexDao().findEntriesByPaths(indexedPaths)
		if (existingOwners.any { it.space != space.dbValue }) return null

		// Shared roots cannot distinguish an unindexed legacy Normal file from Private content.
		// An explicit same-space download row or an archive receipt is required in that case.
		val otherSpace = if (space == FavouriteSpace.NORMAL) FavouriteSpace.PRIVATE else FavouriteSpace.NORMAL
		val sharedRoot = downloadDestinationStore.readableRoots(otherSpace).any { file.isInside(it) }
		if (sharedRoot && receiptSpace != space && existingOwners.none { it.space == space.dbValue }) return null

		return ExhentaiDownloadedCopy.readTitleMatchedChapter(manga, file)
	}

	private fun canInferSpaceFromPath(): Boolean =
		downloadDestinationStore.privateUsesOwnRoot() && !downloadDestinationStore.rootsOverlap()

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

/** Debug-only, per lookup, fixed reason codes; never stores gallery or filesystem identifiers. */
internal class LegacyCbzTrace : AbstractCoroutineContextElement(Key) {
	enum class Stage {
		CANDIDATE, ARCHIVE_REJECTED, OWNERSHIP_CONFLICT, UNVERIFIED_ALIAS, SPACE_REJECTED,
		AMBIGUOUS_RECOVERY,
		DESTINATION_CHAPTER_UNAVAILABLE, DESTINATION_CANDIDATE, DESTINATION_SHARED_ROOT,
		DESTINATION_NO_UNIQUE_CBZ, DESTINATION_LAYOUT_UNSUPPORTED, DESTINATION_SELECTED,
	}

	private val stages = java.util.EnumSet.noneOf(Stage::class.java)
	val hasCandidate get() = Stage.CANDIDATE in stages || Stage.DESTINATION_CANDIDATE in stages
	fun summary(): String = stages.joinToString(",")

	companion object Key : CoroutineContext.Key<LegacyCbzTrace> {
		suspend fun record(stage: Stage) {
			if (BuildConfig.DEBUG) currentCoroutineContext()[Key]?.stages?.add(stage)
		}

		fun log(message: String) {
			if (BuildConfig.DEBUG) LegacyCbzDiagnosticExport.record(message)
		}
	}
}
