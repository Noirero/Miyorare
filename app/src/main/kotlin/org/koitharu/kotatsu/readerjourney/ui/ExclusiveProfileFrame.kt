package org.koitharu.kotatsu.readerjourney.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeTokens
import org.koitharu.kotatsu.readerjourney.theme.ReferenceRankThemeVisualSpec
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

private enum class ProfileFrameAmbient {
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

private data class ProfileFrameAssetSpec(
	@DrawableRes val drawableRes: Int,
	val ambient: ProfileFrameAmbient,
	val idleDurationMs: Int,
	val glowAlpha: Float,
	val avatarFraction: Float = 0.58f,
)

private object ProfileFrameAssetRegistry {
	fun resolve(themeId: RankThemeId): ProfileFrameAssetSpec = when (themeId) {
		RankThemeId.FIRST_PAGE -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_first_page_silver, ProfileFrameAmbient.MICRO_GLINT, 15_000, 0.12f,
		)
		RankThemeId.FIRST_LIGHT -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_first_light_blue, ProfileFrameAmbient.HALO, 12_000, 0.13f,
		)
		RankThemeId.CYAN_CODEX -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_cyan_orbit, ProfileFrameAmbient.ORBIT, 14_000, 0.14f,
		)
		RankThemeId.EMERALD_COMPASS -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_emerald_pulse, ProfileFrameAmbient.CRYSTAL, 8_000, 0.13f,
		)
		RankThemeId.VIOLET_VAULT -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_arcane_scholar, ProfileFrameAmbient.GLYPH, 10_000, 0.14f,
		)
		RankThemeId.ARCANE_SCHOLAR -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_violet_halo, ProfileFrameAmbient.MOON, 10_000, 0.13f,
		)
		RankThemeId.NEON_ARCHIVE -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_rose_nebula, ProfileFrameAmbient.NEBULA, 12_000, 0.14f,
		)
		RankThemeId.CRIMSON_LIBRARY -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_crimson_ember, ProfileFrameAmbient.EMBER, 10_000, 0.15f,
		)
		RankThemeId.EMBER_VETERAN -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_amber_manuscript, ProfileFrameAmbient.LAUREL, 14_000, 0.15f,
		)
		RankThemeId.GOLDEN_MANUSCRIPT -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_golden_manuscript_deluxe, ProfileFrameAmbient.CROWN, 14_000, 0.16f,
		)
		RankThemeId.IMPERIAL_AURORA -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_eternal_library_prism, ProfileFrameAmbient.PRISM, 13_000, 0.17f,
		)
		RankThemeId.ETERNAL_LIBRARY -> ProfileFrameAssetSpec(
			R.drawable.profile_frame_celestial_infinity, ProfileFrameAmbient.CELESTIAL, 16_000, 0.18f,
		)
	}
}

