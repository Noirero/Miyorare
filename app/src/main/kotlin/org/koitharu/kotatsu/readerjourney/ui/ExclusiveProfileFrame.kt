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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeTokens
import org.koitharu.kotatsu.readerjourney.theme.ReferenceRankThemeVisualSpec
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class ProfileFrameQualityMode {
	NORMAL,
	REDUCED,
	BATTERY_SAVER,
}

enum class ProfileFrameState {
	LOCKED,
	UNLOCKED,
	EQUIPPED,
	PREVIEWING,
}

internal enum class ProfileFrameAmbient {
	MICRO_GLINT,
	HALO,
	ORBIT,
	CRYSTAL,
	GLYPH,
	MOON,
	NEBULA,
	EMBER,
	LAUREL,
	CROWN,
	PRISM,
	CELESTIAL,
}

internal data class ProfileFrameAssetSpec(
	@DrawableRes val drawableRes: Int,
	val ambient: ProfileFrameAmbient,
	val idleDurationMs: Int,
	val oneShotDurationMs: Int,
	val glowAlpha: Float,
	val revealDurationMs: Int = 230,
	val sweepDurationMs: Int? = null,
	val avatarFraction: Float = 0.58f,
)

internal object ProfileFrameAssetRegistry {
	fun resolve(themeId: RankThemeId): ProfileFrameAssetSpec = when (themeId) {
		RankThemeId.FIRST_PAGE -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_01_first_page_silver_base,
			ProfileFrameAmbient.MICRO_GLINT,
			idleDurationMs = 15_000,
			oneShotDurationMs = 300,
			glowAlpha = 0.10f,
		)
		RankThemeId.FIRST_LIGHT -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_02_first_light_blue_base,
			ProfileFrameAmbient.HALO,
			idleDurationMs = 10_000,
			oneShotDurationMs = 340,
			glowAlpha = 0.11f,
		)
		RankThemeId.CYAN_CODEX -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_03_cyan_orbit_base,
			ProfileFrameAmbient.ORBIT,
			idleDurationMs = 14_000,
			oneShotDurationMs = 320,
			glowAlpha = 0.12f,
		)
		RankThemeId.EMERALD_COMPASS -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_04_emerald_pulse_base,
			ProfileFrameAmbient.CRYSTAL,
			idleDurationMs = 8_000,
			oneShotDurationMs = 380,
			glowAlpha = 0.12f,
		)
		RankThemeId.VIOLET_VAULT -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_05_arcane_scholar_base,
			ProfileFrameAmbient.GLYPH,
			idleDurationMs = 10_000,
			oneShotDurationMs = 360,
			glowAlpha = 0.13f,
			sweepDurationMs = 1_400,
		)
		RankThemeId.ARCANE_SCHOLAR -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_06_violet_halo_base,
			ProfileFrameAmbient.MOON,
			idleDurationMs = 10_000,
			oneShotDurationMs = 360,
			glowAlpha = 0.12f,
		)
		RankThemeId.NEON_ARCHIVE -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_07_rose_nebula_base,
			ProfileFrameAmbient.NEBULA,
			idleDurationMs = 12_000,
			oneShotDurationMs = 320,
			glowAlpha = 0.13f,
			sweepDurationMs = 1_600,
		)
		RankThemeId.CRIMSON_LIBRARY -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_08_crimson_ember_base,
			ProfileFrameAmbient.EMBER,
			idleDurationMs = 10_000,
			oneShotDurationMs = 380,
			glowAlpha = 0.13f,
		)
		RankThemeId.EMBER_VETERAN -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_09_amber_manuscript_base,
			ProfileFrameAmbient.LAUREL,
			idleDurationMs = 14_000,
			oneShotDurationMs = 420,
			glowAlpha = 0.14f,
			sweepDurationMs = 1_600,
		)
		RankThemeId.GOLDEN_MANUSCRIPT -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_10_golden_manuscript_deluxe_base,
			ProfileFrameAmbient.CROWN,
			idleDurationMs = 14_000,
			oneShotDurationMs = 420,
			glowAlpha = 0.15f,
			sweepDurationMs = 1_400,
		)
		RankThemeId.IMPERIAL_AURORA -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_11_eternal_library_prism_base,
			ProfileFrameAmbient.PRISM,
			idleDurationMs = 13_000,
			oneShotDurationMs = 420,
			glowAlpha = 0.16f,
			revealDurationMs = 245,
			sweepDurationMs = 1_400,
		)
		RankThemeId.ETERNAL_LIBRARY -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_12_celestial_infinity_base,
			ProfileFrameAmbient.CELESTIAL,
			idleDurationMs = 16_000,
			oneShotDurationMs = 420,
			glowAlpha = 0.17f,
			revealDurationMs = 255,
			sweepDurationMs = 1_600,
		)
	}
}

