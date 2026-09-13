package org.koitharu.kotatsu.sources.compat

import java.io.File
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only compatibility bridge for legacy E-Hentai downloads.
 *
 * Miyorare Global intentionally keeps writing new downloads to `ExHentai (OTHER)`. This resolver
 * only discovers older sidecar-free downloads written below language-specific `E-Hentai (...)`
 * folders. It never moves, renames, copies, deletes, or rewrites user files.
 */
internal object EhentaiLegacyDownloadResolver {

	const val OFFICIAL_SOURCE_NAME = EhentaiSourceFamily.OFFICIAL_SOURCE_NAME

	private val invalidFileNameChars = Regex("[\\\\/:*?\"<>]")
	private val repeatedWhitespace = Regex("\\s+")
	private val legacyBracketTag = Regex("\\[[^\\]]*]")
	private val legacyEventTag = Regex("\\([C0-9]*\\)")
	private val duplicateDirectorySuffix = Regex("_[0-9]+$")
	private val hashedLegacyChapterFile = Regex(
		"^Chapter_[0-9a-f]{6,64}(?: \\([0-9]+\\))?\\.cbz$",
		RegexOption.IGNORE_CASE,
	)
	private val resolvedPathCache = ConcurrentHashMap<String, String>()
	private val sourceDirectoryIndexCache = ConcurrentHashMap<String, SourceDirectoryIndex>()

	fun isOfficialSource(storedName: String): Boolean = EhentaiSourceFamily.isOfficialSource(storedName)

	fun isFamilySource(storedName: String): Boolean = EhentaiSourceFamily.isKnownStoredSource(storedName)

