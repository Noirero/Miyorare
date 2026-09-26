package org.koitharu.kotatsu.readerjourney.theme

import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode

/**
 * Central mixer for CUSTOM Exclusive Theme loadouts.
 *
 * Foundation owns layout-neutral surfaces/text. Navigation may come from an entirely different
 * theme. Accent and glow are merged into every non-navigation semantic component centrally so
 * screens never branch on rank identity or rebuild colours themselves.
 */
object ExclusiveThemeMixerResolver {

	fun resolve(
		foundationTheme: RankThemeId,
		loadout: ReaderJourneyCosmeticLoadout,
		variant: RankThemeVariant,
	): ResolvedExclusiveTheme {
		val foundation = resolveTheme(foundationTheme, variant)
		if (loadout.mode != ReaderJourneyCosmeticMode.CUSTOM) return foundation

		val wallpaperTheme = RankThemeVisualRegistry.all
			.firstOrNull { it.wallpaperId == loadout.selectedWallpaperId }
			?.themeId
			?: foundationTheme
		val wallpaper = resolveTheme(wallpaperTheme, variant)
		val navigation = resolveTheme(
			RankThemeId.fromStableId(loadout.navigationThemeId) ?: foundationTheme,
			variant,
		)
		val accent = resolveTheme(
			RankThemeId.fromStableId(loadout.accentThemeId) ?: foundationTheme,
			variant,
		)
		val glow = resolveTheme(
			RankThemeId.fromStableId(loadout.glowThemeId) ?: foundationTheme,
			variant,
		)

		return foundation.copy(
			tokens = mixTokens(
				foundation = foundation.tokens,
				wallpaper = wallpaper.tokens,
				accent = accent.tokens,
				glow = glow.tokens,
			),
			navigation = navigation.navigation,
			shared = mixComponent(foundation.shared, accent.shared, glow.shared),
			favourites = mixComponent(foundation.favourites, accent.favourites, glow.favourites),
			settings = mixComponent(foundation.settings, accent.settings, glow.settings),
			details = mixComponent(foundation.details, accent.details, glow.details),
		)
	}

	private fun resolveTheme(
		id: RankThemeId,
		variant: RankThemeVariant,
	): ResolvedExclusiveTheme = ExclusiveThemeContractResolver.resolve(
		definition = RankThemeRegistry.resolveOrDefault(id.stableId),
		variant = variant,
	)

	private fun mixTokens(
		foundation: RankThemeTokens,
		wallpaper: RankThemeTokens,
		accent: RankThemeTokens,
		glow: RankThemeTokens,
	): RankThemeTokens = foundation.copy(
		background = wallpaper.background,
		backgroundGradientStart = wallpaper.backgroundGradientStart,
		backgroundGradientMiddle = wallpaper.backgroundGradientMiddle,
		backgroundGradientEnd = wallpaper.backgroundGradientEnd,
		primaryAccent = accent.primaryAccent,
		secondaryAccent = accent.secondaryAccent,
		onAccent = accent.onAccent,
		selectedStateColor = accent.selectedStateColor,
		iconAccent = accent.iconAccent,
		snackbarAccent = accent.snackbarAccent,
		activeGradientStart = accent.activeGradientStart ?: accent.primaryAccent,
		activeGradientEnd = accent.activeGradientEnd ?: accent.secondaryAccent,
		glowColor = glow.glowColor,
	)

	private fun mixComponent(
		foundation: ResolvedExclusiveThemeComponent,
		accent: ResolvedExclusiveThemeComponent,
		glow: ResolvedExclusiveThemeComponent,
	): ResolvedExclusiveThemeComponent = foundation.copy(
		selectedStops = accent.selectedStops,
		iconStops = accent.iconStops,
		interactiveText = accent.interactiveText,
		selectedMix = accent.selectedMix,
		iconMix = accent.iconMix,
		glowStops = glow.glowStops,
	)
}