/**
 * Golden-reference renderer for the 12 Reader Journey Exclusive profile frames.
 *
 * The ornamental silhouette and most of the premium glow live in transparent local assets.
 * Runtime work is intentionally limited to the avatar, a restrained outer glow, one lightweight
 * authored ambient timeline, interaction reveal, level chip and state treatment.
 */
@Composable
fun ExclusiveProfileFrame(
	spec: ReferenceRankThemeVisualSpec,
	tokens: RankThemeTokens,
	modifier: Modifier = Modifier,
	levelText: String? = null,
	state: ProfileFrameState = ProfileFrameState.UNLOCKED,
	animate: Boolean = false,
	qualityMode: ProfileFrameQualityMode = ProfileFrameQualityMode.NORMAL,
	content: @Composable () -> Unit,
) {
	val asset = remember(spec.themeId) { ProfileFrameAssetRegistry.resolve(spec.themeId) }
	val primary = Color(tokens.primaryAccent.toInt())
	val secondary = Color(tokens.secondaryAccent.toInt())
	val reduceMotion by rememberBooleanPref(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, false)
	val powerSaveMode = rememberProfileFramePowerSaveMode()
	val effectiveQualityMode = if (powerSaveMode) ProfileFrameQualityMode.BATTERY_SAVER else qualityMode
	val effectiveAnimate = animate && !reduceMotion && !powerSaveMode
	val reveal = remember(spec.themeId, state, effectiveAnimate) { Animatable(1f) }
	val badgeReveal = remember(spec.themeId, state, effectiveAnimate, levelText) { Animatable(1f) }
	val glowReveal = remember(spec.themeId, state, effectiveAnimate) { Animatable(0.8f) }
	val accentEvent = remember(spec.themeId, state, effectiveAnimate) { Animatable(1f) }
	val sweepEvent = remember(spec.themeId, state, effectiveAnimate) { Animatable(1f) }
	val revealEnabled = effectiveAnimate &&
		(state == ProfileFrameState.EQUIPPED || state == ProfileFrameState.PREVIEWING)

	LaunchedEffect(spec.themeId, state, effectiveAnimate) {
		if (revealEnabled) {
			reveal.snapTo(0f)
			reveal.animateTo(
				targetValue = 1f,
				animationSpec = tween(durationMillis = asset.revealDurationMs, easing = FastOutSlowInEasing),
			)
		} else {
			reveal.snapTo(1f)
		}
	}
	LaunchedEffect(spec.themeId, state, effectiveAnimate) {
		if (revealEnabled) {
			glowReveal.snapTo(0f)
			glowReveal.animateTo(
				targetValue = 0.8f,
				animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
			)
		} else {
			glowReveal.snapTo(0.8f)
		}
	}
	LaunchedEffect(spec.themeId, state, effectiveAnimate) {
		if (revealEnabled) {
			accentEvent.snapTo(0f)
			delay(220)
			accentEvent.animateTo(
				targetValue = 1f,
				animationSpec = tween(asset.oneShotDurationMs, easing = FastOutSlowInEasing),
			)
		} else {
			accentEvent.snapTo(1f)
		}
	}
	LaunchedEffect(spec.themeId, state, effectiveAnimate) {
		val duration = asset.sweepDurationMs
		if (revealEnabled && duration != null) {
			sweepEvent.snapTo(0f)
			delay(220)
			sweepEvent.animateTo(
				targetValue = 1f,
				animationSpec = tween(durationMillis = duration, easing = LinearEasing),
			)
		} else {
			sweepEvent.snapTo(1f)
		}
	}
	LaunchedEffect(spec.themeId, state, effectiveAnimate, levelText) {
		if (levelText != null && revealEnabled) {
			badgeReveal.snapTo(0f)
			delay(45)
			badgeReveal.animateTo(
				targetValue = 1f,
				animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
			)
		} else {
			badgeReveal.snapTo(1f)
		}
	}

	val locked = state == ProfileFrameState.LOCKED
	val runtimeGlowAlpha = when (effectiveQualityMode) {
		ProfileFrameQualityMode.NORMAL -> asset.glowAlpha
		ProfileFrameQualityMode.REDUCED -> asset.glowAlpha * 0.48f
		ProfileFrameQualityMode.BATTERY_SAVER -> asset.glowAlpha * 0.28f
	}
	val idleEnabled = effectiveAnimate &&
		!locked &&
		effectiveQualityMode != ProfileFrameQualityMode.BATTERY_SAVER &&
		(state == ProfileFrameState.EQUIPPED || state == ProfileFrameState.PREVIEWING)
	val lockedColorFilter = remember(locked) {
		if (!locked) {
			null
		} else {
			ColorFilter.colorMatrix(
				ColorMatrix().apply { setToSaturation(0.45f) },
			)
		}
	}

	Box(
		modifier = modifier.graphicsLayer {
			alpha = reveal.value
			val frameScale = 0.96f + 0.04f * reveal.value
			scaleX = frameScale
			scaleY = frameScale
		},
		contentAlignment = Alignment.Center,
	) {
		Canvas(modifier = Modifier.fillMaxSize()) {
			val settle = (glowReveal.value / 0.8f).coerceIn(0f, 1f)
			drawCircle(
				brush = Brush.radialGradient(
					colors = listOf(
						primary.copy(alpha = runtimeGlowAlpha * settle),
						secondary.copy(alpha = runtimeGlowAlpha * 0.46f * settle),
						Color.Transparent,
					),
					center = center,
					radius = size.minDimension * 0.49f,
				),
				radius = size.minDimension * 0.49f,
				center = center,
			)
		}

		Box(
			modifier = Modifier
				.fillMaxSize(asset.avatarFraction)
				.clip(CircleShape),
			contentAlignment = Alignment.Center,
		) {
			content()
			if (locked) {
				Box(
					modifier = Modifier
						.fillMaxSize()
						.background(Color.Black.copy(alpha = 0.30f)),
				)
			}
		}

		Image(
			painter = painterResource(asset.drawableRes),
			contentDescription = null,
			colorFilter = lockedColorFilter,
			modifier = Modifier
				.fillMaxSize()
				.alpha(if (locked) 0.50f else 1f),
		)

		if (idleEnabled) {
			ProfileFrameAmbientOverlay(
				asset = asset,
				primary = primary,
				secondary = secondary,
				eventPhase = accentEvent.value,
				sweepPhase = sweepEvent.value,
				qualityMode = effectiveQualityMode,
				previewing = state == ProfileFrameState.PREVIEWING,
			)
		}

		if (levelText != null) {
			val badgeShape = RoundedCornerShape(50)
			Box(
				modifier = Modifier
					.align(Alignment.BottomCenter)
					.padding(bottom = 7.dp)
					.graphicsLayer {
						alpha = 0.35f + badgeReveal.value * 0.65f
						val badgeScale = 0.92f + badgeReveal.value * 0.08f
						scaleX = badgeScale
						scaleY = badgeScale
					}
					.clip(badgeShape)
					.background(Color(0xF014161D))
					.border(
						width = 1.dp,
						color = if (locked) {
							Color.White.copy(alpha = 0.32f)
						} else {
							Color.White.copy(alpha = 0.84f)
						},
						shape = badgeShape,
					)
					.padding(horizontal = 7.dp, vertical = 1.dp),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = levelText,
					fontSize = 8.5.sp,
					lineHeight = 9.sp,
					fontWeight = FontWeight.Bold,
					color = Color.White.copy(alpha = if (locked) 0.64f else 1f),
				)
			}
		}

		if (locked) {
			Box(
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(7.dp)
					.size(21.dp)
					.clip(CircleShape)
					.background(Color(0xD811131A))
					.border(1.dp, Color.White.copy(alpha = 0.30f), CircleShape),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painter = painterResource(R.drawable.ic_lock),
					contentDescription = null,
					tint = Color.White.copy(alpha = 0.84f),
					modifier = Modifier.size(12.dp),
				)
			}
		}
	}
}

