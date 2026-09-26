package org.koitharu.kotatsu.readerjourney.theme

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderProfileStore
import javax.inject.Inject
import javax.inject.Singleton

data class ReaderJourneyThemeRuntimeState(
	val loadout: ReaderJourneyCosmeticLoadout = ReaderJourneyCosmeticLoadout(),
	val lifetimeXp: Long = 0L,
	val ledgerReady: Boolean = false,
) {
	fun resolveExclusiveTheme(
		explicitCustomAppearance: Boolean,
		darkTheme: Boolean,
		amoled: Boolean,
		dynamicColorEnabled: Boolean = false,
	): ResolvedExclusiveTheme? {
		if (!ledgerReady) return null
		val resolution = ReaderJourneyThemePresentationResolver.resolve(
			ReaderJourneyThemePresentationRequest(
				loadout = loadout,
				lifetimeXp = lifetimeXp,
				explicitCustomAppearance = explicitCustomAppearance,
				dynamicColorEnabled = dynamicColorEnabled,
			),
		)
		val theme = resolution.theme ?: return null
		val variant = when {
			darkTheme && amoled -> RankThemeVariant.OLED
			darkTheme -> RankThemeVariant.DARK
			else -> RankThemeVariant.LIGHT
		}
		return ExclusiveThemeContractResolver.resolve(
			definition = RankThemeRegistry.resolveOrDefault(theme.stableId),
			variant = variant,
		)
	}

	fun resolveTokens(
		explicitCustomAppearance: Boolean,
		darkTheme: Boolean,
		amoled: Boolean,
		dynamicColorEnabled: Boolean = false,
	): RankThemeTokens? = resolveExclusiveTheme(
		explicitCustomAppearance = explicitCustomAppearance,
		darkTheme = darkTheme,
		amoled = amoled,
		dynamicColorEnabled = dynamicColorEnabled,
	)?.tokens
}

/**
 * Shared local-first presentation state for Compose and legacy Android Views.
 *
 * Reader rank is observed from the Room ledger cache. Cosmetic intent is observed from the atomic
 * ReaderProfileStore snapshot. Neither source stores a second copy of rank ownership.
 */
@Singleton
class ReaderJourneyThemeRuntime @Inject constructor(
	database: MangaDatabase,
	profileStore: ReaderProfileStore,
) {
	private val dao = database.getReaderJourneyDao()

	val state: StateFlow<ReaderJourneyThemeRuntimeState> = combine(
		profileStore.profile,
		dao.observeProfile(),
	) { profile, journey ->
		ReaderJourneyThemeRuntimeState(
			loadout = profile.cosmetics,
			lifetimeXp = journey?.totalXp ?: 0L,
			ledgerReady = true,
		)
	}
		.distinctUntilChanged()
		.stateIn(
			scope = processLifecycleScope,
			started = SharingStarted.Eagerly,
			initialValue = ReaderJourneyThemeRuntimeState(
				loadout = profileStore.profile.value.cosmetics,
				lifetimeXp = 0L,
			),
		)
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReaderJourneyThemeEntryPoint {
	val readerJourneyThemeRuntime: ReaderJourneyThemeRuntime
}

fun Context.readerJourneyThemeRuntimeOrNull(): ReaderJourneyThemeRuntime? =
	runCatching {
		EntryPointAccessors.fromApplication<ReaderJourneyThemeEntryPoint>(
			applicationContext,
		).readerJourneyThemeRuntime
	}.getOrNull()
