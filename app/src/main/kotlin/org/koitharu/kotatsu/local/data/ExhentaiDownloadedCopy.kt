package org.koitharu.kotatsu.local.data

import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.isImage
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sources.compat.EhentaiComicInfo
import org.koitharu.kotatsu.sources.compat.EhentaiLegacyDownloadResolver
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import org.koitharu.kotatsu.sources.compat.ExhentaiChapterFileName
import org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Resolve one physical artifact, not ownership of a title directory. Never modifies user files. */
internal object ExhentaiDownloadedCopy {
	data class Match(
		val local: LocalManga,
		val verified: Boolean,
		val receipt: ExhentaiChapterOwnership.Receipt? = null,
		val physicalMangaId: Long = local.manga.id,
	)

	fun isOfficial(manga: Manga): Boolean = EhentaiSourceFamily.isOfficialSource(manga.source.name)

	/** Flat chapter CBZs use their current root pages; indexed copies keep their own resolver. */
	suspend fun fromDestination(remote: Manga, file: File): LocalManga? = runCatchingCancellable {
		if (remote.chapters?.size != 1) return@runCatchingCancellable null
		val origin = LocalMangaParser.getOrNull(file)?.getMangaInfo()
		if (origin != null && !originMatches(remote, origin)) return@runCatchingCancellable null
		if (origin == null) {
			val claimedId = (EhentaiComicInfo.inspect(file) as? EhentaiComicInfo.Evidence.Gallery)?.id
			if (claimedId == null || claimedId != remote.galleryId()) return@runCatchingCancellable null
		}
		val hasPages = ZipFile(file).use { zip ->
			zip.getEntry("index.json") == null && zip.entries().asSequence().any { entry ->
				!entry.isDirectory && '/' !in entry.name &&
					MimeTypes.getMimeTypeFromExtension(entry.name)?.isImage == true
			}
		}
		if (hasPages) chapterCopy(remote, file, withDetails = true, verified = false)?.local else null
	}.getOrNull()

	private fun Manga.galleryId(): Long? = EhentaiSourceFamily.galleryId(publicUrl, url)?.takeIf { it > 0 }

	private fun originMatches(remote: Manga, origin: Manga): Boolean {
		if (!EhentaiSourceFamily.isKnownStoredSource(origin.source.name)) return false
		val id = remote.galleryId()
		val storedId = origin.galleryId()
		return if (id != null && storedId != null) id == storedId else remote.id == origin.id
	}

	suspend fun find(
		remote: Manga,
		path: File,
		withDetails: Boolean,
		space: FavouriteSpace? = null,
		request: UUID? = null,
		trustedOwnedPath: Boolean = false,
	): Match? = withContext(Dispatchers.IO) {
		if (!isOfficial(remote)) return@withContext null
		runCatchingCancellable {
			if (path.isDirectory) findInDirectory(remote, path.canonicalFile, withDetails, space, request, trustedOwnedPath)
			else findArchive(remote, path.canonicalFile, withDetails, space, request, trustedOwnedPath)
		}.getOrNull()
	}

	private fun ExhentaiChapterOwnership.Evidence.Owned.matches(
		galleryId: Long?,
		space: FavouriteSpace?,
		request: UUID?,
	): Boolean = this.galleryId == galleryId &&
		(space == null || receipt == null || receipt.space == space) &&
		(request == null || receipt?.request == request)

	private suspend fun findArchive(
		remote: Manga,
		file: File,
		withDetails: Boolean,
		space: FavouriteSpace? = null,
		request: UUID? = null,
		trustedOwnedPath: Boolean = false,
	): Match? {
		return when (val evidence = ExhentaiChapterOwnership.inspectDestination(file)) {
			is ExhentaiChapterOwnership.Evidence.Owned -> {
				if (!evidence.matches(remote.galleryId(), space, request)) return null
				val match = if (evidence.single) indexedArchive(remote, file, withDetails)
				else chapterCopy(remote, file, withDetails, true)
				match?.copy(receipt = evidence.receipt)
			}
			ExhentaiChapterOwnership.Evidence.Unusable -> null
			ExhentaiChapterOwnership.Evidence.Unmarked -> {
				if (request != null) return null
				val parser = LocalMangaParser.getOrNull(file) ?: return null
				val origin = parser.getMangaInfo()
				if (origin != null) {
					if (!originMatches(remote, origin)) return null
					val local = parser.getManga(withDetails)
					if (withDetails && local.manga.chapters.isNullOrEmpty()) return null
					Match(LegacyChapterDownloadCompat.linkToRemote(remote, local), true, physicalMangaId = local.manga.id)
				} else {
					// Only an explicitly persisted exact manga+space ownership path may recover an
					// old XML-free CBZ. It remains unverified for deletion or completed reuse.
					when (val xml = EhentaiComicInfo.inspect(file)) {
						is EhentaiComicInfo.Evidence.Gallery ->
							if (xml.id == remote.galleryId()) chapterCopy(remote, file, withDetails, false) else null
						EhentaiComicInfo.Evidence.Absent ->
							if (trustedOwnedPath && EhentaiLegacyDownloadResolver.isLegacyChapterArtifactName(file.name))
								chapterCopy(remote, file, withDetails, false) else null
						EhentaiComicInfo.Evidence.Invalid -> null
					}
				}
			}
		}
	}

