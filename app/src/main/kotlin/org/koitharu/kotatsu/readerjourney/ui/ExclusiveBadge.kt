package org.koitharu.kotatsu.readerjourney.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveThemeQaRuntime
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeTokens
import org.koitharu.kotatsu.readerjourney.theme.ReferenceRankThemeVisualSpec
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rendering policy from MIYORARE_12_BADGE_IMPLEMENTATION_GUIDE_UPDATED.
 *
 * Static artwork owns the silhouette/material identity. Runtime only adds restrained halo,
 * glint/shimmer/orbit accents and interaction state. Grid callers use [useThumbnail] with
 * animation disabled; selected/preview/equipped are the only states allowed to idle.
 */
enum class BadgeQualityMode {
	NORMAL,
	REDUCED,
	BATTERY_SAVER,
}

enum class BadgeState {
	LOCKED,
	UNLOCKED,
	PREVIEWING,
	EQUIPPED,
	PRESSED,
}

internal enum class BadgeAmbient {
	MICRO_GLINT,
	GUIDING_LIGHT,
	ORBIT,
	EMERALD,
	ARCANE,
	MOON,
	ROSE,
	EMBER,
	MANUSCRIPT,
	ROYAL,
	PRISM,
	CELESTIAL,
}

internal data class BadgeAssetSpec(
	@DrawableRes val fullRes: Int,
	@DrawableRes val thumbnailRes: Int,
	val ambient: BadgeAmbient,
	val idleDurationMs: Int,
	val revealDurationMs: Int = 230,
	val oneShotDurationMs: Int = 280,
	val glowAlpha: Float,
	val sweepDurationMs: Int? = null,
)

/**
 * Stable theme IDs remain untouched so previously persisted loadouts continue to resolve.
 * Visual identity is intentionally mapped here instead of deriving it from legacy badgeStyle.
 */
