package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.parsers.model.MangaState
import java.time.ZoneId

/** Source rows, before local replacement or display/status filters, are the only evidence. */
fun predictSourceChapterRelease(
	details: MangaDetails?,
	branch: String?,
	nowMillis: Long,
	zone: ZoneId,
): NextChapterReleasePrediction? {
	if (details == null || !details.isLoaded || details.isLocal) return null
	val source = details.sourceManga
	if (source.state != null && source.state != MangaState.ONGOING) return null
	val dates = source.chapters.orEmpty()
		.filter { it.source == source.source && it.branch == branch }
		.map { it.uploadDate }
	return NextChapterReleaseEstimator.predict(dates, nowMillis, zone)
}
