package org.koitharu.kotatsu.readerjourney.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeTokens
import org.koitharu.kotatsu.readerjourney.theme.ReferenceRankThemeVisualSpec
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class NameplateQualityMode {
	NORMAL,
	REDUCED,
	BATTERY_SAVER,
}

enum class NameplateState {
	LOCKED,
	UNLOCKED,
	PREVIEWING,
	EQUIPPED,
}

enum class NameplateUsage {
	CATALOG,
	PROFILE,
	PREVIEW,
}

internal enum class NameplateAmbient {
	SILVER_GLINT,
	FIRST_LIGHT,
	CYAN_ORBIT,
	EMERALD_PULSE,
	ARCANE_GLYPH,
	VIOLET_HALO,
	ROSE_NEBULA,
	CRIMSON_EMBER,
	AMBER_MANUSCRIPT,
	ROYAL_GOLD,
	PRISM,
	CELESTIAL_INFINITY,
}

internal data class NameplateAssetSpec(
	@DrawableRes val drawableRes: Int,
	@DrawableRes val thumbnailRes: Int,
	val ambient: NameplateAmbient,
	val idleDurationMs: Int,
	val oneShotDurationMs: Int,
	val glowAlpha: Float,
	val revealDurationMs: Int = 230,
	val titleWidthFraction: Float = 0.58f,
)

