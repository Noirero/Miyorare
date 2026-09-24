package org.koitharu.kotatsu.readerjourney.domain

data class ReaderProfileSettings(
	val displayName: String = "",
	val selectedTitle: ReaderAchievementId? = null,
	val showcase: List<ReaderAchievementId> = emptyList(),
) {
	val initial: String
		get() = displayName.trim().firstOrNull()?.uppercase() ?: "R"
}

enum class ReadingPersonality {
	DISCOVERING,
	STEADY_READER,
	EXPLORER,
	MANGA_READER,
	NOVEL_READER,
	BALANCED,
}

object ReadingPersonalityRules {

	fun resolve(
		mangaChapters: Long,
		novelChapters: Long,
		uniqueTitles: Long,
		longestStreak: Int,
	): ReadingPersonality {
		val manga = mangaChapters.coerceAtLeast(0L)
		val novel = novelChapters.coerceAtLeast(0L)
		val total = manga + novel
		if (total == 0L) return ReadingPersonality.DISCOVERING

		// Consistency wins only after a meaningful streak. Content preferences never use title,
		// source, genre or mature identity — only aggregate verified completion counts.
		if (longestStreak >= 30) return ReadingPersonality.STEADY_READER
		if (novel >= 5L && novel >= manga * 2L) return ReadingPersonality.NOVEL_READER
		if (manga >= 5L && manga >= novel * 2L) return ReadingPersonality.MANGA_READER
		if (uniqueTitles >= 10L && uniqueTitles * 3L >= total) return ReadingPersonality.EXPLORER
		return ReadingPersonality.BALANCED
	}
}
