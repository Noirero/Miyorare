package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.ui.graphics.toArgb
import androidx.preference.PreferenceManager
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareAdaptivePalette
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareCustomBackgroundIntensity
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.PrivateFavouritesThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.readerjourney.theme.ReaderJourneyThemeRuntimeState
import org.koitharu.kotatsu.readerjourney.theme.readerJourneyThemeRuntimeOrNull

data class MiyorareViewExclusiveThemeComponent(
	val containerStops: List<Int>,
	val borderStops: List<Int>,
	val cardBorderStops: List<Int>,
	val selectedStops: List<Int>,
	val glowStops: List<Int>,
	val iconStops: List<Int>,
	val content: Int,
	val mutedContent: Int,
	val interactiveText: Int,
	val containerMix: Float,
	val selectedMix: Float,
	val iconMix: Float,
)

data class MiyorareViewExclusiveTheme(
	val stableId: String,
	val shared: MiyorareViewExclusiveThemeComponent,
	val navigation: MiyorareViewExclusiveThemeComponent,
	val favourites: MiyorareViewExclusiveThemeComponent,
	val settings: MiyorareViewExclusiveThemeComponent,
	val details: MiyorareViewExclusiveThemeComponent,
)

/** Android View bridge for the same semantic Modern palette used by Compose. */
data class MiyorareViewPalette(
	val resources: Resources,
	val preset: MiyorareThemePreset,
	val background: Int,
	val surface: Int,
	val surfaceContainer: Int,
	val surfaceContainerHigh: Int,
	val primary: Int,
	val onPrimary: Int,
	val primaryContainer: Int,
	val onPrimaryContainer: Int,
	val secondary: Int,
	val accent: Int,
	val onSurface: Int,
	val onSurfaceVariant: Int,
	val outline: Int,
	val outlineVariant: Int,
	val selectedSurface: Int,
	val borderHighlight: Int,
	val glow: Int,
	val button: Int,
	val onButton: Int,
	val backgroundGradientStart: Int,
	val backgroundGradientMiddle: Int,
	val backgroundGradientEnd: Int,
	val surfaceGradientStart: Int,
	val surfaceGradientMiddle: Int,
	val surfaceGradientEnd: Int,
	val activeGradientStart: Int,
	val activeGradientEnd: Int,
	val exclusiveTheme: MiyorareViewExclusiveTheme? = null,
	val rankThemeId: String? = exclusiveTheme?.stableId,
	val customBackgroundPath: String? = null,
	val customBackgroundBlurPath: String? = null,
	val customBackgroundRevision: Int = 0,
)

/**
 * Hilt-backed View entry point. FOLLOW_NORMAL stays on the exact Normal palette path. Explicit
 * Private themes resolve their own authored visual spec and force a dark Private foundation.
 */
fun Context.miyorareViewPalette(
	settings: AppSettings,
	effectLevel: VisualEffectLevel,
): MiyorareViewPalette {
	val privateFavourites = isPrivateFavouritesHost()
	val privateTheme = if (privateFavourites) privateFavouritesThemeFromPreferences() else PrivateFavouritesThemePreset.FOLLOW_NORMAL
	val privateSpec = if (privateFavourites) PrivateFavouritesVisualResolver.resolve(privateTheme) else null
	val preset = if (privateFavourites) privateTheme.resolve(settings.miyorareThemePreset) else settings.miyorareThemePreset
	val customBackgroundActive = !privateFavourites &&
		preset == MiyorareThemePreset.CUSTOM &&
		MiyorareCustomBackgroundStore.hasBackground(this)
	val palette = buildMiyorareViewPalette(
		preset = preset,
		customAccent = settings.miyorareCustomAccent,
		adaptivePalette = if (customBackgroundActive) settings.miyorareAdaptivePalette else null,
		amoled = settings.isAmoledTheme,
		effectLevel = effectLevel,
		forceDark = privateSpec != null,
		customBackgroundPath = if (customBackgroundActive) MiyorareCustomBackgroundStore.sharpPathOrNull(this) else null,
		customBackgroundBlurPath = if (customBackgroundActive) MiyorareCustomBackgroundStore.blurPathOrNull(this) else null,
		customBackgroundRevision = if (customBackgroundActive) settings.miyorareCustomBackgroundRevision else 0,
		rankThemeState = readerJourneyThemeRuntimeOrNull()?.state?.value,
		allowRankTheme = privateSpec == null && settings.isRankThemeEnabled,
		reduceRankThemeEffects = settings.isRankThemeReduceGlow || settings.isRankThemeMinimalCosmetics,
	)
	return privateSpec?.let(palette::applyPrivateFavouritesVisualSpec) ?: palette
}