internal object NameplateAssetRegistry {
	fun resolve(themeId: RankThemeId): NameplateAssetSpec = when (themeId) {
		RankThemeId.FIRST_PAGE -> NameplateAssetSpec(
			drawableRes = R.drawable.nameplate_01_first_page_silver_base,
			thumbnailRes = R.drawable.nameplate_01_first_page_silver_thumb,
			ambient = NameplateAmbient.SILVER_GLINT,
			idleDurationMs = 15_000,
			oneShotDurationMs = 240,
			glowAlpha = 0.075f,
			titleWidthFraction = 0.55f,
		)
		RankThemeId.FIRST_LIGHT -> NameplateAssetSpec(
			drawableRes = R.drawable.nameplate_02_first_light_blue_base,
			thumbnailRes = R.drawable.nameplate_02_first_light_blue_thumb,
			ambient = NameplateAmbient.FIRST_LIGHT,
			idleDurationMs = 10_000,
			oneShotDurationMs = 260,
			glowAlpha = 0.085f,
			titleWidthFraction = 0.55f,
		)
		RankThemeId.CYAN_CODEX -> NameplateAssetSpec(
			drawableRes = R.drawable.nameplate_03_cyan_orbit_base,
			thumbnailRes = R.drawable.nameplate_03_cyan_orbit_thumb,
			ambient = NameplateAmbient.CYAN_ORBIT,
			idleDurationMs = 15_000,
			oneShotDurationMs = 250,
			glowAlpha = 0.085f,
			titleWidthFraction = 0.55f,
		)
		RankThemeId.EMERALD_COMPASS -> NameplateAssetSpec(
			drawableRes = R.drawable.nameplate_04_emerald_pulse_base,
			thumbnailRes = R.drawable.nameplate_04_emerald_pulse_thumb,
			ambient = NameplateAmbient.EMERALD_PULSE,
			idleDurationMs = 10_000,
			oneShotDurationMs = 300,
			glowAlpha = 0.09f,
			titleWidthFraction = 0.55f,
		)
		RankThemeId.VIOLET_VAULT -> NameplateAssetSpec(
			drawableRes = R.drawable.nameplate_05_arcane_scholar_base,
			thumbnailRes = R.drawable.nameplate_05_arcane_scholar_thumb,
			ambient = NameplateAmbient.ARCANE_GLYPH,
			idleDurationMs = 11_000,
			oneShotDurationMs = 280,
			glowAlpha = 0.085f,
			titleWidthFraction = 0.54f,
		)
		RankThemeId.ARCANE_SCHOLAR -> NameplateAssetSpec(
			drawableRes = R.drawable.nameplate_06_violet_halo_base,
			thumbnailRes = R.drawable.nameplate_06_violet_halo_thumb,
			ambient = NameplateAmbient.VIOLET_HALO,
			idleDurationMs = 11_000,
			oneShotDurationMs = 280,
			glowAlpha = 0.085f,
			titleWidthFraction = 0.54f,
		)
		RankThemeId.NEON_ARCHIVE -> NameplateAssetSpec(
			drawableRes = R.drawable.nameplate_07_rose_nebula_base,
			thumbnailRes = R.drawable.nameplate_07_rose_nebula_thumb,
			ambient = NameplateAmbient.ROSE_NEBULA,
			idleDurationMs = 14_000,
			oneShotDurationMs = 280,
			glowAlpha = 0.09f,
			titleWidthFraction = 0.53f,
		)
		RankThemeId.CRIMSON_LIBRARY -> NameplateAssetSpec(
			drawableRes = R.drawable.nameplate_08_crimson_ember_base,
			thumbnailRes = R.drawable.nameplate_08_crimson_ember_thumb,
			ambient = NameplateAmbient.CRIMSON_EMBER,
			idleDurationMs = 13_000,
			oneShotDurationMs = 300,
			glowAlpha = 0.095f,
			titleWidthFraction = 0.53f,
		)
		RankThemeId.EMBER_VETERAN -> NameplateAssetSpec(
			drawableRes = R.drawable.nameplate_09_amber_manuscript_base,
			thumbnailRes = R.drawable.nameplate_09_amber_manuscript_thumb,
			ambient = NameplateAmbient.AMBER_MANUSCRIPT,
			idleDurationMs = 15_000,
			oneShotDurationMs = 300,
			glowAlpha = 0.10f,
			titleWidthFraction = 0.50f,
		)
		RankThemeId.GOLDEN_MANUSCRIPT -> NameplateAssetSpec(
			drawableRes = R.drawable.nameplate_10_golden_manuscript_deluxe_base,
			thumbnailRes = R.drawable.nameplate_10_golden_manuscript_deluxe_thumb,
			ambient = NameplateAmbient.ROYAL_GOLD,
			idleDurationMs = 13_000,
			oneShotDurationMs = 300,
			glowAlpha = 0.105f,
			titleWidthFraction = 0.52f,
		)
		RankThemeId.IMPERIAL_AURORA -> NameplateAssetSpec(
			drawableRes = R.drawable.nameplate_11_eternal_library_prism_base,
			thumbnailRes = R.drawable.nameplate_11_eternal_library_prism_thumb,
			ambient = NameplateAmbient.PRISM,
			idleDurationMs = 13_000,
			oneShotDurationMs = 300,
			glowAlpha = 0.10f,
			revealDurationMs = 245,
			titleWidthFraction = 0.52f,
		)
		RankThemeId.ETERNAL_LIBRARY -> NameplateAssetSpec(
			drawableRes = R.drawable.nameplate_12_celestial_infinity_base,
			thumbnailRes = R.drawable.nameplate_12_celestial_infinity_thumb,
			ambient = NameplateAmbient.CELESTIAL_INFINITY,
			idleDurationMs = 16_000,
			oneShotDurationMs = 300,
			glowAlpha = 0.10f,
			revealDurationMs = 255,
			titleWidthFraction = 0.52f,
		)
	}
}

/**
 * Asset-first renderer for the twelve Reader Journey Exclusive nameplates.
 *
 * The ornament/material lives in local transparent assets while title text remains runtime content.
 * Only the selected/previewed/equipped item is allowed to run an ambient timeline. Reduce Motion
 * keeps a short alpha-only reveal; Android power saver disables ambient motion and uses the static
 * material asset as the visual fallback.
 */
