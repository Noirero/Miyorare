package org.koitharu.kotatsu.search.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.search.ui.multi.SearchSourceStatus
import org.koitharu.kotatsu.search.ui.multi.searchSourceStatus
import java.io.File

class GlobalSearchChromeRegressionTest {

	@Test fun sourceStatusFollowsLoadingAndErrorNotResultCount() {
		assertEquals(SearchSourceStatus.LOADING, searchSourceStatus(true, false))
		assertEquals(SearchSourceStatus.LOADING, searchSourceStatus(true, true))
		assertEquals(SearchSourceStatus.ERROR, searchSourceStatus(false, true))
		assertEquals(SearchSourceStatus.COMPLETE, searchSourceStatus(false, false))
	}

	@Test fun groupedResultsRetainOriginalHorizontalCoverRendererAndSizing() {
		val layout = resource("layout/item_list_group.xml")
		val delegate = source("search/ui/multi/adapter/SearchResultsAD.kt")
		assertTrue(layout.contains("""android:orientation="horizontal""""))
		val rail = layout.substringAfter("<androidx.recyclerview.widget.RecyclerView").substringBefore("/>")
		assertTrue(rail.contains("""android:layout_width="wrap_content""""))
		assertTrue(rail.contains("""android:orientation="horizontal""""))
		assertTrue(rail.contains("""android:paddingHorizontal="@dimen/grid_spacing""""))
		assertTrue(delegate.contains("mangaGridItemAD(sizeResolver, listener, coverContext = ModernCoverContext.BROWSING)"))
		assertTrue(delegate.contains("adapter.items = item.list"))
		assertTrue(delegate.contains("binding.recyclerView.isGone = item.list.isEmpty()"))
		assertTrue(source("search/ui/multi/SearchActivity.kt").contains("DynamicItemSizeResolver(resources, this, settings, adjustWidth = true)"))
	}

	@Test fun sourceIdentityAndLoadingFooterKeepExistingContracts() {
		val delegate = source("search/ui/multi/adapter/SearchResultsAD.kt")
		assertTrue(delegate.contains("item.source.name to item.titleResId"))
		assertTrue(delegate.indexOf("disposeImage()") in 0 until delegate.indexOf("setImageAsync(item.source)"))
		assertTrue(delegate.contains("binding.buttonMore.isVisible = isSource"))
		assertTrue(source("search/ui/multi/SearchViewModel.kt").contains("loading -> content + LoadingFooter()"))
		assertTrue(source("search/ui/multi/adapter/SearchAdapter.kt").contains("loadingFooterAD()"))
		assertFalse(resource("layout/activity_search.xml").contains("FloatingActionButton"))
		assertTrue(source("search/ui/multi/GlobalSearchChrome.kt").contains("miyorareViewPaletteFromPreferences()"))
	}

	@Test fun faviconRebindDelegatesRetryAndDeduplicationToCoil() {
		val delegate = source("search/ui/multi/adapter/SearchResultsAD.kt")
			.lineSequence()
			.filterNot { it.trimStart().startsWith("//") }
			.joinToString("")
			.replace(Regex("""\s+"""), "")
		// The identity guard must end before either image request. A same-source
		// rebind must reach Coil even if the preceding request failed or was cancelled.
		assertTrue(delegate.contains(
			"if(boundSourceKey!=sourceKey){binding.imageViewSourceIcon.disposeImage()boundSourceKey=sourceKey}" +
				"if(isSource){binding.imageViewSourceIcon.setImageAsync(item.source)}else{" +
				"binding.imageViewSourceIcon.setImageAsync(when(item.titleResId){",
		))
	}

	private fun resource(path: String) = read("src/main/res/$path")
	private fun source(path: String) = read("src/main/kotlin/org/koitharu/kotatsu/$path")
	private fun read(path: String) = sequenceOf(File(path), File("app", path))
		.firstOrNull(File::isFile)?.readText() ?: error("Missing $path")
}
