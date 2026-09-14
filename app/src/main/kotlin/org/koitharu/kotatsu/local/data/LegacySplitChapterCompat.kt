package org.koitharu.kotatsu.local.data

import android.net.Uri
import androidx.core.net.toFile
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.sources.compat.EhentaiLegacyDownloadResolver
import java.io.File
import java.util.Locale

/**
 * Read-only bridge for the short-lived Miyorare Global/ExHentai layout that exposed one gallery
 * pagination page as one chapter. Current packs expose the gallery as one canonical chapter.
 *
 * This bridge never merges, moves, renames, copies, or rewrites the old CBZ files. Instead it
 * exposes one virtual local chapter whose pages are read from the original part files in order.
 * Matching is deliberately restricted to the official EXHENTAI source and to an unambiguous,
 * contiguous legacy filename sequence with at least two parts.
 */
internal object LegacySplitChapterCompat {

	private const val COMPOSITE_SCHEME = "miyorare-local-composite"
	private const val COMPOSITE_AUTHORITY = "chapter"

	private val numberedChapter = Regex(
		"^(.*?Chapter\\s+)([0-9]+)\\.cbz$",
		RegexOption.IGNORE_CASE,
	)
	private val duplicateChapter = Regex(
		"^(.*?Chapter)(?: \\(([0-9]+)\\))?\\.cbz$",
		RegexOption.IGNORE_CASE,
	)

	fun linkToRemote(remoteManga: Manga, localManga: LocalManga): LocalManga? {
		if (!EhentaiLegacyDownloadResolver.isOfficialSource(remoteManga.source.name)) return null
		val remoteChapters = remoteManga.chapters.orEmpty()
		val localChapters = localManga.manga.chapters.orEmpty()
		if (remoteChapters.size != 1 || localChapters.size < 2) return null

		val parts = findLegacyParts(localChapters) ?: return null
		val linked = remoteChapters.single().copy(
			url = buildCompositeUrl(parts.map { it.url }),
			source = LocalMangaSource,
		)
		val consumed = parts.toHashSet()
		return localManga.copy(
			manga = localManga.manga.copy(
				id = remoteManga.id,
				chapters = buildList(localChapters.size - parts.size + 1) {
					add(linked)
					localChapters.filterTo(this) { it !in consumed }
				},
			),
		)
	}

	fun componentUrls(url: String): List<String>? {
		val uri = Uri.parse(url)
		if (!uri.scheme.equals(COMPOSITE_SCHEME, ignoreCase = true)) return null
		if (!uri.authority.equals(COMPOSITE_AUTHORITY, ignoreCase = true)) return null
		return uri.getQueryParameters("part")
			.map(String::trim)
			.filter(String::isNotEmpty)
			.takeIf { it.size >= 2 }
	}

	fun componentFiles(url: String): List<File>? = componentUrls(url)?.map { component ->
		val uri = Uri.parse(component)
		if (!uri.scheme.equals("file", ignoreCase = true)) return null
		val base = runCatching { uri.buildUpon().fragment(null).build().toFile() }.getOrNull() ?: return null
		val fragment = uri.fragment?.takeIf { it.isNotBlank() }?.let { Uri.decode(it) }
		if (fragment == null) base else File(base, fragment)
	}

	private fun findLegacyParts(chapters: List<MangaChapter>): List<MangaChapter>? {
		val artifacts = chapters.mapNotNull(::parseArtifact)
		if (artifacts.size < 2) return null
		val groups = artifacts.groupBy { artifact ->
			GroupKey(artifact.container, artifact.kind, artifact.base.lowercase(Locale.ROOT))
		}
		val validGroups = groups.values.mapNotNull { group ->
			if (group.size < 2) return@mapNotNull null
			val byIndex = group.associateBy { it.index }
			if (byIndex.size != group.size) return@mapNotNull null
			if (byIndex.keys != (1..group.size).toSet()) return@mapNotNull null
			(1..group.size).map { index -> checkNotNull(byIndex[index]).chapter }
		}
		if (validGroups.isEmpty()) return null
		val bestSize = validGroups.maxOf { it.size }
		return validGroups.filter { it.size == bestSize }.singleOrNull()
	}

	private fun parseArtifact(chapter: MangaChapter): LegacyArtifact? {
		val uri = Uri.parse(chapter.url)
		val encodedFragment = uri.fragment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
		val name = when {
			encodedFragment != null -> Uri.decode(encodedFragment)
			else -> Uri.decode(uri.lastPathSegment.orEmpty())
		}
		if (!name.endsWith(".cbz", ignoreCase = true)) return null

		val container = if (encodedFragment != null) {
			uri.buildUpon().fragment(null).build().toString()
		} else {
			val local = runCatching { uri.toFile() }.getOrNull() ?: return null
			local.parentFile?.absolutePath ?: return null
		}

		numberedChapter.matchEntire(name)?.let { match ->
			val index = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
			return LegacyArtifact(chapter, container, LegacyKind.NUMBERED, match.groupValues[1], index)
		}
		duplicateChapter.matchEntire(name)?.let { match ->
			val duplicateIndex = match.groupValues[2].toIntOrNull()
			val index = if (duplicateIndex == null) 1 else duplicateIndex + 1
			return LegacyArtifact(chapter, container, LegacyKind.DUPLICATE_SUFFIX, match.groupValues[1], index)
		}
		return null
	}

	private fun buildCompositeUrl(parts: List<String>): String = Uri.Builder()
		.scheme(COMPOSITE_SCHEME)
		.authority(COMPOSITE_AUTHORITY)
		.apply { parts.forEach { appendQueryParameter("part", it) } }
		.build()
		.toString()

	private data class LegacyArtifact(
		val chapter: MangaChapter,
		val container: String,
		val kind: LegacyKind,
		val base: String,
		val index: Int,
	)

	private data class GroupKey(
		val container: String,
		val kind: LegacyKind,
		val base: String,
	)

	private enum class LegacyKind {
		NUMBERED,
		DUPLICATE_SUFFIX,
	}
}
