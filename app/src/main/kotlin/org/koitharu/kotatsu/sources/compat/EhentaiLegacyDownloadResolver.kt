package org.koitharu.kotatsu.sources.compat

import java.io.File
import java.net.URI
import java.text.Normalizer
import java.util.Locale

/**
 * Read-only compatibility bridge for legacy E-Hentai downloads.
 *
 * Miyorare Global intentionally keeps writing new downloads to `ExHentai (OTHER)`. This resolver
 * only discovers older sidecar-free downloads written below `E-Hentai (ALL)` or `E-Hentai (EN)`.
 * It never moves, renames, copies, deletes, or rewrites user files.
 */
internal object EhentaiLegacyDownloadResolver {

	const val OFFICIAL_SOURCE_NAME = "TSUKI:MIYORARE:miyorare-global:EXHENTAI"

	private val legacySourceDirectoryNames = setOf(
		"E-Hentai (ALL)",
		"E-Hentai (EN)",
	)

	private val invalidFileNameChars = Regex("[\\\\/:*?\"<>]")
	private val repeatedWhitespace = Regex("\\s+")
	private val legacyBracketTag = Regex("\\[[^\\]]*]")
	private val legacyEventTag = Regex("\\([C0-9]*\\)")
	private val duplicateDirectorySuffix = Regex("_[0-9]+$")

	fun isOfficialSource(storedName: String): Boolean = storedName == OFFICIAL_SOURCE_NAME

	/**
	 * Find a single unambiguous legacy manga directory inside [root].
	 *
	 * Both the current `root/downloads/<source>/<title>` layout and the older
	 * `root/<source>/<title>` layout are checked. A candidate must contain the legacy `Chapter.cbz`.
	 * If EN and ALL (or duplicate title directories) both match, no automatic choice is made.
	 */
	fun findUniqueDirectory(root: File, remoteSourceName: String, remoteTitle: String): File? {
		if (!isOfficialSource(remoteSourceName)) return null

		val matches = LinkedHashMap<String, File>()
		val contentRoots = linkedSetOf(
			File(root, "downloads"),
			root,
		)
		for (contentRoot in contentRoots) {
			val sourceDirectories = contentRoot.listFiles()
				?.filter { it.isDirectory && isLegacySourceDirectoryName(it.name) }
				.orEmpty()
			for (sourceDirectory in sourceDirectories) {
				for (candidate in sourceDirectory.listFiles().orEmpty()) {
					if (!candidate.isDirectory || !hasLegacyChapter(candidate)) continue
					if (!titlesMatch(remoteTitle, candidate.name)) continue
					val key = runCatching { candidate.canonicalPath }.getOrDefault(candidate.absolutePath)
					matches.putIfAbsent(key, candidate)
				}
			}
		}
		return matches.values.singleOrNull()
	}

	/**
	 * Weakest automatic reconnect evidence used only after exact id/public-url/canonical-url checks
	 * have failed. The legacy source directory and a unique E-Hentai-normalized title must agree.
	 */
	fun matchesDownloadedCopy(
		remoteSourceName: String,
		remoteTitle: String,
		downloadedTitle: String,
		downloadedUrl: String?,
	): Boolean {
		if (!isOfficialSource(remoteSourceName)) return false
		val file = downloadedUrl.toLocalFileOrNull() ?: return false
		val mangaDirectory = when {
			file.isDirectory -> file
			file.isFile && file.name.equals(LEGACY_CHAPTER_FILE, ignoreCase = true) -> file.parentFile
			// A parser may hand us a path that no longer exists. Never reconnect missing storage.
			else -> null
		} ?: return false
		val sourceDirectory = mangaDirectory.parentFile ?: return false
		if (!isLegacySourceDirectoryName(sourceDirectory.name)) return false
		if (!hasLegacyChapter(mangaDirectory)) return false

		return titlesMatch(remoteTitle, downloadedTitle) || titlesMatch(remoteTitle, mangaDirectory.name)
	}

	internal fun titlesMatch(remoteTitle: String, legacyTitle: String): Boolean {
		val remote = normalizedTitle(remoteTitle)
		if (remote.isEmpty()) return false
		val legacy = normalizedTitle(legacyTitle)
		if (remote == legacy) return true

		// LocalMangaOutput can append `_1`, `_2`, ... when two physical directories would otherwise
		// collide. Accept the suffix only as candidate evidence; ambiguity is handled by the caller.
		val withoutDuplicateSuffix = legacyTitle.replace(duplicateDirectorySuffix, "")
		return remote == normalizedTitle(withoutDuplicateSuffix)
	}

	internal fun isLegacySourceDirectoryName(name: String): Boolean =
		legacySourceDirectoryNames.any { it.equals(name, ignoreCase = true) }

	internal fun normalizedTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
		.replace(legacyBracketTag, " ")
		.replace(legacyEventTag, " ")
		.replace("|", " _ ")
		.replace(invalidFileNameChars, "_")
		.replace(repeatedWhitespace, " ")
		.trim()
		.trimEnd('.')
		.lowercase(Locale.ROOT)

	private fun hasLegacyChapter(directory: File): Boolean =
		directory.listFiles()?.any {
			it.isFile && it.name.equals(LEGACY_CHAPTER_FILE, ignoreCase = true)
		} == true

	private fun String?.toLocalFileOrNull(): File? {
		val raw = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
		val uri = runCatching { URI(raw) }.getOrNull() ?: return null
		if (!uri.scheme.equals("file", ignoreCase = true)) return null
		val path = uri.path?.takeIf(String::isNotEmpty) ?: return null
		return File(path)
	}

	private const val LEGACY_CHAPTER_FILE = "Chapter.cbz"
}
