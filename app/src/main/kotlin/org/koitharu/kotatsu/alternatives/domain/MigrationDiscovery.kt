package org.koitharu.kotatsu.alternatives.domain

import org.koitharu.kotatsu.parsers.model.Manga

enum class MigrationSearchPhase {
	CHECKING_SOURCE,
	SEARCHING_SOURCES,
	VERIFYING_CANDIDATES,
}

/** Transient progress shown while a replacement search is running. */
data class MigrationSearchProgress(
	val phase: MigrationSearchPhase,
	val query: String? = null,
	val queryIndex: Int = 0,
	val queryTotal: Int = 0,
	val completedSources: Int = 0,
	val totalSources: Int = 0,
	val candidateCount: Int = 0,
	val lastSource: String? = null,
)

/** Transient network evidence; rediscovered after process recreation and freshly revalidated before execution. */
data class MigrationDiscovery(
	val seed: Manga,
	val assessment: MigrationMatchAssessment? = null,
	/** Compatibility storage name; semantically this means the current manga is still readable. */
	val sourceHealthy: Boolean = false,
	/** True only when discovery could not inspect enough of the selected scope to make a usable result. */
	val incomplete: Boolean = false,
	/** Provider/source failures that did not prevent useful search results. */
	val partialFailureCount: Int = 0,
	val progress: MigrationSearchProgress? = null,
) {
	val currentSourceReadable: Boolean
		get() = sourceHealthy

	val searchPartial: Boolean
		get() = partialFailureCount > 0
}

data class MigrationSelection(val source: String, val id: Long, val fingerprint: String) {
	val isRawSearchIdentity: Boolean
		get() = fingerprint.isEmpty()

	companion object {
		/** Raw Search-Everywhere result: stable source/id only. Full fingerprint is created after refresh. */
		fun fromSearchResult(manga: Manga) = MigrationSelection(manga.source.name, manga.id, "")

		/** Full execution identity after destination details have been refreshed. */
		fun of(manga: Manga) = MigrationSelection(manga.source.name, manga.id, java.security.MessageDigest.getInstance("SHA-256").digest(buildString {
			append(manga.title); append('|'); append(manga.publicUrl)
			append('|'); append(manga.authors.sorted()); append('|'); append(manga.altTitles.sorted())
			append('|'); append(manga.chapters.orEmpty().map { listOf(it.id, it.volume, it.number, it.branch, it.url) }.sortedBy { it.toString() })
		}.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
	}
}

object MigrationExecutionPolicy {
	fun select(
		current: MigrationDiscovery,
		selection: MigrationSelection,
		reviewed: Boolean,
		allowReadableSource: Boolean = false,
	): Manga? {
		if (current.currentSourceReadable && !allowReadableSource) return null
		val assessment = current.assessment ?: return null
		if (!reviewed && (current.incomplete || assessment.state != MigrationMatchState.READY ||
			assessment.best?.manga?.let(MigrationSelection::of) != selection)) return null
		return assessment.candidates.firstOrNull { MigrationSelection.of(it.manga) == selection }?.manga
	}
}
