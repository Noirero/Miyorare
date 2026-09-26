package org.koitharu.kotatsu.main.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import org.koitharu.kotatsu.core.ui.ExclusiveThemeComponentPalette
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveBottomNavigationSpec
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveNavigationActiveShape
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveNavigationIndicator
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveNavigationOrnament
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveNavigationSilhouette
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
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
	val ambientEnabled = !reduceMotion && !minimalCosmetics && spec.ambientCycleMs != null
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

private fun DrawScope.drawExclusiveBody(
	spec: ExclusiveBottomNavigationSpec,
	containerBrush: Brush,
	borderBrush: Brush,
	glowBrush: Brush,
	radiusPx: Float,
	selectedX: Float,
	ambientPhase: Float,
	reduceGlow: Boolean,
) {
	// Body is a real vector-drawn capsule, never a full-navigation bitmap.
	drawRoundRect(
		brush = containerBrush,
		cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx),
	)

	if (!reduceGlow) {
		// Premium glow budget: one soft halo plus one near halo; the sharp stroke is drawn below.
		drawRoundRect(
			brush = glowBrush,
			alpha = 0.10f,
			cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx),
			style = Stroke(width = 8.dp.toPx()),
		)
		drawRoundRect(
			brush = glowBrush,
			alpha = 0.20f,
			cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx),
			style = Stroke(width = 3.dp.toPx()),
		)
	}

	drawRoundRect(
		brush = borderBrush,
		alpha = 0.94f,
		cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx),
		style = Stroke(width = spec.borderWidthDp.dp.toPx()),
	)

	if (spec.doubleBorder) {
		val inset = 3.dp.toPx()
		drawRoundRect(
			brush = borderBrush,
			alpha = 0.34f,
			topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
			size = androidx.compose.ui.geometry.Size(size.width - inset * 2f, size.height - inset * 2f),
			cornerRadius = androidx.compose.ui.geometry.CornerRadius(
				(radiusPx - inset).coerceAtLeast(1f),
				(radiusPx - inset).coerceAtLeast(1f),
			),
			style = Stroke(width = 0.75.dp.toPx()),
		)
	}

	if (spec.innerHighlight) {
		val y = 3.dp.toPx()
		drawLine(
			color = Color.White.copy(alpha = 0.16f),
			start = androidx.compose.ui.geometry.Offset(radiusPx * 0.72f, y),
			end = androidx.compose.ui.geometry.Offset(size.width - radiusPx * 0.72f, y),
			strokeWidth = 0.75.dp.toPx(),
			cap = StrokeCap.Round,
		)
	}

	drawSilhouetteAccents(spec, borderBrush)
	drawBarOrnaments(spec, borderBrush, glowBrush, selectedX, ambientPhase, reduceGlow)

	// Ranks with an ambient cycle use at most one low-alpha travelling border highlight.
	if (spec.ambientCycleMs != null && ambientPhase > 0f) {
		val x = (size.width * ambientPhase).coerceIn(0f, size.width)
		drawLine(
			color = Color.White.copy(alpha = if (reduceGlow) 0.05f else 0.14f),
			start = androidx.compose.ui.geometry.Offset((x - 14.dp.toPx()).coerceAtLeast(0f), 2.dp.toPx()),
			end = androidx.compose.ui.geometry.Offset((x + 14.dp.toPx()).coerceAtMost(size.width), 2.dp.toPx()),
			strokeWidth = 1.dp.toPx(),
			cap = StrokeCap.Round,
		)
	}
}