/**
 * Preference-backed palette entry point for custom Views that cannot receive Hilt dependencies.
 * Returns null for Classic so Modern-only header shells stay completely isolated from Classic.
 * FOLLOW_NORMAL inherits the current Normal preset/light-dark path. Explicit Private themes use
 * their own dark palette and artwork identity.
 */
fun Context.miyorareViewPaletteFromPreferences(
	privateFavourites: Boolean = false,
): MiyorareViewPalette? {
	val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
	val designStyle = prefs.getString(MiyorareAppearance.KEY_DESIGN_STYLE, null)
		?.let { value -> MiyorareDesignStyle.entries.firstOrNull { it.name == value } }
		?: MiyorareDesignStyle.CLASSIC
	if (designStyle != MiyorareDesignStyle.MODERN) return null

	val normalPreset = prefs.getString(MiyorareAppearance.KEY_THEME_PRESET, null)
		?.let { value -> MiyorareThemePreset.entries.firstOrNull { it.name == value } }
		?: MiyorareThemePreset.MIYORARE
	val privateTheme = if (privateFavourites) privateFavouritesThemeFromPreferences() else PrivateFavouritesThemePreset.FOLLOW_NORMAL
	val privateSpec = if (privateFavourites) PrivateFavouritesVisualResolver.resolve(privateTheme) else null
	val preset = if (privateFavourites) privateTheme.resolve(normalPreset) else normalPreset
	val customAccent = prefs.getString(
		MiyorareAppearance.KEY_CUSTOM_ACCENT,
		MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
	)?.let { value -> MiyorareAppearance.normalizeAccent(value) }
		?: MiyorareAppearance.DEFAULT_CUSTOM_ACCENT
	val effectLevel = prefs.getString(VisualEffectPreferences.KEY_LEVEL, null)
		?.let { value -> VisualEffectLevel.entries.firstOrNull { it.name == value } }
		?: VisualEffectLevel.BALANCED
	val reduceRankThemeEffects =
		prefs.getBoolean(AppSettings.KEY_RANK_THEME_REDUCE_GLOW, false) ||
			prefs.getBoolean(AppSettings.KEY_RANK_THEME_MINIMAL_COSMETICS, false)
	val customBackgroundActive = !privateFavourites &&
		preset == MiyorareThemePreset.CUSTOM &&
		MiyorareCustomBackgroundStore.hasBackground(this)
	val customIntensity = prefs.getString(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_INTENSITY, null)
		?.let { value -> MiyorareCustomBackgroundIntensity.entries.firstOrNull { it.name == value } }
		?: MiyorareCustomBackgroundIntensity.BALANCED
	val adaptivePalette = if (
		customBackgroundActive &&
		prefs.getBoolean(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_COLOR_SYNC, true)
	) {
		MiyorareAppearance.resolveAdaptivePalette(
			primary = prefs.getString(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_PRIMARY, null),
			secondary = prefs.getString(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_SECONDARY, null),
			tertiary = prefs.getString(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_TERTIARY, null),
			intensity = customIntensity,
		)
	} else null

	val palette = buildMiyorareViewPalette(
		preset = preset,
		customAccent = customAccent,
		adaptivePalette = adaptivePalette,
		amoled = prefs.getBoolean(AppSettings.KEY_THEME_AMOLED, false),
		effectLevel = effectLevel,
		forceDark = privateSpec != null,
		customBackgroundPath = if (customBackgroundActive) MiyorareCustomBackgroundStore.sharpPathOrNull(this) else null,
		customBackgroundBlurPath = if (customBackgroundActive) MiyorareCustomBackgroundStore.blurPathOrNull(this) else null,
		customBackgroundRevision = if (customBackgroundActive) {
			prefs.getInt(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_REVISION, 0)
		} else {
			0
		},
		rankThemeState = readerJourneyThemeRuntimeOrNull()?.state?.value,
		allowRankTheme = privateSpec == null && prefs.getBoolean(AppSettings.KEY_RANK_THEME_ENABLED, false),
		reduceRankThemeEffects = reduceRankThemeEffects,
	)
	return privateSpec?.let(palette::applyPrivateFavouritesVisualSpec) ?: palette
}

private fun Context.isPrivateFavouritesHost(): Boolean {
	val activity = findActivity() ?: return false
	return activity.intent?.getIntExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.NORMAL.dbValue) ==
		FavouriteSpace.PRIVATE.dbValue ||
		activity.intent?.action == AppRouter.ACTION_PRIVATE_FAVOURITES_SETTINGS ||
		activity.intent?.action == AppRouter.ACTION_PRIVATE_EXTENSIONS_SETTINGS
}

