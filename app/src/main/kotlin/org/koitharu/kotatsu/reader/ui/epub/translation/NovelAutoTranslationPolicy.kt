package org.koitharu.kotatsu.reader.ui.epub.translation

/** Pure eligibility rules for per-novel automatic translation. */
internal object NovelAutoTranslationPolicy {

	fun shouldStart(
		enabled: Boolean,
		readerActive: Boolean,
		chapterReady: Boolean,
		hasTranslation: Boolean,
		translationInFlight: Boolean,
		chapterId: Long,
		lastAttemptedChapterId: Long,
	): Boolean = enabled &&
		readerActive &&
		chapterReady &&
		!hasTranslation &&
		!translationInFlight &&
		chapterId > 0L &&
		chapterId != lastAttemptedChapterId

	fun shouldRetainProjection(chapterIndex: Int, currentIndex: Int, radius: Int): Boolean =
		kotlin.math.abs(chapterIndex - currentIndex) <= radius.coerceAtLeast(0)

	fun providerBinding(engine: NovelTranslationEngineKind, provider: NovelAiProvider): String =
		if (engine == NovelTranslationEngineKind.AI) "ai:${provider.id}" else NovelTranslationEngineKind.ONLINE.id
}
