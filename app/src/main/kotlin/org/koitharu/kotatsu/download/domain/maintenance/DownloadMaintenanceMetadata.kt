package org.koitharu.kotatsu.download.domain.maintenance

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable

/** Existing offline identity wins; provider access is the final, controlled fallback. */
internal object DownloadMaintenanceMetadata {
	data class Resolution(val manga: Manga, val failure: DownloadMaintenanceReason? = null, val fetched: Boolean = false)

	suspend fun resolve(cached: Manga, indexed: Manga?, fetch: suspend (Manga) -> Manga): Resolution {
		if (!cached.chapters.isNullOrEmpty()) return Resolution(cached)
		if (indexed != null && indexed.id == cached.id && !indexed.chapters.isNullOrEmpty() &&
			DownloadMaintenanceUseCase.sameSource(indexed.source.name, cached.source.name)) return Resolution(indexed)
		val result = runCatchingCancellable { fetch(cached) }
		val details = result.getOrNull()
		if (details == null) return Resolution(cached, DownloadMaintenanceReason.PROVIDER_UNAVAILABLE)
		if (details.id != cached.id || !DownloadMaintenanceUseCase.sameSource(details.source.name, cached.source.name) || details.chapters.isNullOrEmpty()) {
			return Resolution(cached, DownloadMaintenanceReason.METADATA_UNAVAILABLE)
		}
		return Resolution(details, fetched = true)
	}
}