@Composable
fun ExclusiveNameplate(
	spec: ReferenceRankThemeVisualSpec,
	tokens: RankThemeTokens,
	modifier: Modifier = Modifier,
	state: NameplateState = NameplateState.UNLOCKED,
	animate: Boolean = false,
	qualityMode: NameplateQualityMode = NameplateQualityMode.NORMAL,
	usage: NameplateUsage = NameplateUsage.PROFILE,
	pressed: Boolean = false,
	content: @Composable () -> Unit,
) {
	val asset = remember(spec.themeId) { NameplateAssetRegistry.resolve(spec.themeId) }

	// Catalog rows are a scrolling surface, not a showcase surface. Render the already-authored
	// static thumbnail directly and skip preference observers, power receivers, Animatable state,
	// reveal/equip effects and graphics layers entirely. Preview/profile keep the full renderer.
	if (usage == NameplateUsage.CATALOG) {
		ExclusiveNameplateCatalogThumbnail(
			asset = asset,
			state = state,
			pressed = pressed,
			modifier = modifier,
			content = content,
		)
		return
	}

	val reduceMotion by rememberBooleanPref(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, false)
	val powerSaveMode = rememberNameplatePowerSaveMode()
	val normalMotion = animate && !reduceMotion && !powerSaveMode
	val alphaOnlyMotion = animate && (reduceMotion || powerSaveMode)
	val revealEligible = state == NameplateState.EQUIPPED || state == NameplateState.PREVIEWING
	val reveal = remember(spec.themeId, state, animate, reduceMotion, powerSaveMode) { Animatable(1f) }
	val textReveal = remember(spec.themeId, state, animate, reduceMotion, powerSaveMode) { Animatable(1f) }
	val glowReveal = remember(spec.themeId, state, normalMotion) { Animatable(1f) }
	val equipAccent = remember(spec.themeId, state, normalMotion) { Animatable(1f) }

	LaunchedEffect(spec.themeId, state, animate, reduceMotion, powerSaveMode) {
		when {
			revealEligible && normalMotion -> {
				reveal.snapTo(0f)
				reveal.animateTo(
					1f,
					tween(durationMillis = asset.revealDurationMs, easing = FastOutSlowInEasing),
				)
			}
			revealEligible && alphaOnlyMotion -> {
				reveal.snapTo(0f)
				reveal.animateTo(1f, tween(durationMillis = 140))
			}
			else -> reveal.snapTo(1f)
		}
	}
	LaunchedEffect(spec.themeId, state, animate, reduceMotion, powerSaveMode) {
		when {
			revealEligible && normalMotion -> {
				textReveal.snapTo(0f)
				delay(36)
				textReveal.animateTo(1f, tween(durationMillis = 180, easing = FastOutSlowInEasing))
			}
			revealEligible && alphaOnlyMotion -> {
				textReveal.snapTo(0f)
				textReveal.animateTo(1f, tween(durationMillis = 140))
			}
			else -> textReveal.snapTo(1f)
		}
	}
	LaunchedEffect(spec.themeId, state, normalMotion) {
		if (revealEligible && normalMotion) {
			glowReveal.snapTo(0f)
			glowReveal.animateTo(1f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
		} else {
			glowReveal.snapTo(1f)
		}
	}
	LaunchedEffect(spec.themeId, state, normalMotion) {
		if (revealEligible && normalMotion) {
			equipAccent.snapTo(0f)
			delay(150)
			equipAccent.animateTo(
				1f,
				tween(durationMillis = asset.oneShotDurationMs, easing = FastOutSlowInEasing),
			)
		} else {
			equipAccent.snapTo(1f)
		}
	}

	val effectiveQualityMode = if (powerSaveMode) NameplateQualityMode.BATTERY_SAVER else qualityMode
	val locked = state == NameplateState.LOCKED
	val idleEnabled = normalMotion &&
		!locked &&
		effectiveQualityMode != NameplateQualityMode.BATTERY_SAVER &&
		(state == NameplateState.EQUIPPED || state == NameplateState.PREVIEWING)

	val stateGlowScale = when (state) {
		NameplateState.LOCKED -> 0f
		NameplateState.UNLOCKED -> 0.48f
		NameplateState.PREVIEWING -> 0.78f
		NameplateState.EQUIPPED -> 1f
	}
	val qualityGlowScale = when (effectiveQualityMode) {
		NameplateQualityMode.NORMAL -> 1f
		NameplateQualityMode.REDUCED -> 0.52f
		NameplateQualityMode.BATTERY_SAVER -> 0.30f
	}
	val usageGlowScale = when (usage) {
		NameplateUsage.CATALOG -> 0.62f
		NameplateUsage.PROFILE -> 0.84f
		NameplateUsage.PREVIEW -> 1f
	}
	// Catalog cards are intentionally static thumbnails. Keep their material/glow baked into the
	// local asset and avoid an extra full-size radial-gradient Canvas per visible item while scrolling.
	// Profile/preview surfaces retain the runtime glow where depth is actually visible and useful.
	val runtimeGlowAlpha = if (usage == NameplateUsage.CATALOG) {
		0f
	} else {
		asset.glowAlpha * stateGlowScale * qualityGlowScale * usageGlowScale
	}
	val lockedColorFilter = remember(locked) {
		if (locked) {
			ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.60f) })
		} else {
			null
		}
	}
	val pressProgress by animateFloatAsState(
		targetValue = if (pressed) 1f else 0f,
		animationSpec = tween(durationMillis = if (pressed) 90 else 135),
		label = "exclusive-nameplate-press",
	)
	val textOffsetPx = with(LocalDensity.current) { 2.dp.toPx() }
	val imageRes = asset.drawableRes

	Box(
		modifier = modifier.graphicsLayer {
			alpha = reveal.value
			val revealScale = if (normalMotion && revealEligible) 0.97f + (0.03f * reveal.value) else 1f
			val pressScale = if (reduceMotion) 1f else 1f - (0.02f * pressProgress)
			scaleX = revealScale * pressScale
			scaleY = revealScale * pressScale
		},
		contentAlignment = Alignment.Center,
	) {
		if (runtimeGlowAlpha > 0f) {
			Canvas(modifier = Modifier.fillMaxSize()) {
				val insetX = size.width * 0.04f
				val insetY = size.height * 0.13f
				drawOval(
					brush = Brush.radialGradient(
						colors = listOf(
							Color(tokens.primaryAccent.toInt()).copy(alpha = runtimeGlowAlpha * glowReveal.value),
							Color(tokens.secondaryAccent.toInt()).copy(alpha = runtimeGlowAlpha * 0.52f * glowReveal.value),
							Color.Transparent,
						),
						center = center,
						radius = size.width * 0.52f,
					),
					topLeft = Offset(insetX, insetY),
					size = Size(size.width - insetX * 2f, size.height - insetY * 2f),
				)
			}
		}

		Image(
			painter = painterResource(imageRes),
			contentDescription = null,
			contentScale = ContentScale.Fit,
			colorFilter = lockedColorFilter,
			modifier = Modifier
				.fillMaxSize()
				.graphicsLayer { alpha = if (locked) 0.62f else 1f },
		)

		Box(
			modifier = Modifier
				.fillMaxWidth(asset.titleWidthFraction)
				.fillMaxHeight(0.62f)
				.graphicsLayer {
					alpha = textReveal.value
					translationY = if (normalMotion) (1f - textReveal.value) * textOffsetPx else 0f
				},
			contentAlignment = Alignment.Center,
		) {
			content()
		}

		if (idleEnabled) {
			NameplateAmbientOverlay(
				asset = asset,
				primary = Color(tokens.primaryAccent.toInt()),
				secondary = Color(tokens.secondaryAccent.toInt()),
				strength = if (effectiveQualityMode == NameplateQualityMode.REDUCED) 0.58f else 1f,
			)
		}
		if (equipAccent.value < 1f && !locked) {
			NameplateEquipAccent(
				ambient = asset.ambient,
				phase = equipAccent.value,
			)
		}

		if (pressProgress > 0f) {
			Canvas(modifier = Modifier.fillMaxSize()) {
				drawRect(Color.Black.copy(alpha = 0.045f * pressProgress))
			}
		}

		if (locked) {
			Box(
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.size(20.dp)
					.background(Color(0xD811131A), CircleShape)
					.border(1.dp, Color.White.copy(alpha = 0.30f), CircleShape),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painter = painterResource(R.drawable.ic_lock),
					contentDescription = null,
					tint = Color.White.copy(alpha = 0.82f),
					modifier = Modifier.size(12.dp),
				)
			}
		}
	}
}


