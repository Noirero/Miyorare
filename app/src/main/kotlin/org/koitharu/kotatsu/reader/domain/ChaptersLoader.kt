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
import org.koitharu.kotatsu.local.library.LocalContentReader
import org.koitharu.kotatsu.local.library.ReaderBackingLeaseContext
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import javax.inject.Inject

private const val PAGES_TRIM_THRESHOLD = 120

@ViewModelScoped
class ChaptersLoader @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val localContentReader: LocalContentReader,
) {

	private val chapters = LongSparseArray<MangaChapter>()
	private val sourceChapters = LongSparseArray<MangaChapter>()
	private val chapterPages = ChapterPages()
	private val mutex = Mutex()
	private val owners = HashMap<Long, Any>()
	private val previewOwners = HashMap<Long, Any>()

	val size: Int
		get() = chapters.size()

	suspend fun init(manga: MangaDetails) = mutex.withLock {
        releaseAllLocked()
        chapterPages.clear()
		chapters.clear()
		sourceChapters.clear()
		manga.allChapters.forEach {
			chapters.put(it.id, it)
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
	 * Force-resolve one already loaded remote chapter and atomically replace only its page snapshot.
	 * Neighbouring chapters stay intact, so stale metadata recovery cannot silently rebuild Reader state.
	 * Source/network failures are local to this attempt; cancellation still propagates normally.
	 */
	@CheckResult
	suspend fun refreshChapterPages(chapterId: Long): Boolean {
		val chapter = checkNotNull(chapters[chapterId]) { "Requested chapter not found" }
		val sourceChapter = (if (chapter.source == LocalMangaSource) sourceChapters[chapterId] else chapter)
			?: return false
		if (sourceChapter.source == LocalMangaSource) return false
		val repository = mangaRepositoryFactory.create(sourceChapter.source)
		val freshRepository = repository as? FreshChapterPagesRepository ?: return false
		val pages = runCatchingCancellable { freshRepository.getFreshPages(sourceChapter) }.getOrNull()
		if (pages.isNullOrEmpty()) return false
		val readerPages = pages.mapIndexed { index, page -> ReaderPage(page, index, chapterId) }
		return mutex.withLock {
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
        previewOwners[chapterId]?.let { return@withLock loadChapterWithoutNewLease(chapterId) }
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

    private suspend fun loadChapterWithoutNewLease(chapterId: Long): List<ReaderPage> {
        // Preview reloads still use the existing URL set through the repository's stable backing.
        return loadChapter(chapterId, previewOwners.getValue(chapterId))
    }

    private suspend fun loadChapter(chapterId: Long, token: Any): List<ReaderPage> =
        withContext(ReaderBackingLeaseContext(token)) {
            val chapter = checkNotNull(chapters[chapterId]) { "Requested chapter not found" }
            loadPagesLocalFirst(chapter).mapIndexed { index, page -> ReaderPage(page, index, chapterId) }
        }

    private suspend fun loadPagesLocalFirst(chapter: MangaChapter): List<MangaPage> {
        val primary = runCatchingCancellable {
            mangaRepositoryFactory.create(chapter.source).getPages(chapter)
        }
        val primaryPages = primary.getOrNull()
        if (!primaryPages.isNullOrEmpty()) return primaryPages
        val sourceChapter = sourceChapters[chapter.id]
        if (chapter.source == LocalMangaSource && sourceChapter != null &&
            sourceChapter.source != LocalMangaSource) {
            return mangaRepositoryFactory.create(sourceChapter.source).getPages(sourceChapter)
        }
        return primary.getOrThrow()
    }
}
