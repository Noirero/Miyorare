package org.koitharu.kotatsu.local.data

import android.net.Uri
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.security.MessageDigest
import java.util.Locale

/**
 * Compatibility bridge for sidecar-free chapter files created by Mihon/Keiyoushi-style downloads.
 *
 * Miyorare deliberately keeps the same physical `downloads/Source (LANG)/Manga/` layout. Older
 * folders usually have no index.json, so their chapter IDs are local-only and cannot be compared
 * directly with the Tsuki/Miyorare source IDs. Match the physical CBZ by Mihon's chapter naming
 * semantics (scanlator + chapter name) and its six-character URL hash when available, then expose
 * the remote chapter ID while keeping the original file URL. New Miyorare downloads deliberately
 * omit the hash; this bridge accepts both hashed and hashless files. No file is moved, renamed,
 * copied, or rewritten.
 */
internal object LegacyChapterDownloadCompat {

	private const val MAX_MANGA_CHAPTER_FILENAME_LENGTH = 96
	private const val MAX_NOVEL_CHAPTER_FILENAME_LENGTH = 120
	private const val HASH_LENGTH = 6
	private const val HEX_DIGITS = "0123456789abcdef"

	private val parenthesizedHash = Regex("^(.*?)[\\s]*\\(([0-9a-fA-F]{$HASH_LENGTH})\\)$")
	private val underscoreHash = Regex("^(.*)_([A-Za-z0-9]{$HASH_LENGTH})$")

	/**
	 * Re-key chapters from a legacy physical download to the current remote chapter IDs.
	 * Unmatched local-only chapters are retained, so provider removals never make an offline CBZ
	 * disappear from the local representation.
	 */
	fun linkToRemote(remoteManga: Manga, localManga: LocalManga): LocalManga {
		val remoteChapters = remoteManga.chapters.orEmpty()
		val localChapters = localManga.manga.chapters.orEmpty()
		if (remoteChapters.isEmpty() || localChapters.isEmpty()) {
			return if (localManga.manga.id == remoteManga.id) {
				localManga
			} else {
				localManga.copy(manga = localManga.manga.copy(id = remoteManga.id))
			}
		}

		// Sidecar-free libraries can contain hundreds or thousands of chapters. The old matcher scanned
		// the entire remaining local list up to three times for every remote chapter. Build lightweight
		// indexes once and keep candidate order stable; matching priority and ambiguity rules stay the
		// same while the common path becomes linear instead of quadratic.
		val candidates = ArrayList<LocalCandidate>(localChapters.size)
		val byId = HashMap<Long, MutableList<LocalCandidate>>(localChapters.size)
		val byFileName = HashMap<String, MutableList<LocalCandidate>>(localChapters.size)
		val byArtifactBase = HashMap<String, MutableList<LocalCandidate>>(localChapters.size)
		for (chapter in localChapters) {
			val candidate = LocalCandidate(chapter)
			candidates += candidate
			byId.getOrPut(chapter.id) { ArrayList(1) }.add(candidate)
			chapter.localArtifactFileName()?.let { fileName ->
				byFileName.getOrPut(fileName.lowercase(Locale.ROOT)) { ArrayList(1) }.add(candidate)
			}
			chapter.artifactBaseKey()?.let { base ->
				byArtifactBase.getOrPut(base) { ArrayList(1) }.add(candidate)
			}
		}

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

			val localCandidate = takeFirstActive(byId[remoteChapter.id])
				?: takeFirstActive(byFileName[expectedFileName.lowercase(Locale.ROOT)])
				?: if (!isNovel) {
					findBestArtifactMatch(
						candidatesByBase = byArtifactBase,
						remote = remoteChapter,
						expectedBaseName = baseName,
					)
				} else {
					null
				}
				?: continue

			localCandidate.active = false
			linked += remoteChapter.copy(
				url = localCandidate.chapter.url,
				source = LocalMangaSource,
			)
		}

