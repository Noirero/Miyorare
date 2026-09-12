package org.koitharu.kotatsu.local.data.index

/**
 * Persistent, non-destructive link from a provider-specific remote manga id to an existing local
 * download. The original local manga id is retained so a reused/replaced path can be rejected.
 */
internal data class DownloadPathAlias(
	val localMangaId: Long,
	val path: String,
) {
	fun serialize(): String = "$localMangaId|$path"

	companion object {
		fun parse(raw: String?): DownloadPathAlias? {
			if (raw.isNullOrEmpty()) return null
			val separator = raw.indexOf('|')
			if (separator <= 0 || separator == raw.lastIndex) return null
			val localMangaId = raw.substring(0, separator).toLongOrNull() ?: return null
			val path = raw.substring(separator + 1).takeIf { it.isNotBlank() } ?: return null
			return DownloadPathAlias(localMangaId, path)
		}
	}
}