private fun DrawScope.drawSilhouetteAccents(
	spec: ExclusiveBottomNavigationSpec,
	borderBrush: Brush,
) {
	val edge = 8.dp.toPx()
	val h = size.height
	val w = size.width
	when (spec.silhouette) {
		ExclusiveNavigationSilhouette.CAPSULE -> Unit
		ExclusiveNavigationSilhouette.ANGULAR -> {
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(edge, h * .30f), androidx.compose.ui.geometry.Offset(edge * 1.9f, 2.dp.toPx()), 1.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(w - edge, h * .30f), androidx.compose.ui.geometry.Offset(w - edge * 1.9f, 2.dp.toPx()), 1.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(edge, h * .70f), androidx.compose.ui.geometry.Offset(edge * 1.9f, h - 2.dp.toPx()), 1.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(w - edge, h * .70f), androidx.compose.ui.geometry.Offset(w - edge * 1.9f, h - 2.dp.toPx()), 1.dp.toPx())
		}
		ExclusiveNavigationSilhouette.BEVELED,
		ExclusiveNavigationSilhouette.ORNAMENTAL -> {
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(2.dp.toPx(), h * .35f), androidx.compose.ui.geometry.Offset(edge * 1.4f, h * .20f), 1.1.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(2.dp.toPx(), h * .65f), androidx.compose.ui.geometry.Offset(edge * 1.4f, h * .80f), 1.1.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(w - 2.dp.toPx(), h * .35f), androidx.compose.ui.geometry.Offset(w - edge * 1.4f, h * .20f), 1.1.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(w - 2.dp.toPx(), h * .65f), androidx.compose.ui.geometry.Offset(w - edge * 1.4f, h * .80f), 1.1.dp.toPx())
		}
		ExclusiveNavigationSilhouette.PRISM -> {
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(8.dp.toPx(), h / 2f), 4.dp.toPx(), 0.9f)
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(w - 8.dp.toPx(), h / 2f), 4.dp.toPx(), 0.9f)
		}
		ExclusiveNavigationSilhouette.CELESTIAL -> {
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(4.dp.toPx(), h * .28f), androidx.compose.ui.geometry.Offset(13.dp.toPx(), h * .16f), .9.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(w - 4.dp.toPx(), h * .28f), androidx.compose.ui.geometry.Offset(w - 13.dp.toPx(), h * .16f), .9.dp.toPx())
		}
	}
}

