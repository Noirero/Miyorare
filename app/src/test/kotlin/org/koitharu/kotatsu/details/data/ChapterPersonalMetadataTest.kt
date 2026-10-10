package org.koitharu.kotatsu.details.data

import org.junit.Assert.*
import org.junit.Test

class ChapterPersonalMetadataTest {
	@Test fun normalizationClearsWhitespaceAndKeepsIndependentFields() {
		assertTrue(ChapterPersonalMetadata.normalized(null, "  ").isEmpty)
		assertEquals(ChapterPersonalMetadata(5, "Peak chapter"), ChapterPersonalMetadata.normalized(5, " Peak chapter "))
		assertEquals(ChapterPersonalMetadata(4), ChapterPersonalMetadata.normalized(4, ""))
		assertEquals(ChapterPersonalMetadata(note = "Keep"), ChapterPersonalMetadata.normalized(null, "Keep"))
	}

	@Test fun onlyUnratedAndFiveStarValuesAreAccepted() {
		for (rating in 1..5) assertEquals(rating, ChapterPersonalMetadata(rating).rating)
		assertNull(ChapterPersonalMetadata().rating)
		for (invalid in listOf(0, -1, 6, 100)) {
			assertTrue(runCatching { ChapterPersonalMetadata(invalid) }.exceptionOrNull() is IllegalArgumentException)
		}
	}

	@Test fun noteLimitIsEnforcedBeforePersistence() {
		val limit = ChapterPersonalMetadata.MAX_NOTE_LENGTH
		assertEquals(limit, ChapterPersonalMetadata.normalized(null, "a".repeat(limit)).note!!.length)
		assertTrue(runCatching { ChapterPersonalMetadata.normalized(null, "a".repeat(limit + 1)) }.isFailure)
	}
}