/**
 * Asset-first renderer for the 12 Reader Journey profile frames.
 *
 * Ornament geometry is stored in transparent drawables. Runtime code only owns the avatar,
 * restrained glow, state treatment, a separate level chip and at most one small ambient motion.
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
	val reveal = remember(spec.themeId, state, animate) { Animatable(1f) }
	val badgeReveal = remember(spec.themeId, state, animate, levelText) { Animatable(1f) }
	val revealEnabled = animate &&
		(state == ProfileFrameState.EQUIPPED || state == ProfileFrameState.PREVIEWING)

	LaunchedEffect(spec.themeId, state, animate) {
		if (revealEnabled) {
			reveal.snapTo(0f)
			reveal.animateTo(1f, tween(durationMillis = 230, easing = FastOutSlowInEasing))
		} else {
			reveal.snapTo(1f)
		}
	}
	LaunchedEffect(spec.themeId, state, animate, levelText) {
		if (levelText != null && revealEnabled) {
			badgeReveal.snapTo(0f)
			delay(45)
			badgeReveal.animateTo(1f, tween(durationMillis = 190, easing = FastOutSlowInEasing))
		} else {
			badgeReveal.snapTo(1f)
		}
	}

	val locked = state == ProfileFrameState.LOCKED
	val runtimeGlowAlpha = when (qualityMode) {
		ProfileFrameQualityMode.NORMAL -> asset.glowAlpha
		ProfileFrameQualityMode.REDUCED -> asset.glowAlpha * 0.48f
		ProfileFrameQualityMode.BATTERY_SAVER -> asset.glowAlpha * 0.28f
	}
	val idleEnabled = animate &&
		!locked &&
		qualityMode == ProfileFrameQualityMode.NORMAL &&
		(state == ProfileFrameState.EQUIPPED || state == ProfileFrameState.PREVIEWING)

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
			drawCircle(
				brush = Brush.radialGradient(
					colors = listOf(
						primary.copy(alpha = runtimeGlowAlpha),
						secondary.copy(alpha = runtimeGlowAlpha * 0.55f),
						Color.Transparent,
					),
					center = center,
					radius = size.minDimension * 0.50f,
				),
				radius = size.minDimension * 0.50f,
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
						.background(Color.Black.copy(alpha = 0.28f)),
				)
			}
		}

		Image(
			painter = painterResource(asset.drawableRes),
			contentDescription = null,
			modifier = Modifier
				.fillMaxSize()
				.alpha(if (locked) 0.46f else 1f),
		)

		if (idleEnabled) {
			ProfileFrameAmbientOverlay(
				asset = asset,
				primary = primary,
				secondary = secondary,
				previewing = state == ProfileFrameState.PREVIEWING,
			)
		}

		if (levelText != null) {
			val badgeShape = RoundedCornerShape(50)
			Box(
				modifier = Modifier
					.align(Alignment.BottomCenter)
					.padding(bottom = 8.dp)
					.graphicsLayer {
						alpha = 0.35f + badgeReveal.value * 0.65f
						val badgeScale = 0.92f + badgeReveal.value * 0.08f
						scaleX = badgeScale
						scaleY = badgeScale
					}
					.clip(badgeShape)
					.background(Color(0xE814161D))
					.border(
						width = 1.dp,
						color = primary.copy(alpha = if (locked) 0.36f else 0.76f),
						shape = badgeShape,
					)
					.padding(horizontal = 8.dp, vertical = 3.dp),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = levelText,
					style = MaterialTheme.typography.labelSmall,
					fontWeight = FontWeight.Bold,
					color = Color.White.copy(alpha = if (locked) 0.62f else 1f),
				)
			}
		}

		if (locked) {
			Box(
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(7.dp)
					.size(22.dp)
					.clip(CircleShape)
					.background(Color(0xCC11131A))
					.border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painter = painterResource(R.drawable.ic_lock),
					contentDescription = null,
					tint = Color.White.copy(alpha = 0.82f),
					modifier = Modifier.size(13.dp),
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
	val strength = if (previewing) 0.58f else 1f

	Canvas(modifier = Modifier.fillMaxSize()) {
		val min = size.minDimension
		val radius = min * 0.405f
		val pulse = (sin(phase * 2f * PI).toFloat() + 1f) * 0.5f
		val angle = phase * 2f * PI
		val orbitPoint = Offset(
			x = center.x + cos(angle).toFloat() * radius,
			y = center.y + sin(angle).toFloat() * radius,
		)

		when (asset.ambient) {
			ProfileFrameAmbient.ORBIT -> {
				drawCircle(
					color = secondary.copy(alpha = 0.26f * strength),
					radius = min * 0.035f,
					center = orbitPoint,
				)
				drawCircle(
					color = Color.White.copy(alpha = 0.72f * strength),
					radius = min * 0.018f,
					center = orbitPoint,
				)
			}

			ProfileFrameAmbient.PRISM,
			ProfileFrameAmbient.CELESTIAL -> {
				drawArc(
					color = Color.White.copy(alpha = (0.18f + pulse * 0.28f) * strength),
					startAngle = phase * 360f,
					sweepAngle = if (asset.ambient == ProfileFrameAmbient.CELESTIAL) 42f else 30f,
					useCenter = false,
					topLeft = Offset(center.x - radius, center.y - radius),
					size = Size(radius * 2f, radius * 2f),
					style = Stroke(width = (min * 0.012f).coerceAtLeast(1f)),
				)
				drawCircle(
					color = if (asset.ambient == ProfileFrameAmbient.PRISM) secondary else Color.White,
					radius = min * 0.011f,
					center = orbitPoint,
					alpha = 0.65f * strength,
				)
			}

			ProfileFrameAmbient.HALO,
			ProfileFrameAmbient.MOON,
			ProfileFrameAmbient.NEBULA -> {
				drawCircle(
					color = primary.copy(alpha = (0.045f + pulse * 0.075f) * strength),
					radius = radius + min * 0.045f,
					center = center,
					style = Stroke(width = min * 0.018f),
				)
			}

			else -> {
				val fixedAngle = when (asset.ambient) {
					ProfileFrameAmbient.MICRO_GLINT -> -PI / 2
					ProfileFrameAmbient.CRYSTAL -> -PI / 3
					ProfileFrameAmbient.GLYPH -> PI / 4
					ProfileFrameAmbient.EMBER -> PI / 6
					ProfileFrameAmbient.LAUREL -> PI * 0.72
					ProfileFrameAmbient.CROWN -> -PI / 2
					else -> -PI / 2
				}
				val p = Offset(
					x = center.x + cos(fixedAngle).toFloat() * radius,
					y = center.y + sin(fixedAngle).toFloat() * radius,
				)
				drawCircle(
					color = Color.White.copy(alpha = (0.16f + pulse * 0.54f) * strength),
					radius = min * (0.007f + pulse * 0.008f),
					center = p,
				)
			}
		}
	}
}
