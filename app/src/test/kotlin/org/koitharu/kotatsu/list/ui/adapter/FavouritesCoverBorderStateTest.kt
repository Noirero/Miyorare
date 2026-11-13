package org.koitharu.kotatsu.list.ui.adapter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouritesCoverBorderStateTest {

	@Test fun sameUrlRebindKeepsPendingAndReadyStates() {
		val state = FavouritesCoverBorderState()
		val request = Any()
		state.bind("cover")
		state.start("cover", request)
		state.bind("cover")
		assertTrue(state.isPending)
		state.finish(request)
		state.bind("cover")
		assertFalse(state.isPending)
	}

	@Test fun recycledHolderRejectsOldCompletionEvenWhenUrlIsReused() {
		val state = FavouritesCoverBorderState()
		val oldRequest = Any()
		val newRequest = Any()
		state.bind("a")
		state.start("a", oldRequest)
		state.bind("b")
		state.start("a", oldRequest)
		state.finish(oldRequest)
		assertTrue(state.isPending)
		state.bind("a")
		state.start("a", newRequest)
		state.finish(oldRequest)
		assertTrue(state.isPending)
		state.finish(newRequest)
		assertFalse(state.isPending)
	}

	@Test fun missingCoverAndErrorKeepFallbackBorderWhileRetryIsGated() {
		val state = FavouritesCoverBorderState()
		val failed = Any()
		val retry = Any()
		state.bind("cover")
		state.start("cover", failed)
		state.finish(failed)
		assertFalse(state.isPending)
		state.start("cover", retry)
		assertTrue(state.isPending)
		state.bind(null)
		state.finish(retry)
		assertFalse(state.isPending)
	}
}
