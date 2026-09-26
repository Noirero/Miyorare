package org.koitharu.kotatsu.main.ui.nav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.core.ui.ExclusiveThemeComponentPalette
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveBottomNavigationRegistry
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveBottomNavigationSpec
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveNavigationActiveShape
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveNavigationIndicator
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveNavigationMotion
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveNavigationOrnament
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveNavigationSilhouette
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Real UI renderer for all 12 Exclusive bottom-navigation presets.
 *
 * The five slots never change position or width. Theme differences are purely visual: body chrome,
 * selected-state shape, ornament language, glow and bounded micro-motion.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExclusiveBottomNavigationBar(
	items: List<FloatingNavBarItem>,
	selectedId: Int,
	showLabels: Boolean,
	spec: ExclusiveBottomNavigationSpec,
	palette: ExclusiveThemeComponentPalette,
	onItemSelected: (Int) -> Unit,
	onItemReselected: (Int) -> Unit,
	onItemLongClick: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	if (items.isEmpty()) return

	val reduceMotion by rememberBooleanPref(AppSettings.KEY_RANK_THEME_REDUCE_MOTION, false)
	val reduceGlow by rememberBooleanPref(AppSettings.KEY_RANK_THEME_REDUCE_GLOW, false)
	val minimalCosmetics by rememberBooleanPref(AppSettings.KEY_RANK_THEME_MINIMAL_COSMETICS, false)
	val powerSaveMode = rememberPowerSaveMode()
	val ambientEnabled = !reduceMotion && !minimalCosmetics && !powerSaveMode && spec.ambientCycleMs != null
	val ambientPhase = if (ambientEnabled) {
		val transition = rememberInfiniteTransition(label = "exclusiveNavAmbient")
		val phase by transition.animateFloat(
			initialValue = 0f,
			targetValue = 1f,
			animationSpec = infiniteRepeatable(
				animation = tween(
					durationMillis = spec.ambientCycleMs ?: 10_000,
					easing = LinearEasing,
				),
				repeatMode = RepeatMode.Restart,
			),
			label = "exclusiveNavAmbientPhase",
		)
		phase
	} else {
		0f
	}

	val selectionEvent = remember(spec.stableId) { Animatable(1f) }
	LaunchedEffect(selectedId, spec.stableId, reduceMotion) {
		if (reduceMotion) {
			selectionEvent.snapTo(1f)
		} else {
			selectionEvent.snapTo(0f)
			selectionEvent.animateTo(
				targetValue = 1f,
				animationSpec = tween(
					durationMillis = spec.selectionAccentDurationMs,
					easing = LinearEasing,
				),
			)
		}
	}

	val containerBrush = remember(palette.containerStops) {
		Brush.horizontalGradient(palette.containerStops)
	}
	val borderBrush = remember(palette.borderStops) {
		Brush.horizontalGradient(palette.borderStops)
	}
	val glowBrush = remember(palette.glowStops) {
		Brush.horizontalGradient(palette.glowStops)
	}
	val selectedIndex = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
	val radiusDp = spec.cornerRadiusDp.dp

	Box(
		modifier = modifier
			.height(spec.heightDp.dp)
			.drawBehind {
				val radius = radiusDp.toPx()
				val selectedX = if (items.isNotEmpty()) {
					size.width * (selectedIndex + 0.5f) / items.size
				} else {
					size.width / 2f
				}
				drawExclusiveBody(
					spec = spec,
					containerBrush = containerBrush,
					borderBrush = borderBrush,
					glowBrush = glowBrush,
					radiusPx = radius,
					selectedX = selectedX,
					ambientPhase = ambientPhase,
					selectionEventPhase = selectionEvent.value,
					reduceGlow = reduceGlow || minimalCosmetics,
				)
			},
		contentAlignment = Alignment.Center,
	) {
		Row(
			modifier = Modifier
				.fillMaxSize()
				.padding(horizontal = 4.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			items.forEach { item ->
				ExclusiveNavigationItem(
					item = item,
					selected = item.id == selectedId,
					showLabel = showLabels,
					spec = spec,
					palette = palette,
					ambientPhase = ambientPhase,
					selectionEventPhase = selectionEvent.value,
					reduceMotion = reduceMotion,
					reduceGlow = reduceGlow || minimalCosmetics,
					onClick = {
						if (item.id == selectedId) onItemReselected(item.id) else onItemSelected(item.id)
					},
					onLongClick = { onItemLongClick(item.id) },
				)
			}
		}
	}
}

/**
 * Customizer preview intentionally reuses the exact production renderer and preset registry.
 * This prevents the old failure mode where preview showed a generic recoloured capsule while
 * runtime geometry came from a different code path.
 */
@Composable
internal fun ExclusiveBottomNavigationPreview(
	themeId: RankThemeId,
	modifier: Modifier = Modifier,
) {
	val spec = remember(themeId.stableId) { ExclusiveBottomNavigationRegistry.resolve(themeId) }
	val palette = remember(spec.stableId) {
		ExclusiveThemeComponentPalette(
			containerStops = spec.containerStops.map { Color(it.toInt()) },
			borderStops = spec.borderStops.map { Color(it.toInt()) },
			cardBorderStops = spec.borderStops.map { Color(it.toInt()) },
			selectedStops = spec.selectedStops.map { Color(it.toInt()) },
			glowStops = spec.glowStops.map { Color(it.toInt()) },
			iconStops = spec.iconStops.map { Color(it.toInt()) },
			content = Color(spec.content.toInt()),
			mutedContent = Color(spec.mutedContent.toInt()),
			interactiveText = Color(spec.interactiveText.toInt()),
			containerMix = spec.containerMix,
			selectedMix = spec.selectedMix,
			iconMix = spec.iconMix,
		)
	}
	val items = remember {
		listOf(
			NavItem.FAVORITES,
			NavItem.EXPLORE,
			NavItem.BOOKMARKS,
			NavItem.LOCAL,
			NavItem.READER_JOURNEY,
		).map { nav ->
			FloatingNavBarItem(
				id = nav.id,
				titleRes = nav.navTitle,
				icon = nav.icon,
				badgeCount = 0,
			)
		}
	}
	ExclusiveBottomNavigationBar(
		items = items,
		selectedId = items.first().id,
		showLabels = true,
		spec = spec,
		palette = palette,
		onItemSelected = {},
		onItemReselected = {},
		onItemLongClick = {},
		modifier = modifier,
	)
}

private fun DrawScope.drawExclusiveBody(
	spec: ExclusiveBottomNavigationSpec,
	containerBrush: Brush,
	borderBrush: Brush,
	glowBrush: Brush,
	radiusPx: Float,
	selectedX: Float,
	ambientPhase: Float,
	selectionEventPhase: Float,
	reduceGlow: Boolean,
) {
	// Silhouette is part of theme identity, not a recolour. Non-capsule tiers therefore draw
	// their actual body path here; ornaments only refine that geometry afterwards.
	val baseInset = when (spec.silhouette) {
		ExclusiveNavigationSilhouette.CAPSULE -> 1.dp.toPx()
		ExclusiveNavigationSilhouette.ANGULAR -> 1.5.dp.toPx()
		ExclusiveNavigationSilhouette.NOTCHED,
		ExclusiveNavigationSilhouette.AGGRESSIVE -> 2.dp.toPx()
		ExclusiveNavigationSilhouette.BEVELED,
		ExclusiveNavigationSilhouette.ORNAMENTAL -> 2.5.dp.toPx()
		ExclusiveNavigationSilhouette.PRISM,
		ExclusiveNavigationSilhouette.CELESTIAL -> 4.dp.toPx()
	}

	drawExclusiveBodyLayer(
		spec = spec,
		brush = containerBrush,
		alpha = 1f,
		radiusPx = radiusPx,
		inset = baseInset,
	)

	if (!reduceGlow) {
		// Premium glow budget: one soft halo plus one near halo; the sharp stroke is drawn below.
		drawExclusiveBodyLayer(
			spec = spec,
			brush = glowBrush,
			alpha = 0.10f,
			radiusPx = radiusPx,
			inset = baseInset,
			strokeWidth = 8.dp.toPx(),
		)
		drawExclusiveBodyLayer(
			spec = spec,
			brush = glowBrush,
			alpha = 0.20f,
			radiusPx = radiusPx,
			inset = baseInset,
			strokeWidth = 3.dp.toPx(),
		)
	}

	val borderAmbient = ((sin(ambientPhase * 2f * PI).toFloat() + 1f) * .5f)
	val authoredBorderAlpha = when (spec.motion) {
		ExclusiveNavigationMotion.PRISM_SHIMMER -> .86f + .08f * borderAmbient
		else -> .94f
	}
	drawExclusiveBodyLayer(
		spec = spec,
		brush = borderBrush,
		alpha = authoredBorderAlpha,
		radiusPx = radiusPx,
		inset = baseInset,
		strokeWidth = spec.borderWidthDp.dp.toPx(),
	)

	if (spec.doubleBorder) {
		drawExclusiveBodyLayer(
			spec = spec,
			brush = borderBrush,
			alpha = 0.34f,
			radiusPx = radiusPx,
			inset = baseInset + 3.dp.toPx(),
			strokeWidth = 0.75.dp.toPx(),
		)
	}

	if (spec.innerHighlight) {
		val top = baseInset + 2.dp.toPx()
		val shoulder = when (spec.silhouette) {
			ExclusiveNavigationSilhouette.CAPSULE,
			ExclusiveNavigationSilhouette.CELESTIAL -> radiusPx * .70f
			ExclusiveNavigationSilhouette.PRISM -> 24.dp.toPx()
			ExclusiveNavigationSilhouette.ORNAMENTAL,
			ExclusiveNavigationSilhouette.BEVELED -> 22.dp.toPx()
			else -> 18.dp.toPx()
		}
		drawLine(
			color = Color.White.copy(alpha = 0.16f),
			start = androidx.compose.ui.geometry.Offset(shoulder, top),
			end = androidx.compose.ui.geometry.Offset(size.width - shoulder, top),
			strokeWidth = 0.75.dp.toPx(),
			cap = StrokeCap.Round,
		)
	}

	drawSilhouetteAccents(spec, borderBrush)
	drawBarOrnaments(
		spec = spec,
		borderBrush = borderBrush,
		glowBrush = glowBrush,
		selectedX = selectedX,
		ambientPhase = ambientPhase,
		selectionEventPhase = selectionEventPhase,
		reduceGlow = reduceGlow,
	)

	// Only the themes whose two source documents explicitly author a border sweep get one.
	val ambientSweepDurationMs = when (spec.motion) {
		ExclusiveNavigationMotion.AMBER_SWEEP -> 1_400
		ExclusiveNavigationMotion.PRISM_SHIMMER -> 1_400
		else -> 0
	}
	val ambientCycleMs = spec.ambientCycleMs ?: 0
	if (ambientSweepDurationMs > 0 && ambientCycleMs > 0 && ambientPhase > 0f) {
		val sweepWindow = ambientSweepDurationMs.toFloat() / ambientCycleMs.toFloat()
		if (ambientPhase < sweepWindow) {
			val local = (ambientPhase / sweepWindow).coerceIn(0f, 1f)
			val x = size.width * local
			val alpha = sin(PI * local).toFloat().coerceAtLeast(0f) *
				if (reduceGlow) .05f else .16f
			drawLine(
				color = Color.White.copy(alpha = alpha),
				start = androidx.compose.ui.geometry.Offset((x - 18.dp.toPx()).coerceAtLeast(0f), baseInset),
				end = androidx.compose.ui.geometry.Offset((x + 18.dp.toPx()).coerceAtMost(size.width), baseInset),
				strokeWidth = 1.dp.toPx(),
				cap = StrokeCap.Round,
			)
		}
	}

	// One-shot authored sweep on selection for manuscript/prism themes.
	val hasSelectionSweep =
		spec.motion == ExclusiveNavigationMotion.AMBER_SWEEP ||
			spec.motion == ExclusiveNavigationMotion.GOLDEN_MEDALLION ||
			spec.motion == ExclusiveNavigationMotion.PRISM_SHIMMER
	if (!reduceGlow && selectionEventPhase >= 0f && selectionEventPhase < .999f && hasSelectionSweep) {
		val eventAlpha = sin(PI * selectionEventPhase).toFloat().coerceAtLeast(0f)
		val x = size.width * selectionEventPhase
		drawLine(
			color = Color.White.copy(alpha = .26f * eventAlpha),
			start = androidx.compose.ui.geometry.Offset((x - 18.dp.toPx()).coerceAtLeast(0f), baseInset),
			end = androidx.compose.ui.geometry.Offset((x + 18.dp.toPx()).coerceAtMost(size.width), baseInset),
			strokeWidth = 1.25.dp.toPx(),
			cap = StrokeCap.Round,
		)
	}
}

private fun DrawScope.drawExclusiveBodyLayer(
	spec: ExclusiveBottomNavigationSpec,
	brush: Brush,
	alpha: Float,
	radiusPx: Float,
	inset: Float,
	strokeWidth: Float? = null,
) {
	val width = (size.width - inset * 2f).coerceAtLeast(1f)
	val height = (size.height - inset * 2f).coerceAtLeast(1f)
	val effectiveRadius = minOf((radiusPx - inset).coerceAtLeast(1f), height / 2f)
	val capsuleLike =
		spec.silhouette == ExclusiveNavigationSilhouette.CAPSULE ||
			spec.silhouette == ExclusiveNavigationSilhouette.CELESTIAL

	if (capsuleLike) {
		if (strokeWidth == null) {
			drawRoundRect(
				brush = brush,
				alpha = alpha,
				topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
				size = androidx.compose.ui.geometry.Size(width, height),
				cornerRadius = androidx.compose.ui.geometry.CornerRadius(effectiveRadius, effectiveRadius),
			)
		} else {
			drawRoundRect(
				brush = brush,
				alpha = alpha,
				topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
				size = androidx.compose.ui.geometry.Size(width, height),
				cornerRadius = androidx.compose.ui.geometry.CornerRadius(effectiveRadius, effectiveRadius),
				style = Stroke(width = strokeWidth),
			)
		}
		return
	}

	val path = exclusiveBodyPath(spec.silhouette, inset)
	if (strokeWidth == null) {
		drawPath(path = path, brush = brush, alpha = alpha)
	} else {
		drawPath(path = path, brush = brush, alpha = alpha, style = Stroke(width = strokeWidth))
	}
}

private fun DrawScope.exclusiveBodyPath(
	silhouette: ExclusiveNavigationSilhouette,
	inset: Float,
): Path {
	val left = inset
	val right = size.width - inset
	val top = inset
	val bottom = size.height - inset
	val centerY = (top + bottom) / 2f
	fun d(value: Float) = value.dp.toPx()

	return Path().apply {
		when (silhouette) {
			ExclusiveNavigationSilhouette.ANGULAR -> {
				val cut = d(13f)
				moveTo(left + cut, top)
				lineTo(right - cut, top)
				lineTo(right, top + cut)
				lineTo(right, bottom - cut)
				lineTo(right - cut, bottom)
				lineTo(left + cut, bottom)
				lineTo(left, bottom - cut)
				lineTo(left, top + cut)
			}
			ExclusiveNavigationSilhouette.NOTCHED -> {
				val shoulder = d(18f)
				val tooth = d(5f)
				moveTo(left + shoulder, top)
				lineTo(right - shoulder, top)
				lineTo(right - tooth, top + d(8f))
				lineTo(right, top + d(15f))
				lineTo(right - tooth, centerY - d(5f))
				lineTo(right, centerY)
				lineTo(right - tooth, centerY + d(5f))
				lineTo(right, bottom - d(15f))
				lineTo(right - shoulder, bottom)
				lineTo(left + shoulder, bottom)
				lineTo(left, bottom - d(15f))
				lineTo(left + tooth, centerY + d(5f))
				lineTo(left, centerY)
				lineTo(left + tooth, centerY - d(5f))
				lineTo(left, top + d(15f))
				lineTo(left + tooth, top + d(8f))
			}
			ExclusiveNavigationSilhouette.AGGRESSIVE -> {
				val cut = d(20f)
				moveTo(left + cut, top)
				lineTo(right - cut, top)
				lineTo(right - d(6f), top + d(9f))
				lineTo(right, centerY)
				lineTo(right - d(6f), bottom - d(9f))
				lineTo(right - cut, bottom)
				lineTo(left + cut, bottom)
				lineTo(left + d(6f), bottom - d(9f))
				lineTo(left, centerY)
				lineTo(left + d(6f), top + d(9f))
			}
			ExclusiveNavigationSilhouette.BEVELED -> {
				val shoulder = d(23f)
				moveTo(left + shoulder, top)
				lineTo(right - shoulder, top)
				cubicTo(right - d(12f), top, right - d(7f), top + d(5f), right - d(5f), top + d(10f))
				lineTo(right, centerY - d(7f))
				lineTo(right - d(3f), centerY)
				lineTo(right, centerY + d(7f))
				lineTo(right - d(5f), bottom - d(10f))
				cubicTo(right - d(7f), bottom - d(5f), right - d(12f), bottom, right - shoulder, bottom)
				lineTo(left + shoulder, bottom)
				cubicTo(left + d(12f), bottom, left + d(7f), bottom - d(5f), left + d(5f), bottom - d(10f))
				lineTo(left, centerY + d(7f))
				lineTo(left + d(3f), centerY)
				lineTo(left, centerY - d(7f))
				lineTo(left + d(5f), top + d(10f))
				cubicTo(left + d(7f), top + d(5f), left + d(12f), top, left + shoulder, top)
			}
			ExclusiveNavigationSilhouette.ORNAMENTAL -> {
				val shoulder = d(27f)
				moveTo(left + shoulder, top)
				lineTo(right - shoulder, top)
				cubicTo(right - d(15f), top, right - d(9f), top + d(5f), right - d(7f), top + d(10f))
				lineTo(right - d(3f), centerY - d(11f))
				lineTo(right, centerY)
				lineTo(right - d(3f), centerY + d(11f))
				lineTo(right - d(7f), bottom - d(10f))
				cubicTo(right - d(9f), bottom - d(5f), right - d(15f), bottom, right - shoulder, bottom)
				lineTo(left + shoulder, bottom)
				cubicTo(left + d(15f), bottom, left + d(9f), bottom - d(5f), left + d(7f), bottom - d(10f))
				lineTo(left + d(3f), centerY + d(11f))
				lineTo(left, centerY)
				lineTo(left + d(3f), centerY - d(11f))
				lineTo(left + d(7f), top + d(10f))
				cubicTo(left + d(9f), top + d(5f), left + d(15f), top, left + shoulder, top)
			}
			ExclusiveNavigationSilhouette.PRISM -> {
				val shoulder = d(26f)
				moveTo(left + shoulder, top)
				lineTo(right - shoulder, top)
				cubicTo(right - d(15f), top, right - d(9f), top + d(4f), right - d(7f), top + d(9f))
				lineTo(right, centerY - d(7f))
				lineTo(right - d(4f), centerY)
				lineTo(right, centerY + d(7f))
				lineTo(right - d(7f), bottom - d(9f))
				cubicTo(right - d(9f), bottom - d(4f), right - d(15f), bottom, right - shoulder, bottom)
				lineTo(left + shoulder, bottom)
				cubicTo(left + d(15f), bottom, left + d(9f), bottom - d(4f), left + d(7f), bottom - d(9f))
				lineTo(left, centerY + d(7f))
				lineTo(left + d(4f), centerY)
				lineTo(left, centerY - d(7f))
				lineTo(left + d(7f), top + d(9f))
				cubicTo(left + d(9f), top + d(4f), left + d(15f), top, left + shoulder, top)
			}
			ExclusiveNavigationSilhouette.CAPSULE,
			ExclusiveNavigationSilhouette.CELESTIAL -> {
				// Capsule-like bodies are handled by drawRoundRect in drawExclusiveBodyLayer.
				moveTo(left, top)
				lineTo(right, top)
				lineTo(right, bottom)
				lineTo(left, bottom)
			}
		}
		close()
	}
}

private fun DrawScope.drawSilhouetteAccents(
	spec: ExclusiveBottomNavigationSpec,
	borderBrush: Brush,
) {
	val h = size.height
	val w = size.width
	when (spec.silhouette) {
		ExclusiveNavigationSilhouette.CAPSULE -> Unit
		ExclusiveNavigationSilhouette.ANGULAR -> {
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(8.dp.toPx(), h * .50f), androidx.compose.ui.geometry.Offset(16.dp.toPx(), h * .50f), 1.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(w - 8.dp.toPx(), h * .50f), androidx.compose.ui.geometry.Offset(w - 16.dp.toPx(), h * .50f), 1.dp.toPx())
		}
		ExclusiveNavigationSilhouette.NOTCHED -> {
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(7.dp.toPx(), h / 2f), 3.5.dp.toPx(), .76f)
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(w - 7.dp.toPx(), h / 2f), 3.5.dp.toPx(), .76f)
		}
		ExclusiveNavigationSilhouette.AGGRESSIVE -> {
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(7.dp.toPx(), h * .33f), androidx.compose.ui.geometry.Offset(18.dp.toPx(), h * .43f), 1.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(w - 7.dp.toPx(), h * .33f), androidx.compose.ui.geometry.Offset(w - 18.dp.toPx(), h * .43f), 1.dp.toPx())
		}
		ExclusiveNavigationSilhouette.BEVELED -> {
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(8.dp.toPx(), h * .24f), androidx.compose.ui.geometry.Offset(19.dp.toPx(), h * .18f), 1.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(w - 8.dp.toPx(), h * .24f), androidx.compose.ui.geometry.Offset(w - 19.dp.toPx(), h * .18f), 1.dp.toPx())
		}
		ExclusiveNavigationSilhouette.ORNAMENTAL -> {
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(9.dp.toPx(), h / 2f), 5.dp.toPx(), .84f)
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(w - 9.dp.toPx(), h / 2f), 5.dp.toPx(), .84f)
		}
		ExclusiveNavigationSilhouette.PRISM -> {
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(10.dp.toPx(), h / 2f), 4.5.dp.toPx(), 0.9f)
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(w - 10.dp.toPx(), h / 2f), 4.5.dp.toPx(), 0.9f)
		}
		ExclusiveNavigationSilhouette.CELESTIAL -> {
			val sideArcLeft = Path().apply {
				moveTo(5.dp.toPx(), h * .50f)
				cubicTo(10.dp.toPx(), h * .18f, 22.dp.toPx(), h * .18f, 27.dp.toPx(), h * .50f)
				cubicTo(22.dp.toPx(), h * .82f, 10.dp.toPx(), h * .82f, 5.dp.toPx(), h * .50f)
			}
			drawPath(sideArcLeft, borderBrush, alpha = .48f, style = Stroke(width = .8.dp.toPx()))
			val sideArcRight = Path().apply {
				moveTo(w - 5.dp.toPx(), h * .50f)
				cubicTo(w - 10.dp.toPx(), h * .18f, w - 22.dp.toPx(), h * .18f, w - 27.dp.toPx(), h * .50f)
				cubicTo(w - 22.dp.toPx(), h * .82f, w - 10.dp.toPx(), h * .82f, w - 5.dp.toPx(), h * .50f)
			}
			drawPath(sideArcRight, borderBrush, alpha = .48f, style = Stroke(width = .8.dp.toPx()))
		}
	}
}

