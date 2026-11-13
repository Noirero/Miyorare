package org.koitharu.kotatsu.sources.compat

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Provenance for chapter CBZs written by the official ExHentai downloader. The ZIP comment
 * belongs to the artifact, not the title folder, so multiple galleries can share a folder.
 * Missing provenance is normal for old/imported CBZs; it is not proof of a conflict.
 * Call from an IO context, with a bounded set of already-discovered chapter files.
 */
internal object ExhentaiChapterOwnership {
	private const val PREFIX = "miyorare:exhentai:chapter:v1:"
	private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "heif", "heic", "jxl")

	fun comment(galleryId: Long): String {
		require(galleryId > 0)
		return PREFIX + galleryId
	}

	sealed interface Evidence {
		data class Owned(val galleryId: Long) : Evidence
		data object Unmarked : Evidence
		data object Unusable : Evidence
	}

	/** Exact current copy wins. A caller-supplied legacy match is read-only and must be unmarked. */
	fun select(galleryId: Long, candidates: Collection<File>, legacyMatch: File?): File? {
		val evidence = candidates.distinct().associateWith(::inspect)
		val exact = evidence.filterValues { it == Evidence.Owned(galleryId) }.keys
		val preferred = exact.filter { it.name.equals(ExhentaiChapterFileName.withGalleryId(galleryId), ignoreCase = true) }
		return when {
			preferred.isNotEmpty() -> preferred.singleOrNull()
			exact.isNotEmpty() -> exact.singleOrNull()
			legacyMatch != null && evidence[legacyMatch] == Evidence.Unmarked -> legacyMatch
			else -> null
		}
	}

	fun inspect(artifact: File): Evidence {
		if (!artifact.isFile || !artifact.extension.equals("cbz", ignoreCase = true)) return Evidence.Unusable
		return try {
			ZipFile(artifact).use { archive ->
				if (archive.entries().asSequence().none { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }) {
					return Evidence.Unusable
				}
				val storedComment = archive.comment ?: return Evidence.Unmarked
				if (!storedComment.startsWith(PREFIX)) return Evidence.Unmarked
				val owner = storedComment.removePrefix(PREFIX).toLongOrNull()?.takeIf { it > 0 }
					?: return Evidence.Unusable
				if (storedComment != comment(owner)) return Evidence.Unusable
				val filename = artifact.name
				if (!filename.equals(ExhentaiChapterFileName.PLAIN, ignoreCase = true) &&
					!filename.equals(ExhentaiChapterFileName.withGalleryId(owner), ignoreCase = true)
				) return Evidence.Unusable
				Evidence.Owned(owner)
			}
		} catch (_: IOException) {
			Evidence.Unusable
		} catch (_: IllegalArgumentException) {
			Evidence.Unusable
		}
	}
}
