package org.koitharu.kotatsu.list.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.list.ui.model.ModernCoverContext

class ModernCoverBoundaryRegressionTest {
	@Test fun overlayAndBorderHaveIndependentHostPolicies() {
		for (style in MiyorareDesignStyle.entries) for (host in ModernCoverContext.entries) {
			assertEquals(style == MiyorareDesignStyle.MODERN && host != ModernCoverContext.LEGACY, host.usesOverlay(style))
			assertEquals(style == MiyorareDesignStyle.MODERN && host == ModernCoverContext.COLLECTION, host.usesCollectionBorder(style))
		}
	}

	@Test fun browsingOptInIsExplicitAndPickerAndListDelegatesStayLegacy() {
		val adapter = source("list/ui/adapter/MangaListAdapter.kt")
		val grid = source("list/ui/adapter/MangaGridItemAD.kt")
		assertTrue(grid.contains("binding.favouriteCoverOverlays.isVisible = usesModernCover"))
		assertTrue(grid.contains("coverContext.usesCollectionBorder(designStyle)"))
		assertFalse(grid.contains("isVisible = isNormalModernFavourites"))
		assertTrue(adapter.contains("coverContext: ModernCoverContext = ModernCoverContext.LEGACY"))
		assertTrue(adapter.contains("mangaListItemAD(listener, titleClickListener)"))
		assertTrue(adapter.contains("mangaListDetailedItemAD(listener, titleClickListener)"))
		assertTrue(source("picker/ui/manga/MangaPickerFragment.kt").contains("coverContext = ModernCoverContext.LEGACY"))
		for (path in listOf("list/ui/MangaListFragment.kt", "history/ui/HistoryListAdapter.kt",
			"search/ui/multi/adapter/SearchAdapter.kt", "search/ui/multi/adapter/SearchResultsAD.kt")) {
			assertTrue(path, source(path).contains("ModernCoverContext.BROWSING"))
		}
		for (path in listOf("favourites/ui/list/FavouritesListFragment.kt", "favourites/ui/list/LocalFavouritesListFragment.kt")) {
			assertTrue(path, source(path).contains("coverContext = ModernCoverContext.COLLECTION"))
		}
	}

	@Test fun stateProjectionCannotInventUnreadPinOrContinueAuthority() {
		val state = source("list/ui/model/ModernCoverState.kt")
		assertTrue(state.contains("unreadCount = model.unreadCount"))
		assertTrue(state.contains("legacyCounter = if (collection) 0 else model.counter"))
		assertTrue(state.contains("isPinned = collection && model.isPinned"))
		assertTrue(state.contains("showContinueReading = collection && model.showContinueReading"))
		assertTrue(state.contains("model.source.isLocal"))
		assertTrue(state.contains("model.source.getLanguageCode()"))
		val mapper = source("list/domain/MangaListMapper.kt")
		assertFalse(mapper.contains("unreadCount ="))
		assertTrue(mapper.contains("trackingRepository.getNewChaptersCount(mangaId)"))
		val cover = source("list/ui/FavouriteCoverOverlayView.kt")
		for (forbidden in listOf("AppSettings", "Repository", "getLanguageCode", "getPinned", "getSnapshot")) {
			assertFalse(forbidden, cover.contains(forbidden))
		}
	}

	private fun source(path: String) = sequenceOf(
		File("src/main/kotlin/org/koitharu/kotatsu", path),
		File("app/src/main/kotlin/org/koitharu/kotatsu", path),
	).first(File::isFile).readText()
}