		candidates.asSequence()
			.filter { it.active }
			.mapTo(linked) { it.chapter }
		return localManga.copy(
			manga = localManga.manga.copy(
				id = remoteManga.id,
				chapters = linked,
			),
		)
	}

	/**
	 * Score a physical CBZ against a remote chapter for UI-side fallback matching.
	 * 2 = the six-character suffix equals Mihon's MD5(url) hash, 1 = unique compatible basename,
	 * 0 = not a compatible artifact.
	 */
	fun artifactMatchScore(local: MangaChapter, remote: MangaChapter): Int {
		val expectedBases = generatedDownloadBases(remote)
		if (expectedBases.isEmpty()) return 0
		return artifactMatchScore(local, remote, expectedBases)
	}

	private fun takeFirstActive(candidates: List<LocalCandidate>?): LocalCandidate? {
		return candidates?.firstOrNull { it.active }
	}

	private fun findBestArtifactMatch(
		candidatesByBase: Map<String, List<LocalCandidate>>,
		remote: MangaChapter,
		expectedBaseName: String,
	): LocalCandidate? {
		val expectedBases = generatedDownloadBases(remote).toMutableSet().apply { add(expectedBaseName) }
		val matchingCandidates = LinkedHashSet<LocalCandidate>()
		for (base in expectedBases) {
			candidatesByBase[base.normalizedFileIdentity()]?.forEach { candidate ->
				if (candidate.active) matchingCandidates += candidate
			}
		}
		var bestCandidate: LocalCandidate? = null
		var bestScore = 0
		var bestScoreCount = 0
		for (candidate in matchingCandidates) {
			val score = artifactMatchScore(candidate.chapter, remote, expectedBases)
			when {
				score > bestScore -> {
					bestCandidate = candidate
					bestScore = score
					bestScoreCount = 1
				}
				score > 0 && score == bestScore -> bestScoreCount++
			}
		}
		return if (bestScore > 0 && bestScoreCount == 1) bestCandidate else null
	}

	private fun artifactMatchScore(
		local: MangaChapter,
		remote: MangaChapter,
		expectedBaseNames: Set<String>,
	): Int {
		val artifactName = local.localArtifactFileName() ?: return 0
		if (!artifactName.endsWith(".cbz", ignoreCase = true)) return 0
		val stem = artifactName.substringBeforeLast('.')
		val hashed = parseHashedStem(stem)
		val physicalBase = hashed?.base ?: stem
		val normalizedPhysical = physicalBase.normalizedFileIdentity()
		if (expectedBaseNames.none { it.normalizedFileIdentity() == normalizedPhysical }) return 0

		if (hashed == null) return 1
		val expectedHash = remote.url.mihonUrlHash() ?: return 1
		return if (hashed.token.equals(expectedHash, ignoreCase = true)) 2 else 1
	}

	private fun MangaChapter.artifactBaseKey(): String? {
		val artifactName = localArtifactFileName() ?: return null
		if (!artifactName.endsWith(".cbz", ignoreCase = true)) return null
		val stem = artifactName.substringBeforeLast('.')
		return (parseHashedStem(stem)?.base ?: stem).normalizedFileIdentity()
	}

	private fun parseHashedStem(stem: String): HashedStem? {
		parenthesizedHash.matchEntire(stem)?.let { match ->
			val base = match.groupValues[1].trimEnd()
			if (base.isNotEmpty()) return HashedStem(base, match.groupValues[2])
		}
		underscoreHash.matchEntire(stem)?.let { match ->
			val base = match.groupValues[1].trimEnd()
			if (base.isNotEmpty()) return HashedStem(base, match.groupValues[2])
		}
		return null
	}

	private fun expectedChapterBaseName(chapter: MangaChapter, branchIndex: Int, isNovel: Boolean): String {
		if (isNovel) {
			return readableChapterFileName(
				chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter ${branchIndex + 1}",
			).take(MAX_NOVEL_CHAPTER_FILENAME_LENGTH)
		}
		return (generatedDownloadBase(chapter)
			?: "Chapter ${branchIndex + 1}")
			.let(::readableChapterFileName)
			.take(MAX_MANGA_CHAPTER_FILENAME_LENGTH)
	}

	/**
	 * Current Miyorare naming deliberately follows Mihon's semantic portion only:
	 * `<scanlator>_<chapter name>` when a scanlator exists, without `_md5hash`.
	 */
	private fun generatedDownloadBase(chapter: MangaChapter): String? {
		val rawTitle = chapter.title?.trim().orEmpty()
		val group = chapter.scanlator?.trim().orEmpty()
		val chapterName = rawTitle.ifEmpty { "Chapter" }
		return when {
			group.isNotEmpty() -> "${group}_$chapterName"
			rawTitle.isNotEmpty() -> rawTitle
			else -> null
		}
	}

	/**
	 * Before the Keiyoushi compatibility rule was tightened, Miyorare prefixed the scanlator only
	 * when the chapter title itself was exactly `Chapter`. Keep that historical form readable too.
	 */
	private fun legacyMiyorareDownloadBase(chapter: MangaChapter): String? {
		val rawTitle = chapter.title?.trim().orEmpty()
		val group = chapter.scanlator?.trim().orEmpty()
		return when {
			rawTitle.isEmpty() && group.isNotEmpty() -> "${group}_Chapter"
			rawTitle.equals("Chapter", ignoreCase = true) && group.isNotEmpty() -> "${group}_Chapter"
			rawTitle.isNotEmpty() -> rawTitle
			else -> null
		}
	}

	private fun generatedDownloadBases(chapter: MangaChapter): Set<String> {
		val result = LinkedHashSet<String>(2)
		generatedDownloadBase(chapter)?.let(result::add)
		legacyMiyorareDownloadBase(chapter)?.let(result::add)
		return result
	}

	private fun readableChapterFileName(value: String): String = value
		.replace('|', '_')
		.replace(Regex("[\\/:*?\"<>]"), "_")
		.replace(Regex("\\s+"), " ")
		.replace(Regex("\\s*_\\s*"), " _ ")
		.trim()
		.trimEnd('.', ' ')
		.ifEmpty { "Chapter" }

	private fun String.normalizedFileIdentity(): String = lowercase(Locale.ROOT)
		.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
		.trim()

	private fun MangaChapter.localArtifactFileName(): String? {
		val parsed = Uri.parse(url)
		val encodedName = parsed.fragment
			?.substringAfterLast('/')
			?.takeIf { it.isNotBlank() }
			?: parsed.lastPathSegment
			?: return null
		val name = Uri.decode(encodedName)
		return name.takeIf {
			it.endsWith(".cbz", ignoreCase = true) || it.endsWith(".epub", ignoreCase = true)
		}
	}

	private fun String.mihonUrlHash(): String? {
		if (isBlank()) return null
		val digest = MessageDigest.getInstance("MD5").digest(toByteArray(Charsets.UTF_8))
		return buildString(HASH_LENGTH) {
			for (i in 0 until HASH_LENGTH / 2) {
				val value = digest[i].toInt() and 0xff
				append(HEX_DIGITS[value ushr 4])
				append(HEX_DIGITS[value and 0x0f])
			}
		}
	}

	private class LocalCandidate(
		val chapter: MangaChapter,
	) {
		var active: Boolean = true
	}

	private data class HashedStem(
		val base: String,
		val token: String,
	)
}