internal object ExclusiveBadgeAssetRegistry {
	fun resolve(themeId: RankThemeId): BadgeAssetSpec = when (themeId) {
		RankThemeId.FIRST_PAGE -> BadgeAssetSpec(
			R.drawable.badge_01_first_page_silver_base,
			R.drawable.badge_01_first_page_silver_thumb,
			BadgeAmbient.MICRO_GLINT,
			idleDurationMs = 15_000,
			oneShotDurationMs = 260,
			glowAlpha = 0.09f,
		)
		RankThemeId.FIRST_LIGHT -> BadgeAssetSpec(
			R.drawable.badge_02_first_light_blue_base,
			R.drawable.badge_02_first_light_blue_thumb,
			BadgeAmbient.GUIDING_LIGHT,
			idleDurationMs = 10_000,
			oneShotDurationMs = 320,
			glowAlpha = 0.11f,
		)
		RankThemeId.CYAN_CODEX -> BadgeAssetSpec(
			R.drawable.badge_03_cyan_orbit_base,
			R.drawable.badge_03_cyan_orbit_thumb,
			BadgeAmbient.ORBIT,
			idleDurationMs = 15_000,
			oneShotDurationMs = 300,
			glowAlpha = 0.12f,
		)
		RankThemeId.EMERALD_COMPASS -> BadgeAssetSpec(
			R.drawable.badge_04_emerald_pulse_base,
			R.drawable.badge_04_emerald_pulse_thumb,
			BadgeAmbient.EMERALD,
			idleDurationMs = 10_000,
			oneShotDurationMs = 340,
			glowAlpha = 0.12f,
		)
		RankThemeId.VIOLET_VAULT -> BadgeAssetSpec(
			R.drawable.badge_05_arcane_scholar_base,
			R.drawable.badge_05_arcane_scholar_thumb,
			BadgeAmbient.ARCANE,
			idleDurationMs = 12_000,
			oneShotDurationMs = 340,
			glowAlpha = 0.13f,
			sweepDurationMs = 1_400,
		)
		RankThemeId.ARCANE_SCHOLAR -> BadgeAssetSpec(
			R.drawable.badge_06_violet_halo_base,
			R.drawable.badge_06_violet_halo_thumb,
			BadgeAmbient.MOON,
			idleDurationMs = 11_000,
			oneShotDurationMs = 340,
			glowAlpha = 0.12f,
		)
		RankThemeId.NEON_ARCHIVE -> BadgeAssetSpec(
			R.drawable.badge_07_rose_nebula_base,
			R.drawable.badge_07_rose_nebula_thumb,
			BadgeAmbient.ROSE,
			idleDurationMs = 12_000,
			oneShotDurationMs = 300,
			glowAlpha = 0.12f,
			sweepDurationMs = 1_500,
		)
		RankThemeId.CRIMSON_LIBRARY -> BadgeAssetSpec(
			R.drawable.badge_08_crimson_ember_base,
			R.drawable.badge_08_crimson_ember_thumb,
			BadgeAmbient.EMBER,
			idleDurationMs = 13_000,
			oneShotDurationMs = 320,
			glowAlpha = 0.13f,
		)
		RankThemeId.EMBER_VETERAN -> BadgeAssetSpec(
			R.drawable.badge_09_amber_manuscript_base,
			R.drawable.badge_09_amber_manuscript_thumb,
			BadgeAmbient.MANUSCRIPT,
			idleDurationMs = 15_000,
			oneShotDurationMs = 340,
			glowAlpha = 0.12f,
			sweepDurationMs = 1_500,
		)
		RankThemeId.GOLDEN_MANUSCRIPT -> BadgeAssetSpec(
			R.drawable.badge_10_golden_manuscript_deluxe_base,
			R.drawable.badge_10_golden_manuscript_deluxe_thumb,
			BadgeAmbient.ROYAL,
			idleDurationMs = 13_000,
			oneShotDurationMs = 340,
			glowAlpha = 0.14f,
			sweepDurationMs = 1_400,
		)
		RankThemeId.IMPERIAL_AURORA -> BadgeAssetSpec(
			R.drawable.badge_11_eternal_library_prism_base,
			R.drawable.badge_11_eternal_library_prism_thumb,
			BadgeAmbient.PRISM,
			idleDurationMs = 13_000,
			revealDurationMs = 245,
			oneShotDurationMs = 360,
			glowAlpha = 0.15f,
			sweepDurationMs = 1_500,
		)
		RankThemeId.ETERNAL_LIBRARY -> BadgeAssetSpec(
			R.drawable.badge_12_celestial_infinity_base,
			R.drawable.badge_12_celestial_infinity_thumb,
			BadgeAmbient.CELESTIAL,
			idleDurationMs = 17_000,
			revealDurationMs = 255,
			oneShotDurationMs = 360,
			glowAlpha = 0.16f,
			sweepDurationMs = 1_600,
		)
	}
}

