package org.koitharu.kotatsu.list.ui.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HistoryCoverOverlayProbePolicyTest {
	@Test fun nonHistoryAndReleaseBehaviorsAreUnchanged() {
		for (configured in listOf(false, true)) {
			for (modern in listOf(false, true)) {
				for (bypass in listOf(false, true)) {
					assertEquals(configured, resolveHistoryCoverOverlay(configured, modern, false, bypass))
				}
			}
		}
		val fragment = source("history/ui/HistoryListFragment.kt")
		val grid = source("list/ui/adapter/MangaGridItemAD.kt")
		assertTrue(fragment.contains("debugHistoryOverlayBypass = if (BuildConfig.DEBUG)"))
		assertTrue(grid.contains("val historyProbe = BuildConfig.DEBUG && debugHistoryOverlayBypass != null"))
		assertTrue(source("list/ui/adapter/MangaListAdapter.kt")
			.contains("debugHistoryOverlayBypass: (() -> Boolean)? = null"))
	}

	@Test fun onlyOptedInModernHistoryCanBeBypassed() {
		for (bypass in listOf(false, true)) {
			assertEquals(!bypass, resolveHistoryCoverOverlay(true, true, true, bypass))
			assertEquals(true, resolveHistoryCoverOverlay(true, false, true, bypass)) // Local cinematic host
			assertEquals(false, resolveHistoryCoverOverlay(false, false, true, bypass)) // Classic / legacy
			assertEquals(false, resolveHistoryCoverOverlay(false, true, true, bypass)) // Not configured
		}
	}

	@Test fun sequenceA_B_ARevertsWithoutPersistingBypass() {
		val toggles = listOf(false, true, false)
		assertEquals(listOf(true, false, true),
			toggles.map { resolveHistoryCoverOverlay(true, true, true, it) })
		assertEquals(listOf(true, true, true),
			toggles.map { resolveHistoryCoverOverlay(true, false, true, it) })
	}

	private fun source(path: String) = sequenceOf(
		File("src/main/kotlin/org/koitharu/kotatsu", path),
		File("app/src/main/kotlin/org/koitharu/kotatsu", path),
	).first(File::isFile).readText()
}
