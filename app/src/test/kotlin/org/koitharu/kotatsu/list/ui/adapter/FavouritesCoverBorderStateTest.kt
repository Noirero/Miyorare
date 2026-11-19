package org.koitharu.kotatsu.list.ui.adapter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouritesCoverBorderStateTest {

	@Test fun sameUrlRebindKeepsPendingAndReadyStates() {
		val state = FavouritesCoverBorderState()
		state.bind("cover")
		val binding = state.requestBindingIdentity
		val request = Any()
		state.start("cover", binding, request)
		state.bind("cover")
		assertSame(binding, state.requestBindingIdentity)
		assertTrue(state.isPending)
		state.finish(request)
		state.bind("cover")
		assertFalse(state.isPending)
	}

	@Test fun matchingRequestTokenCompletesActiveLoading() {
		val state = FavouritesCoverBorderState()
		state.bind("cover")
		val token = Any()
		state.start("cover", state.requestBindingIdentity, token)
		assertTrue(state.isPending)
		state.finish(token)
		assertFalse(state.isPending)
	}

	@Test fun recycledHolderRejectsOldCallbacksEvenWhenUrlIsReused() {
		val state = FavouritesCoverBorderState()
		state.bind("a")
		val oldBinding = state.requestBindingIdentity
		val oldRequest = Any()
		state.start("a", oldBinding, oldRequest)
		state.bind("b")
		state.start("a", oldBinding, oldRequest)
		state.finish(oldRequest)
		assertTrue(state.isPending)
		state.bind("a")
		assertNotSame(oldBinding, state.requestBindingIdentity)
		state.start("a", oldBinding, oldRequest)
		val newRequest = Any()
		state.start("a", state.requestBindingIdentity, newRequest)
		state.finish(oldRequest)
		assertTrue(state.isPending)
		state.finish(newRequest)
		assertFalse(state.isPending)
	}

	@Test fun cancellationClearsOnlyTheActiveRequest() {
		val state = FavouritesCoverBorderState()
		state.bind("cover")
		val binding = state.requestBindingIdentity
		val old = Any()
		val active = Any()
		state.start("cover", binding, old)
		state.start("cover", binding, active)
		state.cancel(old)
		assertTrue(state.isPending)
		state.cancel(active)
		assertFalse(state.isPending)
	}

	@Test fun nullCoverAndErrorKeepFallbackBorderWhileRetryIsGated() {
		val state = FavouritesCoverBorderState()
		val failed = Any()
		val retry = Any()
		state.bind("cover")
		state.start("cover", state.requestBindingIdentity, failed)
		state.finish(failed)
		assertFalse(state.isPending)
		state.start("cover", state.requestBindingIdentity, retry)
		assertTrue(state.isPending)
		state.bind(null)
		state.finish(retry)
		assertFalse(state.isPending)
	}
}
