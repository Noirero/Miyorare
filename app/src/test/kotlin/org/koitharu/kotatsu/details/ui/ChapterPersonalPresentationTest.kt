package org.koitharu.kotatsu.details.ui

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.details.data.ChapterPersonalMetadata
import java.util.Locale

class ChapterPersonalPresentationTest {
	@Test fun unratedAndNoteOnlyNeverInventARatingOrActivateTheStar() {
		val empty = ChapterPersonalMetadata().presentation(Locale.US)
		assertFalse(empty.hasNote)
		assertFalse(empty.isRated)
		assertNull(empty.ratingText)
		val note = ChapterPersonalMetadata(note = "Peak").presentation(Locale.US)
		assertTrue(note.hasNote)
		assertFalse(note.isRated)
		assertNull(note.ratingText)
	}

	@Test fun everyStoredIntegerIsShownWithIndependentNotePresence() {
		for (rating in 1..5) for (note in listOf(null, "Peak")) {
			val presentation = ChapterPersonalMetadata(rating, note).presentation(Locale.US)
			assertEquals(rating.toString(), presentation.ratingText)
			assertTrue(presentation.isRated)
			assertEquals(note != null, presentation.hasNote)
		}
	}

	@Test fun blankNotesFollowExistingNormalization() {
		for (note in listOf(null, "", " \n\t ")) {
			assertFalse(ChapterPersonalMetadata.normalized(3, note).presentation(Locale.US).hasNote)
		}
	}

	@Test fun integerFormattingUsesTheActiveLocaleWithoutDecimalPrecision() {
		for (locale in listOf(Locale.US, Locale.forLanguageTag("id-ID"), Locale.forLanguageTag("ar"))) {
			assertEquals(String.format(locale, "%d", 4), ChapterPersonalMetadata(4).presentation(locale).ratingText)
		}
	}
}
