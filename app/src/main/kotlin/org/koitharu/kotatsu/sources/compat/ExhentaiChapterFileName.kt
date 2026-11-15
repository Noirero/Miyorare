package org.koitharu.kotatsu.sources.compat

/** For official ExHentai chapter CBZs only; outer SINGLE_CBZ containers are not chapter files. */
internal object ExhentaiChapterFileName {
	const val PLAIN = "Chapter.cbz"
	private const val LEGACY_HASH_LENGTH = 6
	private val numericSuffix = Regex("^Chapter_([0-9]+)\\.cbz$", RegexOption.IGNORE_CASE)

	fun withGalleryId(galleryId: Long): String {
		require(galleryId > 0)
		val id = galleryId.toString()
		// Avoid emitting a name indistinguishable from a six-digit legacy URL hash.
		val suffix = if (id.length == LEGACY_HASH_LENGTH) "0$id" else id
		return "Chapter_$suffix.cbz"
	}

	/** A null result is not positive evidence for any gallery. Six digits stay legacy-ambiguous. */
	fun explicitGalleryId(fileName: String): Long? {
		val suffix = numericSuffix.matchEntire(fileName)?.groupValues?.get(1) ?: return null
		if (suffix.length == LEGACY_HASH_LENGTH) return null
		return suffix.toLongOrNull()?.takeIf { it > 0 }
	}
}