private fun Context.buildMiyorareViewPalette(
	preset: MiyorareThemePreset,
	customAccent: String,
	adaptivePalette: MiyorareAdaptivePalette?,
	amoled: Boolean,
	effectLevel: VisualEffectLevel,
	forceDark: Boolean,
	customBackgroundPath: String?,
	customBackgroundBlurPath: String?,
	customBackgroundRevision: Int,
	rankThemeState: ReaderJourneyThemeRuntimeState?,
	allowRankTheme: Boolean,
	reduceRankThemeEffects: Boolean,
): MiyorareViewPalette {
	val darkTheme = forceDark || (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
		Configuration.UI_MODE_NIGHT_YES
	val resolvedExclusiveTheme = if (allowRankTheme) {
		rankThemeState?.resolveExclusiveTheme(
			explicitCustomAppearance = preset == MiyorareThemePreset.CUSTOM,
			darkTheme = darkTheme,
			amoled = amoled,
		)
	} else {
		null
	}
	val rankThemeId = resolvedExclusiveTheme?.id?.stableId
	val effectiveEffectLevel = if (resolvedExclusiveTheme != null && reduceRankThemeEffects) {
		VisualEffectLevel.LIGHT
	} else {
		effectLevel
	}
	val colors = miyorareThemeColors(
		preset = preset,
		customAccent = customAccent,
		adaptivePalette = adaptivePalette,
		darkTheme = darkTheme,
		amoled = amoled,
		effectLevel = effectiveEffectLevel,
		exclusiveTheme = resolvedExclusiveTheme,
	)
	val scheme = colors.colorScheme
	val palette = colors.visualPalette
	return MiyorareViewPalette(
		resources = resources,
		preset = preset,
		background = scheme.background.toArgb(),
		surface = scheme.surface.toArgb(),
		surfaceContainer = scheme.surfaceContainer.toArgb(),
		surfaceContainerHigh = scheme.surfaceContainerHigh.toArgb(),
		primary = scheme.primary.toArgb(),
		onPrimary = scheme.onPrimary.toArgb(),
		primaryContainer = scheme.primaryContainer.toArgb(),
		onPrimaryContainer = scheme.onPrimaryContainer.toArgb(),
		secondary = scheme.secondary.toArgb(),
		accent = scheme.tertiary.toArgb(),
		onSurface = scheme.onSurface.toArgb(),
		onSurfaceVariant = scheme.onSurfaceVariant.toArgb(),
		outline = scheme.outline.toArgb(),
		outlineVariant = scheme.outlineVariant.toArgb(),
		selectedSurface = palette.selectedSurface.toArgb(),
		borderHighlight = palette.borderHighlight.toArgb(),
		glow = palette.glow.toArgb(),
		button = palette.button.toArgb(),
		onButton = palette.onButton.toArgb(),
		backgroundGradientStart = palette.backgroundGradientStart.toArgb(),
		backgroundGradientMiddle = palette.backgroundGradientMiddle.toArgb(),
		backgroundGradientEnd = palette.backgroundGradientEnd.toArgb(),
		surfaceGradientStart = palette.surfaceGradientStart.toArgb(),
		surfaceGradientMiddle = palette.surfaceGradientMiddle.toArgb(),
		surfaceGradientEnd = palette.surfaceGradientEnd.toArgb(),
		activeGradientStart = palette.activeGradientStart.toArgb(),
		activeGradientEnd = palette.activeGradientEnd.toArgb(),
		exclusiveTheme = palette.exclusiveTheme?.toViewPalette(),
		rankThemeId = rankThemeId,
		customBackgroundPath = customBackgroundPath,
		customBackgroundBlurPath = customBackgroundBlurPath,
		customBackgroundRevision = customBackgroundRevision,
	)
}

private fun ExclusiveThemeComponentPalette.toViewPalette(): MiyorareViewExclusiveThemeComponent =
	MiyorareViewExclusiveThemeComponent(
		containerStops = containerStops.map { it.toArgb() },
		borderStops = borderStops.map { it.toArgb() },
		cardBorderStops = cardBorderStops.map { it.toArgb() },
		selectedStops = selectedStops.map { it.toArgb() },
		glowStops = glowStops.map { it.toArgb() },
		iconStops = iconStops.map { it.toArgb() },
		content = content.toArgb(),
		mutedContent = mutedContent.toArgb(),
		interactiveText = interactiveText.toArgb(),
		containerMix = containerMix,
		selectedMix = selectedMix,
		iconMix = iconMix,
	)

private fun ResolvedExclusiveThemePalette.toViewPalette(): MiyorareViewExclusiveTheme =
	MiyorareViewExclusiveTheme(
		stableId = stableId,
		shared = shared.toViewPalette(),
		navigation = navigation.toViewPalette(),
		favourites = favourites.toViewPalette(),
		settings = settings.toViewPalette(),
		details = details.toViewPalette(),
	)
