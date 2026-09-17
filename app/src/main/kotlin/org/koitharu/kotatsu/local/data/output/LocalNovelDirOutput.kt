package org.koitharu.kotatsu.local.data.output

import androidx.core.net.toUri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.internal.closeQuietly
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.isEpubFile
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.io.File
import java.util.zip.ZipFile

/**
 * Stores a downloaded web novel as one EPUB per chapter:
 * Novel title/Chapter title.epub
 *
 * Each EPUB remains a valid standalone book and keeps Miyorare's index inside the archive, so no
 * index.json or cover.jpg sidecar is needed in the novel directory.
 */
class LocalNovelDirOutput(
	rootFile: File,
	private val manga: Manga,
) : LocalMangaOutput(rootFile) {

	private val outputs = HashMap<MangaChapter, LocalNovelEpubOutput>()
	private val fileNames = HashMap<MangaChapter, String>()
	private val mutex = Mutex()

	init {
		// Just like manga CBZ folders, the title directory must exist before the first temporary
		// chapter archive is created. This also lets an existing Mihon-style folder be adopted.
		check(rootFile.exists() || rootFile.mkdirs()) { "Cannot create novel directory $rootFile" }
	}

	override suspend fun mergeWithExisting() = Unit

	override suspend fun addCover(file: File, type: MimeType?) {
		// Deliberately keep the folder clean: every visible file is a chapter EPUB.
	}

	override suspend fun addPage(
		chapter: IndexedValue<MangaChapter>,
		file: File,
		pageNumber: Int,
		type: MimeType?,
	) = mutex.withLock {
		val output = outputs.getOrPut(chapter.value) {
			LocalNovelEpubOutput(File(rootFile, chapterFileName(chapter)), manga)
		}
		// A novel chapter is represented by one HTML page. The embedded metadata may retain the
		// source id for compatibility, but finding/reusing the chapter file never depends on that id.
		output.addPage(chapter, file, pageNumber, type)
	}

	override suspend fun flushChapter(chapter: MangaChapter): Boolean = mutex.withLock {
		val output = outputs.remove(chapter) ?: return@withLock false
		output.finish()
		output.close()
		true
	}

	override suspend fun finish() = mutex.withLock {
		outputs.values.forEach { output ->
			output.finish()
			output.close()
		}
		outputs.clear()
	}

	override suspend fun cleanup() = mutex.withLock {
		outputs.values.forEach { output ->
			output.cleanup()
			output.closeQuietly()
		}
		outputs.clear()
	}

	override fun close() {
		outputs.values.forEach { it.closeQuietly() }
		outputs.clear()
	}

	private fun chapterFileName(chapter: IndexedValue<MangaChapter>): String {
		fileNames[chapter.value]?.let { return it }
		val baseName = readableChapterFileName(
			chapter.value.title?.takeIf { it.isNotBlank() } ?: "Chapter ${chapter.index + 1}",
		).take(MAX_CHAPTER_FILENAME_LENGTH)

		// The deterministic file name is the chapter identity for sidecar-free downloads. Do not open
		// an existing EPUB merely to compare its embedded source/chapter id: offline files copied from
		// another install can have different generated ids while still being the exact chapter we need.
		// Reserving names only within this active download also preserves the normal (1), (2) fallback
		// when two chapters genuinely have the same visible title.
		var i = 0
		while (true) {
			val name = (if (i == 0) baseName else "$baseName ($i)") + ".epub"
			if (name !in fileNames.values) {
				fileNames[chapter.value] = name
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
		private const val MAX_CHAPTER_FILENAME_LENGTH = 120

		/**
		 * Remove only the concrete EPUB that represents each selected chapter.
		 *
		 * A novel directory may contain `Chapter 1.epub` and `Chapter 1 (1).epub`. Both archives can
		 * carry the same remote chapter id, so deleting every archive whose embedded index contains
		 * that id removes both files. Prefer the exact file encoded in the selected local chapter URL;
		 * for a remote id fallback, consume only one matching archive per requested id.
		 */
		suspend fun deleteChapters(root: File, manga: Manga, ids: Set<Long>) {
			val remaining = ids.toMutableSet()
			val rootCanonical = root.canonicalFile

			manga.chapters.orEmpty().forEach { chapter ->
				if (chapter.id !in remaining) return@forEach
				val path = chapter.url.toUri().path ?: return@forEach
				val chapterFile = File(path).canonicalFile
				if (chapterFile.parentFile == rootCanonical && chapterFile.isEpubFile) {
					if (chapterFile.delete()) remaining.remove(chapter.id)
				}
			}

			if (remaining.isEmpty()) return

			val files = root.listFiles { file -> file.isEpubFile }.orEmpty()
			for (id in remaining.toList()) {
				val victim = files.firstOrNull { file ->
					val chapterIds = runCatching {
						ZipFile(file).use { zip ->
							val entry = zip.getEntry(ENTRY_NAME_INDEX) ?: return@use emptySet<Long>()
							val index = MangaIndex(zip.getInputStream(entry).use { it.reader().readText() })
							index.getMangaInfo()?.chapters?.mapTo(HashSet()) { it.id }.orEmpty()
						}
					}.getOrDefault(emptySet())
					id in chapterIds
				}
				if (victim != null && victim.delete()) remaining.remove(id)
			}

			check(remaining.isEmpty()) {
				"${remaining.size} of ${ids.size} chapters was not removed: not found"
			}
		}
	}
}
