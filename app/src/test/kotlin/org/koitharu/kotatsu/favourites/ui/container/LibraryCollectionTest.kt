package org.koitharu.kotatsu.favourites.ui.container

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID

class LibraryCollectionTest {
	private val tabs = listOf(
		FavouriteTabModel(0L, null, 3),
		FavouriteTabModel(DOWNLOADED_FAVOURITES_CATEGORY_ID, "Downloaded", 2),
		FavouriteTabModel(LOCAL_FAVOURITES_CATEGORY_ID, "Local", 1),
		FavouriteTabModel(12L, "Reading", 2),
		FavouriteTabModel(13L, "Completed", 1),
	)

	@Test
	fun `favourites retains all ordinary categories and their counts`() {
		assertEquals(listOf(tabs[0], tabs[3], tabs[4]), LibraryCollection.FAVOURITES.categories(tabs))
	}

	@Test
	fun `downloads and local expose one independent page even with no items`() {
		assertEquals(listOf(tabs[1].copy(count = 0)), LibraryCollection.DOWNLOADED.categories(tabs.map { it.copy(count = 0) }))
		assertEquals(listOf(tabs[2].copy(count = 0)), LibraryCollection.LOCAL.categories(tabs.map { it.copy(count = 0) }))
	}

	@Test
	fun `switching collections does not mutate category identity or order`() {
		val original = tabs.toList()
		LibraryCollection.DOWNLOADED.categories(tabs)
		LibraryCollection.LOCAL.categories(tabs)
		assertEquals(original, tabs)
		assertEquals(listOf(0L, 12L, 13L), LibraryCollection.FAVOURITES.categories(tabs).map { it.id })
	}
}
