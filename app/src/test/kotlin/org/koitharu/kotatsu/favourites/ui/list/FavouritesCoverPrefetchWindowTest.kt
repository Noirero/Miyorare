package org.koitharu.kotatsu.favourites.ui.list

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.list.ui.model.ListModel

class FavouritesCoverPrefetchWindowTest {

	@Test
	fun `newly selected Set prefetches from the start while its adapter is stale`() {
		val previous = items(0, 256)
		val incoming = items(1000, 64)
		assertEquals(0, preferredCoverPrefetchStart(firstVisible = 125, adapterItems = previous, incomingItems = incoming))
		assertEquals(0, preferredCoverPrefetchStart(firstVisible = 30, adapterItems = items(0, 64), incomingItems = items(1000, 48)))
	}

	@Test
	fun `equal item counts are not enough to reuse an old Set scroll position`() {
		val previous = items(0, 64)
		val incoming = items(1000, 64)
		assertEquals(0, preferredCoverPrefetchStart(firstVisible = 30, adapterItems = previous, incomingItems = incoming))
		// Once the new adapter snapshot is committed, the visible anchor is valid again.
		assertEquals(30, preferredCoverPrefetchStart(firstVisible = 30, adapterItems = incoming.toList(), incomingItems = incoming))
	}

	@Test
	fun `stable viewport prefetch follows scrolling rather than the last page`() {
		val snapshot = items(0, 64)
		assertEquals(12, preferredCoverPrefetchStart(firstVisible = 12, adapterItems = snapshot, incomingItems = snapshot))
		assertEquals(45, preferredCoverPrefetchStart(firstVisible = 45, adapterItems = snapshot, incomingItems = snapshot))
	}

	@Test
	fun `no visible position or adapter snapshot uses the first page`() {
		val snapshot = items(0, 64)
		assertEquals(0, preferredCoverPrefetchStart(firstVisible = -1, adapterItems = snapshot, incomingItems = snapshot))
		assertEquals(0, preferredCoverPrefetchStart(firstVisible = 64, adapterItems = snapshot, incomingItems = snapshot))
		assertEquals(0, preferredCoverPrefetchStart(firstVisible = -1, adapterItems = emptyList(), incomingItems = emptyList()))
		assertEquals(0, preferredCoverPrefetchStart(firstVisible = 8, adapterItems = null, incomingItems = snapshot))
	}

	private fun items(start: Int, count: Int): List<ListModel> =
		(start until start + count).map(::StubItem)

	private data class StubItem(val id: Int) : ListModel {
		override fun areItemsTheSame(other: ListModel): Boolean = other is StubItem && id == other.id
	}
}
