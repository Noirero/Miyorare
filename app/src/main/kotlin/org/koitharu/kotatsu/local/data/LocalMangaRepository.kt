package org.koitharu.kotatsu.local.data

import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
import java.io.File
import java.util.EnumSet
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_PARALLELISM = 4
private const val LOCAL_PAGE_SIZE = 100
private const val FILENAME_SKIP = ".notamanga"
private const val MAX_MANGA_CHAPTER_FILENAME_LENGTH = 96
private const val MAX_NOVEL_CHAPTER_FILENAME_LENGTH = 120

@Singleton
@OptIn(InternalParsersApi::class)
class LocalMangaRepository @Inject constructor(
	private val storageManager: LocalStorageManager,
	private val localMangaIndex: LocalMangaIndex,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
	private val settings: AppSettings,
	private val lock: MangaLock,
) : MangaRepository {

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
		val list = localMangaIndex.getAll().toMutableList()
		if (settings.isNsfwContentDisabled) list.removeAll { it.manga.isNsfw() }
		if (filter != null) {
			val query = filter.query
			if (!query.isNullOrEmpty()) list.retainAll { x -> x.isMatchesQuery(query) }
			if (filter.tags.isNotEmpty()) list.retainAll { x -> x.containsTags(filter.tags.mapToSet { it.title }) }
			if (filter.tagsExclude.isNotEmpty()) list.removeAll { x -> x.containsAnyTag(filter.tagsExclude.mapToSet { it.title }) }
			filter.contentRating.singleOrNull()?.let { contentRating ->
				val isNsfw = contentRating == ContentRating.ADULT
				list.retainAll { x -> x.manga.isNsfw() == isNsfw }
			}
			if (!query.isNullOrEmpty() && order == SortOrder.RELEVANCE) list.sortBy { x -> x.manga.title.levenshteinDistance(query) }
		}
		when (order) {
			SortOrder.ALPHABETICAL -> list.sortWith(compareBy(AlphanumComparator()) { x -> x.manga.title })
			SortOrder.RATING -> list.sortByDescending { x -> x.manga.rating }
			SortOrder.NEWEST, SortOrder.UPDATED -> list.sortWith(compareBy({ x -> -x.createdAt }, { x -> x.manga.id }))
			else -> Unit
		}
		val start = offset.coerceAtLeast(0)
		if (start >= list.size) {
			return emptyList()
		}
		val end = minOf(start + LOCAL_PAGE_SIZE, list.size)
		return list.subList(start, end).unwrap()
	}

	override suspend fun getDetails(manga: Manga): Manga = when {
		!manga.isLocal -> requireNotNull(findSavedManga(manga, withDetails = true)?.manga) { "Manga is not local or saved" }
		else -> LocalMangaParser(manga.url.toUri()).getManga(withDetails = true).manga
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = LocalMangaParser(chapter.url.toUri()).getPages(chapter)

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
			// local_index represents a title that has downloadable content. Keeping an index/cover-only
			// container after its final chapter is removed would leave the virtual Downloaded category
			// with a false-positive entry and allow it to reappear after a storage rescan.
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

	/**
	 * Resolve the saved copy before the first details-screen emission. Normal DropSauce downloads are
	 * found through the index. Sidecar-free Mihon-style folders do not carry the remote manga id, so
	 * also probe the exact download path. This is still cheap (a few deterministic file checks) and
	 * makes an already-present CBZ/EPUB usable immediately while offline instead of waiting for a
	 * broad storage scan or a network refresh.
	 */
	suspend fun findSavedMangaIndexed(remoteManga: Manga): LocalManga? = runCatchingCancellable {
		localMangaIndex.get(remoteManga.id, withDetails = true)?.let {
			return@runCatchingCancellable linkDownloadedChapters(remoteManga, it)
		}
		findSavedMangaAtExpectedPath(remoteManga, withDetails = true)
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
		val files = getAllFiles()
		return channelFlow {
			for (file in files) {
				launch {
					val mangaInput = LocalMangaParser.getOrNull(file)
					runCatchingCancellable {
						val mangaInfo = mangaInput?.getMangaInfo()
						if (mangaInfo != null && mangaInfo.id == remoteManga.id) send(mangaInput)
					}.onFailure { it.printStackTraceDebug() }
				}
			}
		}.firstOrNull()?.getManga(withDetails)?.let {
			linkDownloadedChapters(remoteManga, it)
		}
	}.onSuccess { x: LocalManga? ->
		if (x != null) localMangaIndex.put(x)
	}.onFailure { it.printStackTraceDebug() }.getOrNull()

	override suspend fun getPageUrl(page: MangaPage) = page.url

	override suspend fun getRelated(seed: Manga): List<Manga> = emptyList()

	suspend fun getOutputDir(manga: Manga, fallback: File?): File? {
		val defaultDir = fallback?.takeIfWriteable() ?: storageManager.getDefaultWriteableDir()
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
		val files = getAllFiles()
		val dispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLELISM)
		for (file in files) {
			launch(dispatcher) {
				runCatchingCancellable { LocalMangaParser.getOrNull(file)?.getManga(withDetails = false) }
					.onFailure { e -> e.printStackTraceDebug() }
					.onSuccess { m -> if (m != null) send(m) }
			}
		}
	}

	/**
	 * Check the deterministic current download path first:
	 * downloads/<source>/<title>/Chapter.cbz for manga and
	 * downloads/00.Novel/<source>/<title>/Chapter.epub for novels.
	 */
	private suspend fun findSavedMangaAtExpectedPath(remoteManga: Manga, withDetails: Boolean): LocalManga? {
		for (dir in storageManager.getReadableDirs()) {
			val output = LocalMangaOutput.get(dir, remoteManga) ?: continue
			try {
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
	 * A sidecar-free CBZ/EPUB folder can be parsed completely offline, but its generated local chapter
	 * ids differ from the source ids. Re-link only files whose concrete artifact name is exactly the
	 * name DropSauce would use for that remote chapter. The resulting chapter keeps the local URL and
	 * LOCAL source (so the reader never requests the network) while using the remote id/metadata (so
	 * download state, history and chapter selection remain attached to the source chapter).
	 */
	private fun linkDownloadedChapters(remoteManga: Manga, localManga: LocalManga): LocalManga {
		val remoteChapters = remoteManga.chapters.orEmpty()
		val localChapters = localManga.manga.chapters.orEmpty()
		if (remoteChapters.isEmpty() || localChapters.isEmpty()) return localManga

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
			linked += remoteChapter.copy(
				url = localChapter.url,
				source = LocalMangaSource,
			)
		}

		// Preserve genuinely local/imported chapters that do not correspond to a source chapter.
		linked.addAll(remainingLocal)
		return localManga.copy(manga = localManga.manga.copy(chapters = linked))
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
		parsed.fragment
			?.substringAfterLast('/')
			?.takeIf(::isChapterArtifactName)
			?.let { return it }

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
					if (child.isDirectory && child.name == LocalMangaOutput.DOWNLOADS_DIR_NAME) {
						scanDownloadRoot(child, result)
					} else {
						scanLegacyEntry(child, result)
					}
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
					child.withChildren { novels ->
						novels.filterNot { it.isHidden || it.shouldSkip() }.forEach(result::add)
					}
				} else {
					// Legacy `00.Novel/<Title>/Chapter.epub` has no source level. Keep the title
					// directory intact instead of treating each chapter artifact as a separate novel.
					result.add(child)
				}
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

	/**
	 * Source folders created before [LocalMangaOutput.SOURCE_DIR_MARKER] are detected from their
	 * children. This keeps `downloads/SourceName/Title/Chapter.cbz` visible without mistaking a
	 * normal title folder (whose chapter archives are direct children) for a source folder.
	 */
	private fun File.isDownloadSourceDirectory(): Boolean {
		if (File(this, LocalMangaOutput.SOURCE_DIR_MARKER).isFile) return true
		return withChildren { titles ->
			val sample = titles.filterNot { it.isHidden || it.shouldSkip() }
				.take(LEGACY_SOURCE_PROBE_LIMIT)
				.toList()
			if (sample.any { it.isFile && it.isSupportedDownloadArtifact() }) return@withChildren false
			sample.any { title ->
				title.isDirectory && title.withChildren { artifacts ->
					artifacts.any { it.isFile && it.isSupportedDownloadArtifact() }
				}
			} || sample.isNotEmpty() && sample.all { it.isDirectory }
		}
	}

	private fun File.isSupportedDownloadArtifact(): Boolean = when (extension.lowercase(Locale.ROOT)) {
		"cbz", "zip", "epub", "pdf" -> true
		else -> false
	}

	private fun Collection<LocalManga>.unwrap(): List<Manga> = map { it.manga }

	private fun File.shouldSkip(): Boolean = isDirectory && File(this, FILENAME_SKIP).exists()

	private companion object {
		const val LEGACY_SOURCE_PROBE_LIMIT = 8
	}
}
