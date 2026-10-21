package org.koitharu.kotatsu.details.data

import androidx.sqlite.db.SimpleSQLiteQuery
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.db.entity.MangaState
import org.koitharu.kotatsu.parsers.model.Manga

data class ReleasePredictionManga(
	val id: Long,
	val title: String,
	val url: String,
	val publicUrl: String,
	val coverUrl: String?,
	val largeCoverUrl: String?,
	val source: String,
	val sourceTitle: String?,
	val state: String?,
) {
	fun toManga() = Manga(
		id = id, title = title, url = url, publicUrl = publicUrl,
		coverUrl = coverUrl, largeCoverUrl = largeCoverUrl, source = MangaSource(source, sourceTitle),
		state = state?.let { MangaState(it) }, altTitles = emptySet(), rating = -1f,
		contentRating = null, authors = emptySet(), description = null, tags = emptySet(), chapters = null,
	)
}

data class ReleasePredictionBranch(
	val mangaId: Long,
	val source: String,
	val branch: String?,
	val scanlator: String? = null,
)

data class ReleasePredictionDate(
	val mangaId: Long,
	val branch: String?,
	val uploadDate: Long,
	val scanlator: String? = null,
)

/**
 * SQLite on Android uses the device's local calendar, as does the estimator. Group before LIMIT:
 * a burst of chapters on one day must not crowd out the twelve distinct release events.
 * Each subquery uses the existing manga_id primary-key prefix. Keep compound/bind counts bounded
 * for Android 8 SQLite (no window functions). The representative timestamp is real source evidence.
 *
 * Scanlator is normalized only by trim/blank-to-null. Do not fuzzy-merge publisher/group names:
 * multiple named scanlators are independent prediction lineages, while missing metadata remains a
 * safe fallback only when the branch does not expose multiple named lineages.
 */
internal fun releasePredictionDatesQuery(branches: List<ReleasePredictionBranch>, nowMillis: Long): SimpleSQLiteQuery {
	require(branches.isNotEmpty() && branches.size <= 100)
	val arguments = ArrayList<Any?>(branches.size * 5)
	val normalizedScanlator = "CASE WHEN TRIM(scanlator) = '' THEN NULL ELSE TRIM(scanlator) END"
	val sql = branches.joinToString(" UNION ALL ") { branch ->
		arguments.addAll(listOf(branch.mangaId, branch.source, branch.branch, branch.scanlator, nowMillis))
		"""
		SELECT * FROM (
			SELECT manga_id AS mangaId, branch, MAX(upload_date) AS uploadDate,
				$normalizedScanlator AS scanlator
			FROM chapters
			WHERE manga_id = ? AND source = ? AND branch IS ?
				AND $normalizedScanlator IS ? AND upload_date > 0 AND upload_date <= ?
			GROUP BY date(upload_date / 1000, 'unixepoch', 'localtime')
			ORDER BY uploadDate DESC
			LIMIT 12
		)
		""".trimIndent()
	}
	return SimpleSQLiteQuery(sql, arguments.toTypedArray())
}
