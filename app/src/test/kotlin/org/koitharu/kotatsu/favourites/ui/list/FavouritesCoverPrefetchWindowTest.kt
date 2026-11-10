package org.koitharu.kotatsu.favourites.ui.list

import org.junit.Assert.assertEquals
import org.junit.Test

class FavouritesCoverPrefetchWindowTest {

	@Test
	fun `newly selected Set prefetches from the start while its adapter is stale`() {
		assertEquals(0, preferredCoverPrefetchStart(firstVisible = 125, adapterCount = 256, incomingCount = 64))
		assertEquals(0, preferredCoverPrefetchStart(firstVisible = 30, adapterCount = 64, incomingCount = 48))
	}

	@Test
	fun `stable viewport prefetch follows scrolling rather than the last page`() {
		assertEquals(12, preferredCoverPrefetchStart(firstVisible = 12, adapterCount = 64, incomingCount = 64))
		assertEquals(45, preferredCoverPrefetchStart(firstVisible = 45, adapterCount = 64, incomingCount = 64))
	}

	@Test
	fun `no visible position uses first page and never goes negative`() {
		assertEquals(0, preferredCoverPrefetchStart(firstVisible = -1, adapterCount = 64, incomingCount = 64))
		assertEquals(0, preferredCoverPrefetchStart(firstVisible = 64, adapterCount = 64, incomingCount = 64))
		assertEquals(0, preferredCoverPrefetchStart(firstVisible = -1, adapterCount = 0, incomingCount = 0))
	}
}