private fun DrawScope.drawBarOrnaments(
	spec: ExclusiveBottomNavigationSpec,
	borderBrush: Brush,
	glowBrush: Brush,
	selectedX: Float,
	ambientPhase: Float,
	selectionEventPhase: Float,
	reduceGlow: Boolean,
) {
	val w = size.width
	val h = size.height
	val ambientWave = ((sin(ambientPhase * 2f * PI).toFloat() + 1f) * .5f)
	val fastAmbientWave = ((sin(ambientPhase * 6f * PI).toFloat() + 1f) * .5f)
	val eventWave = sin(PI * selectionEventPhase).toFloat().coerceAtLeast(0f)
	val accentAlpha = if (reduceGlow) 0.32f else 0.66f
	when (spec.ornament) {
		ExclusiveNavigationOrnament.NONE -> Unit
		ExclusiveNavigationOrnament.TOP_FLARE -> {
			drawStarFlare(
				center = androidx.compose.ui.geometry.Offset(selectedX, 3.dp.toPx()),
				color = Color.White.copy(alpha = accentAlpha),
				radius = 4.dp.toPx(),
			)
		}
		ExclusiveNavigationOrnament.ORBIT -> {
			drawStaticDots(spec.staticDotCount, glowBrush, alpha = 0.52f)
		}
		ExclusiveNavigationOrnament.SIDE_LINES -> {
			val half = 18.dp.toPx()
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset((selectedX - half - 15.dp.toPx()).coerceAtLeast(14.dp.toPx()), h / 2f), androidx.compose.ui.geometry.Offset((selectedX - half).coerceAtLeast(20.dp.toPx()), h / 2f), 1.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset((selectedX + half).coerceAtMost(w - 20.dp.toPx()), h / 2f), androidx.compose.ui.geometry.Offset((selectedX + half + 15.dp.toPx()).coerceAtMost(w - 14.dp.toPx()), h / 2f), 1.dp.toPx())
			val emeraldPulseWindow = (320f / spec.selectionAccentDurationMs.toFloat()).coerceAtMost(1f)
			val emeraldRise = if (selectionEventPhase < emeraldPulseWindow) {
				(selectionEventPhase / emeraldPulseWindow).coerceIn(0f, 1f)
			} else {
				1f
			}
			val flareAlpha = accentAlpha * (.64f + .18f * ambientWave + .18f * eventWave)
			val flareY = 9.dp.toPx() - 6.dp.toPx() * emeraldRise
			drawStarFlare(
				androidx.compose.ui.geometry.Offset(selectedX, flareY),
				Color.White.copy(alpha = flareAlpha),
				4.dp.toPx() * (.90f + .10f * eventWave),
			)
		}
		ExclusiveNavigationOrnament.DIAMONDS -> {
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(6.dp.toPx(), h / 2f), 5.dp.toPx(), 0.74f)
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(w - 6.dp.toPx(), h / 2f), 5.dp.toPx(), 0.74f)
			// Arcane Scholar: one restrained glyph line keeps the body readable as an artifact,
			// not merely a purple notched bar.
			drawLine(
				brush = borderBrush,
				start = androidx.compose.ui.geometry.Offset(w * .43f, 7.dp.toPx()),
				end = androidx.compose.ui.geometry.Offset(w * .57f, 7.dp.toPx()),
				strokeWidth = .75.dp.toPx(),
				alpha = .48f,
			)
			drawDiamond(
				borderBrush,
				androidx.compose.ui.geometry.Offset(w / 2f, 7.dp.toPx()),
				2.2.dp.toPx(),
				.44f + .16f * ambientWave + .10f * eventWave,
			)
			if (selectionEventPhase >= 0f && selectionEventPhase < .999f) {
				val shimmerX = w * selectionEventPhase
				drawLine(
					color = Color.White.copy(alpha = .24f * eventWave),
					start = androidx.compose.ui.geometry.Offset((shimmerX - 10.dp.toPx()).coerceAtLeast(0f), h * .22f),
					end = androidx.compose.ui.geometry.Offset((shimmerX + 10.dp.toPx()).coerceAtMost(w), h * .22f),
					strokeWidth = .8.dp.toPx(),
					cap = StrokeCap.Round,
				)
			}
		}
		ExclusiveNavigationOrnament.STARS -> {
			drawStaticDots(spec.staticDotCount, glowBrush, alpha = 0.52f)
		}
		ExclusiveNavigationOrnament.NEBULA_STARS -> {
			// Rose Nebula uses a static low-opacity haze rather than runtime blur/particle emitters.
			drawCircle(
				brush = glowBrush,
				alpha = if (reduceGlow) .018f else (.035f + .015f * ambientWave + .14f * eventWave),
				radius = h * .58f,
				center = androidx.compose.ui.geometry.Offset(w * .28f, h * .54f),
			)
			drawCircle(
				brush = glowBrush,
				alpha = if (reduceGlow) .014f else (.028f + .012f * ambientWave + .10f * eventWave),
				radius = h * .48f,
				center = androidx.compose.ui.geometry.Offset(w * .72f, h * .42f),
			)
			drawStaticDots(spec.staticDotCount, glowBrush, alpha = 0.42f)
		}
		ExclusiveNavigationOrnament.EMBERS -> {
			drawStaticDots(spec.staticDotCount, glowBrush, alpha = 0.56f)
		}
		ExclusiveNavigationOrnament.MANUSCRIPT -> {
			val y0 = 9.dp.toPx()
			val y1 = h - 9.dp.toPx()
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(12.dp.toPx(), y0), androidx.compose.ui.geometry.Offset(22.dp.toPx(), y0), .9.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(12.dp.toPx(), y0), androidx.compose.ui.geometry.Offset(12.dp.toPx(), y0 + 8.dp.toPx()), .9.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(w - 12.dp.toPx(), y1), androidx.compose.ui.geometry.Offset(w - 22.dp.toPx(), y1), .9.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(w - 12.dp.toPx(), y1), androidx.compose.ui.geometry.Offset(w - 12.dp.toPx(), y1 - 8.dp.toPx()), .9.dp.toPx())
			drawLine(
				brush = borderBrush,
				start = androidx.compose.ui.geometry.Offset(w * .38f, h * .18f),
				end = androidx.compose.ui.geometry.Offset(w * .62f, h * .18f),
				strokeWidth = .7.dp.toPx(),
				alpha = .30f,
			)
		}
		ExclusiveNavigationOrnament.GOLD_FINIALS -> {
			val gemAlpha = .72f + .18f * ambientWave + .10f * eventWave
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(7.dp.toPx(), h / 2f), 6.dp.toPx(), gemAlpha)
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(w - 7.dp.toPx(), h / 2f), 6.dp.toPx(), gemAlpha)
			drawStarFlare(
				androidx.compose.ui.geometry.Offset(selectedX, 3.dp.toPx()),
				Color.White.copy(alpha = accentAlpha * (.78f + .22f * eventWave)),
				4.dp.toPx() * (1f + .05f * eventWave),
			)
		}
		ExclusiveNavigationOrnament.PRISM_SHARDS -> {
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(w / 2f, 3.dp.toPx()), 4.dp.toPx(), .9f)
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(w / 2f, h - 3.dp.toPx()), 4.dp.toPx(), .9f)
			val shardPulse = (.58f + .30f * fastAmbientWave + .12f * eventWave).coerceAtMost(1f)
			drawStarFlare(androidx.compose.ui.geometry.Offset(w * .18f, 5.dp.toPx()), Color.White.copy(alpha = accentAlpha * .56f * shardPulse), 2.8.dp.toPx())
			drawStarFlare(androidx.compose.ui.geometry.Offset(w * .82f, h - 5.dp.toPx()), Color.White.copy(alpha = accentAlpha * .48f * shardPulse), 2.5.dp.toPx())
			drawStaticDots(spec.staticDotCount, glowBrush, alpha = 0.42f)
		}
		ExclusiveNavigationOrnament.INFINITY_ARCS -> {
			val path = Path().apply {
				moveTo(8.dp.toPx(), h * .55f)
				cubicTo(w * .18f, h * .08f, w * .32f, h * .08f, w * .50f, h * .50f)
				cubicTo(w * .68f, h * .92f, w * .82f, h * .92f, w - 8.dp.toPx(), h * .45f)
			}
			val arcDrift = .82f + .18f * ((sin(ambientPhase * 4f * PI).toFloat() + 1f) * .5f)
			drawPath(
				path = path,
				brush = borderBrush,
				alpha = if (reduceGlow) .22f else .42f * arcDrift,
				style = Stroke(width = .8.dp.toPx(), cap = StrokeCap.Round),
			)
			val inverse = Path().apply {
				moveTo(8.dp.toPx(), h * .45f)
				cubicTo(w * .18f, h * .92f, w * .32f, h * .92f, w * .50f, h * .50f)
				cubicTo(w * .68f, h * .08f, w * .82f, h * .08f, w - 8.dp.toPx(), h * .55f)
			}
			drawPath(
				path = inverse,
				brush = borderBrush,
				alpha = if (reduceGlow) .18f else .34f * (1.02f - .12f * arcDrift),
				style = Stroke(width = .7.dp.toPx(), cap = StrokeCap.Round),
			)
			val twinkleA = ((sin(ambientPhase * 8f * PI).toFloat() + 1f) * .5f)
			val twinkleB = ((sin(ambientPhase * 8f * PI + PI.toFloat()).toFloat() + 1f) * .5f)
			drawStarFlare(androidx.compose.ui.geometry.Offset(w / 2f, 3.dp.toPx()), Color.White.copy(alpha = accentAlpha * (.72f + .28f * twinkleA)), 4.5.dp.toPx())
			drawStarFlare(androidx.compose.ui.geometry.Offset(w / 2f, h - 3.dp.toPx()), Color.White.copy(alpha = accentAlpha * (.58f + .28f * twinkleB)), 4.dp.toPx())
			drawStaticDots(spec.staticDotCount, glowBrush, alpha = .34f)
			if (ambientPhase > 0f && !reduceGlow) {
				val theta = ambientPhase * 2f * PI.toFloat()
				val lightX = w / 2f + sin(theta) * w * .38f
				val lightY = h / 2f + sin(theta * 2f) * h * .18f
				val dx = cos(theta) * w * .38f
				val dy = cos(theta * 2f) * h * .36f
				val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(.001f)
				val half = 4.dp.toPx()
				drawLine(
					color = Color.White.copy(alpha = .12f),
					start = androidx.compose.ui.geometry.Offset(lightX - dx / length * half, lightY - dy / length * half),
					end = androidx.compose.ui.geometry.Offset(lightX + dx / length * half, lightY + dy / length * half),
					strokeWidth = 1.dp.toPx(),
					cap = StrokeCap.Round,
				)
			}
			if (selectionEventPhase >= 0f && selectionEventPhase < .999f && !reduceGlow) {
				val theta = selectionEventPhase * 2f * PI.toFloat()
				val sweepX = w / 2f + sin(theta) * w * .38f
				val sweepY = h / 2f + sin(theta * 2f) * h * .18f
				val dx = cos(theta) * w * .38f
				val dy = cos(theta * 2f) * h * .36f
				val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(.001f)
				val half = 6.dp.toPx()
				drawLine(
					color = Color.White.copy(alpha = .42f * eventWave),
					start = androidx.compose.ui.geometry.Offset(sweepX - dx / length * half, sweepY - dy / length * half),
					end = androidx.compose.ui.geometry.Offset(sweepX + dx / length * half, sweepY + dy / length * half),
					strokeWidth = 1.35.dp.toPx(),
					cap = StrokeCap.Round,
				)
			}
		}
	}

	if (spec.bottomFlare && spec.ornament != ExclusiveNavigationOrnament.INFINITY_ARCS) {
		drawStarFlare(
			center = androidx.compose.ui.geometry.Offset(selectedX, h - 3.dp.toPx()),
			color = Color.White.copy(alpha = accentAlpha * .82f),
			radius = 3.5.dp.toPx(),
		)
	}
	if (spec.topFlare &&
		spec.ornament != ExclusiveNavigationOrnament.TOP_FLARE &&
		spec.ornament != ExclusiveNavigationOrnament.SIDE_LINES &&
		spec.ornament != ExclusiveNavigationOrnament.GOLD_FINIALS &&
		spec.ornament != ExclusiveNavigationOrnament.INFINITY_ARCS
	) {
		val topFlareAlpha = when (spec.motion) {
			ExclusiveNavigationMotion.VIOLET_HALO -> accentAlpha * (.62f + .38f * eventWave)
			ExclusiveNavigationMotion.PRISM_SHIMMER -> accentAlpha * (.72f + .28f * eventWave)
			else -> accentAlpha
		}
		drawStarFlare(
			center = androidx.compose.ui.geometry.Offset(selectedX, 3.dp.toPx()),
			color = Color.White.copy(alpha = topFlareAlpha),
			radius = 3.5.dp.toPx(),
		)
	}

}

