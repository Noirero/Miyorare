package org.koitharu.kotatsu.details.domain

import java.time.LocalDate
import java.time.ZoneId

internal data class ReleasePredictionEvidencePoint(
	val uploadDate: Long,
	val scanlator: String?,
)

internal data class ReleasePredictionLineage(
	val prediction: NextChapterReleasePrediction,
	val releaseDates: List<LocalDate>,
	val scanlator: String?,
	val latestEvent: LocalDate,
	val consistentIntervals: Int,
	val intervalCount: Int,
)

internal fun normalizeReleasePredictionScanlator(value: String?): String? =
	value?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Select one prediction lineage without mixing independent named scanlators.
 *
 * Zero/one named scanlator preserves the legacy branch-wide evidence so sources with missing
 * scanlator metadata do not lose predictions. Once a branch contains multiple named scanlators,
 * each named lineage must stand on its own evidence and ambiguous null rows are not mixed into it.
 */
internal fun selectReleasePredictionLineage(
	points: Iterable<ReleasePredictionEvidencePoint>,
	nowMillis: Long,
	zone: ZoneId,
	selectedScanlator: String? = null,
): ReleasePredictionLineage? {
	val normalized = points.map { point ->
		point.copy(scanlator = normalizeReleasePredictionScanlator(point.scanlator))
	}
	val selected = normalizeReleasePredictionScanlator(selectedScanlator)
	if (selected != null) {
		return assessReleasePredictionLineage(
			points = normalized.filter { it.scanlator == selected },
			scanlator = selected,
			nowMillis = nowMillis,
			zone = zone,
		)
	}

	val named = normalized.asSequence().mapNotNull { it.scanlator }.distinct().sorted().toList()
	if (named.size <= 1) {
		return assessReleasePredictionLineage(normalized, null, nowMillis, zone)
	}

	return named.mapNotNull { scanlator ->
		assessReleasePredictionLineage(
			points = normalized.filter { it.scanlator == scanlator },
			scanlator = scanlator,
			nowMillis = nowMillis,
			zone = zone,
		)
	}.minWithOrNull(releasePredictionLineagePreference)
}

private fun assessReleasePredictionLineage(
	points: Iterable<ReleasePredictionEvidencePoint>,
	scanlator: String?,
	nowMillis: Long,
	zone: ZoneId,
): ReleasePredictionLineage? {
	val assessment = NextChapterReleaseEstimator.assess(points.map { it.uploadDate }, nowMillis, zone) ?: return null
	return ReleasePredictionLineage(
		prediction = assessment.prediction,
		releaseDates = assessment.releaseDates,
		scanlator = scanlator,
		latestEvent = assessment.latestEvent,
		consistentIntervals = assessment.consistentIntervals,
		intervalCount = assessment.intervalCount,
	)
}

/** Preferred candidates sort first: recency, reliability ratio, evidence depth, deterministic name. */
internal val releasePredictionLineagePreference = Comparator<ReleasePredictionLineage> { left, right ->
	val recency = right.latestEvent.compareTo(left.latestEvent)
	if (recency != 0) return@Comparator recency

	val leftReliability = left.consistentIntervals.toLong() * right.intervalCount.toLong()
	val rightReliability = right.consistentIntervals.toLong() * left.intervalCount.toLong()
	val reliability = rightReliability.compareTo(leftReliability)
	if (reliability != 0) return@Comparator reliability

	val evidenceDepth = right.intervalCount.compareTo(left.intervalCount)
	if (evidenceDepth != 0) return@Comparator evidenceDepth

	compareValues(left.scanlator.orEmpty(), right.scanlator.orEmpty())
}
