package org.koitharu.kotatsu.explore.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExploreUiRestoreRegressionTest {

	@Test
	fun `suggestions are visible while pinned remains a dedicated non duplicate section`() {
		val viewModel = source("kotlin/org/koitharu/kotatsu/explore/ui/ExploreViewModel.kt")
			.replace(Regex("\\s+"), "")
		val adapter = source("kotlin/org/koitharu/kotatsu/explore/ui/adapter/ExploreAdapter.kt")
			.replace(Regex("\\s+"), "")
		val delegates = source("kotlin/org/koitharu/kotatsu/explore/ui/adapter/ExploreAdapterDelegates.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(viewModel.contains("getSuggestionFlow().map{recommendation->"))
		assertTrue(viewModel.contains("ListHeader(R.string.suggestions,R.string.more,R.id.nav_suggestions)"))
		assertTrue(viewModel.contains("RecommendationsItem(recommendation.toRecommendationList())"))

		assertTrue(adapter.contains("valpinned=filteredSources.filter{it.source.isPinned}"))
		assertTrue(adapter.contains("valsources=filteredSources.filterNot{it.source.isPinned}"))
		assertTrue(adapter.contains("appendPinnedSection(pinned)"))
		assertTrue(adapter.contains("ExplorePinnedHeaderPayload"))

		assertTrue(delegates.contains("isExplorePinnedHeaderPayload->listener?.onListHeaderClick(item,it)"))
		assertTrue(delegates.contains("binding.textViewTitle.drawableStart=if(item.source.isPinned)iconPinnedelsenull"))
		assertFalse(delegates.contains("binding.textViewTitle.drawableEnd=if(item.source.isPinned)"))
	}

	@Test
	fun `pre redesign explore dimensions are restored`() {
		val sourceRow = resource("layout/item_explore_source_list.xml")
		val sectionHeader = resource("layout/item_explore_suggestions_header.xml")
		val exploreHeader = resource("layout/layout_explore_header.xml")

		assertTrue(sourceRow.contains("android:minHeight=\"54dp\""))
		assertTrue(sourceRow.contains("android:layout_width=\"36dp\""))
		assertTrue(sectionHeader.contains("android:minHeight=\"42dp\""))
		assertFalse(sourceRow.contains("android:textSize=\"16sp\""))
		assertFalse(sectionHeader.contains("android:textSize=\"16sp\""))
		assertFalse(exploreHeader.contains("android:textSize=\"16sp\""))
	}

	private fun source(relativePath: String): String {
		return sequenceOf(
			File("src/main", relativePath),
			File("app/src/main", relativePath),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find production source: $relativePath")
	}

	private fun resource(relativePath: String): String {
		return sequenceOf(
			File("src/main/res", relativePath),
			File("app/src/main/res", relativePath),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find resource: $relativePath")
	}
}
