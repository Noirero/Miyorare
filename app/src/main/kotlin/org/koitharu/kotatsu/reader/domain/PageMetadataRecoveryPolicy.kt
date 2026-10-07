package org.koitharu.kotatsu.reader.domain

/**
 * Reader-session policy for deciding when stale page-list metadata deserves one forced refresh.
 * A single broken image is not evidence that the whole page list is stale: require failures from
 * distinct pages in the same chapter, then allow exactly one recovery attempt for that chapter.
 */
internal class PageMetadataRecoveryPolicy(
	private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
) {
	private val failures = HashMap<ChapterKey, MutableSet<Long>>()
	private val recovered = HashSet<ChapterKey>()

	init {
		require(failureThreshold > 1)
	}

	@Synchronized
	fun recordNotFound(sourceName: String, chapterId: Long, pageId: Long): Boolean {
		val key = ChapterKey(sourceName, chapterId)
		if (key in recovered) return false
		val failedPages = failures.getOrPut(key) { HashSet(failureThreshold) }
		failedPages += pageId
		if (failedPages.size < failureThreshold) return false
		failures.remove(key)
		recovered += key
		return true
	}

	@Synchronized
	fun clear() {
		failures.clear()
		recovered.clear()
	}

	private data class ChapterKey(
		val sourceName: String,
		val chapterId: Long,
	)

	private companion object {
		const val DEFAULT_FAILURE_THRESHOLD = 2
	}
}
