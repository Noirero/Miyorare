package org.koitharu.kotatsu.readerjourney.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode
import javax.inject.Inject
import javax.inject.Singleton

enum class ExclusiveThemeQaComponent {
	NAVIGATION,
	PROFILE_FRAME,
	BADGE,
	NAMEPLATE,
	WALLPAPER,
	ACCENT_GLOW,
}

enum class ExclusiveThemeQaMotionOverride {
	SYSTEM,
	FORCE_ON,
	FORCE_OFF,
}

enum class ExclusiveThemeQaRewardState {
	NORMAL,
	LOCKED,
	UNLOCKED,
	PREVIEWING,
	EQUIPPED,
}

/**
 * Beta/debug-only Reader Journey presentation override.
 *
 * This state is deliberately separate from ReaderProfileStore and the Reader Journey ledger.
 * It may change what is rendered, but it never grants ownership or writes XP/rank/unlock state.
 */
data class ExclusiveThemeQaState(
	val enabled: Boolean = false,
	val selectedThemeId: String = RankThemeId.FIRST_PAGE.stableId,
	val fullTheme: Boolean = true,
	val components: Set<ExclusiveThemeQaComponent> = emptySet(),
	val reduceMotion: ExclusiveThemeQaMotionOverride = ExclusiveThemeQaMotionOverride.SYSTEM,
	val batterySaver: ExclusiveThemeQaMotionOverride = ExclusiveThemeQaMotionOverride.SYSTEM,
	val rewardState: ExclusiveThemeQaRewardState = ExclusiveThemeQaRewardState.NORMAL,
) {
	val isActive: Boolean
		get() = BuildConfig.EXCLUSIVE_THEME_QA_ENABLED && enabled

	val selectedTheme: RankThemeId
		get() = RankThemeId.fromStableId(selectedThemeId) ?: RankThemeId.FIRST_PAGE

	fun effectiveLoadout(
		production: ReaderJourneyCosmeticLoadout,
		fallbackTheme: RankThemeId,
	): ReaderJourneyCosmeticLoadout {
		if (!isActive) return production

		val theme = selectedTheme
		val visual = RankThemeVisualRegistry.resolve(theme) ?: return production
		if (fullTheme) {
			return production.copy(
				mode = ReaderJourneyCosmeticMode.FULL_SET,
				selectedThemeId = theme.stableId,
				navigationThemeId = null,
				accentThemeId = null,
				glowThemeId = null,
				selectedBadgeId = visual.badgeId,
				selectedWallpaperId = visual.wallpaperId,
				selectedFrameId = visual.frameId,
				selectedNameplateId = visual.nameplateId,
				selectedReaderCardId = visual.cardId,
				selectedProgressStyleId = visual.progressId,
				frame = theme.rank,
				glow = theme.rank,
				background = theme.rank,
				progressBar = theme.rank,
			)
		}

		val foundation = RankThemeId.fromStableId(production.selectedThemeId) ?: fallbackTheme
		return production.copy(
			mode = ReaderJourneyCosmeticMode.CUSTOM,
			selectedThemeId = foundation.stableId,
			navigationThemeId = if (ExclusiveThemeQaComponent.NAVIGATION in components) {
				theme.stableId
			} else {
				production.navigationThemeId
			},
			accentThemeId = if (ExclusiveThemeQaComponent.ACCENT_GLOW in components) {
				theme.stableId
			} else {
				production.accentThemeId
			},
			glowThemeId = if (ExclusiveThemeQaComponent.ACCENT_GLOW in components) {
				theme.stableId
			} else {
				production.glowThemeId
			},
			selectedBadgeId = if (ExclusiveThemeQaComponent.BADGE in components) {
				visual.badgeId
			} else {
				production.selectedBadgeId
			},
			selectedWallpaperId = if (ExclusiveThemeQaComponent.WALLPAPER in components) {
				visual.wallpaperId
			} else {
				production.selectedWallpaperId
			},
			selectedFrameId = if (ExclusiveThemeQaComponent.PROFILE_FRAME in components) {
				visual.frameId
			} else {
				production.selectedFrameId
			},
			selectedNameplateId = if (ExclusiveThemeQaComponent.NAMEPLATE in components) {
				visual.nameplateId
			} else {
				production.selectedNameplateId
			},
			selectedReaderCardId = if (ExclusiveThemeQaComponent.NAMEPLATE in components) {
				visual.cardId
			} else {
				production.selectedReaderCardId
			},
			frame = if (ExclusiveThemeQaComponent.PROFILE_FRAME in components) theme.rank else production.frame,
			glow = if (ExclusiveThemeQaComponent.ACCENT_GLOW in components) theme.rank else production.glow,
			background = if (ExclusiveThemeQaComponent.WALLPAPER in components) theme.rank else production.background,
		)
	}

	fun effectiveReduceMotion(systemValue: Boolean): Boolean =
		if (!isActive) systemValue else when (reduceMotion) {
			ExclusiveThemeQaMotionOverride.SYSTEM -> systemValue
			ExclusiveThemeQaMotionOverride.FORCE_ON -> true
			ExclusiveThemeQaMotionOverride.FORCE_OFF -> false
		}

	fun effectiveBatterySaver(systemValue: Boolean): Boolean =
		if (!isActive) systemValue else when (batterySaver) {
			ExclusiveThemeQaMotionOverride.SYSTEM -> systemValue
			ExclusiveThemeQaMotionOverride.FORCE_ON -> true
			ExclusiveThemeQaMotionOverride.FORCE_OFF -> false
		}
}