	private suspend fun indexedArchive(remote: Manga, file: File, withDetails: Boolean): Match? {
		val parser = LocalMangaParser.getOrNull(file) ?: return null
		val origin = parser.getMangaInfo() ?: return null
		if (!originMatches(remote, origin)) return null
		val local = parser.getManga(withDetails)
		if (withDetails && local.manga.chapters.isNullOrEmpty()) return null
		return Match(LegacyChapterDownloadCompat.linkToRemote(remote, local), true, physicalMangaId = local.manga.id)
	}

	private suspend fun findInDirectory(
		remote: Manga,
		dir: File,
		withDetails: Boolean,
		space: FavouriteSpace?,
		request: UUID?,
		trustedOwnedPath: Boolean,
	): Match? {
		val entries = dir.listFiles { f -> f.isFile && f.extension.equals("cbz", ignoreCase = true) } ?: return null
		val candidates = entries.filter { it.canonicalFile == it }.associateWith(ExhentaiChapterOwnership::inspect)
		val galleryId = remote.galleryId()
		if (galleryId != null) {
			val exact = candidates.filterValues { (it as? ExhentaiChapterOwnership.Evidence.Owned)?.matches(galleryId, space, request) == true }.keys
			val named = exact.filter { it.name.equals(ExhentaiChapterFileName.withGalleryId(galleryId), true) }
			(named.singleOrNull() ?: exact.singleOrNull())?.let { return findArchive(remote, it, withDetails, space, request) }
			if (exact.isNotEmpty()) return null
		}
		if (request != null) return null
		val embeddedMatches = ArrayList<File>()
		val metadataMatches = ArrayList<File>()
		for ((file, evidence) in candidates) {
			if (evidence != ExhentaiChapterOwnership.Evidence.Unmarked) continue
			val embedded = LocalMangaParser.getOrNull(file)?.getMangaInfo()
			when {
				embedded != null && originMatches(remote, embedded) -> embeddedMatches += file
				embedded != null -> Unit // A foreign index cannot be overridden by an XML claim.
				else -> {
					val xmlId = (EhentaiComicInfo.inspect(file) as? EhentaiComicInfo.Evidence.Gallery)?.id
					if (galleryId != null && xmlId == galleryId) metadataMatches += file
				}
			}
		}
		embeddedMatches.singleOrNull()?.let { return findArchive(remote, it, withDetails) }
		if (embeddedMatches.isNotEmpty()) return null
		metadataMatches.singleOrNull()?.let { return findArchive(remote, it, withDetails) }
		// A persisted exact ownership row can read one old unmarked chapter, but never
		// promotes it to verified deletion/completed-download evidence.
		if (trustedOwnedPath && candidates.size == 1) {
			val only = candidates.entries.single()
			if (only.value == ExhentaiChapterOwnership.Evidence.Unmarked &&
				EhentaiLegacyDownloadResolver.isLegacyChapterArtifactName(only.key.name) &&
				EhentaiComicInfo.inspect(only.key) == EhentaiComicInfo.Evidence.Absent
			) return chapterCopy(remote, only.key, withDetails, false)
		}
		return null
	}

	private suspend fun chapterCopy(remote: Manga, file: File, withDetails: Boolean, verified: Boolean): Match? {
		val local = LocalMangaParser.getOrNull(file)?.getManga(withDetails = false) ?: return null
		val uri = file.toUri().toString()
		val chapters = if (withDetails) remote.chapters?.singleOrNull()?.let {
			listOf(it.copy(source = LocalMangaSource, url = uri))
		} else null
		return Match(local.copy(manga = local.manga.copy(title = remote.title, chapters = chapters)), verified)
	}

}
