package org.koitharu.kotatsu.list.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.list.domain.ReadingProgress

class ModernCoverReadingStateTest {

	@Test
	fun `completed progress with zero unread is caught up`() {
		assertEquals(
			ModernCoverReadingState.CAUGHT_UP,
			resolveModernCoverReadingState(0, progress(1f)),
		)
	}

	@Test
	fun `partial progress with unread chapters is orange in progress state`() {
		assertEquals(
			ModernCoverReadingState.IN_PROGRESS,
			resolveModernCoverReadingState(3, progress(0.8f)),
		)
	}

	@Test
	fun `unread without reading progress stays unread`() {
		assertEquals(
			ModernCoverReadingState.UNREAD,
			resolveModernCoverReadingState(3, null),
		)
	}

	@Test
	fun `missing unread projection never becomes caught up`() {
		assertEquals(
			ModernCoverReadingState.NONE,
			resolveModernCoverReadingState(null, progress(1f)),
		)
	}

	@Test
	fun `unknown cached chapter authority never becomes caught up`() {
		assertEquals(
			ModernCoverReadingState.NONE,
			resolveModernCoverReadingState(-1, progress(1f)),
		)
	}

	@Test
	fun `new unread chapters beat an old completed progress snapshot`() {
		assertEquals(
			ModernCoverReadingState.IN_PROGRESS,
			resolveModernCoverReadingState(2, progress(1f)),
		)
	}

	private fun progress(percent: Float) = ReadingProgress(
		percent = percent,
		totalChapters = 10,
		mode = ProgressIndicatorMode.PERCENT_READ,
	)
}