/**
 * Process-visible read-only bridge used by renderers that are not Hilt entry points.
 * The only writer is [ExclusiveThemeQaStore].
 */
object ExclusiveThemeQaRuntime {
	private val mutableState = MutableStateFlow(ExclusiveThemeQaState())
	val state: StateFlow<ExclusiveThemeQaState> = mutableState.asStateFlow()

	internal fun publish(state: ExclusiveThemeQaState) {
		mutableState.value = if (BuildConfig.EXCLUSIVE_THEME_QA_ENABLED) state else ExclusiveThemeQaState()
	}
}

@Singleton
class ExclusiveThemeQaStore @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val mutableState = MutableStateFlow(load())
	val state: StateFlow<ExclusiveThemeQaState> = mutableState.asStateFlow()

	init {
		ExclusiveThemeQaRuntime.publish(mutableState.value)
	}

	fun setEnabled(value: Boolean) = update { it.copy(enabled = value) }

	fun setTheme(theme: RankThemeId) = update { it.copy(selectedThemeId = theme.stableId) }

	fun setFullTheme(value: Boolean) = update { it.copy(fullTheme = value) }

	fun setComponent(component: ExclusiveThemeQaComponent, enabled: Boolean) = update { current ->
		current.copy(
			components = if (enabled) current.components + component else current.components - component,
		)
	}

	fun setReduceMotion(value: ExclusiveThemeQaMotionOverride) = update { it.copy(reduceMotion = value) }

	fun setBatterySaver(value: ExclusiveThemeQaMotionOverride) = update { it.copy(batterySaver = value) }

	fun setRewardState(value: ExclusiveThemeQaRewardState) = update { it.copy(rewardState = value) }

	fun reset() {
		if (!BuildConfig.EXCLUSIVE_THEME_QA_ENABLED) return
		prefs.edit().clear().apply()
		publish(ExclusiveThemeQaState())
	}

	private fun update(transform: (ExclusiveThemeQaState) -> ExclusiveThemeQaState) {
		if (!BuildConfig.EXCLUSIVE_THEME_QA_ENABLED) return
		val next = transform(mutableState.value)
		save(next)
		publish(next)
	}

	private fun publish(next: ExclusiveThemeQaState) {
		mutableState.value = next
		ExclusiveThemeQaRuntime.publish(next)
	}

	private fun load(): ExclusiveThemeQaState {
		if (!BuildConfig.EXCLUSIVE_THEME_QA_ENABLED) return ExclusiveThemeQaState()
		val components = prefs.getStringSet(KEY_COMPONENTS, emptySet())
			.orEmpty()
			.mapNotNull { raw -> ExclusiveThemeQaComponent.entries.firstOrNull { it.name == raw } }
			.toSet()
		return ExclusiveThemeQaState(
			enabled = prefs.getBoolean(KEY_ENABLED, false),
			selectedThemeId = prefs.getString(KEY_THEME, RankThemeId.FIRST_PAGE.stableId)
				?: RankThemeId.FIRST_PAGE.stableId,
			fullTheme = prefs.getBoolean(KEY_FULL_THEME, true),
			components = components,
			reduceMotion = enumValue(
				prefs.getString(KEY_REDUCE_MOTION, null),
				ExclusiveThemeQaMotionOverride.SYSTEM,
			),
			batterySaver = enumValue(
				prefs.getString(KEY_BATTERY_SAVER, null),
				ExclusiveThemeQaMotionOverride.SYSTEM,
			),
			rewardState = enumValue(
				prefs.getString(KEY_REWARD_STATE, null),
				ExclusiveThemeQaRewardState.NORMAL,
			),
		)
	}

	private fun save(state: ExclusiveThemeQaState) {
		prefs.edit()
			.putBoolean(KEY_ENABLED, state.enabled)
			.putString(KEY_THEME, state.selectedThemeId)
			.putBoolean(KEY_FULL_THEME, state.fullTheme)
			.putStringSet(KEY_COMPONENTS, state.components.mapTo(mutableSetOf()) { it.name })
			.putString(KEY_REDUCE_MOTION, state.reduceMotion.name)
			.putString(KEY_BATTERY_SAVER, state.batterySaver.name)
			.putString(KEY_REWARD_STATE, state.rewardState.name)
			.apply()
	}

	private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T =
		enumValues<T>().firstOrNull { it.name == raw } ?: fallback

	private companion object {
		const val PREFS_NAME = "exclusive_theme_qa"
		const val KEY_ENABLED = "enabled"
		const val KEY_THEME = "theme"
		const val KEY_FULL_THEME = "full_theme"
		const val KEY_COMPONENTS = "components"
		const val KEY_REDUCE_MOTION = "reduce_motion"
		const val KEY_BATTERY_SAVER = "battery_saver"
		const val KEY_REWARD_STATE = "reward_state"
	}
}