@Composable
fun ExclusiveBadge(
	spec: ReferenceRankThemeVisualSpec,
	tokens: RankThemeTokens,
	modifier: Modifier = Modifier,
	state: BadgeState = BadgeState.UNLOCKED,
	animate: Boolean = false,
	qualityMode: BadgeQualityMode = BadgeQualityMode.NORMAL,
	useThumbnail: Boolean = false,
	profileMode: Boolean = false,
) {
	val asset = remember(spec.themeId) { ExclusiveBadgeAssetRegistry.resolve(spec.themeId) }
	val primary = Color(tokens.primaryAccent.toInt())
	val secondary = Color(tokens.secondaryAccent.toInt())
	val qaState by ExclusiveThemeQaRuntime.state.collectAsState()
	val reduceMotionPreference by rememberBooleanPref(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, false)
	val reduceMotion = qaState.effectiveReduceMotion(reduceMotionPreference)
	val systemPowerSaveMode = rememberBadgePowerSaveMode()
	val powerSaveMode = qaState.effectiveBatterySaver(systemPowerSaveMode)
	val effectiveQuality = if (powerSaveMode) BadgeQualityMode.BATTERY_SAVER else qualityMode
	val activeState = state == BadgeState.PREVIEWING || state == BadgeState.EQUIPPED
	val revealEnabled = animate && activeState
	val idleEnabled = animate && activeState && !reduceMotion && !powerSaveMode &&
		effectiveQuality != BadgeQualityMode.BATTERY_SAVER
	val locked = state == BadgeState.LOCKED
	val pressed = state == BadgeState.PRESSED
	val pressScale by animateFloatAsState(
		targetValue = if (pressed) 0.975f else 1f,
		animationSpec = tween(durationMillis = if (pressed) 90 else 135, easing = FastOutSlowInEasing),
		label = "exclusive-badge-press-scale",
	)

	val reveal = remember(spec.themeId, state, animate, reduceMotion) { Animatable(1f) }
	val glowSettle = remember(spec.themeId, state, animate) { Animatable(1f) }
	val oneShot = remember(spec.themeId, state, animate) { Animatable(1f) }
	val sweep = remember(spec.themeId, state, animate) { Animatable(1f) }

	LaunchedEffect(spec.themeId, state, animate, reduceMotion) {
		if (!revealEnabled) {
			reveal.snapTo(1f)
			return@LaunchedEffect
		}
		reveal.snapTo(0f)
		reveal.animateTo(
			1f,
			tween(
				durationMillis = if (reduceMotion) 145 else asset.revealDurationMs,
				easing = FastOutSlowInEasing,
			),
		)
	}
	LaunchedEffect(spec.themeId, state, animate, reduceMotion, powerSaveMode) {
		if (!revealEnabled || reduceMotion || powerSaveMode) {
			// Reduce Motion requires a static glow: only the 120-160ms alpha reveal may move.
			// Battery Saver likewise skips decorative settling while keeping the authored artwork.
			glowSettle.snapTo(1f)
			return@LaunchedEffect
		}
		glowSettle.snapTo(0f)
		glowSettle.animateTo(1f, tween(270, easing = FastOutSlowInEasing))
	}
	LaunchedEffect(spec.themeId, state, animate, reduceMotion) {
		if (!revealEnabled || reduceMotion || powerSaveMode) {
			oneShot.snapTo(1f)
			return@LaunchedEffect
		}
		oneShot.snapTo(0f)
		delay(160)
		oneShot.animateTo(1f, tween(asset.oneShotDurationMs, easing = FastOutSlowInEasing))
	}
	LaunchedEffect(spec.themeId, state, animate, reduceMotion) {
		val duration = asset.sweepDurationMs
		if (!revealEnabled || reduceMotion || powerSaveMode || duration == null) {
			sweep.snapTo(1f)
			return@LaunchedEffect
		}
		sweep.snapTo(0f)
		delay(180)
		sweep.animateTo(1f, tween(duration, easing = LinearEasing))
	}

	val stateGlowScale = when (state) {
		BadgeState.LOCKED -> 0f
		BadgeState.UNLOCKED -> 0.26f
		BadgeState.PREVIEWING -> 0.74f
		BadgeState.EQUIPPED -> 1f
		BadgeState.PRESSED -> 0.48f
	}
	val qualityScale = when (effectiveQuality) {
		BadgeQualityMode.NORMAL -> 1f
		BadgeQualityMode.REDUCED -> 0.48f
		BadgeQualityMode.BATTERY_SAVER -> 0.34f
	}
	val profileScale = if (profileMode) 0.84f else 1f
	val runtimeGlow = asset.glowAlpha * stateGlowScale * qualityScale * profileScale
	val colorFilter = remember(locked) {
		if (!locked) null else ColorFilter.colorMatrix(
			ColorMatrix().apply { setToSaturation(0.45f) },
		)
	}
	val drawable = if (useThumbnail) asset.thumbnailRes else asset.fullRes

	Box(
		modifier = modifier.graphicsLayer {
			alpha = reveal.value
			val revealScale = if (reduceMotion) 1f else 0.94f + reveal.value * 0.06f
			scaleX = revealScale * pressScale
			scaleY = revealScale * pressScale
		},
		contentAlignment = Alignment.Center,
	) {
		if (!locked && runtimeGlow > 0f) {
			Canvas(modifier = Modifier.fillMaxSize()) {
				drawCircle(
					brush = Brush.radialGradient(
						listOf(
							primary.copy(alpha = runtimeGlow * glowSettle.value),
							secondary.copy(alpha = runtimeGlow * 0.48f * glowSettle.value),
							Color.Transparent,
						),
						center = center,
						radius = size.minDimension * 0.52f,
					),
					radius = size.minDimension * 0.52f,
					center = center,
				)
			}
		}

		Image(
			painter = painterResource(drawable),
			contentDescription = null,
			colorFilter = colorFilter,
			modifier = Modifier
				.fillMaxSize()
				.alpha(if (locked) 0.62f else 1f),
		)

		if (idleEnabled) {
			BadgeAmbientOverlay(
				asset = asset,
				primary = primary,
				secondary = secondary,
				oneShotPhase = oneShot.value,
				sweepPhase = sweep.value,
				qualityMode = effectiveQuality,
				previewing = state == BadgeState.PREVIEWING,
			)
		}

		if (pressed) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(Color.Black.copy(alpha = 0.04f)),
			)
		}

		if (locked) {
			Box(
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(2.dp)
					.size(18.dp)
					.background(Color(0xD812151B), CircleShape)
					.border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painter = painterResource(R.drawable.ic_lock),
					contentDescription = null,
					tint = Color.White.copy(alpha = 0.82f),
					modifier = Modifier.size(10.dp),
				)
			}
		}
	}
}

