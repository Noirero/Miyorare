package org.koitharu.kotatsu.local.data

import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sources.compat.EhentaiLegacyDownloadResolver
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import org.koitharu.kotatsu.sources.compat.ExhentaiChapterFileName
import org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership
import java.io.File

/** Resolve one physical artifact, not ownership of a title directory. Never modifies user files. */
internal object ExhentaiDownloadedCopy {
	data class Match(val local: LocalManga, val verified: Boolean)

	fun isOfficial(manga: Manga): Boolean = EhentaiSourceFamily.isOfficialSource(manga.source.name)

	private fun Manga.galleryId(): Long? = EhentaiSourceFamily.galleryId(publicUrl, url)?.takeIf { it > 0 }

	private fun originMatches(remote: Manga, origin: Manga): Boolean {
		if (!EhentaiSourceFamily.isKnownStoredSource(origin.source.name)) return false
		val id = remote.galleryId()
		val storedId = origin.galleryId()
		return if (id != null && storedId != null) id == storedId else remote.id == origin.id
	}

	suspend fun find(remote: Manga, path: File, withDetails: Boolean): Match? = withContext(Dispatchers.IO) {
		if (!isOfficial(remote)) return@withContext null
		runCatchingCancellable {
			if (path.isDirectory) findInDirectory(remote, path.canonicalFile, withDetails)
			else findArchive(remote, path.canonicalFile, withDetails)
		}.getOrNull()
	}

	private suspend fun findArchive(remote: Manga, file: File, withDetails: Boolean): Match? {
		return when (val evidence = ExhentaiChapterOwnership.inspect(file)) {
			is ExhentaiChapterOwnership.Evidence.Owned ->
				if (evidence.galleryId == remote.galleryId()) chapterCopy(remote, file, withDetails, true) else null
			ExhentaiChapterOwnership.Evidence.Unusable -> null
			ExhentaiChapterOwnership.Evidence.Unmarked -> {
				val parser = LocalMangaParser.getOrNull(file) ?: return null
				val origin = parser.getMangaInfo()
				if (origin != null) {
					if (!originMatches(remote, origin)) return null
					val local = parser.getManga(withDetails)
					if (withDetails && local.manga.chapters.isNullOrEmpty()) return null
					Match(LegacyChapterDownloadCompat.linkToRemote(remote, local), true)
				} else if (EhentaiLegacyDownloadResolver.isLegacyChapterArtifactName(file.name)) {
					chapterCopy(remote, file, withDetails, false)
				} else null
			}
		}
	}

	private suspend fun findInDirectory(remote: Manga, dir: File, withDetails: Boolean): Match? {
		val entries = dir.listFiles { f -> f.isFile && f.extension.equals("cbz", ignoreCase = true) } ?: return null
		val candidates = entries.filter { it.canonicalFile == it }.associateWith(ExhentaiChapterOwnership::inspect)
		val galleryId = remote.galleryId()
		if (galleryId != null) {
			val exact = candidates.filterValues { it == ExhentaiChapterOwnership.Evidence.Owned(galleryId) }.keys
			val named = exact.filter { it.name.equals(ExhentaiChapterFileName.withGalleryId(galleryId), true) }
			(named.singleOrNull() ?: exact.singleOrNull())?.let { return chapterCopy(remote, it, withDetails, true) }
			if (exact.isNotEmpty()) return null
		}
		val unknownFiles = candidates.filterValues { it == ExhentaiChapterOwnership.Evidence.Unmarked }.keys
		if (unknownFiles.isEmpty()) return null
		val parser = LocalMangaParser.getOrNull(dir) ?: return null
		val origin = parser.getMangaInfo()
		if (origin != null && !originMatches(remote, origin)) return null

		// Preserve the existing legacy split and hash rules, but never hand them a marked foreign
		// file or a corrupt CBZ. A title-based fallback must not become a deletion authorization.
		val physical = LocalMangaParser(dir, ignoreDirectoryIndex = true).getManga(withDetails = true)
		val chapters = physical.manga.chapters.orEmpty().filter { chapter ->
			chapterArtifact(chapter)?.canonicalFile in unknownFiles
		}
		if (chapters.isEmpty()) return null
		val snapshot = physical.copy(manga = physical.manga.copy(chapters = chapters))
		val remoteChapter = remote.chapters.orEmpty().singleOrNull()
		if (remoteChapter != null) {
			LegacySplitChapterCompat.linkToRemote(remote, snapshot)?.let { split ->
				return Match(split.withRequestedDetails(withDetails), false)
			}
			val linked = LegacyChapterDownloadCompat.linkToRemote(remote, snapshot)
			val chapter = linked.manga.chapters.orEmpty().firstOrNull { it.id == remoteChapter.id }
				?: chapters.singleOrNull()?.takeIf {
					chapterArtifact(it)?.name?.let(EhentaiLegacyDownloadResolver::isLegacyChapterArtifactName) == true
				}
				?: return null
			val cbz = chapterArtifact(chapter) ?: return null
			return chapterCopy(remote, cbz, withDetails, false)
		}
		val onlyFile = unknownFiles.singleOrNull()?.takeIf {
			EhentaiLegacyDownloadResolver.isLegacyChapterArtifactName(it.name)
		} ?: return null
		return chapterCopy(remote, onlyFile, withDetails, false)
	}

	private suspend fun chapterCopy(remote: Manga, file: File, withDetails: Boolean, verified: Boolean): Match? {
		val local = LocalMangaParser.getOrNull(file)?.getManga(withDetails = false) ?: return null
		val uri = file.toUri().toString()
		val chapters = if (withDetails) remote.chapters?.singleOrNull()?.let {
			listOf(it.copy(source = LocalMangaSource, url = uri))
		} else null
		return Match(local.copy(manga = local.manga.copy(title = remote.title, chapters = chapters)), verified)
	}

	private fun LocalManga.withRequestedDetails(withDetails: Boolean): LocalManga =
		if (withDetails) this else copy(manga = manga.copy(chapters = null))

	private fun chapterArtifact(chapter: MangaChapter): File? = runCatching {
		val uri = Uri.parse(chapter.url)
		val file = uri.buildUpon().fragment(null).build().toFile()
		if (file.isDirectory) uri.fragment?.let { File(file, it) } else file
	}.getOrNull()
}
