package org.koitharu.kotatsu.local.domain

import android.content.Context
import android.util.Base64
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
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
import org.koitharu.kotatsu.local.data.LegacyChapterDownloadCompat
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.sources.compat.EhentaiLegacyDownloadResolver
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import org.koitharu.kotatsu.sources.compat.EhentaiComicInfo
import org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership
import org.koitharu.kotatsu.sources.compat.ExhentaiChapterFileName
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.longHashCode
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
	@ApplicationContext private val context: Context,
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
		forExhentaiDetails: Boolean = false,
	): LocalManga? {
		// Details alone may interpret an unrelated/old CBZ as a read-only title match.
		// All existing callers retain strict ownership and download-completion semantics.
		val detailsTitleRead = forExhentaiDetails &&
			(manga.source.name == EhentaiSourceFamily.OFFICIAL_SOURCE_NAME ||
				manga.source.name == GEKKO_EXHENTAI_SOURCE_NAME)
		if (!BuildConfig.DEBUG || !ExhentaiDownloadedCopy.isOfficial(manga)) {
			return resolveSavedManga(manga, favouriteSpace, detailsTitleRead)
				?.let { if (detailsTitleRead) projectExhentaiChapterIdentity(manga, it) else it }
		}
		val trace = LegacyCbzTrace()
		return withContext(trace) {
			val saved = resolveSavedManga(manga, favouriteSpace, detailsTitleRead)
				?.let { if (detailsTitleRead) projectExhentaiChapterIdentity(manga, it) else it }
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
		detailsTitleRead: Boolean,
	): LocalManga? {
		val officialExhentai = ExhentaiDownloadedCopy.isOfficial(manga)
		val titleFallback = EhentaiSourceFamily.supportsTitleMatchedDownloads(manga.source.name)
		// ExHentai browse always resolves Normal; never scan an unscoped pool of Private files.
		val space = favouriteSpace ?: if (titleFallback) FavouriteSpace.NORMAL else null
		if (space == null) {
			return localMangaRepository.findSavedManga(manga, withDetails = true)
		}

		val activeExhentaiScope = officialExhentai || manga.source.name == GEKKO_EXHENTAI_SOURCE_NAME
		val roots = if (activeExhentaiScope) downloadDestinationStore.activeExhentaiRoots(space)
			else downloadDestinationStore.readableRoots(space)

		// An exact receipt/ownership row is not permission to bypass a multi-file read choice.
		// Keep non-Details callers (completion/automatic removal) on their original strict path.
		if (detailsTitleRead && supportsActiveExhentaiChoice(manga) &&
			listActiveExhentaiTitleFiles(manga, space).size > 1) return null

		// Space ownership is stronger than the global Local index for sidecar-free downloads.
		val ownership = database.getFavouriteDownloadIndexDao().findEntry(space.dbValue, manga.id)
		if (ownership != null) {
			val ownedFile = File(ownership.path)
			val inKnownRoot = roots.any { ownedFile.isInside(it) } &&
				(!activeExhentaiScope || downloadDestinationStore.isVisibleInActiveExhentaiDestination(space, ownedFile))
			if (officialExhentai) {
				val match = localMangaRepository.findExhentaiAtPath(
					manga, ownedFile, withDetails = true, space = space,
					trustedOwnedPath = inKnownRoot,
				)
				if (match != null && (!activeExhentaiScope || inKnownRoot) && (inKnownRoot || (
						match.verified && match.receipt?.space == space &&
							match.local.file.canonicalFile == ownedFile.canonicalFile
						))
				) return match.local
			} else if (inKnownRoot) {
				if (detailsTitleRead && manga.source.name == GEKKO_EXHENTAI_SOURCE_NAME && ownedFile.isDirectory) {
					// Older ownership rows may point at a gallery directory. Details/Delete must use
					// the unambiguous CBZ itself, not the generic directory-backed LocalManga.
					findTitleMatchedChapter(manga, space, roots, detailsTitleRead = true)?.let { return it }
					if (requiresExhentaiReadChoice(manga, space)) return null
				}
				localMangaRepository.findSavedMangaAtPath(manga, ownedFile, withDetails = true)?.let { return it }
			}
		}

		// When Normal and Private share a physical destination, path alone cannot identify which space
		// owns an unindexed legacy artifact. Do not let opening Details manufacture ownership for the
		// active space; the background legacy reconciler is the only compatibility path allowed to
		// persist ownership after checking favourite membership and chapter evidence.
		if (officialExhentai) {
			// Details must not fall through to another same-title copy while a user choice
			// is unresolved. Verified exact ownership above still takes precedence.
			if (detailsTitleRead) {
				findTitleMatchedChapter(manga, space, roots, detailsTitleRead = true)?.let { return it }
				if (requiresExhentaiReadChoice(manga, space)) return null
			}
			return localMangaRepository.findExhentaiDestinationCopy(manga, space, roots)
				?: localMangaRepository.findExhentaiInRoots(manga, roots, withDetails = true, space = space)
				?: findTitleMatchedChapter(manga, space, roots, detailsTitleRead)
		}
		// The generic title/index bridge persists aliases and can silently promote another
		// ExHentai source's file into ownership. For title-compatible sources, only the
		// explicit source-owned row above or the read-only fallback may supply local state.
		if (titleFallback) return findTitleMatchedChapter(manga, space, roots, detailsTitleRead)
		if (!canInferSpaceFromPath()) return null

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
		return null
	}

	/**
	 * Only Details/Reader may use this weak association. It is not registered in Local indexes or
	 * favourite_download_index, so worker completion and destructive operations keep their guards.
	 */
	private suspend fun findTitleMatchedChapter(
		manga: Manga,
		space: FavouriteSpace,
		roots: Collection<File>,
		detailsTitleRead: Boolean,
	): LocalManga? {
		if (manga.chapters?.size != 1) return null
		if (detailsTitleRead) {
			val candidate = loadActiveExhentaiTitleCandidates(manga, space).singleOrNull() ?: return null
			val galleryId = EhentaiSourceFamily.galleryId(manga.publicUrl, manga.url)
			return candidate.second.takeIf {
				candidate.first.galleryId == null || candidate.first.galleryId == galleryId
			}
		}
		val candidates = LinkedHashSet<File>()
		for (root in roots) {
			for (file in EhentaiLegacyDownloadResolver.findTitleMatchedChapters(root, manga.title)) {
				if (!file.isInside(root) ||
					((manga.source.name == EhentaiSourceFamily.OFFICIAL_SOURCE_NAME ||
						manga.source.name == GEKKO_EXHENTAI_SOURCE_NAME) &&
						!downloadDestinationStore.isVisibleInActiveExhentaiDestination(space, file))) continue
				candidates += file.canonicalFile
				// Bound total candidates across all historical roots, not only per source directory.
				// The Details path must not inspect an unbounded number of duplicate archives.
				if (candidates.size > MAX_DETAILS_TITLE_CANDIDATES) return null
				// Existing non-Details call sites fail closed on multiple physical copies.
				if (!detailsTitleRead && candidates.size > 1) return null
			}
		}
		val file = candidates.singleOrNull() ?: return null
		val evidence = ExhentaiChapterOwnership.inspectDestination(file)
		if (!detailsTitleRead && evidence == ExhentaiChapterOwnership.Evidence.Unusable) return null
		val receiptSpace = (evidence as? ExhentaiChapterOwnership.Evidence.Owned)?.receipt?.space
		if (receiptSpace != null && receiptSpace != space) return null

		val indexedPaths = listOf(file.canonicalPath, file.parentFile.canonicalPath)
		val existingOwners = database.getFavouriteDownloadIndexDao().findEntriesByPaths(indexedPaths)
		if (existingOwners.any { it.space != space.dbValue }) return null

		// Shared roots cannot distinguish an unindexed legacy Normal file from Private content.
		// An explicit same-space download row or an archive receipt is required in that case.
		val otherSpace = if (space == FavouriteSpace.NORMAL) FavouriteSpace.PRIVATE else FavouriteSpace.NORMAL
		val sharedRoot = downloadDestinationStore.readableRoots(otherSpace).any { file.isInsideExhentaiLayout(it) }
		if (sharedRoot && receiptSpace != space && existingOwners.none { it.space == space.dbValue }) return null

		return ExhentaiDownloadedCopy.readTitleMatchedChapter(manga, file, allowIndexed = detailsTitleRead)
	}

	data class ExhentaiReadChoice(
		val path: String,
		val galleryId: Long?,
		val length: Long,
		val lastModified: Long,
		val readable: Boolean = true,
		val sha256: String? = null,
	)

	/** A selected file is bound to this Reader session, not stored as a gallery preference. */
	data class ExhentaiSessionChoice(
		val mangaId: Long,
		val sourceName: String,
		val space: FavouriteSpace,
		val candidate: ExhentaiReadChoice,
		val sha256: String,
	)

	private suspend fun choiceCandidates(manga: Manga, space: FavouriteSpace): List<ExhentaiReadChoice> {
		if (!supportsActiveExhentaiChoice(manga)) return emptyList()
		val candidates = loadActiveExhentaiTitleCandidates(manga, space).map { it.first }
		if (candidates.size >= 2) return candidates
		val single = candidates.singleOrNull() ?: return emptyList()
		val galleryId = EhentaiSourceFamily.galleryId(manga.publicUrl, manga.url)
		return if (single.readable && single.galleryId != null && single.galleryId != galleryId)
			candidates else emptyList()
	}

	private suspend fun requiresExhentaiReadChoice(manga: Manga, space: FavouriteSpace): Boolean =
		choiceCandidates(manga, space).isNotEmpty()

	/** Snapshot readable candidates only at a Read action, never during Details discovery. */
	suspend fun getExhentaiReadChoices(manga: Manga, space: FavouriteSpace): List<ExhentaiReadChoice> =
		choiceCandidates(manga, space).map { candidate ->
			val fingerprint = if (candidate.readable) fingerprintOfChoice(candidate) else null
			candidate.copy(readable = candidate.readable && fingerprint != null, sha256 = fingerprint)
		}

	suspend fun selectExhentaiReadChoice(
		manga: Manga,
		space: FavouriteSpace,
		choice: ExhentaiReadChoice,
	): ExhentaiSessionChoice? {
		val fingerprint = choice.sha256 ?: return null
		if (!choice.readable || choiceCandidates(manga, space).none { it == choice.copy(sha256 = null) } ||
			fingerprintOfChoice(choice) != fingerprint) return null
		return ExhentaiSessionChoice(manga.id, manga.source.name, space, choice, fingerprint)
	}

	suspend fun resolveExhentaiSessionChoice(
		manga: Manga,
		space: FavouriteSpace,
		selection: ExhentaiSessionChoice,
	): LocalManga? {
		if (!supportsActiveExhentaiChoice(manga) || selection.mangaId != manga.id ||
			selection.sourceName != manga.source.name || selection.space != space ||
			selection.candidate.sha256 != selection.sha256) return null
		val candidate = loadActiveExhentaiTitleCandidates(manga, space)
			.firstOrNull { it.first == selection.candidate.copy(sha256 = null) } ?: return null
		if (candidate.second == null || fingerprintOfChoice(candidate.first) != selection.sha256) return null
		return candidate.second?.let { projectExhentaiChapterIdentity(manga, it) }
	}

	/**
	 * A readable file is not necessarily the online chapter. Preserve the standard download
	 * names (including legacy variants and gallery-ID filenames), but expose a differently
	 * named chapter in the matched gallery folder as a separate local chapter.
	 * This is only a Details/Reader projection; never persist ownership or rewrite source rows.
	 */
	private fun projectExhentaiChapterIdentity(manga: Manga, local: LocalManga): LocalManga {
		if (!supportsActiveExhentaiChoice(manga)) return local
		val remote = manga.chapters?.singleOrNull() ?: return local
		val physical = local.file
		val chapter = local.manga.chapters?.singleOrNull() ?: return local
		val parent = physical.parentFile ?: return local
		val sourceDirectory = parent.parentFile ?: return local
		if (!physical.extension.equals("cbz", ignoreCase = true) ||
			!EhentaiLegacyDownloadResolver.titlesMatch(manga.title, parent.name) ||
			!(sourceDirectory.name.equals("ExHentai (OTHER)", ignoreCase = true) ||
				EhentaiLegacyDownloadResolver.isLegacySourceDirectoryName(sourceDirectory.name))
		) return local // Outer SINGLE_CBZ containers and other layouts keep their existing mapping.
		val name = physical.name
		val stem = physical.nameWithoutExtension
		if (EhentaiLegacyDownloadResolver.isLegacyChapterArtifactName(name) ||
			ExhentaiChapterFileName.explicitGalleryId(name) != null
		) return local
		// Generic Gekko downloads can use a source chapter basename and a numeric collision suffix.
		val base = stem.replace(Regex(" \\([0-9]+\\)$"), "")
		val namedChapter = chapter.copy(url = Uri.fromFile(File(parent, "$base.cbz")).toString())
		if (base.equals("Chapter", ignoreCase = true) ||
			LegacyChapterDownloadCompat.artifactMatchScore(namedChapter, remote) > 0
		) return local

		// Scope the stable chapter ID to the source, manga and physical path. The original source
		// chapter remains present and its personal state must never be migrated to this ID.
		val hash = ("exhentai-local-chapter:${manga.source.name}:${manga.id}:${physical.path}").longHashCode() and Long.MAX_VALUE
		val id = if (hash == 0L || hash == remote.id) (if (remote.id == 1L) 2L else 1L) else hash
		val independent = chapter.copy(id = id, title = stem)
		return local.copy(manga = local.manga.copy(chapters = listOf(independent)))
	}

	private fun supportsActiveExhentaiChoice(manga: Manga) =
		manga.chapters?.size == 1 &&
			(manga.source.name == EhentaiSourceFamily.OFFICIAL_SOURCE_NAME ||
				manga.source.name == GEKKO_EXHENTAI_SOURCE_NAME)

	/** A read association is not ownership; still invalidate it on same-size/mtime replacement. */
	private suspend fun fingerprintOfChoice(choice: ExhentaiReadChoice): String? = withContext(Dispatchers.IO) {
		val file = File(choice.path)
		if (!file.isFile || file.canonicalPath != choice.path ||
			file.length() != choice.length || file.lastModified() != choice.lastModified
		) return@withContext null
		val hash = runCatching {
			val digest = MessageDigest.getInstance("SHA-256")
			file.inputStream().buffered(65536).use { input ->
				val buffer = ByteArray(65536)
				while (true) {
					val count = input.read(buffer)
					if (count < 0) break
					if (count > 0) digest.update(buffer, 0, count)
				}
			}
			Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
		}.getOrNull()
		hash?.takeIf { file.isFile && file.canonicalPath == choice.path &&
			file.length() == choice.length && file.lastModified() == choice.lastModified }
	}

	private suspend fun listActiveExhentaiTitleFiles(
		manga: Manga,
		space: FavouriteSpace,
	): List<File> = withContext(Dispatchers.IO) {
		if (!supportsActiveExhentaiChoice(manga)) return@withContext emptyList()
		val candidates = LinkedHashSet<File>()
		for (root in downloadDestinationStore.activeExhentaiRoots(space)) {
			for (path in EhentaiLegacyDownloadResolver.findReadChoiceCandidates(root, manga.title)) {
				if (!path.isInside(root) ||
					!downloadDestinationStore.isVisibleInActiveExhentaiDestination(space, path)) continue
				candidates += path.canonicalFile
			}
		}
		candidates.toList()
	}

	private suspend fun loadActiveExhentaiTitleCandidates(
		manga: Manga,
		space: FavouriteSpace,
	): List<Pair<ExhentaiReadChoice, LocalManga?>> = withContext(Dispatchers.IO) {
		listActiveExhentaiTitleFiles(manga, space).map { file ->
			val local = ExhentaiDownloadedCopy.readTitleMatchedChapter(manga, file, allowIndexed = true)
			val evidence = ExhentaiChapterOwnership.inspectDestination(file)
			val id = (evidence as? ExhentaiChapterOwnership.Evidence.Owned)?.galleryId
				?: readGalleryIdFromOptionalArchiveIndex(file)
				?: (EhentaiComicInfo.inspect(file) as? EhentaiComicInfo.Evidence.Gallery)?.id
			val choice = ExhentaiReadChoice(file.canonicalPath, id, file.length(), file.lastModified(), local != null)
			choice to local
		}
	}

	private fun readGalleryIdFromOptionalArchiveIndex(file: File): Long? = runCatching {
		ZipFile(file).use { archive ->
			val entry = archive.getEntry("index.json")?.takeIf { it.size in 1L..65536L } ?: return@use null
			val index = JSONObject(archive.getInputStream(entry).bufferedReader().use { it.readText() })
			// index.json.id is a Kotatsu Manga ID, not the ExHentai gallery number.
			// Only the gallery URL establishes a comparable ExHentai gallery ID.
			EhentaiSourceFamily.galleryId(index.optString("public_url"), index.optString("url"))
		}
	}.getOrNull()

	private companion object {
		const val GEKKO_EXHENTAI_SOURCE_NAME = "TSUKI:GEKKOUSHI:gekkoushi:EXHENTAI"
		const val MAX_DETAILS_TITLE_CANDIDATES = 32
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

	/**
	 * Root folders can be nested (e.g. Normal=/library, Private=/library/private).
	 * Only the actual download layout belongs to that space; containment in the
	 * parent directory alone must never classify Private files as Normal.
	 */
	private fun File.isInsideExhentaiLayout(root: File): Boolean {
		val relative = runCatching {
			val parent = root.canonicalFile.toPath()
			val target = canonicalFile.toPath()
			if (!target.startsWith(parent)) return false
			parent.relativize(target)
		}.getOrNull() ?: return false
		val first = relative.getName(0).toString()
		val sourceIndex = if (first == "downloads") 1 else 0
		if (relative.nameCount <= sourceIndex + 1) return false
		val source = relative.getName(sourceIndex).toString()
		return source.equals("ExHentai (OTHER)", ignoreCase = true) ||
			EhentaiLegacyDownloadResolver.isLegacySourceDirectoryName(source)
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
