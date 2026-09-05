package org.koitharu.kotatsu.local.data.output

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.internal.closeQuietly
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.deleteAwait
import org.koitharu.kotatsu.core.zip.ZipOutput
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import java.io.File

class LocalMangaDirOutput(
	rootFile: File,
	manga: Manga,
	prepareForDownload: Boolean = false,
) : LocalMangaOutput(rootFile) {

	private val chaptersOutput = HashMap<MangaChapter, ZipOutput>()
	// Keep metadata only in memory while downloading. Mihon-style chapter folders should contain CBZ files only.
	private val index = MangaIndex(null)
	private val mutex = Mutex()

	init {
		// The readable Mihon-style manga title directory may not exist yet for a new download.
		// Create it before ZipOutput tries to create Chapter.cbz.tmp inside it.
		check(rootFile.exists() || rootFile.mkdirs()) { "Cannot create manga directory $rootFile" }
		if (prepareForDownload) {
			// Old DropSauce metadata/cover files can make a pre-existing Mihon-style folder take the
			// indexed path. Remove them only when a download is actually about to write; read-only
			// lookup must never mutate a user's legacy folder.
			File(rootFile, ENTRY_NAME_INDEX).delete()
			rootFile.listFiles()?.forEach { file ->
				if (file.isFile && file.name.substringBeforeLast('.', file.name).equals("cover", ignoreCase = true)) {
					file.delete()
				}
			}
		}
		if (!manga.isLocal) {
			index.setMangaInfo(manga)
		}
	}

	override suspend fun mergeWithExisting() = Unit

	override suspend fun addCover(file: File, type: MimeType?) {
		// Intentionally do not copy cover files into the manga download directory.
	}

	override suspend fun addPage(chapter: IndexedValue<MangaChapter>, file: File, pageNumber: Int, type: MimeType?) =
		mutex.withLock {
			val output = chaptersOutput.getOrPut(chapter.value) {
				ZipOutput(File(rootFile, chapterFileName(chapter) + SUFFIX_TMP))
			}
			val name = buildString {
				append(pageNumber + 1)
				org.koitharu.kotatsu.core.util.MimeTypes.getExtension(type)?.let { ext ->
					append('.')
					append(ext)
				}
			}
			runInterruptible(Dispatchers.IO) {
				output.put(name, file)
			}
			index.addChapter(chapter, chapterFileName(chapter))
		}

	override suspend fun flushChapter(chapter: MangaChapter): Boolean = mutex.withLock {
		val output = chaptersOutput.remove(chapter) ?: return@withLock false
		output.flushAndFinish()
		true
	}

	override suspend fun finish() = mutex.withLock {
		for (output in chaptersOutput.values) {
			output.flushAndFinish()
		}
		chaptersOutput.clear()
	}

	override suspend fun cleanup() = mutex.withLock {
		for (output in chaptersOutput.values) {
			output.file.deleteAwait()
		}
	}

	override fun close() {
		for (output in chaptersOutput.values) {
			output.closeQuietly()
		}
	}

	suspend fun deleteChapters(ids: Set<Long>) = mutex.withLock {
		val chapters = checkNotNull(
			(index.getMangaInfo() ?: LocalMangaParser(rootFile).getManga(withDetails = true).manga).chapters,
		) {
			"No chapters found"
		}.withIndex()
		val victimsIds = ids.toMutableSet()
		for (chapter in chapters) {
			if (!victimsIds.remove(chapter.value.id)) {
				continue
			}
			val chapterFile = index.getChapterFileName(chapter.value.id)?.let {
				File(rootFile, it)
			} ?: chapter.value.url.toUri().fragment
				?.takeIf { it.isNotBlank() }
				?.let { File(rootFile, it) }
				?: chapter.value.url.toUri().toFile()

			// A sidecar-free manga chapter URL is `file:///Manga#Chapter.cbz`. Calling toFile() on
			// that URL returns the manga directory and used to delete every downloaded chapter.
			// Only allow the exact child artifact that belongs to the selected chapter.
			val rootCanonical = rootFile.canonicalFile
			val chapterCanonical = chapterFile.canonicalFile
			check(chapterCanonical.parentFile == rootCanonical) {
				"Refusing to delete non-chapter path: $chapterCanonical"
			}
			chapterCanonical.deleteAwait()
			index.removeChapter(chapter.value.id)
		}
		check(victimsIds.isEmpty()) {
			"${victimsIds.size} of ${ids.size} chapters was not removed: not found"
		}
	}

	private suspend fun ZipOutput.flushAndFinish() = runInterruptible(Dispatchers.IO) {
		val e: Throwable? = try {
			finish()
			null
		} catch (e: Throwable) {
			e
		} finally {
			close()
		}
		if (e == null) {
			val resFile = File(file.absolutePath.removeSuffix(SUFFIX_TMP))
			file.renameTo(resFile)
		} else {
			file.delete()
			throw e
		}
	}

	private fun chapterFileName(chapter: IndexedValue<MangaChapter>): String {
		index.getChapterFileName(chapter.value.id)?.let {
			return it
		}
		val rawTitle = chapter.value.title?.nullIfEmpty()
		val scanlator = chapter.value.scanlator?.nullIfEmpty()?.let(::readableChapterFileName)
		val baseName = when {
			rawTitle == null -> scanlator?.let { "${it}_Chapter" } ?: "Chapter ${chapter.index + 1}"
			rawTitle.trim().equals("Chapter", ignoreCase = true) && scanlator != null -> "${scanlator}_Chapter"
			else -> readableChapterFileName(rawTitle)
		}.take(MAX_CHAPTER_FILENAME_LENGTH)
		var i = 0
		while (true) {
			val name = (if (i == 0) baseName else "$baseName ($i)") + ".cbz"
			if (!File(rootFile, name).exists()) {
				return name
			}
			i++
		}
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

	companion object {
		private const val MAX_CHAPTER_FILENAME_LENGTH = 96
	}
}
