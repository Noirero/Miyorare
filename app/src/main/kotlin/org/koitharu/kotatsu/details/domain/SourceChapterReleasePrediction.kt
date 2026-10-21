package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaState
import java.time.ZoneId

/** Source rows, before local replacement or display/status filters, are the only evidence. */
fun predictSourceChapterRelease(
	details: MangaDetails?,
	branch: String?,
	nowMillis: Long,
	zone: ZoneId,
): NextChapterReleasePrediction? =
	sourceReleasePredictionLineage(details, branch, null, nowMillis, zone)?.prediction

internal fun predictSourceChapterRelease(
	details: MangaDetails?,
	branch: String?,
	scanlator: String?,
	nowMillis: Long,
	zone: ZoneId,
): NextChapterReleasePrediction? =
	sourceReleasePredictionLineage(details, branch, scanlator, nowMillis, zone)?.prediction

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

internal fun sourceReleasePredictionItem(
	details: MangaDetails?,
	branch: String?,
	nowMillis: Long,
	zone: ZoneId,
): ChapterReleasePredictionItem? = sourceReleasePredictionItem(details, branch, null, nowMillis, zone)

internal fun sourceReleasePredictionItem(
	details: MangaDetails?,
	branch: String?,
	scanlator: String?,
	nowMillis: Long,
	zone: ZoneId,
): ChapterReleasePredictionItem? {
	val lineage = sourceReleasePredictionLineage(details, branch, scanlator, nowMillis, zone) ?: return null
	return ChapterReleasePredictionItem(
		manga = requireNotNull(details).toManga().copy(chapters = null),
		prediction = lineage.prediction,
		releaseDates = lineage.releaseDates,
		branch = branch,
		scanlator = lineage.scanlator,
	)
}

private fun sourceReleasePredictionLineage(
	details: MangaDetails?,
	branch: String?,
	scanlator: String?,
	nowMillis: Long,
	zone: ZoneId,
): ReleasePredictionLineage? {
	if (details == null || !details.isLoaded || details.isLocal) return null
	val source = details.sourceManga
	if (source.state != null && source.state != MangaState.ONGOING) return null
	val points = source.chapters.orEmpty().asSequence()
		.filter { it.source == source.source && it.branch == branch }
		.map { ReleasePredictionEvidencePoint(it.uploadDate, it.scanlator) }
		.toList()
	return selectReleasePredictionLineage(points, nowMillis, zone, scanlator)
}
