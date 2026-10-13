package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaState
import java.time.Instant
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
	return predictSourceChapterRelease(source, dates, nowMillis, zone)
}

/** Persisted projections have already selected the current source and one branch. */
internal fun predictSourceChapterRelease(
	manga: Manga,
	uploadDates: Iterable<Long>,
	nowMillis: Long,
	zone: ZoneId,
): NextChapterReleasePrediction? {
	if (manga.isLocal || (manga.state != null && manga.state != MangaState.ONGOING)) return null
	return NextChapterReleaseEstimator.predict(uploadDates, nowMillis, zone)
}

internal fun sourceReleasePredictionItem(details: MangaDetails?, branch: String?, nowMillis: Long, zone: ZoneId): ChapterReleasePredictionItem? {
	val prediction = predictSourceChapterRelease(details, branch, nowMillis, zone) ?: return null
	val source = requireNotNull(details).sourceManga
	val dates = source.chapters.orEmpty().asSequence()
		.filter { it.source == source.source && it.branch == branch && it.uploadDate > 0 && it.uploadDate <= nowMillis }
		.map { Instant.ofEpochMilli(it.uploadDate).atZone(zone).toLocalDate() }
		.distinct().sorted().toList().takeLast(12)
	return ChapterReleasePredictionItem(details.toManga().copy(chapters = null), prediction, dates, branch)
}