@Composable
private fun BadgeAmbientOverlay(
	asset: BadgeAssetSpec,
	primary: Color,
	secondary: Color,
	oneShotPhase: Float,
	sweepPhase: Float,
	qualityMode: BadgeQualityMode,
	previewing: Boolean,
) {
	val transition = rememberInfiniteTransition(label = "exclusive-badge-idle")
	val phase by transition.animateFloat(
		initialValue = 0f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(
			animation = tween(asset.idleDurationMs, easing = LinearEasing),
			repeatMode = RepeatMode.Restart,
		),
		label = "exclusive-badge-phase",
	)
	val qualityScale = when (qualityMode) {
		BadgeQualityMode.NORMAL -> 1f
		BadgeQualityMode.REDUCED -> 0.48f
		BadgeQualityMode.BATTERY_SAVER -> 0f
	}
	val strength = (if (previewing) 0.66f else 1f) * qualityScale

	Canvas(modifier = Modifier.fillMaxSize()) {
		val min = size.minDimension
		val radius = min * 0.40f
		val pulse = ((sin(phase * 2f * PI).toFloat() + 1f) * 0.5f)
		val pulseFast = ((sin(phase * 4f * PI).toFloat() + 1f) * 0.5f)
		val eventWave = sin(PI * oneShotPhase).toFloat().coerceAtLeast(0f)
		val sweepWave = sin(PI * sweepPhase).toFloat().coerceAtLeast(0f)
		val angle = phase * 2f * PI

		when (asset.ambient) {
			BadgeAmbient.MICRO_GLINT -> {
				drawBadgeTwinkle(
					Offset(center.x, center.y - radius * 1.02f),
					Color.White,
					min * 0.032f,
					(0.10f + 0.48f * pulseFast + 0.24f * eventWave) * strength,
				)
			}

			BadgeAmbient.GUIDING_LIGHT -> {
				drawCircle(
					color = primary.copy(alpha = (0.04f + 0.055f * pulse + 0.08f * eventWave) * strength),
					radius = radius * (0.90f + 0.025f * pulse),
					center = center,
					style = Stroke(min * 0.014f),
				)
				drawBadgeTwinkle(
					Offset(center.x + radius * 0.70f, center.y - radius * 0.67f),
					Color.White,
					min * 0.026f,
					(0.18f + 0.45f * pulseFast) * strength,
				)
			}

			BadgeAmbient.ORBIT -> {
				drawCircle(
					brush = Brush.radialGradient(
						listOf(
							Color.White.copy(alpha = (0.035f + 0.030f * pulse) * strength),
							primary.copy(alpha = (0.025f + 0.025f * pulse) * strength),
							Color.Transparent,
						),
						center = center,
						radius = min * 0.28f,
					),
					radius = min * 0.28f,
					center = center,
				)
				val p = Offset(
					center.x + cos(angle).toFloat() * radius,
					center.y + sin(angle).toFloat() * radius * 0.58f,
				)
				drawCircle(secondary.copy(alpha = 0.28f * strength), min * 0.026f, p)
				drawCircle(Color.White.copy(alpha = 0.78f * strength), min * 0.010f, p)
				drawBadgeTwinkle(
					p,
					Color.White,
					min * 0.020f,
					(0.12f + 0.52f * pulseFast) * strength,
				)
			}

			BadgeAmbient.EMERALD -> {
				if (eventWave > 0.01f) {
					drawCircle(
						brush = Brush.radialGradient(
							listOf(
								Color.White.copy(alpha = 0.12f * eventWave * strength),
								primary.copy(alpha = 0.06f * eventWave * strength),
								Color.Transparent,
							),
							center = center,
							radius = min * 0.25f,
						),
						radius = min * 0.25f,
						center = center,
					)
				}
				val y = center.y - radius + radius * 2f * phase
				drawLine(
					Color.White.copy(alpha = (0.08f + 0.18f * pulse + 0.20f * eventWave) * strength),
					Offset(center.x - min * 0.055f, y),
					Offset(center.x + min * 0.055f, y + min * 0.05f),
					min * 0.010f,
					StrokeCap.Round,
				)
				drawBadgeTwinkle(
					Offset(center.x - radius * 0.75f, center.y + radius * 0.08f),
					Color(0xFFB9FFE8),
					min * 0.020f,
					(0.10f + 0.42f * pulseFast) * strength,
				)
			}

			BadgeAmbient.ARCANE -> {
				if (eventWave > 0.01f) {
					drawCircle(
						brush = Brush.radialGradient(
							listOf(
								Color.White.copy(alpha = 0.10f * eventWave * strength),
								secondary.copy(alpha = 0.08f * eventWave * strength),
								Color.Transparent,
							),
							center = center,
							radius = min * 0.25f,
						),
						radius = min * 0.25f,
						center = center,
					)
				}
				drawArc(
					Color.White.copy(alpha = (0.07f + 0.12f * pulse + 0.18f * eventWave) * strength),
					startAngle = 205f + phase * 20f,
					sweepAngle = 46f,
					useCenter = false,
					topLeft = Offset(center.x - radius, center.y - radius),
					size = Size(radius * 2f, radius * 2f),
					style = Stroke(min * 0.009f, cap = StrokeCap.Round),
				)
				if (sweepWave > 0.01f) {
					drawLine(
						Color(0xFFE8D3FF).copy(alpha = 0.28f * sweepWave * strength),
						Offset(center.x - radius * 0.45f + radius * 0.9f * sweepPhase, center.y - radius * 0.38f),
						Offset(center.x - radius * 0.32f + radius * 0.9f * sweepPhase, center.y + radius * 0.34f),
						min * 0.010f,
						StrokeCap.Round,
					)
				}
			}

			BadgeAmbient.MOON -> {
				drawCircle(
					primary.copy(alpha = (0.035f + 0.065f * pulse) * strength),
					radius * (0.86f + pulse * 0.025f),
					center,
					style = Stroke(min * 0.014f),
				)
				drawBadgeTwinkle(
					Offset(center.x - radius * 0.92f, center.y),
					Color.White,
					min * 0.018f,
					(0.10f + 0.38f * pulseFast) * strength,
				)
				drawBadgeTwinkle(
					Offset(center.x + radius * 0.92f, center.y),
					Color.White,
					min * 0.018f,
					(0.10f + 0.38f * (1f - pulseFast)) * strength,
				)
				drawBadgeTwinkle(
					Offset(center.x, center.y + radius * 1.02f),
					Color(0xFFF4D7FF),
					min * 0.016f,
					(0.08f + 0.28f * pulse + 0.20f * eventWave) * strength,
				)
			}

			BadgeAmbient.ROSE -> {
				drawCircle(
					brush = Brush.radialGradient(
						listOf(primary.copy(alpha = (0.06f + 0.035f * pulse) * strength), Color.Transparent),
						center = center,
						radius = radius * 0.82f,
					),
					radius = radius * 0.82f,
					center = center,
				)
				if (sweepWave > 0.01f) {
					drawArc(
						Color(0xFFFFE8F4).copy(alpha = 0.30f * sweepWave * strength),
						128f + 50f * sweepPhase,
						34f,
						false,
						Offset(center.x - radius * 0.7f, center.y - radius * 0.7f),
						Size(radius * 1.4f, radius * 1.4f),
						style = Stroke(min * 0.010f, cap = StrokeCap.Round),
					)
				}
				drawBadgeTwinkle(
					Offset(center.x + radius * 0.76f, center.y - radius * 0.42f),
					Color.White,
					min * 0.018f,
					(0.08f + 0.42f * pulseFast) * strength,
				)
			}

			BadgeAmbient.EMBER -> {
				val travel = phase
				val ember = Offset(
					center.x + radius * 0.70f,
					center.y + radius * 0.42f - radius * 0.95f * travel,
				)
				drawCircle(
					Color(0xFFFFB35D).copy(alpha = (0.12f + 0.42f * (1f - travel)) * strength),
					min * 0.010f,
					ember,
				)
				drawBadgeTwinkle(
					Offset(center.x, center.y - radius * 0.70f),
					Color(0xFFFFF1D0),
					min * 0.022f,
					(0.12f + 0.44f * pulseFast + 0.22f * eventWave) * strength,
				)
			}

			BadgeAmbient.MANUSCRIPT -> {
				drawBadgeTwinkle(
					Offset(center.x, center.y - radius * 1.02f),
					Color(0xFFFFE5A5),
					min * 0.021f,
					(0.12f + 0.44f * pulseFast) * strength,
				)
				drawBadgeTwinkle(
					Offset(center.x + radius * 0.58f, center.y - radius * 0.24f),
					Color.White,
					min * 0.017f,
					(0.10f + 0.38f * pulse) * strength,
				)
				if (sweepWave > 0.01f) {
					drawLine(
						Color(0xFFFFE9C3).copy(alpha = 0.24f * sweepWave * strength),
						Offset(center.x - radius * 0.46f, center.y - radius * 0.26f + radius * 0.50f * sweepPhase),
						Offset(center.x + radius * 0.34f, center.y - radius * 0.16f + radius * 0.50f * sweepPhase),
						min * 0.009f,
						StrokeCap.Round,
					)
				}
			}

			BadgeAmbient.ROYAL -> {
				if (eventWave > 0.01f) {
					drawCircle(
						brush = Brush.radialGradient(
							listOf(
								Color.White.copy(alpha = 0.13f * eventWave * strength),
								Color(0xFFFFE8A1).copy(alpha = 0.07f * eventWave * strength),
								Color.Transparent,
							),
							center = center,
							radius = min * 0.24f,
						),
						radius = min * 0.24f,
						center = center,
					)
				}
				drawBadgeTwinkle(
					Offset(center.x, center.y - radius * 1.02f),
					Color.White,
					min * 0.026f,
					(0.18f + 0.46f * pulseFast + 0.22f * eventWave) * strength,
				)
				drawBadgeTwinkle(
					Offset(center.x - radius * 0.72f, center.y + radius * 0.34f),
					Color(0xFF8CCBFF),
					min * 0.012f,
					(0.06f + 0.26f * pulseFast) * strength,
				)
				if (sweepWave > 0.01f) {
					drawArc(
						Color(0xFFFFEDB5).copy(alpha = 0.34f * sweepWave * strength),
						130f + sweepPhase * 160f,
						32f,
						false,
						Offset(center.x - radius, center.y - radius),
						Size(radius * 2f, radius * 2f),
						style = Stroke(min * 0.011f, cap = StrokeCap.Round),
					)
				}
			}

			BadgeAmbient.PRISM -> {
				drawArc(
					brush = Brush.sweepGradient(
						listOf(
							Color(0xFF88F2FF),
							Color(0xFF7FA1FF),
							Color(0xFFB878FF),
							Color(0xFFFF83D9),
							Color(0xFFFFE4A0),
							Color(0xFF88F2FF),
						),
						center,
					),
					alpha = (0.08f + 0.10f * pulse) * strength,
					startAngle = phase * 58f,
					sweepAngle = 105f,
					useCenter = false,
					topLeft = Offset(center.x - radius, center.y - radius),
					size = Size(radius * 2f, radius * 2f),
					style = Stroke(min * 0.009f, cap = StrokeCap.Round),
				)
				if (sweepWave > 0.01f) {
					val x = center.x - radius * 0.55f + radius * 1.10f * sweepPhase
					drawLine(
						Color.White.copy(alpha = 0.40f * sweepWave * strength),
						Offset(x - min * 0.08f, center.y - radius * 0.62f),
						Offset(x + min * 0.08f, center.y + radius * 0.54f),
						min * 0.012f,
						StrokeCap.Round,
					)
				}
				drawBadgeTwinkle(
					Offset(center.x - radius * 0.66f, center.y - radius * 0.60f),
					Color.White,
					min * 0.020f,
					(0.08f + 0.44f * pulseFast) * strength,
				)
			}

			BadgeAmbient.CELESTIAL -> {
				drawCircle(
					color = Color(0xFFD6F8FF).copy(alpha = (0.025f + 0.035f * pulse) * strength),
					radius = radius * (0.88f + 0.025f * pulse),
					center = center,
					style = Stroke(min * 0.010f),
				)
				val arcA = Path().apply {
					moveTo(center.x - radius, center.y)
					cubicTo(
						center.x - radius * 0.48f, center.y - radius * 0.58f,
						center.x + radius * 0.48f, center.y + radius * 0.58f,
						center.x + radius, center.y,
					)
				}
				val arcB = Path().apply {
					moveTo(center.x - radius, center.y)
					cubicTo(
						center.x - radius * 0.48f, center.y + radius * 0.58f,
						center.x + radius * 0.48f, center.y - radius * 0.58f,
						center.x + radius, center.y,
					)
				}
				drawPath(
					arcA,
					Color(0xFFFFF0C5).copy(alpha = 0.11f * strength),
					style = Stroke(min * 0.008f, cap = StrokeCap.Round),
				)
				drawPath(
					arcB,
					Color(0xFFD6F8FF).copy(alpha = 0.10f * strength),
					style = Stroke(min * 0.007f, cap = StrokeCap.Round),
				)
				val t = phase * 2f * PI
				fun infinityPoint(v: Double): Offset = Offset(
					center.x + sin(v).toFloat() * radius,
					center.y + sin(v * 2.0).toFloat() * radius * 0.48f,
				)
				drawLine(
					Color.White.copy(alpha = (0.28f + 0.16f * eventWave) * strength),
					infinityPoint(t),
					infinityPoint(t + 0.13),
					min * 0.012f,
					StrokeCap.Round,
				)
				drawBadgeTwinkle(
					Offset(center.x, center.y - radius * 1.05f),
					Color.White,
					min * 0.022f,
					(0.16f + 0.44f * pulseFast) * strength,
				)
				drawBadgeTwinkle(
					Offset(center.x + radius * 0.78f, center.y - radius * 0.50f),
					Color(0xFFFFF0C5),
					min * 0.014f,
					(0.07f + 0.30f * (1f - pulseFast)) * strength,
				)
			}
		}
	}
}

