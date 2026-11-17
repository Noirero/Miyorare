package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.parsers.model.Manga

/** Compare the unfiltered source snapshots within the selected branch and translation group. */
internal fun countNewChaptersForReleaseCalendar(
	baseline: Manga,
	remote: Manga,
	branch: String?,
	scanlator: String?,
): Int {
	check(baseline.id == remote.id && baseline.source.name == remote.source.name)
	val selectedGroup = normalizeReleasePredictionScanlator(scanlator)
	fun Manga.chapterIds(): Set<Long> = checkNotNull(chapters).asSequence()
		.filter { it.source.name == source.name && it.branch == branch }
		.filter { selectedGroup == null || normalizeReleasePredictionScanlator(it.scanlator) == selectedGroup }
		.map { it.id }
		.toSet()

	val known = baseline.chapterIds()
	val latest = remote.chapterIds()
	check(latest.isNotEmpty() || known.isEmpty())
	return latest.count { it !in known }
}
