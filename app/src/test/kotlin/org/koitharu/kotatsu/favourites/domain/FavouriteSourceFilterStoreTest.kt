package org.koitharu.kotatsu.favourites.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouriteSourceFilterStoreTest {

	@Test
	fun `source selection is shared across category pages`() {
		val store = FavouriteSourceFilterStore()
		store.set(FavouriteContentType.MANGA, "MIHON_123", true)

		assertEquals(setOf("MIHON_123"), store.state.value[FavouriteContentType.MANGA])
	}

	@Test
	fun `manga and novel source selections stay independent`() {
		val store = FavouriteSourceFilterStore()
		store.set(FavouriteContentType.MANGA, "MIHON_123", true)
		store.set(FavouriteContentType.NOVEL, "MIHON_456", true)
		store.clear(FavouriteContentType.MANGA)

		assertTrue(store.state.value[FavouriteContentType.MANGA].isNullOrEmpty())
		assertEquals(setOf("MIHON_456"), store.state.value[FavouriteContentType.NOVEL])
	}
}