@Composable
private fun ExclusiveNameplateCatalogThumbnail(
	asset: NameplateAssetSpec,
	state: NameplateState,
	pressed: Boolean,
	modifier: Modifier,
	content: @Composable () -> Unit,
) {
	val locked = state == NameplateState.LOCKED
	val lockedColorFilter = remember(locked) {
		if (locked) {
			ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.60f) })
		} else {
			null
		}
	}
	val context = LocalContext.current
	val densityDpi = context.resources.displayMetrics.densityDpi
	val thumbnailBitmap by produceState<ImageBitmap?>(null, asset.thumbnailRes, densityDpi) {
		value = withContext(Dispatchers.IO) {
			NameplateCatalogBitmapCache.get(context, asset.thumbnailRes)
		}
	}
	Box(
		modifier = modifier,
		contentAlignment = Alignment.Center,
	) {
		if (thumbnailBitmap != null) {
			Image(
				bitmap = thumbnailBitmap,
				contentDescription = null,
				contentScale = ContentScale.Fit,
				colorFilter = lockedColorFilter,
				alpha = if (locked) 0.62f else 1f,
				modifier = Modifier.fillMaxSize(),
			)
		} else {
			// Defensive fallback for non-bitmap drawables; approved V2 thumbnails are raster WebP.
			Image(
				painter = painterResource(asset.thumbnailRes),
				contentDescription = null,
				contentScale = ContentScale.Fit,
				colorFilter = lockedColorFilter,
				alpha = if (locked) 0.62f else 1f,
				modifier = Modifier.fillMaxSize(),
			)
		}
		Box(
			modifier = Modifier
				.fillMaxWidth(asset.titleWidthFraction)
				.fillMaxHeight(0.62f),
			contentAlignment = Alignment.Center,
		) {
			content()
		}
		if (pressed) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(Color.Black.copy(alpha = 0.045f)),
			)
		}
		if (locked) {
			Box(
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.size(20.dp)
					.background(Color(0xD811131A), CircleShape)
					.border(1.dp, Color.White.copy(alpha = 0.30f), CircleShape),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painter = painterResource(R.drawable.ic_lock),
					contentDescription = null,
					tint = Color.White.copy(alpha = 0.82f),
					modifier = Modifier.size(12.dp),
				)
			}
		}
	}
}

