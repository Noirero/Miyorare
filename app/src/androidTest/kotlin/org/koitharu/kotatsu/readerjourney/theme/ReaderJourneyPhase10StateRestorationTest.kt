package org.koitharu.kotatsu.readerjourney.theme

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticPolicy
import org.koitharu.kotatsu.readerjourney.domain.ReaderProfileStore
import org.koitharu.kotatsu.readerjourney.domain.ReaderRank

/**
 * Phase 10 Android persistence probe.
 *
 * Re-instantiating ReaderProfileStore from SharedPreferences models the state boundary that matters
 * after process recreation: only one encoded CosmeticLoadoutV2 snapshot is restored, and corrupt
 * snapshots fail closed.
 */
@RunWith(AndroidJUnit4::class)
class ReaderJourneyPhase10StateRestorationTest {

	private val context: Context
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	@Before
	fun setUp() {
		clearProfilePrefs()
	}

	@After
	fun tearDown() {
		clearProfilePrefs()
	}

	@Test
	fun fullSetSnapshotSurvivesStoreRecreationWithoutHalfAppliedState() {
		val initial = ReaderProfileStore(context)
		val expected = ReaderJourneyCosmeticPolicy.equipFullSet(
			loadout = ReaderJourneyCosmeticLoadout(),
			theme = RankThemeId.NEON_ARCHIVE,
			currentRank = ReaderRank.LEGEND,
		).copy(autoEquipNewRankTheme = true)

		initial.updateCosmetics(expected)

		val recreated = ReaderProfileStore(context)
		assertEquals(expected, recreated.profile.value.cosmetics)
		assertEquals(ReaderJourneyCosmeticMode.FULL_SET, recreated.profile.value.cosmetics.mode)
		assertEquals(RankThemeId.NEON_ARCHIVE.stableId, recreated.profile.value.cosmetics.selectedThemeId)

		val visual = requireNotNull(RankThemeVisualRegistry.resolve(RankThemeId.NEON_ARCHIVE))
		assertEquals(visual.badgeId, recreated.profile.value.cosmetics.selectedBadgeId)
		assertEquals(visual.wallpaperId, recreated.profile.value.cosmetics.selectedWallpaperId)
		assertEquals(visual.cardId, recreated.profile.value.cosmetics.selectedReaderCardId)
		assertEquals(visual.progressId, recreated.profile.value.cosmetics.selectedProgressStyleId)
	}

	@Test
	fun corruptOrOldSnapshotFailsClosedOnRecreation() {
		context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
			.edit()
			.putString(COSMETIC_SNAPSHOT_KEY, "v=999|mode=FULL_SET|theme=LEGEND_ETERNAL_LIBRARY")
			.commit()

		val recreated = ReaderProfileStore(context)
		val loadout = recreated.profile.value.cosmetics

		assertEquals(ReaderJourneyCosmeticMode.AUTO, loadout.mode)
		assertNull(loadout.selectedThemeId)
		assertNull(loadout.selectedBadgeId)
		assertNull(loadout.selectedWallpaperId)
		assertNull(loadout.selectedReaderCardId)
		assertNull(loadout.selectedProgressStyleId)
	}

	private fun clearProfilePrefs() {
		context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
			.edit()
			.clear()
			.commit()
	}

	private companion object {
		const val PROFILE_PREFS = "reader_journey_profile"
		const val COSMETIC_SNAPSHOT_KEY = "cosmetic_loadout_v2"
	}
}
