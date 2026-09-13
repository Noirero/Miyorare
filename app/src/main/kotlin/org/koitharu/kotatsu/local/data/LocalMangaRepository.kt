package org.koitharu.kotatsu.local.data

import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.AlphanumComparator
import org.koitharu.kotatsu.core.util.ext.deleteAwait
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.takeIfWriteable
import org.koitharu.kotatsu.core.util.ext.withChildren
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.data.output.LocalMangaUtil
import org.koitharu.kotatsu.local.domain.MangaLock
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sources.compat.DownloadReconnectPlanner
import org.koitharu.kotatsu.sources.compat.DownloadReconnectSelection
import org.koitharu.kotatsu.sources.compat.DownloadedContentMatch
import java.io.File
import java.util.Collections
import java.util.EnumSet
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_PARALLELISM = 4
private const val LOCAL_PAGE_SIZE = 100
private const val FILE_SCAN_QUEUE_CAPACITY = MAX_PARALLELISM * 2
private const val FILENAME_SKIP = ".notamanga"
private const val MAX_MANGA_CHAPTER_FILENAME_LENGTH = 96
private const val MAX_NOVEL_CHAPTER_FILENAME_LENGTH = 120

@Singleton
@OptIn(InternalParsersApi::class)
class LocalMangaRepository @Inject constructor(
	private val storageManager: LocalStorageManager,
	private val localMangaIndex: LocalMangaIndex,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
	private val downloadReconnectPlanner: DownloadReconnectPlanner,
	private val settings: AppSettings,
	private val lock: MangaLock,
) : MangaRepository {

	@Volatile
	private var listQueryCache: LocalListQueryCache? = null
	private val listQueryCacheLock = Any()

	override val source = LocalMangaSource

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override val sortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.RELEVANCE,
	)

	override var defaultSortOrder: SortOrder
		get() = settings.localListOrder
		set(value) {
			settings.localListOrder = value
		}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = localMangaIndex.getAvailableTags(
			skipNsfw = settings.isNsfwContentDisabled,
		).mapToSet { MangaTag(title = it, key = it, source = source) },
		availableContentRating = if (!settings.isNsfwContentDisabled) {
			EnumSet.of(ContentRating.SAFE, ContentRating.ADULT)
		} else {
			emptySet()
		},
	)

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
		val sourceSnapshot = localMangaIndex.getAll()
		val hideNsfw = settings.isNsfwContentDisabled
		val filterKey = filter.toLocalFilterKey()
		val cached = synchronized(listQueryCacheLock) {
			listQueryCache?.takeIf { cache ->
				cache.sourceSnapshot === sourceSnapshot &&
					cache.hideNsfw == hideNsfw &&
					cache.order == order &&
					cache.filter == filterKey
			}?.result
		}
		val list = cached ?: buildFilteredList(sourceSnapshot, hideNsfw, order, filter).also { result ->
			synchronized(listQueryCacheLock) {
				listQueryCache = LocalListQueryCache(sourceSnapshot, hideNsfw, order, filterKey, result)
			}
		}
		val start = offset.coerceAtLeast(0)
		if (start >= list.size) return emptyList()
		val end = minOf(start + LOCAL_PAGE_SIZE, list.size)
		return list.subList(start, end).unwrap()
	}

	private fun buildFilteredList(
		sourceSnapshot: List<LocalManga>,
		hideNsfw: Boolean,
		order: SortOrder?,
		filter: MangaListFilter?,
	): List<LocalManga> {
		val list = sourceSnapshot.toMutableList()
		if (hideNsfw) list.removeAll { it.manga.isNsfw() }
		if (filter != null) {
			val query = filter.query
			if (!query.isNullOrEmpty()) list.retainAll { x -> x.isMatchesQuery(query) }
			if (filter.tags.isNotEmpty()) list.retainAll { x -> x.containsTags(filter.tags.mapToSet { it.title }) }
			if (filter.tagsExclude.isNotEmpty()) list.removeAll { x -> x.containsAnyTag(filter.tagsExclude.mapToSet { it.title }) }
			filter.contentRating.singleOrNull()?.let { contentRating ->
				val isNsfw = contentRating == ContentRating.ADULT
				list.retainAll { x -> x.manga.isNsfw() == isNsfw }
			}
			if (!query.isNullOrEmpty() && order == SortOrder.RELEVANCE) {
				list.sortBy { x -> x.manga.title.levenshteinDistance(query) }
			}
		}
		when (order) {
			SortOrder.ALPHABETICAL -> list.sortWith(compareBy(AlphanumComparator()) { x -> x.manga.title })
			SortOrder.RATING -> list.sortByDescending { x -> x.manga.rating }
			SortOrder.NEWEST, SortOrder.UPDATED -> list.sortWith(compareBy({ x -> -x.createdAt }, { x -> x.manga.id }))
			else -> Unit
		}
		return list
	}

	override suspend fun getDetails(manga: Manga): Manga = when {
		!manga.isLocal -> requireNotNull(findSavedManga(manga, withDetails = true)?.manga) { "Manga is not local or saved" }
		else -> LocalMangaParser(manga.url.toUri()).getManga(withDetails = true).manga
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> =
		LocalMangaParser(chapter.url.toUri()).getPages(chapter)

	suspend fun delete(manga: Manga): Boolean {
		val file = manga.url.toUri().toFile()
		val result = file.deleteAwait()
		if (result) {
			localMangaIndex.delete(manga.id)
			localStorageChanges.emit(null)
		}
		return result
	}

	suspend fun deleteChapters(manga: Manga, ids: Set<Long>) = lock.withLock(manga) {
		val subject = if (manga.isLocal) manga else checkNotNull(findSavedManga(manga, withDetails = false)) {
			"Manga is not stored on local storage"
		}.manga
		LocalMangaUtil(subject).deleteChapters(ids)
		val updated = getDetails(subject)
		if (updated.chapters.isNullOrEmpty()) {
			if (!delete(updated)) {
				localMangaIndex.delete(updated.id)
				localStorageChanges.emit(null)
			}
		} else {
			localStorageChanges.emit(LocalManga(updated))
		}
	}

	suspend fun getRemoteManga(localManga: Manga): Manga? = runCatchingCancellable {
		LocalMangaParser(localManga.url.toUri()).getMangaInfo()?.takeUnless { it.isLocal }
	}.onFailure { it.printStackTraceDebug() }.getOrNull()

	suspend fun findSavedMangaIndexed(remoteManga: Manga): LocalManga? = runCatchingCancellable {
		findSavedMangaAtExpectedPath(remoteManga, withDetails = true, preferFastIndexedDirectory = true)?.let {
			return@runCatchingCancellable it
		}
		localMangaIndex.get(remoteManga.id, withDetails = true)?.let {
			return@runCatchingCancellable linkDownloadedChapters(remoteManga, it)
		}
		null
	}.onFailure { it.printStackTraceDebug() }.getOrNull()

	suspend fun findSavedManga(remoteManga: Manga, withDetails: Boolean = true): LocalManga? = runCatchingCancellable {
		findSavedMangaAtExpectedPath(remoteManga, withDetails)?.let {
			return@runCatchingCancellable it
		}
		localMangaIndex.get(remoteManga.id, withDetails)?.let { cached ->
			return@runCatchingCancellable linkDownloadedChapters(remoteManga, cached)
		}
		LocalMangaParser.find(storageManager.getReadableDirs(), remoteManga)?.let {
			return@runCatchingCancellable linkDownloadedChapters(remoteManga, it.getManga(withDetails))
		}
		findSavedMangaByScanning(remoteManga)?.getManga(withDetails)?.let {
			linkDownloadedChapters(remoteManga, it)
		}
	}.onSuccess { x: LocalManga? ->
		if (x != null) localMangaIndex.put(x)
	}.onFailure { it.printStackTraceDebug() }.getOrNull()

	private suspend fun findSavedMangaByScanning(remoteManga: Manga): LocalMangaParser? = channelFlow {
		val queue = Channel<File>(FILE_SCAN_QUEUE_CAPACITY)
		val reconnectCandidates = Collections.synchronizedList(ArrayList<ReconnectScanCandidate>())
		val dispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLELISM)
		val workers = List(MAX_PARALLELISM) {
			launch(dispatcher) {
				for (file in queue) {
					val mangaInput = LocalMangaParser.getOrNull(file) ?: continue
					val mangaInfo = runCatchingCancellable {
						mangaInput.getMangaInfo()
					}.onFailure { it.printStackTraceDebug() }.getOrNull() ?: continue
					val evidence = runCatchingCancellable {
						downloadReconnectPlanner.evidence(remoteManga, mangaInfo)
					}.onFailure { it.printStackTraceDebug() }.getOrDefault(DownloadedContentMatch.NONE)
					if (evidence == DownloadedContentMatch.EXACT_ID) {
						send(mangaInput)
						return@launch
					}
					if (evidence != DownloadedContentMatch.NONE) {
						reconnectCandidates += ReconnectScanCandidate(
							parser = mangaInput,
							evidence = evidence,
							localMangaId = mangaInfo.id,
							file = file,
						)
					}
				}
			}
		}
		try {
			for (file in getAllFiles()) queue.send(file)
		} finally {
			queue.close()
		}
		workers.forEach { it.join() }
		val candidates = synchronized(reconnectCandidates) { reconnectCandidates.toList() }
		val selection = DownloadReconnectPlanner.select(candidates.map { it.evidence })
		if (selection is DownloadReconnectSelection.Automatic) {
			val candidate = candidates[selection.index]
			localMangaIndex.registerDownloadAlias(
				remoteMangaId = remoteManga.id,
				localMangaId = candidate.localMangaId,
				file = candidate.file,
			)
			send(candidate.parser)
		}
	}.firstOrNull()

	override suspend fun getPageUrl(page: MangaPage) = page.url

	override suspend fun getRelated(seed: Manga): List<Manga> = emptyList()

	/**
	 * An explicit root is authoritative. This prevents a user-selected Private destination from
	 * being silently replaced by an older Normal copy discovered in another configured root.
	 * Calls without an explicit root retain the legacy behaviour and can reconnect existing files.
	 */
	suspend fun getOutputDir(manga: Manga, fallback: File?): File? {
		if (fallback != null) return fallback.takeIfWriteable()
		val defaultDir = storageManager.getDefaultWriteableDir()
		if (defaultDir != null && hasExistingOutput(defaultDir, manga)) return defaultDir
		return storageManager.getWriteableDirs().firstOrNull { hasExistingOutput(it, manga) } ?: defaultDir
	}

	private suspend fun hasExistingOutput(root: File, manga: Manga): Boolean {
		val output = LocalMangaOutput.get(root, manga) ?: return false
		output.close()
		return true
	}

	suspend fun cleanup(): Boolean {
		if (lock.isNotEmpty()) return false
		val dirs = storageManager.getWriteableDirs()
		runInterruptible(Dispatchers.IO) {
			val filter = TempFileFilter()
			dirs.forEach { dir ->
				dir.withChildren { children ->
					children.forEach { child -> if (filter.accept(child)) child.deleteRecursively() }
				}
			}
		}
		return true
	}

	fun getRawListAsFlow(): Flow<LocalManga> = channelFlow {
		val queue = Channel<File>(FILE_SCAN_QUEUE_CAPACITY)
		val dispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLELISM)
		repeat(MAX_PARALLELISM) {
			launch(dispatcher) {
				for (file in queue) {
					runCatchingCancellable { LocalMangaParser.getOrNull(file)?.getManga(withDetails = false) }
						.onFailure { e -> e.printStackTraceDebug() }
						.onSuccess { m -> if (m != null) send(m) }
				}
			}
		}
		try {
			for (file in getAllFiles()) queue.send(file)
		} finally {
			queue.close()
		}
	}

	private suspend fun findSavedMangaAtExpectedPath(
		remoteManga: Manga,
		withDetails: Boolean,
		preferFastIndexedDirectory: Boolean = false,
	): LocalManga? {
		for (dir in storageManager.getReadableDirs()) {
			val output = LocalMangaOutput.get(dir, remoteManga) ?: continue
			try {
				if (preferFastIndexedDirectory && withDetails) {
					buildFastIndexedDirectoryCopy(remoteManga, output.rootFile)?.let { return it }
				}
				LocalMangaParser.getOrNull(output.rootFile)?.getManga(withDetails)?.let {
					return linkDownloadedChapters(remoteManga, it)
				}
			} finally {
				output.close()
			}
		}
		return null
	}

	/**
	 * Directory downloads keep a tiny index.json that already contains the exact artifact filename for
	 * every chapter. Reading that JSON avoids opening every CBZ/EPUB during Details/Reader first-load,
	 * while preserving old filename variants and locally retained chapters exactly by stored id.
	 */
	private fun buildFastIndexedDirectoryCopy(remoteManga: Manga, root: File): LocalManga? {
		if (!root.isDirectory) return null
		val indexPath = File(root, LocalMangaOutput.ENTRY_NAME_INDEX)
		val index = MangaIndex.read(FileSystem.SYSTEM, indexPath.toOkioPath()) ?: return null
		val indexedInfo = index.getMangaInfo()?.takeIf { it.id == remoteManga.id } ?: return null
		val linked = ArrayList<MangaChapter>()
		val remoteIds = HashSet<Long>()
		for (chapter in remoteManga.chapters.orEmpty()) {
			remoteIds += chapter.id
			val fileName = index.getChapterFileName(chapter.id) ?: continue
			val artifact = File(root, fileName)
			if (!artifact.isFile) continue
			linked += chapter.copy(url = artifact.toUri().toString(), source = LocalMangaSource)
		}
		// Preserve downloaded chapters no longer present in the refreshed source list. This matches the
		// full parser's behaviour and prevents a fast path from making an offline-only chapter vanish.
		for (chapter in indexedInfo.chapters.orEmpty()) {
			if (chapter.id in remoteIds) continue
			val fileName = index.getChapterFileName(chapter.id) ?: continue
			val artifact = File(root, fileName)
			if (!artifact.isFile) continue
			linked += chapter.copy(url = artifact.toUri().toString(), source = LocalMangaSource)
		}
		if (linked.isEmpty()) return null
		val rootUri = root.toUri().toString()
		val coverUrl = index.getCoverEntry()
			?.let { File(root, it) }
			?.takeIf { it.isFile }
			?.toUri()
			?.toString()
			?: indexedInfo.coverUrl
		return LocalManga(
			manga = indexedInfo.copy(
				url = rootUri,
				publicUrl = rootUri,
				source = LocalMangaSource,
				chapters = linked,
				coverUrl = coverUrl,
				largeCoverUrl = null,
			),
			file = root,
		)
	}

	private fun linkDownloadedChapters(remoteManga: Manga, localManga: LocalManga): LocalManga {
		val remoteChapters = remoteManga.chapters.orEmpty()
		val localChapters = localManga.manga.chapters.orEmpty()
		if (remoteChapters.isEmpty() || localChapters.isEmpty()) {
			return if (localManga.manga.id == remoteManga.id) {
				localManga
			} else {
				localManga.copy(manga = localManga.manga.copy(id = remoteManga.id))
			}
		}
		val remainingLocal = localChapters.toMutableList()
		val linked = ArrayList<MangaChapter>(localChapters.size)
		val branchIndexes = HashMap<String?, Int>()
		val duplicateNames = HashMap<String, Int>()
		val isNovel = remoteManga.source.isNovelSource
		for (remoteChapter in remoteChapters) {
			val branchIndex = branchIndexes[remoteChapter.branch] ?: 0
			branchIndexes[remoteChapter.branch] = branchIndex + 1
			val baseName = expectedChapterBaseName(remoteChapter, branchIndex, isNovel)
			val duplicateKey = baseName.lowercase(Locale.ROOT)
			val duplicateIndex = duplicateNames[duplicateKey] ?: 0
			duplicateNames[duplicateKey] = duplicateIndex + 1
			val expectedFileName = buildString {
				append(baseName)
				if (duplicateIndex > 0) append(" ($duplicateIndex)")
				append(if (isNovel) ".epub" else ".cbz")
			}
			var localIndex = remainingLocal.indexOfFirst { it.id == remoteChapter.id }
			if (localIndex < 0) {
				localIndex = remainingLocal.indexOfFirst { localChapter ->
					localChapter.localArtifactFileName()?.equals(expectedFileName, ignoreCase = true) == true
				}
			}
			if (localIndex < 0) continue
			val localChapter = remainingLocal.removeAt(localIndex)
			linked += remoteChapter.copy(url = localChapter.url, source = LocalMangaSource)
		}
		linked.addAll(remainingLocal)
		return localManga.copy(
			manga = localManga.manga.copy(
				id = remoteManga.id,
				chapters = linked,
			),
		)
	}

	private fun expectedChapterBaseName(chapter: MangaChapter, branchIndex: Int, isNovel: Boolean): String {
		if (isNovel) {
			return readableChapterFileName(
				chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter ${branchIndex + 1}",
			).take(MAX_NOVEL_CHAPTER_FILENAME_LENGTH)
		}
		val rawTitle = chapter.title?.takeIf { it.isNotEmpty() }
		val scanlator = chapter.scanlator?.takeIf { it.isNotEmpty() }?.let(::readableChapterFileName)
		return when {
			rawTitle == null -> scanlator?.let { "${it}_Chapter" } ?: "Chapter ${branchIndex + 1}"
			rawTitle.trim().equals("Chapter", ignoreCase = true) && scanlator != null -> "${scanlator}_Chapter"
			else -> readableChapterFileName(rawTitle)
		}.take(MAX_MANGA_CHAPTER_FILENAME_LENGTH)
	}

	private fun readableChapterFileName(value: String): String {
		return value
			.replace('|', '_')
			.replace(Regex("[\\/:*?\"<>]"), "_")
			.replace(Regex("\\s+"), " ")
			.replace(Regex("\\s*_\\s*"), " _ ")
			.trim()
			.trimEnd('.', ' ')
			.ifEmpty { "Chapter" }
	}

	private fun MangaChapter.localArtifactFileName(): String? {
		val parsed = url.toUri()
		parsed.fragment?.substringAfterLast('/')?.takeIf(::isChapterArtifactName)?.let { return it }
		val rawName = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
		return Uri.decode(rawName).takeIf(::isChapterArtifactName)
	}

	private fun isChapterArtifactName(name: String): Boolean {
		return name.endsWith(".cbz", ignoreCase = true) || name.endsWith(".epub", ignoreCase = true)
	}

	private suspend fun getAllFiles() = storageManager.getReadableDirs()
		.asSequence()
		.flatMap { dir ->
			dir.withChildren { children ->
				val result = ArrayList<File>()
				children.filterNot { it.isHidden || it.shouldSkip() }.forEach { child ->
					if (child.isDirectory && child.name == LocalMangaOutput.DOWNLOADS_DIR_NAME) scanDownloadRoot(child, result)
					else scanLegacyEntry(child, result)
				}
				result
			}
		}

	private fun scanDownloadRoot(downloads: File, result: MutableList<File>) {
		downloads.withChildren { children ->
			children.filterNot { it.isHidden || it.shouldSkip() }.forEach { child ->
				when {
					child.isDirectory && child.name == LocalMangaOutput.NOVEL_DIR_NAME -> scanNovelRoot(child, result)
					child.isDirectory && child.isDownloadSourceDirectory() -> child.withChildren { mangaDirs ->
						mangaDirs.filterNot { it.isHidden || it.shouldSkip() }.forEach(result::add)
					}
					else -> result.add(child)
				}
			}
		}
	}

	private fun scanNovelRoot(novelRoot: File, result: MutableList<File>) {
		novelRoot.withChildren { children ->
			children.filterNot { it.isHidden || it.shouldSkip() }.forEach { child ->
				if (child.isDirectory && child.isDownloadSourceDirectory()) {
					child.withChildren { novels -> novels.filterNot { it.isHidden || it.shouldSkip() }.forEach(result::add) }
				} else result.add(child)
			}
		}
	}

	private fun scanLegacyEntry(child: File, result: MutableList<File>) {
		when {
			child.isDirectory && child.name == LocalMangaOutput.NOVEL_DIR_NAME -> scanNovelRoot(child, result)
			child.isDirectory && File(child, LocalMangaOutput.SOURCE_DIR_MARKER).isFile -> child.withChildren { sourceChildren ->
				sourceChildren.filterNot { it.isHidden || it.shouldSkip() }.forEach(result::add)
			}
			else -> result.add(child)
		}
	}

	private fun File.isDownloadSourceDirectory(): Boolean {
		if (File(this, LocalMangaOutput.SOURCE_DIR_MARKER).isFile) return true
		return withChildren { titles ->
			val sample = titles.filterNot { it.isHidden || it.shouldSkip() }.take(LEGACY_SOURCE_PROBE_LIMIT).toList()
			if (sample.any { it.isFile && it.isSupportedDownloadArtifact() }) return@withChildren false
			sample.any { title ->
				title.isDirectory && title.withChildren { artifacts -> artifacts.any { it.isFile && it.isSupportedDownloadArtifact() } }
			} || sample.isNotEmpty() && sample.all { it.isDirectory }
		}
	}

	private fun File.isSupportedDownloadArtifact(): Boolean = when (extension.lowercase(Locale.ROOT)) {
		"cbz", "zip", "epub", "pdf" -> true
		else -> false
	}

	private fun Collection<LocalManga>.unwrap(): List<Manga> = map { it.manga }

	private fun MangaListFilter?.toLocalFilterKey(): LocalFilterKey = LocalFilterKey(
		query = this?.query,
		tags = this?.tags.orEmpty().mapToSet { it.title },
		tagsExclude = this?.tagsExclude.orEmpty().mapToSet { it.title },
		contentRating = this?.contentRating?.singleOrNull(),
	)

	private fun File.shouldSkip(): Boolean = isDirectory && File(this, FILENAME_SKIP).exists()

	private data class ReconnectScanCandidate(
		val parser: LocalMangaParser,
		val evidence: DownloadedContentMatch,
		val localMangaId: Long,
		val file: File,
	)

	private data class LocalFilterKey(
		val query: String?,
		val tags: Set<String>,
		val tagsExclude: Set<String>,
		val contentRating: ContentRating?,
	)

	private data class LocalListQueryCache(
		val sourceSnapshot: List<LocalManga>,
		val hideNsfw: Boolean,
		val order: SortOrder?,
		val filter: LocalFilterKey,
		val result: List<LocalManga>,
	)

	private companion object {
		const val LEGACY_SOURCE_PROBE_LIMIT = 8
	}
}
