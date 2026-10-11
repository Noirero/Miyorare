package org.koitharu.kotatsu.details.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.details.ui.pager.ChapterGroupingMode
import org.koitharu.kotatsu.details.ui.pager.ChapterListOptionsStore
import org.koitharu.kotatsu.details.ui.pager.ChapterSortMode
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import java.util.UUID

class ChapterGroupingPreferencesTest {
	private lateinit var context: Context
	private lateinit var prefs: SharedPreferences

	@Before fun isolatedPreferences() {
		val target = InstrumentationRegistry.getInstrumentation().targetContext
		prefs = target.getSharedPreferences("chapter_grouping_test_${UUID.randomUUID()}", Context.MODE_PRIVATE)
		context = object : ContextWrapper(target) {
			override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
		}
	}

	private fun store() = ChapterListOptionsStore(context, AppSettings(context))

	@Test fun groupingPersistsImmediatelyAcrossStoreRecreationAndOffAutomaticSwitches() {
		assertEquals(ChapterGroupingMode.AUTOMATIC, store().getDefault(FavouriteSpace.NORMAL, false).groupingMode)
		for (mode in ChapterGroupingMode.entries + ChapterGroupingMode.AUTOMATIC) {
			store().setGroupingMode(FavouriteSpace.NORMAL, false, mode)
			assertEquals(mode, store().getDefault(FavouriteSpace.NORMAL, false).groupingMode)
		}
	}

	@Test fun groupingDefaultsRemainIsolatedByContentAndSpace() {
		store().setGroupingMode(FavouriteSpace.NORMAL, false, ChapterGroupingMode.CHAPTERS_25)
		store().setGroupingMode(FavouriteSpace.PRIVATE, false, ChapterGroupingMode.OFF)
		assertEquals(ChapterGroupingMode.CHAPTERS_25, store().getDefault(FavouriteSpace.NORMAL, false).groupingMode)
		assertEquals(ChapterGroupingMode.OFF, store().getDefault(FavouriteSpace.PRIVATE, false).groupingMode)
		assertEquals(ChapterGroupingMode.AUTOMATIC, store().getDefault(FavouriteSpace.NORMAL, true).groupingMode)
		assertEquals(ChapterGroupingMode.AUTOMATIC, store().getDefault(FavouriteSpace.PRIVATE, true).groupingMode)
	}

	@Test fun existingV1OptionsSurviveUpgradeAndUnknownGroupingFallsBackSafely() {
		prefs.edit {
			putString("details_chapter_options_v1_manga_normal", "1|1|1|0|0|NUMBER|1|NUMBER|1")
			putString("details_chapter_options_v1_manga_normal_grouping", "future_mode")
		}
		val options = store().getDefault(FavouriteSpace.NORMAL, false)
		assertEquals(ChapterGroupingMode.AUTOMATIC, options.groupingMode)
		assertTrue(options.downloadedOnly && options.unreadOnly && options.descending && options.grid)
		assertEquals(ChapterSortMode.NUMBER, options.sortMode)
	}
}