private fun DrawScope.drawStaticDots(count: Int, brush: Brush, alpha: Float) {
	if (count <= 0) return
	val points = listOf(.16f to .29f, .82f to .32f, .28f to .75f, .72f to .72f)
	points.take(count.coerceAtMost(points.size)).forEachIndexed { index, (x, y) ->
		drawCircle(
			brush = brush,
			alpha = alpha * if (index % 2 == 0) 1f else .72f,
			radius = if (index % 2 == 0) 1.35.dp.toPx() else 1.dp.toPx(),
			center = androidx.compose.ui.geometry.Offset(size.width * x, size.height * y),
		)
	}
}

private fun DrawScope.drawDiamond(
	brush: Brush,
	center: androidx.compose.ui.geometry.Offset,
	radius: Float,
	alpha: Float,
) {
	val path = Path().apply {
		moveTo(center.x, center.y - radius)
		lineTo(center.x + radius, center.y)
		lineTo(center.x, center.y + radius)
		lineTo(center.x - radius, center.y)
		close()
	}
	drawPath(path = path, brush = brush, alpha = alpha, style = Stroke(width = .9.dp.toPx()))
}

private fun DrawScope.drawStarFlare(
	center: androidx.compose.ui.geometry.Offset,
	color: Color,
	radius: Float,
) {
	drawLine(
		color = color,
		start = androidx.compose.ui.geometry.Offset(center.x - radius, center.y),
		end = androidx.compose.ui.geometry.Offset(center.x + radius, center.y),
		strokeWidth = .8.dp.toPx(),
		cap = StrokeCap.Round,
	)
	drawLine(
		color = color,
		start = androidx.compose.ui.geometry.Offset(center.x, center.y - radius),
		end = androidx.compose.ui.geometry.Offset(center.x, center.y + radius),
		strokeWidth = .8.dp.toPx(),
		cap = StrokeCap.Round,
	)
	drawCircle(color = color, radius = 1.dp.toPx(), center = center)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.ExclusiveNavigationItem(
	item: FloatingNavBarItem,
	selected: Boolean,
	showLabel: Boolean,
	spec: ExclusiveBottomNavigationSpec,
	palette: ExclusiveThemeComponentPalette,
	ambientPhase: Float,
	selectionEventPhase: Float,
	reduceMotion: Boolean,
	reduceGlow: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val pressed by interactionSource.collectIsPressedAsState()
	val pressScale by animateFloatAsState(
		targetValue = if (pressed) .97f else 1f,
		animationSpec = tween(if (pressed) 90 else 120),
		label = "exclusiveNavPress",
	)
	val effectiveSelectionDuration = if (reduceMotion) 140 else spec.selectionDurationMs
	val selection by animateFloatAsState(
		targetValue = if (selected) 1f else 0f,
		animationSpec = tween(
			durationMillis = effectiveSelectionDuration,
			easing = FastOutSlowInEasing,
		),
		label = "exclusiveNavSelection",
	)
	val selectedContent = palette.content
	val inactiveContent = palette.mutedContent.copy(alpha = .68f)
	val iconTint by animateColorAsState(
		targetValue = if (selected) selectedContent else inactiveContent,
		animationSpec = tween(
			durationMillis = effectiveSelectionDuration,
			easing = FastOutSlowInEasing,
		),
		label = "exclusiveNavIconTint",
	)
	val labelTint by animateColorAsState(
		targetValue = if (selected) palette.interactiveText else inactiveContent.copy(alpha = .92f),
		animationSpec = tween(
			durationMillis = effectiveSelectionDuration,
			easing = FastOutSlowInEasing,
		),
		label = "exclusiveNavLabelTint",
	)
	val title = androidx.compose.ui.res.stringResource(item.titleRes)
	val selectedBrush = remember(palette.selectedStops) {
		Brush.horizontalGradient(palette.selectedStops)
	}
	val glowBrush = remember(palette.glowStops) {
		Brush.horizontalGradient(palette.glowStops)
	}
	val eventWave = sin(PI * selectionEventPhase).toFloat().coerceAtLeast(0f)
	val emeraldPulseWindow = (320f / spec.selectionAccentDurationMs.toFloat()).coerceAtMost(1f)
	val emeraldPulse = if (
		spec.motion == ExclusiveNavigationMotion.EMERALD_PULSE &&
		selectionEventPhase < emeraldPulseWindow
	) {
		sin(PI * (selectionEventPhase / emeraldPulseWindow)).toFloat().coerceAtLeast(0f)
	} else {
		0f
	}
	val density = LocalDensity.current
	val liftPx = with(density) { 2.dp.toPx() }
	val authoredScale = when (spec.motion) {
		ExclusiveNavigationMotion.CLEAN_REVEAL -> .94f + .06f * selection
		ExclusiveNavigationMotion.BLUE_PULSE -> (.88f + .12f * selection) * (1f + .04f * eventWave)
		ExclusiveNavigationMotion.EMERALD_PULSE -> 1f + .025f * emeraldPulse
		ExclusiveNavigationMotion.ARCANE_SHIMMER -> .90f + .10f * selection
		ExclusiveNavigationMotion.VIOLET_HALO -> 1f + .03f * eventWave
		ExclusiveNavigationMotion.ROSE_NEBULA -> .92f + .08f * selection
		ExclusiveNavigationMotion.CRIMSON_EMBER -> .96f + .04f * selection
		ExclusiveNavigationMotion.AMBER_SWEEP -> .94f + .06f * selection
		ExclusiveNavigationMotion.GOLDEN_MEDALLION -> .94f + .06f * selection
		ExclusiveNavigationMotion.PRISM_SHIMMER,
		ExclusiveNavigationMotion.CELESTIAL_INFINITY -> .90f + .10f * selection
		else -> 1f
	}
	val authoredLift = when (spec.motion) {
		ExclusiveNavigationMotion.ROSE_NEBULA,
		ExclusiveNavigationMotion.GOLDEN_MEDALLION -> -liftPx * eventWave
		else -> 0f
	}

	Box(
		modifier = Modifier
			.weight(1f)
			.fillMaxHeight()
			.combinedClickable(
				interactionSource = interactionSource,
				indication = LocalIndication.current,
				onClick = onClick,
				onLongClick = onLongClick,
			)
			.semantics {
				this.selected = selected
				role = Role.Tab
				contentDescription = title
			},
		contentAlignment = Alignment.Center,
	) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Box(
				modifier = Modifier
					.size(spec.activeDiameterDp.dp)
					.graphicsLayer {
						scaleX = pressScale * authoredScale
						scaleY = pressScale * authoredScale
						translationY = authoredLift
					},
				contentAlignment = Alignment.Center,
			) {
				Box(
					modifier = Modifier
						.fillMaxSize()
						.drawBehind {
							drawSelectedDecoration(
								spec = spec,
								selectedBrush = selectedBrush,
								glowBrush = glowBrush,
								progress = selection,
								ambientPhase = ambientPhase,
								selectionEventPhase = selectionEventPhase,
								reduceGlow = reduceGlow,
							)
						},
				)
				BadgedBox(
					badge = {
						if (item.badgeCount > 0) {
							Badge { Text(if (item.badgeCount > 99) "99+" else item.badgeCount.toString()) }
						} else if (item.badgeCount < 0) {
							Badge()
						}
					},
				) {
					NavIcon(
						resId = item.icon,
						selected = selected,
						tint = iconTint,
						modifier = Modifier.size(23.dp),
					)
				}
			}
			if (showLabel) {
				Spacer(Modifier.height(1.dp))
				Text(
					text = title,
					color = labelTint,
					fontSize = 10.5.sp,
					lineHeight = 12.sp,
					fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			Spacer(Modifier.height(2.dp))
			when (spec.indicator) {
				ExclusiveNavigationIndicator.UNDERLINE -> Box(
					modifier = Modifier
						.width(26.dp)
						.height(2.dp)
						.graphicsLayer {
							alpha = selection
							scaleX = selection
						}
						.background(selectedBrush, RoundedCornerShape(2.dp)),
				)
				ExclusiveNavigationIndicator.LIGHT_SEED -> Box(
					modifier = Modifier
						.width(4.dp)
						.height(3.dp)
						.graphicsLayer {
							alpha = selection
							scaleY = .86f + .14f * selection
						}
						.background(selectedBrush, CircleShape),
				)
				ExclusiveNavigationIndicator.NONE -> Spacer(Modifier.height(2.dp))
			}
		}
	}
}

private fun DrawScope.drawSelectedDecoration(
	spec: ExclusiveBottomNavigationSpec,
	selectedBrush: Brush,
	glowBrush: Brush,
	progress: Float,
	ambientPhase: Float,
	selectionEventPhase: Float,
	reduceGlow: Boolean,
) {
	if (progress <= 0.001f) return
	val p = progress.coerceIn(0f, 1f)
	val ambientWave = ((sin(ambientPhase * 2f * PI).toFloat() + 1f) * .5f)
	val eventWave = sin(PI * selectionEventPhase).toFloat().coerceAtLeast(0f)
	val emeraldPulseWindow = (320f / spec.selectionAccentDurationMs.toFloat()).coerceAtMost(1f)
	val emeraldPulse = if (
		spec.motion == ExclusiveNavigationMotion.EMERALD_PULSE &&
		selectionEventPhase < emeraldPulseWindow
	) {
		sin(PI * (selectionEventPhase / emeraldPulseWindow)).toFloat().coerceAtLeast(0f)
	} else {
		0f
	}
	val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
	val motionRadiusScale = when (spec.motion) {
		ExclusiveNavigationMotion.ARCANE_SHIMMER -> 1f + .015f * ambientWave
		ExclusiveNavigationMotion.VIOLET_HALO -> 1f + .01f * ambientWave
		else -> 1f
	}
	val radius = size.minDimension * .42f * (.92f + .08f * p) * motionRadiusScale
	val nearAlpha = .90f * p
	val haloAlpha = (if (reduceGlow) .08f else .22f) * p
	val stroke = 1.25.dp.toPx()

	if (!reduceGlow) {
		drawCircle(
			brush = glowBrush,
			alpha = haloAlpha,
			radius = radius + 4.dp.toPx(),
			center = center,
			style = Stroke(width = 7.dp.toPx()),
		)
	}

	when (spec.activeShape) {
		ExclusiveNavigationActiveShape.SOFT_HALO -> {
			drawCircle(selectedBrush, alpha = .12f * p, radius = radius, center = center)
			drawCircle(selectedBrush, alpha = nearAlpha, radius = radius, center = center, style = Stroke(stroke))
		}
		ExclusiveNavigationActiveShape.RING,
		ExclusiveNavigationActiveShape.EMBER_RING -> {
			val ringPulse = if (spec.motion == ExclusiveNavigationMotion.EMERALD_PULSE) emeraldPulse else 0f
			drawCircle(selectedBrush, alpha = (.10f + .05f * ringPulse) * p, radius = radius, center = center)
			drawCircle(
				selectedBrush,
				alpha = (nearAlpha + .08f * ringPulse).coerceAtMost(1f),
				radius = radius * (1f + .02f * ringPulse),
				center = center,
				style = Stroke(stroke),
			)
			if (spec.activeShape == ExclusiveNavigationActiveShape.EMBER_RING) {
				drawCircle(Color.White.copy(alpha = (.54f + .18f * eventWave) * p), 1.2.dp.toPx(), androidx.compose.ui.geometry.Offset(center.x, center.y - radius))
				drawCircle(Color.White.copy(alpha = (.40f + .14f * eventWave) * p), 1.dp.toPx(), androidx.compose.ui.geometry.Offset(center.x, center.y + radius))
				if (selectionEventPhase >= 0f && selectionEventPhase < .999f) {
					val rise = 6.dp.toPx() * selectionEventPhase
					drawCircle(
						Color.White.copy(alpha = .48f * (1f - selectionEventPhase)),
						1.1.dp.toPx(),
						androidx.compose.ui.geometry.Offset(center.x - 6.dp.toPx(), center.y - radius - rise),
					)
					drawCircle(
						Color.White.copy(alpha = .34f * (1f - selectionEventPhase)),
						.9.dp.toPx(),
						androidx.compose.ui.geometry.Offset(center.x + 7.dp.toPx(), center.y - radius - rise * .75f),
					)
				}
			}
		}
		ExclusiveNavigationActiveShape.ORBIT_RING -> {
			drawCircle(selectedBrush, alpha = .10f * p, radius = radius, center = center)
			drawCircle(selectedBrush, alpha = nearAlpha, radius = radius * .84f, center = center, style = Stroke(stroke))
			drawArc(
				brush = selectedBrush,
				startAngle = -38f,
				sweepAngle = 248f,
				useCenter = false,
				topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
				size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
				alpha = .82f * p,
				style = Stroke(width = .9.dp.toPx(), cap = StrokeCap.Round),
			)
			val theta = ((ambientPhase * 360f) - 38f) * (Math.PI / 180.0)
			val dotX = center.x + cos(theta).toFloat() * radius
			val dotY = center.y + sin(theta).toFloat() * radius
			drawCircle(Color.White.copy(alpha = .92f * p), 1.8.dp.toPx(), androidx.compose.ui.geometry.Offset(dotX, dotY))
		}
		ExclusiveNavigationActiveShape.HEX_GEM -> {
			val hex = Path()
			repeat(6) { index ->
				val angle = Math.toRadians((60.0 * index) - 90.0)
				val x = center.x + cos(angle).toFloat() * radius
				val y = center.y + sin(angle).toFloat() * radius
				if (index == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
			}
			hex.close()
			drawPath(hex, selectedBrush, alpha = (.10f + .04f * ambientWave) * p)
			drawPath(hex, selectedBrush, alpha = (nearAlpha + .08f * eventWave).coerceAtMost(1f), style = Stroke(width = stroke))
			if (selectionEventPhase >= 0f && selectionEventPhase < .999f) {
				val x = (center.x - radius) + (radius * 2f * selectionEventPhase)
				drawLine(
					color = Color.White.copy(alpha = .20f * eventWave * p),
					start = androidx.compose.ui.geometry.Offset(x, center.y - radius * .68f),
					end = androidx.compose.ui.geometry.Offset(x, center.y + radius * .68f),
					strokeWidth = .8.dp.toPx(),
				)
			}
		}
		ExclusiveNavigationActiveShape.DOUBLE_HALO -> {
			val outerProgress = ((p - .25f) / .75f).coerceIn(0f, 1f)
			drawCircle(selectedBrush, alpha = .09f * p, radius = radius * .80f, center = center)
			drawCircle(selectedBrush, alpha = .92f * p, radius = radius * .78f, center = center, style = Stroke(stroke))
			drawCircle(
				selectedBrush,
				alpha = (.38f + .10f * ambientWave + .12f * eventWave) * outerProgress,
				radius = radius,
				center = center,
				style = Stroke(width = .85.dp.toPx()),
			)
		}
		ExclusiveNavigationActiveShape.BUBBLE -> {
			drawCircle(selectedBrush, alpha = (.16f + .08f * ambientWave) * p, radius = radius, center = center)
			drawCircle(selectedBrush, alpha = .86f * p, radius = radius, center = center, style = Stroke(stroke))
		}
		ExclusiveNavigationActiveShape.MEDALLION,
		ExclusiveNavigationActiveShape.CROWN_MEDALLION -> {
			drawCircle(selectedBrush, alpha = .15f * p, radius = radius, center = center)
			drawCircle(selectedBrush, alpha = .92f * p, radius = radius, center = center, style = Stroke(width = 1.35.dp.toPx()))
			drawCircle(Color.White.copy(alpha = .24f * p), radius = radius * .72f, center = center, style = Stroke(width = .7.dp.toPx()))
			if (spec.activeShape == ExclusiveNavigationActiveShape.CROWN_MEDALLION) {
				val crown = Path().apply {
					moveTo(center.x - 7.dp.toPx(), center.y - radius + 4.dp.toPx())
					lineTo(center.x - 3.dp.toPx(), center.y - radius - 2.dp.toPx())
					lineTo(center.x, center.y - radius + 3.dp.toPx())
					lineTo(center.x + 3.dp.toPx(), center.y - radius - 2.dp.toPx())
					lineTo(center.x + 7.dp.toPx(), center.y - radius + 4.dp.toPx())
				}
				drawPath(crown, selectedBrush, alpha = (.76f + .18f * eventWave) * p, style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round))
				drawCircle(
					Color.White.copy(alpha = (.34f + .46f * eventWave + .12f * ambientWave) * p),
					radius = (1.2f + .4f * eventWave).dp.toPx(),
					center = androidx.compose.ui.geometry.Offset(center.x, center.y - radius - 2.dp.toPx()),
				)
			}
		}
		ExclusiveNavigationActiveShape.PRISM_DOUBLE_RING -> {
			drawCircle(selectedBrush, alpha = .10f * p, radius = radius * .80f, center = center)
			drawCircle(selectedBrush, alpha = .96f * p, radius = radius * .78f, center = center, style = Stroke(width = 1.35.dp.toPx()))
			drawCircle(selectedBrush, alpha = (.62f + .08f * ambientWave) * p, radius = radius, center = center, style = Stroke(width = 1.dp.toPx()))
			if (ambientPhase > 0f && !reduceGlow) {
				drawArc(
					color = Color.White.copy(alpha = .20f * p),
					startAngle = ambientPhase * 360f,
					sweepAngle = 34f,
					useCenter = false,
					topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
					size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
					style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
				)
			}
			drawStarFlare(androidx.compose.ui.geometry.Offset(center.x, center.y - radius), Color.White.copy(alpha = (.66f + .18f * eventWave) * p), 3.dp.toPx())
		}
		ExclusiveNavigationActiveShape.LUMINOUS_ORB -> {
			val haloDrift = .78f + .10f * ((sin(ambientPhase * 4f * PI).toFloat() + 1f) * .5f)
			drawCircle(
				brush = Brush.radialGradient(
					listOf(
						Color.White.copy(alpha = .30f * p),
						Color.White.copy(alpha = .08f * p),
						Color.Transparent,
					),
					center = center,
					radius = radius * 1.18f,
				),
				radius = radius * 1.18f,
				center = center,
			)
			drawCircle(selectedBrush, alpha = .94f * p, radius = radius * .78f, center = center, style = Stroke(width = 1.4.dp.toPx()))
			drawCircle(
				Color.White.copy(alpha = (haloDrift + .08f * eventWave).coerceAtMost(.96f) * p),
				radius = radius,
				center = center,
				style = Stroke(width = 1.dp.toPx()),
			)
			drawStarFlare(
				androidx.compose.ui.geometry.Offset(center.x, center.y - radius),
				Color.White.copy(alpha = (.68f + .28f * eventWave) * p),
				3.2.dp.toPx() * (1f + .08f * eventWave),
			)
			drawStarFlare(
				androidx.compose.ui.geometry.Offset(center.x, center.y + radius),
				Color.White.copy(alpha = (.58f + .18f * eventWave) * p),
				2.6.dp.toPx(),
			)
		}
	}
}


@Composable
private fun rememberPowerSaveMode(): Boolean {
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