private fun DrawScope.drawBarOrnaments(
	spec: ExclusiveBottomNavigationSpec,
	borderBrush: Brush,
	glowBrush: Brush,
	selectedX: Float,
	ambientPhase: Float,
	reduceGlow: Boolean,
) {
	val w = size.width
	val h = size.height
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
			drawStarFlare(androidx.compose.ui.geometry.Offset(selectedX, 3.dp.toPx()), Color.White.copy(alpha = accentAlpha), 4.dp.toPx())
		}
		ExclusiveNavigationOrnament.DIAMONDS -> {
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(6.dp.toPx(), h / 2f), 5.dp.toPx(), 0.74f)
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(w - 6.dp.toPx(), h / 2f), 5.dp.toPx(), 0.74f)
		}
		ExclusiveNavigationOrnament.STARS,
		ExclusiveNavigationOrnament.NEBULA_STARS,
		ExclusiveNavigationOrnament.EMBERS -> {
			drawStaticDots(spec.staticDotCount, glowBrush, alpha = if (spec.ornament == ExclusiveNavigationOrnament.NEBULA_STARS) 0.42f else 0.56f)
		}
		ExclusiveNavigationOrnament.MANUSCRIPT -> {
			val y0 = 9.dp.toPx()
			val y1 = h - 9.dp.toPx()
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(12.dp.toPx(), y0), androidx.compose.ui.geometry.Offset(22.dp.toPx(), y0), .9.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(12.dp.toPx(), y0), androidx.compose.ui.geometry.Offset(12.dp.toPx(), y0 + 8.dp.toPx()), .9.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(w - 12.dp.toPx(), y1), androidx.compose.ui.geometry.Offset(w - 22.dp.toPx(), y1), .9.dp.toPx())
			drawLine(borderBrush, androidx.compose.ui.geometry.Offset(w - 12.dp.toPx(), y1), androidx.compose.ui.geometry.Offset(w - 12.dp.toPx(), y1 - 8.dp.toPx()), .9.dp.toPx())
		}
		ExclusiveNavigationOrnament.GOLD_FINIALS -> {
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(7.dp.toPx(), h / 2f), 6.dp.toPx(), .9f)
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(w - 7.dp.toPx(), h / 2f), 6.dp.toPx(), .9f)
			drawStarFlare(androidx.compose.ui.geometry.Offset(selectedX, 3.dp.toPx()), Color.White.copy(alpha = accentAlpha), 4.dp.toPx())
		}
		ExclusiveNavigationOrnament.PRISM_SHARDS -> {
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(w / 2f, 3.dp.toPx()), 4.dp.toPx(), .9f)
			drawDiamond(borderBrush, androidx.compose.ui.geometry.Offset(w / 2f, h - 3.dp.toPx()), 4.dp.toPx(), .9f)
			drawStaticDots(spec.staticDotCount, glowBrush, alpha = 0.44f)
		}
		ExclusiveNavigationOrnament.INFINITY_ARCS -> {
			val path = Path().apply {
				moveTo(8.dp.toPx(), h * .55f)
				cubicTo(w * .18f, h * .08f, w * .32f, h * .08f, w * .50f, h * .50f)
				cubicTo(w * .68f, h * .92f, w * .82f, h * .92f, w - 8.dp.toPx(), h * .45f)
			}
			drawPath(
				path = path,
				brush = borderBrush,
				alpha = if (reduceGlow) .22f else .42f,
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
				alpha = if (reduceGlow) .18f else .34f,
				style = Stroke(width = .7.dp.toPx(), cap = StrokeCap.Round),
			)
			drawStarFlare(androidx.compose.ui.geometry.Offset(w / 2f, 3.dp.toPx()), Color.White.copy(alpha = accentAlpha), 4.5.dp.toPx())
			drawStarFlare(androidx.compose.ui.geometry.Offset(w / 2f, h - 3.dp.toPx()), Color.White.copy(alpha = accentAlpha * .86f), 4.dp.toPx())
			drawStaticDots(spec.staticDotCount, glowBrush, alpha = .38f)
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
		drawStarFlare(
			center = androidx.compose.ui.geometry.Offset(selectedX, 3.dp.toPx()),
			color = Color.White.copy(alpha = accentAlpha),
			radius = 3.5.dp.toPx(),
		)
	}

	// Ambient phase is intentionally only used as a tiny opacity/position cue, never as full-body motion.
	if (ambientPhase > 0f && spec.ornament == ExclusiveNavigationOrnament.PRISM_SHARDS) {
		val x = 14.dp.toPx() + (w - 28.dp.toPx()) * ambientPhase
		drawCircle(Color.White.copy(alpha = .22f), 1.5.dp.toPx(), androidx.compose.ui.geometry.Offset(x, 4.dp.toPx()))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ExclusiveNavigationItem(
	item: FloatingNavBarItem,
	selected: Boolean,
	showLabel: Boolean,
	spec: ExclusiveBottomNavigationSpec,
	palette: ExclusiveThemeComponentPalette,
	ambientPhase: Float,
	reduceGlow: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
) {
	val selection by animateFloatAsState(
		targetValue = if (selected) 1f else 0f,
		animationSpec = tween(spec.selectionDurationMs),
		label = "exclusiveNavSelection",
	)
	val selectedContent = palette.content
	val inactiveContent = palette.mutedContent.copy(alpha = .68f)
	val iconTint by animateColorAsState(
		targetValue = if (selected) selectedContent else inactiveContent,
		animationSpec = tween(spec.selectionDurationMs),
		label = "exclusiveNavIconTint",
	)
	val labelTint by animateColorAsState(
		targetValue = if (selected) palette.interactiveText else inactiveContent.copy(alpha = .92f),
		animationSpec = tween(spec.selectionDurationMs),
		label = "exclusiveNavLabelTint",
	)
	val title = androidx.compose.ui.res.stringResource(item.titleRes)
	val selectedBrush = remember(palette.selectedStops) {
		Brush.horizontalGradient(palette.selectedStops)
	}
	val glowBrush = remember(palette.glowStops) {
		Brush.horizontalGradient(palette.glowStops)
	}

	Box(
		modifier = Modifier
			.weight(1f)
			.fillMaxHeight()
			.combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
				modifier = Modifier.size(spec.activeDiameterDp.dp),
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
						.background(
							if (selected) selectedBrush else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)),
							RoundedCornerShape(2.dp),
						),
				)
				ExclusiveNavigationIndicator.LIGHT_SEED -> Box(
					modifier = Modifier
						.width(4.dp)
						.height(3.dp)
						.background(
							if (selected) selectedBrush else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)),
							CircleShape,
						),
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
	reduceGlow: Boolean,
) {
	if (progress <= 0.001f) return
	val p = progress.coerceIn(0f, 1f)
	val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
	val radius = size.minDimension * .42f * (.92f + .08f * p)
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
			drawCircle(selectedBrush, alpha = .10f * p, radius = radius, center = center)
			drawCircle(selectedBrush, alpha = nearAlpha, radius = radius, center = center, style = Stroke(stroke))
			if (spec.activeShape == ExclusiveNavigationActiveShape.EMBER_RING) {
				drawCircle(Color.White.copy(alpha = .54f * p), 1.2.dp.toPx(), androidx.compose.ui.geometry.Offset(center.x, center.y - radius))
				drawCircle(Color.White.copy(alpha = .40f * p), 1.dp.toPx(), androidx.compose.ui.geometry.Offset(center.x, center.y + radius))
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
			drawPath(hex, selectedBrush, alpha = .12f * p)
			drawPath(hex, selectedBrush, alpha = nearAlpha, style = Stroke(width = stroke))
		}
		ExclusiveNavigationActiveShape.DOUBLE_HALO -> {
			drawCircle(selectedBrush, alpha = .09f * p, radius = radius * .80f, center = center)
			drawCircle(selectedBrush, alpha = .92f * p, radius = radius * .78f, center = center, style = Stroke(stroke))
			drawCircle(selectedBrush, alpha = .44f * p, radius = radius, center = center, style = Stroke(width = .85.dp.toPx()))
		}
		ExclusiveNavigationActiveShape.BUBBLE -> {
			drawCircle(selectedBrush, alpha = .18f * p, radius = radius, center = center)
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
				drawPath(crown, selectedBrush, alpha = .86f * p, style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round))
			}
		}
		ExclusiveNavigationActiveShape.PRISM_DOUBLE_RING -> {
			drawCircle(selectedBrush, alpha = .10f * p, radius = radius * .80f, center = center)
			drawCircle(selectedBrush, alpha = .96f * p, radius = radius * .78f, center = center, style = Stroke(width = 1.35.dp.toPx()))
			drawCircle(selectedBrush, alpha = .70f * p, radius = radius, center = center, style = Stroke(width = 1.dp.toPx()))
			drawStarFlare(androidx.compose.ui.geometry.Offset(center.x, center.y - radius), Color.White.copy(alpha = .76f * p), 3.dp.toPx())
		}
		ExclusiveNavigationActiveShape.LUMINOUS_ORB -> {
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
			drawCircle(Color.White.copy(alpha = .82f * p), radius = radius, center = center, style = Stroke(width = 1.dp.toPx()))
			drawStarFlare(androidx.compose.ui.geometry.Offset(center.x, center.y - radius), Color.White.copy(alpha = .90f * p), 3.2.dp.toPx())
			drawStarFlare(androidx.compose.ui.geometry.Offset(center.x, center.y + radius), Color.White.copy(alpha = .72f * p), 2.6.dp.toPx())
		}
	}
}