private fun DrawScope.drawBadgeTwinkle(
	center: Offset,
	color: Color,
	radius: Float,
	alpha: Float,
) {
	val a = alpha.coerceIn(0f, 1f)
	if (a <= 0.01f) return
	drawLine(
		color.copy(alpha = a),
		Offset(center.x - radius, center.y),
		Offset(center.x + radius, center.y),
		(radius * 0.20f).coerceAtLeast(0.8f),
		StrokeCap.Round,
	)
	drawLine(
		color.copy(alpha = a),
		Offset(center.x, center.y - radius),
		Offset(center.x, center.y + radius),
		(radius * 0.20f).coerceAtLeast(0.8f),
		StrokeCap.Round,
	)
	drawCircle(
		color.copy(alpha = (a * 0.94f).coerceAtMost(1f)),
		(radius * 0.17f).coerceAtLeast(0.8f),
		center,
	)
}

internal object ExclusiveBadgeRuntimeTestHooks {
	@Volatile
	var powerSaveModeOverride: Boolean? = null
}

@Composable
private fun rememberBadgePowerSaveMode(): Boolean {
	val context = LocalContext.current
	val powerManager = remember(context) {
		context.getSystemService(Context.POWER_SERVICE) as PowerManager
	}
	var powerSaveMode by remember(powerManager) { mutableStateOf(powerManager.isPowerSaveMode) }
	DisposableEffect(context, powerManager) {
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context?, intent: Intent?) {
				powerSaveMode = powerManager.isPowerSaveMode
			}
		}
		context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
		onDispose { context.unregisterReceiver(receiver) }
	}
	return if (BuildConfig.DEBUG) {
		ExclusiveBadgeRuntimeTestHooks.powerSaveModeOverride ?: powerSaveMode
	} else {
		powerSaveMode
	}
}