@Composable
private fun ProfileFrameAmbientOverlay(
	asset: ProfileFrameAssetSpec,
	primary: Color,
	secondary: Color,
	eventPhase: Float,
	sweepPhase: Float,
	qualityMode: ProfileFrameQualityMode,
	previewing: Boolean,
) {
	val transition = rememberInfiniteTransition(label = "exclusive-profile-frame-idle")
	val phase by transition.animateFloat(
		initialValue = 0f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(
			animation = tween(asset.idleDurationMs, easing = LinearEasing),
			repeatMode = RepeatMode.Restart,
		),
		label = "exclusive-profile-frame-phase",
	)
	val qualityScale = when (qualityMode) {
		ProfileFrameQualityMode.NORMAL -> 1f
		ProfileFrameQualityMode.REDUCED -> 0.48f
		ProfileFrameQualityMode.BATTERY_SAVER -> 0f
	}
	val strength = (if (previewing) 0.58f else 1f) * qualityScale

	Canvas(modifier = Modifier.fillMaxSize()) {
		val min = size.minDimension
		val radius = min * 0.405f
		val pulse = ((sin(phase * 2f * PI).toFloat() + 1f) * 0.5f)
		val pulseFast = ((sin(phase * 4f * PI).toFloat() + 1f) * 0.5f)
		val eventWave = sin(PI * eventPhase).toFloat().coerceAtLeast(0f)
		val angle = phase * 2f * PI
		val orbitPoint = Offset(
			x = center.x + cos(angle).toFloat() * radius,
			y = center.y + sin(angle).toFloat() * radius,
		)

		when (asset.ambient) {
			ProfileFrameAmbient.MICRO_GLINT -> {
				drawFrameTwinkle(
					Offset(center.x, center.y - radius),
					Color.White,
					min * (0.010f + 0.008f * pulse),
					(0.18f + 0.54f * pulse + 0.20f * eventWave) * strength,
				)
			}

			ProfileFrameAmbient.HALO -> {
				drawCircle(
					color = primary.copy(alpha = (0.05f + 0.07f * pulse + 0.09f * eventWave) * strength),
					radius = radius + min * (0.040f + 0.014f * eventWave),
					center = center,
					style = Stroke(width = min * 0.014f),
				)
				drawFrameTwinkle(
					Offset(center.x + radius * 0.68f, center.y - radius * 0.63f),
					Color.White,
					min * 0.013f,
					(0.22f + 0.50f * pulseFast + 0.20f * eventWave) * strength,
				)
			}

			ProfileFrameAmbient.ORBIT -> {
				val angle2 = angle + PI * 0.82
				val orbitPoint2 = Offset(
					x = center.x + cos(angle2).toFloat() * radius * 0.92f,
					y = center.y + sin(angle2).toFloat() * radius * 0.92f,
				)
				drawCircle(
					color = secondary.copy(alpha = 0.24f * strength),
					radius = min * 0.031f,
					center = orbitPoint,
				)
				drawCircle(
					color = Color.White.copy(alpha = 0.82f * strength),
					radius = min * 0.014f,
					center = orbitPoint,
				)
				drawCircle(
					color = primary.copy(alpha = 0.44f * strength),
					radius = min * 0.012f,
					center = orbitPoint2,
				)
				if (pulseFast > 0.72f) {
					drawFrameTwinkle(
						orbitPoint,
						Color.White,
						min * 0.012f,
						((pulseFast - 0.72f) / 0.28f) * strength,
					)
				}
			}

			ProfileFrameAmbient.CRYSTAL -> {
				val crystalAlpha = (0.20f + 0.54f * pulse + 0.20f * eventWave) * strength
				drawFrameTwinkle(Offset(center.x, center.y - radius), Color.White, min * 0.014f, crystalAlpha)
				drawFrameTwinkle(
					Offset(center.x + radius * 0.82f, center.y - radius * 0.12f),
					Color.White,
					min * 0.010f,
					crystalAlpha * 0.72f,
				)
				drawCircle(
					color = primary.copy(alpha = 0.06f * eventWave * strength),
					radius = radius + min * 0.025f,
					center = center,
					style = Stroke(width = min * 0.018f),
				)
			}

			ProfileFrameAmbient.GLYPH -> {
				drawArc(
					color = Color.White.copy(alpha = (0.08f + 0.18f * pulse + 0.24f * eventWave) * strength),
					startAngle = 205f + phase * 24f,
					sweepAngle = 54f,
					useCenter = false,
					topLeft = Offset(center.x - radius, center.y - radius),
					size = Size(radius * 2f, radius * 2f),
					style = Stroke(width = min * 0.009f, cap = StrokeCap.Round),
				)
				drawFrameTwinkle(
					Offset(center.x - radius * 0.86f, center.y + radius * 0.12f),
					secondary,
					min * 0.012f,
					(0.16f + 0.46f * pulseFast) * strength,
				)
			}

			ProfileFrameAmbient.MOON -> {
				drawCircle(
					color = primary.copy(alpha = (0.04f + 0.07f * pulse) * strength),
					radius = radius + min * 0.040f,
					center = center,
					style = Stroke(width = min * 0.016f),
				)
				val sideAlpha = (0.15f + 0.46f * pulseFast) * strength
				drawFrameTwinkle(
					Offset(center.x - radius * 0.92f, center.y),
					Color.White,
					min * 0.010f,
					sideAlpha,
				)
				drawFrameTwinkle(
					Offset(center.x + radius * 0.92f, center.y),
					Color.White,
					min * 0.010f,
					(0.15f + 0.46f * (1f - pulseFast)) * strength,
				)
				drawFrameTwinkle(
					Offset(center.x, center.y - radius * 1.02f),
					Color(0xFFFFF0C2),
					min * 0.013f,
					(0.18f + 0.36f * pulse + 0.18f * eventWave) * strength,
				)
			}

			ProfileFrameAmbient.NEBULA -> {
				drawCircle(
					brush = Brush.radialGradient(
						listOf(primary.copy(alpha = (0.07f + 0.05f * pulse) * strength), Color.Transparent),
						center = Offset(center.x + radius * 0.72f, center.y - radius * 0.16f),
						radius = min * 0.18f,
					),
					radius = min * 0.18f,
					center = Offset(center.x + radius * 0.72f, center.y - radius * 0.16f),
				)
				drawFrameTwinkle(
					Offset(center.x + radius * 0.80f, center.y - radius * 0.34f),
					Color.White,
					min * 0.011f,
					(0.16f + 0.46f * pulseFast + 0.16f * eventWave) * strength,
				)
				val sheenAlpha = sin(PI * sweepPhase).toFloat().coerceAtLeast(0f)
				if (sheenAlpha > 0.01f) {
					drawArc(
						color = Color(0xFFFFE4EA).copy(alpha = 0.30f * sheenAlpha * strength),
						startAngle = 128f + 42f * sweepPhase,
						sweepAngle = 34f,
						useCenter = false,
						topLeft = Offset(center.x - radius, center.y - radius),
						size = Size(radius * 2f, radius * 2f),
						style = Stroke(width = min * 0.010f, cap = StrokeCap.Round),
					)
				}
			}

			ProfileFrameAmbient.EMBER -> {
				val flicker = (0.12f + 0.56f * pulseFast) * strength
				drawFrameTwinkle(
					Offset(center.x, center.y - radius),
					Color(0xFFFFF1D0),
					min * 0.013f,
					flicker + 0.20f * eventWave,
				)
				drawFrameTwinkle(
					Offset(center.x + radius * 0.78f, center.y + radius * 0.18f),
					Color(0xFFFFD070),
					min * 0.009f,
					(0.10f + 0.38f * (1f - pulseFast)) * strength,
				)
			}

			ProfileFrameAmbient.LAUREL -> {
				val left = (0.12f + 0.54f * pulse) * strength
				val right = (0.12f + 0.54f * (1f - pulse)) * strength
				drawFrameTwinkle(
					Offset(center.x - radius * 0.80f, center.y + radius * 0.28f),
					Color(0xFFFFE3A0),
					min * 0.010f,
					left,
				)
				drawFrameTwinkle(
					Offset(center.x + radius * 0.80f, center.y - radius * 0.24f),
					Color(0xFFFFE3A0),
					min * 0.010f,
					right,
				)
				drawFrameTwinkle(
					Offset(center.x, center.y - radius),
					Color.White,
					min * 0.009f,
					(0.12f + 0.36f * pulseFast) * strength,
				)
				val goldSweep = sin(PI * sweepPhase).toFloat().coerceAtLeast(0f)
				if (goldSweep > 0.01f) {
					drawArc(
						color = Color(0xFFFFF0B0).copy(alpha = 0.34f * goldSweep * strength),
						startAngle = 120f + sweepPhase * 180f,
						sweepAngle = 28f,
						useCenter = false,
						topLeft = Offset(center.x - radius, center.y - radius),
						size = Size(radius * 2f, radius * 2f),
						style = Stroke(width = min * 0.010f, cap = StrokeCap.Round),
					)
				}
			}

			ProfileFrameAmbient.CROWN -> {
				drawCircle(
					color = secondary.copy(alpha = (0.03f + 0.05f * pulse) * strength),
					radius = radius * 0.84f,
					center = center,
					style = Stroke(width = min * 0.012f),
				)
				drawFrameTwinkle(
					Offset(center.x, center.y - radius * 1.03f),
					Color.White,
					min * 0.014f,
					(0.20f + 0.46f * pulseFast + 0.24f * eventWave) * strength,
				)
				drawFrameTwinkle(
					Offset(center.x - radius * 0.78f, center.y + radius * 0.22f),
					Color(0xFFFFE1A0),
					min * 0.009f,
					(0.12f + 0.40f * pulse) * strength,
				)
				drawFrameTwinkle(
					Offset(center.x + radius * 0.78f, center.y - radius * 0.20f),
					Color(0xFFFFE1A0),
					min * 0.009f,
					(0.12f + 0.40f * (1f - pulse)) * strength,
				)
				val regal = sin(PI * sweepPhase).toFloat().coerceAtLeast(0f)
				if (regal > 0.01f) {
					drawArc(
						color = Color.White.copy(alpha = 0.38f * regal * strength),
						startAngle = -110f + sweepPhase * 220f,
						sweepAngle = 32f,
						useCenter = false,
						topLeft = Offset(center.x - radius, center.y - radius),
						size = Size(radius * 2f, radius * 2f),
						style = Stroke(width = min * 0.011f, cap = StrokeCap.Round),
					)
				}
			}

			ProfileFrameAmbient.PRISM -> {
				drawArc(
					brush = Brush.sweepGradient(
						listOf(
							Color(0xFF54E9FF),
							Color(0xFF6F7CFF),
							Color(0xFFFF6BD5),
							Color(0xFFFFE7A3),
							Color(0xFF54E9FF),
						),
						center = center,
					),
					alpha = (0.12f + 0.12f * pulse) * strength,
					startAngle = phase * 360f,
					sweepAngle = 118f,
					useCenter = false,
					topLeft = Offset(center.x - radius, center.y - radius),
					size = Size(radius * 2f, radius * 2f),
					style = Stroke(width = min * 0.008f, cap = StrokeCap.Round),
				)
				drawArc(
					color = Color.White.copy(alpha = (0.15f + 0.22f * pulse + 0.18f * eventWave) * strength),
					startAngle = phase * 360f,
					sweepAngle = 30f,
					useCenter = false,
					topLeft = Offset(center.x - radius, center.y - radius),
					size = Size(radius * 2f, radius * 2f),
					style = Stroke(width = min * 0.010f, cap = StrokeCap.Round),
				)
				drawFrameTwinkle(
					Offset(center.x - radius * 0.72f, center.y - radius * 0.68f),
					Color.White,
					min * 0.011f,
					(0.10f + 0.46f * pulseFast) * strength,
				)
				drawFrameTwinkle(
					Offset(center.x + radius * 0.74f, center.y + radius * 0.68f),
					secondary,
					min * 0.010f,
					(0.10f + 0.40f * (1f - pulseFast)) * strength,
				)
				val prismSweep = sin(PI * sweepPhase).toFloat().coerceAtLeast(0f)
				if (prismSweep > 0.01f) {
					drawArc(
						color = Color.White.copy(alpha = 0.42f * prismSweep * strength),
						startAngle = -100f + sweepPhase * 300f,
						sweepAngle = 24f,
						useCenter = false,
						topLeft = Offset(center.x - radius, center.y - radius),
						size = Size(radius * 2f, radius * 2f),
						style = Stroke(width = min * 0.011f, cap = StrokeCap.Round),
					)
				}
			}

			ProfileFrameAmbient.CELESTIAL -> {
				val halo = 0.04f + 0.055f * pulse
				val drift = sin(phase * 2f * PI).toFloat() * min * 0.008f
				val arcA = Path().apply {
					moveTo(center.x - radius * 0.92f, center.y + drift)
					cubicTo(
						center.x - radius * 0.45f, center.y - radius * 0.56f + drift,
						center.x + radius * 0.45f, center.y + radius * 0.56f - drift,
						center.x + radius * 0.92f, center.y - drift,
					)
				}
				val arcB = Path().apply {
					moveTo(center.x - radius * 0.92f, center.y - drift)
					cubicTo(
						center.x - radius * 0.45f, center.y + radius * 0.56f - drift,
						center.x + radius * 0.45f, center.y - radius * 0.56f + drift,
						center.x + radius * 0.92f, center.y + drift,
					)
				}
				drawPath(arcA, Color(0xFFFFE8AF).copy(alpha = 0.12f * strength), style = Stroke(min * 0.007f, cap = StrokeCap.Round))
				drawPath(arcB, Color(0xFFB8F4FF).copy(alpha = 0.10f * strength), style = Stroke(min * 0.006f, cap = StrokeCap.Round))
				drawCircle(
					color = Color.White.copy(alpha = halo * strength),
					radius = radius * 0.88f,
					center = center,
					style = Stroke(width = min * 0.011f),
				)
				val t = phase * 2f * PI
				val t2 = t + 0.10
				fun infinityPoint(angleValue: Double): Offset = Offset(
					x = center.x + sin(angleValue).toFloat() * radius * 0.92f,
					y = center.y + sin(angleValue * 2.0).toFloat() * radius * 0.43f,
				)
				drawLine(
					color = Color.White.copy(alpha = (0.24f + 0.18f * eventWave) * strength),
					start = infinityPoint(t),
					end = infinityPoint(t2),
					strokeWidth = min * 0.012f,
					cap = StrokeCap.Round,
				)
				drawFrameTwinkle(
					Offset(center.x, center.y - radius * 1.04f),
					Color.White,
					min * 0.013f,
					(0.18f + 0.42f * pulseFast + 0.16f * eventWave) * strength,
				)
				drawFrameTwinkle(
					Offset(center.x + radius * 0.76f, center.y - radius * 0.58f),
					Color(0xFFFFEDC2),
					min * 0.009f,
					(0.10f + 0.36f * (1f - pulseFast)) * strength,
				)
			}
		}
	}
}

private fun DrawScope.drawFrameTwinkle(
	center: Offset,
	color: Color,
	radius: Float,
	alpha: Float,
) {
	val a = alpha.coerceIn(0f, 1f)
	if (a <= 0.01f) return
	drawLine(
		color = color.copy(alpha = a),
		start = Offset(center.x - radius, center.y),
		end = Offset(center.x + radius, center.y),
		strokeWidth = (radius * 0.22f).coerceAtLeast(0.8f),
		cap = StrokeCap.Round,
	)
	drawLine(
		color = color.copy(alpha = a),
		start = Offset(center.x, center.y - radius),
		end = Offset(center.x, center.y + radius),
		strokeWidth = (radius * 0.22f).coerceAtLeast(0.8f),
		cap = StrokeCap.Round,
	)
	drawCircle(
		color = color.copy(alpha = (a * 0.94f).coerceAtMost(1f)),
		radius = (radius * 0.18f).coerceAtLeast(0.8f),
		center = center,
	)
}

@Composable
private fun rememberProfileFramePowerSaveMode(): Boolean {
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
	return powerSaveMode
}
