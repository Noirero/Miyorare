package org.koitharu.kotatsu.list.ui.adapter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouritesCoverBorderDiagnosticTest {
	@Test fun `a recycled holder reports that the stale callback was ignored`() {
		val state = FavouritesCoverBorderState()
		val older = Any()
		assertTrue(state.bind("old"))
		assertTrue(state.start("old", state.requestBindingIdentity, older))

		val current = Any()
		assertTrue(state.bind("current"))
		assertFalse(state.bind("current"))
		assertTrue(state.start("current", state.requestBindingIdentity, current))
		assertFalse(state.finish(older))
		assertTrue(state.isPending)
		assertTrue(state.finish(current))
		assertFalse(state.isPending)
	}
}