private object NameplateCatalogBitmapCache {
	private const val MAX_ENTRIES = 24
	private val cache = LruCache<String, ImageBitmap>(MAX_ENTRIES)

	fun get(context: Context, @DrawableRes resId: Int): ImageBitmap? {
		val densityDpi = context.resources.displayMetrics.densityDpi
		val key = "$resId@$densityDpi"
		synchronized(cache) {
			cache.get(key)?.let { return it }
		}
		val decoded = BitmapFactory.decodeResource(context.resources, resId)?.asImageBitmap() ?: return null
		synchronized(cache) {
			cache.put(key, decoded)
		}
		return decoded
	}
}

private data class NameplateTitleTypography(
	val preferredFontSp: Float,
	val minimumFontSp: Float,
	val preferredLetterSpacingSp: Float,
	val minimumLetterSpacingSp: Float,
)

private fun nameplateTitleTypography(usage: NameplateUsage): NameplateTitleTypography = when (usage) {
	NameplateUsage.CATALOG -> NameplateTitleTypography(
		preferredFontSp = 14.5f,
		minimumFontSp = 10.5f,
		preferredLetterSpacingSp = 0.10f,
		minimumLetterSpacingSp = -0.30f,
	)
	NameplateUsage.PROFILE -> NameplateTitleTypography(
		preferredFontSp = 17.5f,
		minimumFontSp = 12f,
		preferredLetterSpacingSp = 0.10f,
		minimumLetterSpacingSp = -0.30f,
	)
	NameplateUsage.PREVIEW -> NameplateTitleTypography(
		preferredFontSp = 22f,
		minimumFontSp = 14f,
		preferredLetterSpacingSp = 0.12f,
		minimumLetterSpacingSp = -0.25f,
	)
}

/**
 * Runtime-only title renderer shared by Journey active title, preview, catalog/selector and profile.
 *
 * Fitting order is deliberate: preferred size -> progressively smaller font -> slightly tighter
 * letter spacing -> ellipsis only as a final fallback. The surrounding renderer already constrains
 * this composable to each artwork's authored title-safe fraction.
 */
private data class FittedNameplateTitle(
	val style: TextStyle,
	val ellipsisFallback: Boolean,
)

