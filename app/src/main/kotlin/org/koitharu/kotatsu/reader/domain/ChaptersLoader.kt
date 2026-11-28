package org.koitharu.kotatsu.reader.domain

import android.util.LongSparseArray
import androidx.annotation.CheckResult
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.parser.FreshChapterPagesRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.domain.NormalDownloadReadResolver
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.IOException
import org.koitharu.kotatsu.local.domain.model.ExhentaiContentVersion
import org.koitharu.kotatsu.local.library.LocalContentReader
import org.koitharu.kotatsu.local.library.ReaderBackingLeaseContext
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import javax.inject.Inject
import org.koitharu.kotatsu.core.diagnostics.ReaderEvent

private const val PAGES_TRIM_THRESHOLD = 120

@ViewModelScoped
class ChaptersLoader @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val localContentReader: LocalContentReader,
	private val normalReadResolver: NormalDownloadReadResolver,
) {

	@Volatile internal var diagnostics: ReaderDiagnostics? = null

	private val chapters = LongSparseArray<MangaChapter>()
	private val sourceChapters = LongSparseArray<MangaChapter>()
	private val chapterPages = ChapterPages()
	private val mutex = Mutex()
	private val owners = HashMap<Long, Any>()
	private val previewOwners = HashMap<Long, Any>()

	val size: Int
		get() = chapters.size()

    private var initializedMangaId: Long? = null
    private var exhentaiContentVersion: ExhentaiContentVersion? = null
    private var normalReaderManga: Manga? = null
    private var normalReaderSpace: FavouriteSpace? = null
    private var normalReader = false
    private var normalSnapshotSelectors: Map<Long, MangaChapter> = emptyMap()
    // Once selected, a failed retry must not silently choose a different file or remote.
    private val normalSelected = HashMap<Long, MangaChapter>()

	suspend fun init(
        manga: MangaDetails,
        normalProvenance: FavouriteSpace? = null,
        useScopedNormal: Boolean = false,
    ) = mutex.withLock {
        val mangaId = manga.toManga().id
        if (initializedMangaId != mangaId) {
            releaseAllLocked()
            chapterPages.clear()
            initializedMangaId = mangaId
            normalSelected.clear()
            normalReader = false
            normalReaderManga = null
            normalReaderSpace = null
            normalSnapshotSelectors = emptyMap()
        }
        if (useScopedNormal) {
            normalReader = normalReader || manga.local?.normalReaderSelectors != null ||
                normalReadResolver.appliesTo(manga.sourceManga, normalProvenance)
            normalReaderManga = manga.sourceManga.takeIf { normalReader }
            normalReaderSpace = normalProvenance
            normalSnapshotSelectors = manga.local?.normalReaderSelectors.orEmpty()
        }
        updateChapterMetadata(manga)
	}

    private fun updateChapterMetadata(manga: MangaDetails) {
		exhentaiContentVersion = manga.local?.exhentaiContentVersion
		chapters.clear()
		sourceChapters.clear()
		manga.allChapters.forEach {
			chapters.put(it.id, it)
		}
        // A later metadata emission cannot turn an already selected local chapter
        // into an online one. Page leases/snapshots remain as they were.
        normalSelected.forEach { (id, selector) ->
            chapters.put(id, selector.copy(id = id))
        }
		// Keep the remote/source counterpart separately. Downloaded chapters in allChapters deliberately
		// use LocalMangaSource, but if that local artifact is missing/corrupt we can verify lazily at the
		// exact chapter the user opens and fall back to the source instead of probing every file in Details.
		manga.sourceManga.chapters.orEmpty().forEach {
			sourceChapters.put(it.id, it)
		}
	}

	suspend fun loadPrevNextChapter(manga: MangaDetails, currentId: Long, isNext: Boolean): Boolean {
        val chapters = manga.allChapters
        val index = if (isNext) chapters.indexOfFirst { it.id == currentId }
                    else chapters.indexOfLast { it.id == currentId }
        if (index == -1) return false
        val newChapter = chapters.getOrNull(if (isNext) index + 1 else index - 1) ?: return false
        return mutex.withLock {
            if (newChapter.id in chapterPages) return@withLock true
            val token = Any()
            try {
                val newPages = loadChapter(newChapter.id, token)
                if (newPages.isEmpty()) return@withLock false
                if (chapterPages.chaptersSize > 1 && chapterPages.size > PAGES_TRIM_THRESHOLD) {
                    val removedId = (if (isNext) chapterPages.first() else chapterPages.last()).chapterId
                    if (isNext) chapterPages.removeFirst() else chapterPages.removeLast()
                    owners.remove(removedId)?.let { releaseLease(it) }
                }
                val inserted = if (isNext) chapterPages.addLast(newChapter.id, newPages)
                               else chapterPages.addFirst(newChapter.id, newPages)
                if (inserted) owners[newChapter.id] = token
                inserted
            } finally {
                if (owners[newChapter.id] !== token) releaseLease(token)
            }
        }
    }

	@CheckResult
	suspend fun loadSingleChapter(chapterId: Long): Boolean = mutex.withLock {
        val token = Any()
        try {
            val pages = loadChapter(chapterId, token)
            if (pages.isEmpty()) return@withLock false
            chapterPages.clear()
            chapterPages.addLast(chapterId, pages)
            val oldOwners = owners.values.toList()
            owners.clear()
            owners[chapterId] = token
            oldOwners.forEach { releaseLease(it) }
            true
        } finally {
            if (owners[chapterId] !== token) releaseLease(token)
        }
    }

    /**
     * Stage the explicitly reloaded CBZ before replacing the visible chapter/leases.
     * Unlike ordinary local-first loading, a missing/corrupt replacement must never fall back online.
     */
    suspend fun reloadExhentaiVersion(
        manga: MangaDetails,
        verify: suspend () -> Boolean,
    ): Boolean = mutex.withLock {
        val version = manga.local?.exhentaiContentVersion ?: return@withLock false
        if (initializedMangaId != manga.id) return@withLock false
        val chapter = manga.allChapters.singleOrNull { it.id == version.chapterId }
            ?.takeIf { it.source == LocalMangaSource } ?: return@withLock false
        val token = Any()
        try {
            val pages = withContext(ReaderBackingLeaseContext(token)) {
                mangaRepositoryFactory.create(chapter.source).getPages(chapter).mapIndexed { index, page ->
                    val id = version.pageId(chapter.id, page.id)
                    ReaderPage(if (id == page.id) page else page.copy(id = id), index, chapter.id)
                }
            }
            if (pages.isEmpty() || !verify()) return@withLock false
            updateChapterMetadata(manga)
            chapterPages.clear()
            chapterPages.addLast(chapter.id, pages)
            val oldOwners = owners.values.toList() + previewOwners.values.toList()
            owners.clear()
            previewOwners.clear()
            owners[chapter.id] = token
            oldOwners.forEach { releaseLease(it) }
            true
        } finally {
            if (owners[chapter.id] !== token) releaseLease(token)
        }
    }

	/**
	 * Force-resolve one already loaded remote chapter and atomically replace only its page snapshot.
	 * Neighbouring chapters stay intact, so stale metadata recovery cannot silently rebuild Reader state.
	 * Source/network failures are local to this attempt; cancellation still propagates normally.
	 */
	@CheckResult
	suspend fun refreshChapterPages(chapterId: Long): Boolean {
		val sourceChapter = mutex.withLock {
            if (chapterId in normalSelected || chapterId in normalSnapshotSelectors) return@withLock null
            val chapter = checkNotNull(chapters[chapterId]) { "Requested chapter not found" }
            (if (chapter.source == LocalMangaSource) sourceChapters[chapterId] else chapter)
                ?.takeUnless { it.source == LocalMangaSource }
        } ?: return false
		val repository = mangaRepositoryFactory.create(sourceChapter.source)
		val freshRepository = repository as? FreshChapterPagesRepository ?: return false
		val pages = runCatchingCancellable { freshRepository.getFreshPages(sourceChapter) }.getOrNull()
		if (pages.isNullOrEmpty()) return false
		val readerPages = pages.mapIndexed { index, page -> ReaderPage(page, index, chapterId) }
		return mutex.withLock {
            if (chapterId in normalSelected || chapterId in normalSnapshotSelectors) return@withLock false
            val replaced = chapterPages.replace(chapterId, readerPages)
            if (replaced) owners.remove(chapterId)?.let { releaseLease(it) }
            replaced
        }
	}

	fun peekChapter(chapterId: Long): MangaChapter? = chapters[chapterId]

	// All reads below go through synchronized(chapterPages) — the same monitor ChapterPages' own
	// @Synchronized mutators lock on — so a concurrent add/remove can't be observed mid-mutation
	// (e.g. a ConcurrentModificationException from toList()/first()/last() reading the deque while
	// another coroutine is still shifting indices).
	fun hasPages(chapterId: Long): Boolean = synchronized(chapterPages) {
		chapterId in chapterPages
	}

	fun getPages(chapterId: Long): List<MangaPage> = synchronized(chapterPages) {
		chapterPages.subList(chapterId).map { it.toMangaPage() }
	}

	fun getPagesCount(chapterId: Long): Int = synchronized(chapterPages) {
		chapterPages.size(chapterId)
	}

	fun last() = synchronized(chapterPages) { chapterPages.last() }

	fun first() = synchronized(chapterPages) { chapterPages.first() }

	fun snapshot() = synchronized(chapterPages) { chapterPages.toList() }

	/** Preview owners are retained only while their chapter is expanded. */
    suspend fun loadPreviewPages(chapterId: Long): List<ReaderPage> = mutex.withLock {
        previewOwners[chapterId]?.let { return@withLock loadChapter(chapterId, it) }
        val token = Any()
        try {
            val pages = loadChapter(chapterId, token)
            if (pages.isNotEmpty()) previewOwners[chapterId] = token
            pages
        } finally {
            if (previewOwners[chapterId] !== token) releaseLease(token)
        }
    }

    suspend fun releasePreviewsExcept(keep: Set<Long>) = mutex.withLock {
        for (id in previewOwners.keys.toList()) {
            if (id !in keep) previewOwners.remove(id)?.let { releaseLease(it) }
        }
    }

    suspend fun close() = mutex.withLock {
        releaseAllLocked()
        chapterPages.clear()
    }

    private suspend fun releaseAllLocked() {
        val tokens = owners.values.toList() + previewOwners.values.toList()
        owners.clear()
        previewOwners.clear()
        tokens.forEach { releaseLease(it) }
    }

    private suspend fun releaseLease(token: Any) {
        withContext(NonCancellable) { localContentReader.releaseReaderLease(token) }
    }

    private suspend fun loadChapter(chapterId: Long, token: Any): List<ReaderPage> =
        withContext(ReaderBackingLeaseContext(token)) {
            val chapter = checkNotNull(chapters[chapterId]) { "Requested chapter not found" }
            loadScopedPages(chapter).mapIndexed { index, page ->
                val versionedId = exhentaiContentVersion?.pageId(chapterId, page.id) ?: page.id
                ReaderPage(if (versionedId == page.id) page else page.copy(id = versionedId), index, chapterId)
            }
        }

    private suspend fun loadScopedPages(chapter: MangaChapter): List<MangaPage> {
        val manga = normalReaderManga ?: return loadPagesLocalFirst(chapter)
        // No historical fallback, even when the active root changed while this Reader was open.
        val selected = normalReadResolver.resolve(manga, normalReaderSpace)
            ?.normalReaderSelectors?.get(chapter.id)
        val expected = normalSelected[chapter.id] ?: normalSnapshotSelectors[chapter.id]
        if (expected != null && (selected == null || selected.id != expected.id ||
                selected.url != expected.url || selected.source != expected.source)) {
            throw IOException("Selected local chapter changed; reopen Reader")
        }
        if (selected != null) {
            normalSelected[chapter.id] = selected
            chapters.put(chapter.id, selected.copy(id = chapter.id))
            diagnostics?.begin()?.event(ReaderEvent.LOCAL_SELECTED)
            // Use the embedded selector, but keep the logical ID in ReaderPage.
            // Neither an exception nor an empty result can choose the source counterpart.
            val pages = mangaRepositoryFactory.create(selected.source).getPages(selected)
            if (pages.isEmpty()) throw IOException("Selected local chapter has no readable pages")
            return pages
        }
        val remote = sourceChapters[chapter.id] ?: chapter.takeUnless { it.source == LocalMangaSource }
            ?: throw IOException("Selected local chapter is unavailable")
        return loadPagesLocalFirst(remote)
    }

    private suspend fun loadPagesLocalFirst(chapter: MangaChapter): List<MangaPage> {
        val diagnostic = diagnostics?.begin()
        diagnostic?.event(if (chapter.source == LocalMangaSource) ReaderEvent.LOCAL_SELECTED else ReaderEvent.REMOTE_SELECTED)
        val primary = runCatchingCancellable {
            mangaRepositoryFactory.create(chapter.source).getPages(chapter)
        }
        val primaryPages = primary.getOrNull()
        if (!primaryPages.isNullOrEmpty()) return primaryPages
        val sourceChapter = sourceChapters[chapter.id]
        if (chapter.source == LocalMangaSource && sourceChapter != null &&
            sourceChapter.source != LocalMangaSource) {
            diagnostic?.event(if (primary.isFailure) ReaderEvent.FAILED_LOCAL_FALLBACK else ReaderEvent.EMPTY_LOCAL_FALLBACK)
            return mangaRepositoryFactory.create(sourceChapter.source).getPages(sourceChapter)
        }
        return primary.getOrThrow()
    }
}
