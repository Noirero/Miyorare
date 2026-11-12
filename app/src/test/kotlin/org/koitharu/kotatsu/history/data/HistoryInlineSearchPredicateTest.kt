package org.koitharu.kotatsu.history.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryInlineSearchPredicateTest {
	@Test fun `empty and whitespace queries do not constrain history`() {
		assertNull(historyTitleSearchPredicate(""))
		assertNull(historyTitleSearchPredicate("   "))
	}

	@Test fun `title and alternate title match before pagination`() {
		val predicate = historyTitleSearchPredicate("  Naruto  ")
		assertNotNull(predicate)
		assertEquals(
			"(manga.title LIKE '%Naruto%' ESCAPE char(92) OR manga.alt_title LIKE '%Naruto%' ESCAPE char(92))",
			predicate,
		)
	}

	@Test fun `user supplied SQL and LIKE characters cannot change the predicate`() {
		val predicate = historyTitleSearchPredicate("O'Brien%_\\")
		assertNotNull(predicate)
		assertTrue(predicate!!.contains("O''Brien"))
		assertTrue(predicate.contains("\\%"))
		assertTrue(predicate.contains("\\_"))
		assertTrue(predicate.contains("ESCAPE char(92)"))
		assertFalse(predicate.contains("O'Brien%_"))
	}
}