@Composable
fun ExclusiveNameplateTitle(
	title: String,
	usage: NameplateUsage,
) {
	val typography = remember(usage) { nameplateTitleTypography(usage) }
	val textMeasurer = rememberTextMeasurer(cacheSize = 32)
	val density = LocalDensity.current

	BoxWithConstraints(
		modifier = Modifier.fillMaxWidth(),
		contentAlignment = Alignment.Center,
	) {
		val availableWidthPx = with(density) { maxWidth.roundToPx() }
		val fitted = remember(title, usage, availableWidthPx, typography) {
			val constraints = Constraints(maxWidth = availableWidthPx.coerceAtLeast(1))

			fun style(fontSp: Float, letterSpacingSp: Float) = TextStyle(
				fontSize = fontSp.sp,
				fontFamily = FontFamily.Serif,
				fontWeight = FontWeight.SemiBold,
				letterSpacing = letterSpacingSp.sp,
				color = Color(0xFFF6E8D0),
				textAlign = TextAlign.Center,
				shadow = Shadow(
					color = Color(0x99070A10),
					offset = Offset(0f, 1.2f),
					blurRadius = 3.6f,
				),
			)

			fun fits(candidate: TextStyle): Boolean = !textMeasurer.measure(
				text = title,
				style = candidate,
				maxLines = 1,
				softWrap = false,
				overflow = TextOverflow.Clip,
				constraints = constraints,
			).hasVisualOverflow

			val preferred = style(
				fontSp = typography.preferredFontSp,
				letterSpacingSp = typography.preferredLetterSpacingSp,
			)
			if (fits(preferred)) {
				FittedNameplateTitle(preferred, ellipsisFallback = false)
			} else {
				var fittedStyle: TextStyle? = null
				var fontSp = typography.preferredFontSp - 0.5f
				while (fontSp >= typography.minimumFontSp && fittedStyle == null) {
					val candidate = style(
						fontSp = fontSp,
						letterSpacingSp = typography.preferredLetterSpacingSp,
					)
					if (fits(candidate)) fittedStyle = candidate
					fontSp -= 0.5f
				}

				if (fittedStyle != null) {
					FittedNameplateTitle(fittedStyle, ellipsisFallback = false)
				} else {
					var letterSpacingSp = typography.preferredLetterSpacingSp - 0.10f
					while (
						letterSpacingSp >= typography.minimumLetterSpacingSp &&
						fittedStyle == null
					) {
						val candidate = style(
							fontSp = typography.minimumFontSp,
							letterSpacingSp = letterSpacingSp,
						)
						if (fits(candidate)) fittedStyle = candidate
						letterSpacingSp -= 0.10f
					}

					val finalStyle = fittedStyle ?: style(
						fontSp = typography.minimumFontSp,
						letterSpacingSp = typography.minimumLetterSpacingSp,
					)
					FittedNameplateTitle(
						style = finalStyle,
						ellipsisFallback = fittedStyle == null,
					)
				}
			}
		}

		Text(
			text = title,
			style = fitted.style,
			textAlign = TextAlign.Center,
			maxLines = 1,
			softWrap = false,
			overflow = if (fitted.ellipsisFallback) TextOverflow.Ellipsis else TextOverflow.Clip,
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

@Composable
private fun NameplateAmbientOverlay(
	asset: NameplateAssetSpec,
	primary: Color,
	secondary: Color,
	strength: Float,
) {
	val transition = key(asset.drawableRes) { rememberInfiniteTransition(label = "exclusive-nameplate-idle") }
	val phase by transition.animateFloat(
		initialValue = 0f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(
			animation = tween(asset.idleDurationMs, easing = LinearEasing),
			repeatMode = RepeatMode.Restart,
		),
		label = "exclusive-nameplate-phase",
	)

	Canvas(
		modifier = Modifier
			.fillMaxSize()
			.graphicsLayer { alpha = strength },
	) {
		val w = size.width
		val h = size.height
		val pulse = ((sin(phase * 2f * PI).toFloat() + 1f) * 0.5f)
		when (asset.ambient) {
			NameplateAmbient.SILVER_GLINT -> {
				val x = w * (0.18f + phase * 0.64f)
				drawLine(
					Color.White.copy(alpha = 0.14f + pulse * 0.20f),
					Offset(x - w * 0.035f, h * 0.22f),
					Offset(x + w * 0.035f, h * 0.78f),
					strokeWidth = (h * 0.025f).coerceAtLeast(1f),
				)
			}
			NameplateAmbient.FIRST_LIGHT -> {
				val c = Offset(w * 0.50f, h * 0.11f)
				drawCircle(primary.copy(alpha = 0.12f + pulse * 0.16f), h * (0.055f + pulse * 0.020f), c)
				drawCircle(Color.White.copy(alpha = 0.38f + pulse * 0.28f), h * 0.014f, c)
			}
			NameplateAmbient.CYAN_ORBIT -> {
				val a = phase * 2f * PI
				val p = Offset(
					x = w * 0.50f + cos(a).toFloat() * w * 0.42f,
					y = h * 0.50f + sin(a).toFloat() * h * 0.29f,
				)
				drawCircle(secondary.copy(alpha = 0.30f), h * 0.042f, p)
				drawCircle(Color.White.copy(alpha = 0.82f), h * 0.016f, p)
			}
			NameplateAmbient.EMERALD_PULSE -> {
				val c = Offset(w * 0.50f, h * 0.10f)
				drawCircle(secondary.copy(alpha = 0.10f + pulse * 0.18f), h * (0.045f + pulse * 0.025f), c)
			}
			NameplateAmbient.ARCANE_GLYPH -> {
				val x = w * (0.24f + phase * 0.52f)
				drawLine(
					primary.copy(alpha = 0.10f + pulse * 0.12f),
					Offset(x, h * 0.19f),
					Offset(x + w * 0.06f, h * 0.81f),
					strokeWidth = (h * 0.018f).coerceAtLeast(1f),
				)
			}
			NameplateAmbient.VIOLET_HALO -> {
				drawOval(
					color = primary.copy(alpha = 0.04f + pulse * 0.07f),
					topLeft = Offset(w * 0.16f, h * 0.10f),
					size = Size(w * 0.68f, h * 0.80f),
					style = Stroke(width = (h * 0.025f).coerceAtLeast(1f)),
				)
			}
			NameplateAmbient.ROSE_NEBULA -> {
				val c = Offset(w * 0.84f, h * 0.22f)
				drawCircle(Color.White.copy(alpha = 0.18f + pulse * 0.46f), h * (0.012f + pulse * 0.012f), c)
			}
			NameplateAmbient.CRIMSON_EMBER -> {
				val rise = phase * h * 0.08f
				val p = Offset(w * 0.82f, h * 0.67f - rise)
				drawCircle(secondary.copy(alpha = (1f - phase) * 0.58f), h * 0.018f, p)
			}
			NameplateAmbient.AMBER_MANUSCRIPT -> {
				val x = w * (0.18f + phase * 0.64f)
				drawLine(
					Color.White.copy(alpha = 0.09f + pulse * 0.16f),
					Offset(x, h * 0.28f),
					Offset(x + w * 0.08f, h * 0.72f),
					strokeWidth = (h * 0.020f).coerceAtLeast(1f),
				)
			}
			NameplateAmbient.ROYAL_GOLD -> {
				val c = Offset(w * 0.50f, h * 0.08f)
				drawCircle(Color.White.copy(alpha = 0.20f + pulse * 0.48f), h * (0.010f + pulse * 0.012f), c)
				val x = w * (0.24f + phase * 0.52f)
				drawLine(
					secondary.copy(alpha = 0.10f + pulse * 0.12f),
					Offset(x, h * 0.70f),
					Offset(x + w * 0.08f, h * 0.70f),
					strokeWidth = (h * 0.014f).coerceAtLeast(1f),
				)
			}
			NameplateAmbient.PRISM -> {
				val x = w * (0.14f + phase * 0.72f)
				val shimmer = Brush.linearGradient(
					listOf(
						Color.Transparent,
						Color.White.copy(alpha = 0.34f),
						secondary.copy(alpha = 0.22f),
						Color.Transparent,
					),
				)
				// Keep the spectral sweep on the crystal ornament. The dark title plate remains calm.
				drawLine(
					shimmer,
					Offset(x - w * 0.045f, h * 0.08f),
					Offset(x + w * 0.025f, h * 0.34f),
					strokeWidth = (h * 0.020f).coerceAtLeast(1f),
				)
				drawLine(
					shimmer,
					Offset(x - w * 0.025f, h * 0.66f),
					Offset(x + w * 0.045f, h * 0.92f),
					strokeWidth = (h * 0.020f).coerceAtLeast(1f),
				)
			}
			NameplateAmbient.CELESTIAL_INFINITY -> {
				// Alternate between the two outer infinity lobes so the moving light never crosses title text.
				val leftLoop = phase < 0.5f
				val localPhase = if (leftLoop) phase * 2f else (phase - 0.5f) * 2f
				val t = localPhase * 2f * PI
				val loopCenterX = if (leftLoop) w * 0.29f else w * 0.71f
				val p = Offset(
					x = loopCenterX + cos(t).toFloat() * w * 0.145f,
					y = h * 0.50f + sin(t).toFloat() * h * 0.29f,
				)
				drawCircle(Color.White.copy(alpha = 0.76f), h * 0.016f, p)
				drawCircle(secondary.copy(alpha = 0.22f), h * 0.044f, p)
			}
		}
	}
}

@Composable
private fun NameplateEquipAccent(
	ambient: NameplateAmbient,
	phase: Float,
) {
	Canvas(modifier = Modifier.fillMaxSize()) {
		val envelope = sin(phase.coerceIn(0f, 1f) * PI).toFloat().coerceAtLeast(0f)
		if (envelope <= 0f) return@Canvas
		val point = when (ambient) {
			NameplateAmbient.SILVER_GLINT -> Offset(size.width * 0.50f, size.height * 0.10f)
			NameplateAmbient.FIRST_LIGHT -> Offset(size.width * 0.50f, size.height * 0.08f)
			NameplateAmbient.CYAN_ORBIT -> Offset(size.width * 0.82f, size.height * 0.28f)
			NameplateAmbient.EMERALD_PULSE -> Offset(size.width * 0.50f, size.height * 0.09f)
			NameplateAmbient.ARCANE_GLYPH -> Offset(size.width * 0.50f, size.height * 0.12f)
			NameplateAmbient.VIOLET_HALO -> Offset(size.width * 0.34f, size.height * 0.18f)
			NameplateAmbient.ROSE_NEBULA -> Offset(size.width * 0.84f, size.height * 0.20f)
			NameplateAmbient.CRIMSON_EMBER -> Offset(size.width * 0.50f, size.height * 0.10f)
			NameplateAmbient.AMBER_MANUSCRIPT -> Offset(size.width * 0.50f, size.height * 0.09f)
			NameplateAmbient.ROYAL_GOLD -> Offset(size.width * 0.50f, size.height * 0.07f)
			NameplateAmbient.PRISM -> Offset(size.width * 0.50f, size.height * 0.08f)
			NameplateAmbient.CELESTIAL_INFINITY -> Offset(size.width * 0.50f, size.height * 0.08f)
		}
		val r = size.height * (0.012f + envelope * 0.018f)
		drawLine(
			Color.White.copy(alpha = 0.72f * envelope),
			Offset(point.x - r * 3f, point.y),
			Offset(point.x + r * 3f, point.y),
			strokeWidth = (r * 0.45f).coerceAtLeast(1f),
		)
		drawLine(
			Color.White.copy(alpha = 0.72f * envelope),
			Offset(point.x, point.y - r * 3f),
			Offset(point.x, point.y + r * 3f),
			strokeWidth = (r * 0.45f).coerceAtLeast(1f),
		)
		drawCircle(Color.White.copy(alpha = 0.90f * envelope), r, point)
	}
}

internal object ExclusiveNameplateRuntimeTestHooks {
	@Volatile
	var powerSaveModeOverride: Boolean? = null
}

@Composable
private fun rememberNameplatePowerSaveMode(): Boolean {
	val context = LocalContext.current
	ExclusivePowerSaveModeRuntime.ensureInitialized(context)
	val powerSaveMode by ExclusivePowerSaveModeRuntime.state.collectAsState()
	return if (BuildConfig.DEBUG) ExclusiveNameplateRuntimeTestHooks.powerSaveModeOverride ?: powerSaveMode else powerSaveMode
}
