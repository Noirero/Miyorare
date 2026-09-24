package org.koitharu.kotatsu.readerjourney.domain

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-local Reader Profile preferences.
 *
 * This intentionally uses its own SharedPreferences file rather than AppSettings so display name,
 * selected Reader Title and showcase choices are not included in the generic settings backup/cloud
 * sync payload. The profile therefore works fully offline and never requires an account.
 */
@Singleton
class ReaderProfileStore @Inject constructor(
	@ApplicationContext context: Context,
) {

	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val _profile = MutableStateFlow(load())
	val profile: StateFlow<ReaderProfileSettings> = _profile.asStateFlow()

	fun update(
		displayName: String,
		selectedTitle: ReaderAchievementId?,
		showcase: List<ReaderAchievementId>,
	) {
		val safeName = displayName.trim().take(MAX_DISPLAY_NAME_LENGTH)
		val safeShowcase = showcase.distinct().take(MAX_SHOWCASE)
		val updated = ReaderProfileSettings(
			displayName = safeName,
			selectedTitle = selectedTitle,
			showcase = safeShowcase,
			cosmetics = _profile.value.cosmetics,
		)
		if (_profile.value == updated) return
		prefs.edit {
			putString(KEY_DISPLAY_NAME, updated.displayName)
			if (updated.selectedTitle == null) {
				remove(KEY_SELECTED_TITLE)
			} else {
				putString(KEY_SELECTED_TITLE, updated.selectedTitle.name)
			}
			putStringSet(KEY_SHOWCASE, updated.showcase.mapTo(LinkedHashSet()) { it.name })
		}
		_profile.value = updated
	}

	fun updateCosmetics(loadout: ReaderJourneyCosmeticLoadout) {
		val current = _profile.value
		if (current.cosmetics == loadout) return
		prefs.edit {
			putRank(KEY_COSMETIC_FRAME, loadout.frame)
			putRank(KEY_COSMETIC_GLOW, loadout.glow)
			putRank(KEY_COSMETIC_BACKGROUND, loadout.background)
			putRank(KEY_COSMETIC_PROGRESS, loadout.progressBar)
		}
		_profile.value = current.copy(cosmetics = loadout)
	}

	private fun androidx.core.content.SharedPreferences.Editor.putRank(key: String, rank: ReaderRank?) {
		if (rank == null) remove(key) else putString(key, rank.name)
	}

	private fun loadRank(key: String): ReaderRank? =
		prefs.getString(key, null)?.let { raw -> ReaderRank.entries.firstOrNull { it.name == raw } }

	private fun load(): ReaderProfileSettings {
		val selectedTitle = prefs.getString(KEY_SELECTED_TITLE, null)
			?.let { raw -> ReaderAchievementId.entries.firstOrNull { it.name == raw } }
		val showcaseNames = prefs.getStringSet(KEY_SHOWCASE, emptySet()).orEmpty()
		val showcase = ReaderAchievementId.entries.filter { it.name in showcaseNames }.take(MAX_SHOWCASE)
		return ReaderProfileSettings(
			displayName = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty().trim().take(MAX_DISPLAY_NAME_LENGTH),
			selectedTitle = selectedTitle,
			showcase = showcase,
			cosmetics = ReaderJourneyCosmeticLoadout(
				frame = loadRank(KEY_COSMETIC_FRAME),
				glow = loadRank(KEY_COSMETIC_GLOW),
				background = loadRank(KEY_COSMETIC_BACKGROUND),
				progressBar = loadRank(KEY_COSMETIC_PROGRESS),
			),
		)
	}

	private companion object {
		const val PREFS_NAME = "reader_journey_profile"
		const val KEY_DISPLAY_NAME = "display_name"
		const val KEY_SELECTED_TITLE = "selected_title"
		const val KEY_SHOWCASE = "showcase"
		const val KEY_COSMETIC_FRAME = "cosmetic_frame"
		const val KEY_COSMETIC_GLOW = "cosmetic_glow"
		const val KEY_COSMETIC_BACKGROUND = "cosmetic_background"
		const val KEY_COSMETIC_PROGRESS = "cosmetic_progress"
		const val MAX_DISPLAY_NAME_LENGTH = 40
		const val MAX_SHOWCASE = 3
	}
}
