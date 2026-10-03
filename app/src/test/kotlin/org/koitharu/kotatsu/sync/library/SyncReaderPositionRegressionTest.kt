package org.koitharu.kotatsu.sync.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncReaderPositionRegressionTest {
	@Test
	fun sameChapterKeepsPageAndScroll() {
		assertEquals(17 to 0.63f, syncedReaderPosition(42L, 42L, 17, 0.63f))
	}

	@Test
	fun differentChapterStartsAtBeginning() {
		assertEquals(0 to 0f, syncedReaderPosition(41L, 42L, 17, 0.63f))
	}
}
