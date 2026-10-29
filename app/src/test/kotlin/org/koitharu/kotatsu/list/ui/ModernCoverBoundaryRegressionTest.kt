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
		assertTrue(grid.contains("binding.favouriteCoverOverlays.isVisible = usesCoverOverlay"))
		assertTrue(grid.contains("coverContext.usesCollectionBorder(designStyle)"))
		assertFalse(grid.contains("isVisible = isNormalModernFavourites"))
		assertTrue(adapter.contains("coverContext: ModernCoverContext = ModernCoverContext.LEGACY"))
		assertTrue(adapter.contains("mangaListItemAD(listener, titleClickListener, coverContext)"))
		assertTrue(adapter.contains("mangaListDetailedItemAD(listener, titleClickListener, coverContext)"))
		assertTrue(source("picker/ui/manga/MangaPickerFragment.kt").contains("coverContext = ModernCoverContext.LEGACY"))
		val picker = source("picker/ui/manga/MangaPickerViewModel.kt")
		assertTrue(picker.contains("flags = MangaListMapper.NO_MODERN_READING_STATE"))
		for (path in listOf("list/ui/MangaListFragment.kt", "history/ui/HistoryListAdapter.kt",
			"search/ui/multi/adapter/SearchAdapter.kt", "search/ui/multi/adapter/SearchResultsAD.kt")) {
			assertTrue(path, source(path).contains("ModernCoverContext.BROWSING"))
		}
		for (path in listOf("favourites/ui/list/FavouritesListFragment.kt", "favourites/ui/list/LocalFavouritesListFragment.kt")) {
			assertTrue(path, source(path).contains("coverContext = ModernCoverContext.COLLECTION"))
		}
	}

	@Test fun stateProjectionUsesExplicitUnreadAuthorityWithoutReusingTrackerCounter() {
		val state = source("list/ui/model/ModernCoverState.kt")
		assertTrue(state.contains("unreadCount = model.unreadCount"))
		assertTrue(state.contains("legacyCounter = if (collection) 0 else model.counter"))
		assertTrue(state.contains("isPinned = collection && model.isPinned"))
		assertTrue(state.contains("showContinueReading = collection && model.showContinueReading"))
		assertTrue(state.contains("model.source.isLocal"))
		assertTrue(state.contains("model.source.getLanguageCode()"))
		val mapper = source("list/domain/MangaListMapper.kt")
		assertTrue(mapper.contains("favouriteUnreadCounter.getSnapshot"))
		assertTrue(mapper.contains("unreadCount = unreadSnapshot?.getUnreadCountOrNull(it.id)"))
		assertTrue(mapper.contains("flags and NO_MODERN_READING_STATE != 0"))
		assertFalse(mapper.contains("unreadCount = getCounter"))
		assertFalse(mapper.contains("unreadCount = trackingRepository"))
		assertTrue(mapper.contains("trackingRepository.getNewChaptersCount(mangaId)"))
		val unread = source("favourites/domain/FavouriteUnreadCounter.kt")
		assertTrue(unread.contains("fun getUnreadCountOrNull(mangaId: Long): Int?"))
		assertTrue(unread.contains("hasUnreadAuthority = mangaId in unreadAuthority"))
		assertFalse(unread.contains("UNKNOWN_UNREAD_COUNT"))
		val cover = source("list/ui/FavouriteCoverOverlayView.kt")
		for (forbidden in listOf("AppSettings", "Repository", "getLanguageCode", "getPinned", "getSnapshot")) {
			assertFalse(forbidden, cover.contains(forbidden))
		}
	}

	@Test fun modernAppearanceDoesNotDependOnCollectionBorderOrRestoreLegacyProgress() {
		val grid = source("list/ui/adapter/MangaGridItemAD.kt")
		assertTrue(grid.contains("applyGridAppearance(usesModernCover, visualScale)"))
		assertTrue(grid.contains("applyGridAppearance(usesModernCover, currentScale)"))
		assertFalse(grid.contains("applyGridAppearance(hasCollectionBorder"))
		assertTrue(grid.contains("if (usesModernCover && coverHeight > 0)"))
		assertTrue(grid.contains("binding.textViewTitle.setTextColor(if (normalNeon) normalGlass!!.content else onSurface)"))
		assertTrue(grid.contains("if (!usesCoverOverlay && (item.isSaved || item.isFavorite)"))
		val cover = source("list/ui/FavouriteCoverOverlayView.kt")
		assertFalse(cover.contains("preservedProgress"))
		assertTrue(cover.contains("h - px(FavouriteCoverOverlayMetrics.PROGRESS_RESERVED_DP) - gap"))
		assertTrue(cover.contains("chapterCounter.background = shape(R.color.favourite_cover_protection"))
		assertTrue(cover.contains("counterNumber.setTextSize(TypedValue.COMPLEX_UNIT_SP, metrics.badgeSp)"))
		assertTrue(cover.contains("context.getString(R.string.chapters)"))
	}

	@Test fun cinematicLocalRestoresRealModernCoverStatusesWithoutChangingCardGeometry() {
		val grid = source("list/ui/adapter/MangaGridItemAD.kt")
		val local = source("local/ui/LocalListFragment.kt")
		assertTrue(local.contains("localCinematic = true"))
		assertTrue(grid.contains("val usesCoverOverlay = usesModernCover || localCinematic"))
		assertTrue(grid.contains("binding.favouriteCoverOverlays.isVisible = usesCoverOverlay"))
		assertTrue(grid.contains("if (localCinematic) it.copy(legacyCounter = item.counter) else it"))
		assertTrue(grid.contains("useModernCoverOverlay = usesCoverOverlay"))
		assertTrue(grid.contains("applyGridAppearance(usesModernCover, visualScale)"))
		assertTrue(grid.contains("if (localCinematic) {"))
		assertTrue(grid.contains("R.drawable.bg_smart_local_cinematic_selectable"))
		val overlay = source("list/ui/FavouriteCoverOverlayView.kt")
		assertTrue(overlay.contains("local.isVisible = value.isLocalSource"))
		assertTrue(overlay.contains("chapterCounter.isVisible = value.legacyCounter > 0"))
		assertTrue(overlay.contains("statusItems[index].isVisible = active"))
		assertTrue(overlay.contains("action.setOnClickListener"))
	}

	private fun source(path: String) = sequenceOf(
		File("src/main/kotlin/org/koitharu/kotatsu", path),
		File("app/src/main/kotlin/org/koitharu/kotatsu", path),
	).first(File::isFile).readText()
}
