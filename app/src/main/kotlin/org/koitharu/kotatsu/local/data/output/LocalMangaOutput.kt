package org.koitharu.kotatsu.local.data.output

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Closeable
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.text.Normalizer
import java.util.Locale

sealed class LocalMangaOutput(
	val rootFile: File,
) : Closeable {

	abstract suspend fun mergeWithExisting()

	abstract suspend fun addCover(file: File, type: MimeType?)

	abstract suspend fun addPage(chapter: IndexedValue<MangaChapter>, file: File, pageNumber: Int, type: MimeType?)

	abstract suspend fun flushChapter(chapter: MangaChapter): Boolean

	abstract suspend fun finish()

	abstract suspend fun cleanup()

	protected suspend fun replaceRootFile(temp: File) = withContext(Dispatchers.IO) {
		val backup = File(rootFile.path + ".bak" + SUFFIX_TMP)
		backup.delete()
		val hasBackup = rootFile.exists() && rootFile.renameTo(backup)
		if (!hasBackup) {
			rootFile.delete()
		}
		if (temp.renameTo(rootFile)) {
			backup.delete()
		} else {
			if (hasBackup) {
				backup.renameTo(rootFile)
			}
			error("Cannot move $temp to $rootFile")
		}
	}

	companion object {

		const val ENTRY_NAME_INDEX = "index.json"
		const val SOURCE_DIR_MARKER = ".dropsauce-source"
		const val DOWNLOADS_DIR_NAME = "downloads"
		const val NOVEL_DIR_NAME = "00.Novel"
		const val SUFFIX_TMP = ".tmp"
		private val mutex = Mutex()
		private val invalidFileNameChars = Regex("[\\/:*?\"<>]")
		private val repeatedWhitespace = Regex("\\s+")

		suspend fun getOrCreate(
			root: File,
			manga: Manga,
			format: DownloadFormat,
		): LocalMangaOutput = withContext(Dispatchers.IO) {
			val targetFormat = if (format == DownloadFormat.AUTOMATIC) {
				DownloadFormat.MULTIPLE_CBZ
			} else {
				format
			}
			val isNovel = manga.source.isNovelSource
			val downloadsRoot = File(root, DOWNLOADS_DIR_NAME)
			val contentRoot = if (isNovel) File(downloadsRoot, NOVEL_DIR_NAME) else downloadsRoot
			val sourceRoot = getSourceDirectory(contentRoot, manga)

			getImpl(sourceRoot, manga, onlyIfExists = true, format = targetFormat, prepareForDownload = true)
				?: findLegacy(root, manga, targetFormat, prepareForDownload = true)
				?: run {
					check(sourceRoot.exists() || sourceRoot.mkdirs()) {
						"Cannot create source directory $sourceRoot"
					}
					if (sourceRoot != contentRoot) {
						File(sourceRoot, SOURCE_DIR_MARKER).createNewFile()
					}
					checkNotNull(getImpl(sourceRoot, manga, onlyIfExists = false, format = targetFormat, prepareForDownload = true))
				}
		}

		suspend fun get(root: File, manga: Manga): LocalMangaOutput? = withContext(Dispatchers.IO) {
			val isNovel = manga.source.isNovelSource
			val downloadsRoot = File(root, DOWNLOADS_DIR_NAME)
			val contentRoot = if (isNovel) File(downloadsRoot, NOVEL_DIR_NAME) else downloadsRoot
			val sourceRoot = getSourceDirectory(contentRoot, manga)
			getImpl(
				sourceRoot,
				manga,
				onlyIfExists = true,
				format = DownloadFormat.AUTOMATIC,
				prepareForDownload = false,
			) ?: findLegacy(root, manga, DownloadFormat.AUTOMATIC, prepareForDownload = false)
		}

		private suspend fun findLegacy(
			root: File,
			manga: Manga,
			format: DownloadFormat,
			prepareForDownload: Boolean,
		): LocalMangaOutput? {
			val isNovel = manga.source.isNovelSource
			val downloadsRoot = File(root, DOWNLOADS_DIR_NAME)
			val contentRoot = if (isNovel) File(downloadsRoot, NOVEL_DIR_NAME) else downloadsRoot
			val legacyContentRoot = if (isNovel) File(root, NOVEL_DIR_NAME) else root
			val candidates = LinkedHashSet<File>()

			// v0.9.500091-style source folder beneath the current downloads root, without a
			// language suffix. Existing files stay where they are and remain writable.
			candidates += getLegacySourceDirectories(contentRoot, manga)
			getPreviousLanguageSourceDirectory(contentRoot, manga)?.let(candidates::add)

			// Layouts used before the dedicated `downloads` root was introduced. Try both the
			// language-aware name and the original source-only name.
			candidates += getSourceDirectory(legacyContentRoot, manga)
			candidates += getLegacySourceDirectories(legacyContentRoot, manga)
			getPreviousLanguageSourceDirectory(legacyContentRoot, manga)?.let(candidates::add)
			candidates += legacyContentRoot
			if (legacyContentRoot != root) candidates += root

			for (candidate in candidates) {
				getImpl(
					candidate,
					manga,
					onlyIfExists = true,
					format = format,
					prepareForDownload = prepareForDownload,
				)?.let { return it }
			}
			return null
		}

		/** Returns a readable source directory, e.g. `Doujindesu (ID)` or `KDT Novels (JP)`. */
		fun getSourceDirectory(root: File, manga: Manga): File {
			val source = manga.source.unwrap()
			if (source !is MihonMangaSource) {
				return root
			}
			return File(root, mihonSourceDirectoryName(source.displayName, source.language).toReadableFileName())
		}

		internal fun stableSourceLanguageCode(language: String): String = language
			.trim()
			.replace('_', '-')
			.uppercase(Locale.ROOT)
			.ifEmpty { "OTHER" }

		internal fun mihonSourceDirectoryName(displayName: String, language: String): String {
			return "${mihonSourceBaseName(displayName, language)} (${stableSourceLanguageCode(language)})"
		}

		internal fun mihonSourceBaseName(displayName: String, language: String): String {
			val rawLanguage = language.trim()
			if (rawLanguage.isEmpty()) return displayName.trim()
			val escaped = rawLanguage.split('-', '_').joinToString("[-_]") { Regex.escape(it) }
			val suffix = Regex("(?:[_\\s]+$escaped|\\s*\\($escaped\\))[_\\s]*$", RegexOption.IGNORE_CASE)
			return displayName.replace(suffix, "").trim(' ', '_').ifEmpty { displayName.trim() }
		}

		private fun getLegacySourceDirectories(root: File, manga: Manga): List<File> {
			val source = manga.source.unwrap() as? MihonMangaSource ?: return listOf(root)
			val rawName = source.displayName.toReadableFileName()
			val baseName = mihonSourceBaseName(source.displayName, source.language).toReadableFileName()
			return linkedSetOf(rawName, baseName).map { File(root, it) }
		}

		private fun getPreviousLanguageSourceDirectory(root: File, manga: Manga): File? {
			val source = manga.source.unwrap() as? MihonMangaSource ?: return null
			val language = source.language.trim().uppercase(Locale.ROOT)
			val displayName = source.displayName
				.replace(Regex("[_\\s]+${Regex.escape(language)}[_\\s]*$", RegexOption.IGNORE_CASE), "")
				.trim(' ', '_')
			val name = if (language.isNotEmpty()) "$displayName ($language)" else displayName
			return File(root, name.toReadableFileName())
		}

		private suspend fun getImpl(
			root: File,
			manga: Manga,
			onlyIfExists: Boolean,
			format: DownloadFormat,
			prepareForDownload: Boolean,
		): LocalMangaOutput? {
			mutex.withLock {
				var i = 0
				val baseName = manga.title.toReadableFileName()
				val isNovel = manga.source.isNovelSource
				while (true) {
					val fileName = if (i == 0) baseName else baseName + "_$i"
					val dir = File(root, fileName)
					val zip = File(root, "$fileName.cbz")
					val epub = File(root, "$fileName.epub")
					i++
					if (isNovel) {
						return when {
							dir.isDirectory -> {
								if (canWriteTo(dir, manga) || canAdoptDirectory(dir)) {
									LocalNovelDirOutput(dir, manga)
								} else {
									continue
								}
							}

							epub.isFile -> if (canWriteTo(epub, manga)) {
								LocalNovelEpubOutput(epub, manga)
							} else {
								continue
							}

							onlyIfExists -> null
							else -> LocalNovelDirOutput(dir, manga)
						}
					}
					return when {
						dir.isDirectory -> {
							if (canWriteTo(dir, manga) || canAdoptDirectory(dir)) {
								LocalMangaDirOutput(dir, manga, prepareForDownload)
							} else {
								continue
							}
						}

						zip.isFile -> if (canWriteTo(zip, manga)) {
							LocalMangaZipOutput(zip, manga)
						} else {
							continue
						}

						!onlyIfExists -> when (format) {
							DownloadFormat.AUTOMATIC -> null
							DownloadFormat.SINGLE_CBZ -> LocalMangaZipOutput(zip, manga)
							DownloadFormat.MULTIPLE_CBZ -> LocalMangaDirOutput(dir, manga, prepareForDownload)
						}

						else -> null
					}
				}
			}
		}

		private fun String.toReadableFileName(): String {
			return Normalizer.normalize(this, Normalizer.Form.NFC)
				.replace("|", " _ ")
				.replace(invalidFileNameChars, "_")
				.replace(repeatedWhitespace, " ")
				.trim()
				.trimEnd('.')
				.ifEmpty { "Untitled" }
		}

		private fun canAdoptDirectory(dir: File): Boolean {
			return !File(dir, ENTRY_NAME_INDEX).exists()
		}

		private suspend fun canWriteTo(file: File, manga: Manga): Boolean {
			val info = runCatchingCancellable {
				LocalMangaParser(file).getMangaInfo()
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrNull() ?: return false
			return info.id == manga.id
		}
	}
}