	/**
	 * Find a single unambiguous legacy manga directory inside [root].
	 *
	 * Both the current `root/downloads/<source>/<title>` layout and the older
	 * `root/<source>/<title>` layout are checked. A candidate must contain a recognized legacy chapter
	 * artifact (`Chapter.cbz` or a hashed `Chapter_<hash>.cbz`). If two language buckets (or duplicate
	 * title directories) both match, no automatic choice is made. A successful match is cached by
	 * root + gallery id for the rest of the process lifetime; the normal LocalMangaIndex remains
	 * responsible for persistent aliases after its reconnect scan.
	 *
	 * Large libraries are indexed by directory title for a short bounded window. This avoids walking
	 * every gallery folder for every Details/Downloaded lookup while still refreshing after a source
	 * directory changes or the small TTL expires. Negative gallery matches themselves are never
	 * cached, so a newly downloaded file is not hidden behind a stale miss.
	 */
	fun findUniqueDirectory(
		root: File,
		remoteSourceName: String,
		remoteTitle: String,
		remotePublicUrl: String? = null,
		remoteContentUrl: String? = null,
	): File? {
		if (!isFamilySource(remoteSourceName)) return null

		val galleryId = EhentaiSourceFamily.galleryId(remotePublicUrl, remoteContentUrl)
		val cacheKey = galleryId?.let { "${root.stablePath()}#$it" }
		cacheKey?.let(resolvedPathCache::get)?.let(::File)?.takeIf(::isValidCachedDirectory)?.let { return it }

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
				for (candidate in indexedTitleCandidates(sourceDirectory, remoteTitle)) {
					if (!hasLegacyChapter(candidate)) continue
					matches.putIfAbsent(candidate.stablePath(), candidate)
					// Once two distinct valid copies exist the result is necessarily ambiguous. Stop here
					// instead of walking the rest of the user's E-Hentai library for no possible benefit.
					if (matches.size > 1) return null
				}
			}
		}
		return matches.values.singleOrNull()?.also { match ->
			if (cacheKey != null) resolvedPathCache[cacheKey] = match.stablePath()
		}
	}

	/**
	 * Weakest automatic reconnect evidence used only after exact id/public-url/canonical-gallery/url
	 * checks have failed. The legacy source directory and a unique E-Hentai-normalized title must
	 * agree.
	 */
	fun matchesDownloadedCopy(
		remoteSourceName: String,
		remoteTitle: String,
		downloadedTitle: String,
		downloadedUrl: String?,
	): Boolean {
		if (!isFamilySource(remoteSourceName)) return false
		val file = downloadedUrl.toLocalFileOrNull() ?: return false
		val mangaDirectory = when {
			file.isDirectory -> file
			file.isFile && isLegacyChapterArtifactName(file.name) -> file.parentFile
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
		EhentaiSourceFamily.legacySourceDirectoryNames.any { it.equals(name, ignoreCase = true) }

	/**
	 * Old E-Hentai downloads exist in two known single-gallery forms: the plain `Chapter.cbz` and a
	 * hashed `Chapter_<hex>.cbz` variant. Keep this strict so unrelated CBZ files in a same-named
	 * directory are never silently claimed by ExHentai.
	 */
	internal fun isLegacyChapterArtifactName(name: String): Boolean =
		name.equals(LEGACY_CHAPTER_FILE, ignoreCase = true) || hashedLegacyChapterFile.matches(name)

	internal fun normalizedTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
		.replace(legacyBracketTag, " ")
		.replace(legacyEventTag, " ")
		.replace("|", " _ ")
		.replace(invalidFileNameChars, "_")
		.replace(repeatedWhitespace, " ")
		.trim()
		.trimEnd('.')
		.lowercase(Locale.ROOT)

	private fun indexedTitleCandidates(sourceDirectory: File, remoteTitle: String): List<File> {
		val lookup = normalizedTitle(remoteTitle)
		if (lookup.isEmpty()) return emptyList()

		val key = sourceDirectory.stablePath()
		val now = System.currentTimeMillis()
		val modifiedAt = sourceDirectory.lastModified()
		val cached = sourceDirectoryIndexCache[key]
		val index = if (
			cached != null &&
			cached.modifiedAt == modifiedAt &&
			now < cached.expiresAt
		) {
			cached
		} else {
			buildSourceDirectoryIndex(sourceDirectory, modifiedAt, now).also {
				sourceDirectoryIndexCache[key] = it
			}
		}
		return index.byTitle[lookup].orEmpty()
	}

	private fun buildSourceDirectoryIndex(
		sourceDirectory: File,
		modifiedAt: Long,
		now: Long,
	): SourceDirectoryIndex {
		val byTitle = LinkedHashMap<String, MutableList<File>>()
		for (candidate in sourceDirectory.listFiles().orEmpty()) {
			if (!candidate.isDirectory) continue
			val keys = linkedSetOf(normalizedTitle(candidate.name))
			val withoutDuplicateSuffix = candidate.name.replace(duplicateDirectorySuffix, "")
			keys += normalizedTitle(withoutDuplicateSuffix)
			for (title in keys) {
				if (title.isNotEmpty()) {
					byTitle.getOrPut(title) { ArrayList() }.add(candidate)
				}
			}
		}
		return SourceDirectoryIndex(
			modifiedAt = modifiedAt,
			expiresAt = now + SOURCE_INDEX_TTL_MS,
			byTitle = byTitle,
		)
	}

	private fun isValidCachedDirectory(directory: File): Boolean {
		if (!directory.isDirectory || !hasLegacyChapter(directory)) return false
		val sourceDirectory = directory.parentFile ?: return false
		return isLegacySourceDirectoryName(sourceDirectory.name)
	}

	private fun hasLegacyChapter(directory: File): Boolean =
		directory.listFiles()?.any {
			it.isFile && isLegacyChapterArtifactName(it.name)
		} == true

	private fun File.stablePath(): String =
		runCatching { canonicalPath }.getOrDefault(absolutePath)

	private fun String?.toLocalFileOrNull(): File? {
		val raw = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
		val uri = runCatching { URI(raw) }.getOrNull() ?: return null
		if (!uri.scheme.equals("file", ignoreCase = true)) return null
		val path = uri.path?.takeIf(String::isNotEmpty) ?: return null
		return File(path)
	}

	private data class SourceDirectoryIndex(
		val modifiedAt: Long,
		val expiresAt: Long,
		val byTitle: Map<String, List<File>>,
	)

	private const val LEGACY_CHAPTER_FILE = "Chapter.cbz"
	private const val SOURCE_INDEX_TTL_MS = 10_000L
}
