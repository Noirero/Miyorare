package org.koitharu.kotatsu.alternatives.domain

import org.koitharu.kotatsu.parsers.model.Manga

/** Transient network evidence; rediscovered after process recreation and freshly revalidated before execution. */
data class MigrationDiscovery(
	val seed: Manga,
	val assessment: MigrationMatchAssessment? = null,
	val sourceHealthy: Boolean = false,
	val incomplete: Boolean = false,
)

data class MigrationSelection(val source: String, val id: Long, val fingerprint: String) {
	companion object {
		fun of(manga: Manga) = MigrationSelection(manga.source.name, manga.id, java.security.MessageDigest.getInstance("SHA-256").digest(buildString {
			append(manga.title); append('|'); append(manga.publicUrl)
			append('|'); append(manga.authors.sorted()); append('|'); append(manga.altTitles.sorted())
			append('|'); append(manga.chapters.orEmpty().map { listOf(it.id, it.volume, it.number, it.branch, it.url) }.sortedBy { it.toString() })
		}.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
	}
}

object MigrationExecutionPolicy {
	fun select(current: MigrationDiscovery, selection: MigrationSelection, reviewed: Boolean): Manga? {
		if (current.sourceHealthy) return null
		val assessment = current.assessment ?: return null
		if (!reviewed && (current.incomplete || assessment.state != MigrationMatchState.READY ||
			assessment.best?.manga?.let(MigrationSelection::of) != selection)) return null
		return assessment.candidates.firstOrNull { MigrationSelection.of(it.manga) == selection }?.manga
	}
}
