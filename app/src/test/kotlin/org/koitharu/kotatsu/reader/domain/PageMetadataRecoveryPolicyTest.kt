package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMetadataRecoveryPolicyTest {

	@Test
	fun oneNotFoundDoesNotRefreshChapterMetadata() {
		val policy = PageMetadataRecoveryPolicy()

		assertFalse(policy.recordNotFound("source", chapterId = 10L, pageId = 1L))
	}

	@Test
	fun twoDistinctNotFoundPagesTriggerOneRefresh() {
		val policy = PageMetadataRecoveryPolicy()

		assertFalse(policy.recordNotFound("source", chapterId = 10L, pageId = 1L))
		assertTrue(policy.recordNotFound("source", chapterId = 10L, pageId = 2L))
		assertFalse(policy.recordNotFound("source", chapterId = 10L, pageId = 3L))
	}

	@Test
	fun repeatedFailureOfSamePageDoesNotReachThreshold() {
		val policy = PageMetadataRecoveryPolicy()

		assertFalse(policy.recordNotFound("source", chapterId = 10L, pageId = 1L))
		assertFalse(policy.recordNotFound("source", chapterId = 10L, pageId = 1L))
	}

	@Test
	fun failuresAreIsolatedByChapterAndSource() {
		val policy = PageMetadataRecoveryPolicy()

		assertFalse(policy.recordNotFound("source-a", chapterId = 10L, pageId = 1L))
		assertFalse(policy.recordNotFound("source-a", chapterId = 11L, pageId = 2L))
		assertFalse(policy.recordNotFound("source-b", chapterId = 10L, pageId = 2L))
		assertTrue(policy.recordNotFound("source-a", chapterId = 10L, pageId = 2L))
	}

	@Test
	fun clearStartsNewReaderSession() {
		val policy = PageMetadataRecoveryPolicy()
		policy.recordNotFound("source", chapterId = 10L, pageId = 1L)
		policy.recordNotFound("source", chapterId = 10L, pageId = 2L)

		policy.clear()

		assertFalse(policy.recordNotFound("source", chapterId = 10L, pageId = 3L))
		assertTrue(policy.recordNotFound("source", chapterId = 10L, pageId = 4L))
	}
}
