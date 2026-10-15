package org.koitharu.kotatsu.list.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the boundary between existing business authority and the scoped cover renderer. */
class FavouriteCoverWiringRegressionTest {
	@Test
	fun membershipIsProjectedByBothListOwnersAndNeverQueriedByCoverViews() {
		val favourites = source("favourites/ui/list/FavouritesListViewModel.kt")
		val local = source("favourites/ui/list/LocalFavouritesListViewModel.kt")
		for (mapping in listOf(favourites, local)) {
			assertTrue(mapping.contains("getMemberships(favouriteSpace)"))
			assertTrue(mapping.contains("isFavorite = if (usePermanentStatus) mangaId in libraryIds else model.isFavorite"))
			assertTrue(mapping.contains("unreadCount = unreadCount"))
			assertFalse(mapping.contains("favouriteSpace == FavouriteSpace.NORMAL && (mode == ListMode.GRID"))
		}
		assertTrue(favourites.contains("pinnedIds.value.toSet() else pinnedSet"))
		assertTrue(favourites.contains("isPinned = mangaId in persistedPins"))
		// Sorting still uses the existing conditional pins; display reads the persisted IDs separately.
		assertTrue(favourites.contains("pinned.takeIfDefaultState(categoryFilters)"))
		val cover = source("list/ui/FavouriteCoverOverlayView.kt")
		assertFalse(cover.contains("getMemberships"))
		assertFalse(cover.contains("getLanguageCode"))
		assertFalse(cover.contains("openReader"))
	}

	@Test
	fun legacyGeometryIsBypassedOnlyForTheExplicitModernOwner() {
		val indicators = source("list/ui/MangaIndicatorsView.kt")
		val replacement = indicators.substringAfter("if (useModernCoverOverlay && root?")
			.substringBefore("bind(isSaved, isLocalSource, isFavorite)")
		assertTrue(replacement.contains("ribbon.isVisible = false"))
		assertTrue(replacement.contains("return"))
		assertTrue(replacement.contains("start = 8, end = 8, bottom = 4, matchWidth = true"))
		assertTrue(replacement.contains("progress?.setModernBottomEdge(true)"))
		assertFalse(replacement.contains("if (useModernFavouritesPresentation"))
		assertFalse(replacement.contains("restoreLegacyGridPresentation()"))
		assertFalse(replacement.contains("translation"))
	}

	private fun source(path: String) = sequenceOf(
		File("src/main/kotlin/org/koitharu/kotatsu", path),
		File("app/src/main/kotlin/org/koitharu/kotatsu", path),
	).first(File::isFile).readText()
}
