package org.koitharu.kotatsu.settings.compose

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.koitharu.kotatsu.core.prefs.MiyorareSettingsIconStyle
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.MiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.miyorareIconSurface
import org.koitharu.kotatsu.core.ui.miyorareSurface
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.main.ui.nav.rememberAnyDrawablePainter

@Composable
fun SettingsItem(
	title: String,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	@DrawableRes icon: Int? = null,
	iconColors: CategoryIconColors? = null,
	tintIcon: Boolean = true,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
	accentColor: Color? = null,
	onClick: (() -> Unit)? = null,
	hapticEffect: HapticEffect? = null,
	supportingContent: @Composable (() -> Unit)? = null,
	trailing: @Composable (() -> Unit)? = null,
) {
	val visualPalette = LocalMiyorareVisualPalette.current
	val modern = visualPalette.isModern
	val iconStyle = LocalSettingsIconStyle.current
	val modernIcons = iconStyle.usesModernIcons(modern)
	val largeFont = LocalDensity.current.fontScale >= 1.3f
	val haptic = rememberHapticEffect()
	val pendingHighlight by SettingsSearchHighlight.pendingTitle.collectAsState()
	val isHighlightTarget = pendingHighlight != null && pendingHighlight == title
	val scrollToHighlight = LocalSettingsHighlightScroll.current
	val rowWindowY = remember { mutableFloatStateOf(Float.NaN) }
	val highlight = remember { Animatable(0f) }
	LaunchedEffect(isHighlightTarget) {
		if (isHighlightTarget) {
			val y = snapshotFlow { rowWindowY.floatValue }.first { !it.isNaN() }
			scrollToHighlight(y)
			highlight.snapTo(1f)
			delay(320)
			highlight.animateTo(0f, animationSpec = tween(durationMillis = 1100))
			SettingsSearchHighlight.consume(title)
		}
	}
	val containerColor = lerp(
		MaterialTheme.colorScheme.surfaceContainer,
		MaterialTheme.colorScheme.primaryContainer,
		highlight.value,
	)
	val contentColor = lerp(
		MaterialTheme.colorScheme.onSurface,
		MaterialTheme.colorScheme.onPrimaryContainer,
		highlight.value,
	)
	val surfaceModifier = modifier
		.onGloballyPositioned { rowWindowY.floatValue = it.positionInWindow().y }
		.let {
			if (modern) {
				it.miyorareSurface(
					palette = visualPalette,
					shape = shape,
					selectedFraction = highlight.value,
				)
			} else {
				it
			}
		}
	Surface(
		modifier = surfaceModifier,
		shape = shape,
		color = if (modern) Color.Transparent else containerColor,
		contentColor = contentColor,
	) {
		Row(
			modifier = Modifier
				.heightIn(min = if (modern) 64.dp else 72.dp)
				.let {
					if (onClick != null && enabled) {
						it.clickable {
							if (hapticEffect != null) haptic(hapticEffect)
							onClick()
						}
					} else {
						it
					}
				}
				.padding(
					horizontal = if (modern) 14.dp else 12.dp,
					vertical = if (modern) 10.dp else 10.dp,
				),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (icon != null) {
				when {
					modernIcons -> SettingsIconModern(
						iconRes = icon,
						palette = visualPalette,
						enabled = enabled,
						tintIcon = tintIcon,
					)
					iconColors != null -> SettingsIconBubble(
						iconRes = icon,
						colors = iconColors,
						enabled = enabled,
						tintIcon = tintIcon,
					)
					iconStyle == MiyorareSettingsIconStyle.MATERIAL_3 && modern -> SettingsIconBubble(
						iconRes = icon,
						colors = CategoryIconColors(
							MaterialTheme.colorScheme.primaryContainer,
							MaterialTheme.colorScheme.onPrimaryContainer,
						),
						enabled = enabled,
						tintIcon = tintIcon,
					)
					else -> SettingsIconPlain(
						iconRes = icon,
						enabled = enabled,
						tintOverride = accentColor,
						tintIcon = tintIcon,
					)
				}
				Spacer(Modifier.width(if (modern) 12.dp else 14.dp))
			}
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleMedium,
					color = accentColor?.copy(alpha = if (enabled) 1f else 0.56f) ?: textColor(enabled),
					maxLines = if (largeFont) 4 else 2,
					overflow = TextOverflow.Ellipsis,
				)
				if (!subtitle.isNullOrBlank()) {
					Text(
						text = subtitle,
						style = MaterialTheme.typography.bodySmall,
						color = accentColor?.copy(alpha = if (enabled) 0.8f else 0.48f) ?: secondaryTextColor(enabled),
					)
				}
				supportingContent?.invoke()
			}
			if (trailing != null) {
				Spacer(Modifier.width(8.dp))
				trailing()
			}
		}
	}
}

@Composable
fun SwitchSettingsItem(
	title: String,
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	@DrawableRes icon: Int? = null,
	iconColors: CategoryIconColors? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
) {
	val haptic = rememberHapticEffect()
	val onCheckedChangeHaptic: (Boolean) -> Unit = { value ->
		haptic(if (value) HapticEffect.TOGGLE_ON else HapticEffect.TOGGLE_OFF)
		onCheckedChange(value)
	}
	SettingsItem(
		title = title,
		modifier = modifier,
		subtitle = subtitle,
		icon = icon,
		iconColors = iconColors,
		shape = shape,
		enabled = enabled,
		hapticEffect = null,
		onClick = if (enabled) {
			{ onCheckedChangeHaptic(!checked) }
		} else null,
		trailing = {
			Switch(checked = checked, onCheckedChange = onCheckedChangeHaptic, enabled = enabled)
		},
	)
}

/**
 * A manually selected Modern icon style must draw its inset surface even while the
 * surrounding Settings theme is Material 3. The normal Classic palette deliberately
 * has isModern=false and a transparent border highlight.
 */
internal fun MiyorareVisualPalette.forSettingsIconSurface(colors: ColorScheme): MiyorareVisualPalette =
	if (isModern) this else copy(
		isModern = true,
		iconGradientStart = colors.primaryContainer,
		selectedSurface = colors.surfaceContainerHigh,
		iconGradientEnd = colors.secondaryContainer,
		borderHighlight = colors.outlineVariant,
	)

@Composable
internal fun SettingsIconModern(
	@DrawableRes iconRes: Int,
	palette: MiyorareVisualPalette,
	enabled: Boolean,
	tintIcon: Boolean,
	tintOverride: Color? = null,
	applyImageAlpha: Boolean = true,
) {
	val alpha = if (enabled) 1f else 0.5f
	val shape = RoundedCornerShape(13.dp)
	val iconSurfacePalette = palette.forSettingsIconSurface(MaterialTheme.colorScheme)
	Box(
		modifier = Modifier
			.size(40.dp)
			.miyorareIconSurface(palette = iconSurfacePalette, shape = shape, alpha = alpha),
		contentAlignment = Alignment.Center,
	) {
		androidx.compose.foundation.Image(
			painter = rememberAnyDrawablePainter(iconRes),
			contentDescription = null,
			modifier = Modifier.size(21.dp),
			colorFilter = if (tintIcon) {
				ColorFilter.tint((tintOverride ?: if (palette.isModern) lerp(palette.primary, palette.secondary, 0.18f) else MaterialTheme.colorScheme.onPrimaryContainer).copy(alpha = alpha))
			} else null,
			alpha = if (applyImageAlpha) alpha else 1f,
		)
	}
}

@Composable
internal fun SettingsIconBubble(
	@DrawableRes iconRes: Int,
	colors: CategoryIconColors,
	enabled: Boolean,
	tintIcon: Boolean = true,
) {
	val containerAlpha = if (enabled) 1f else 0.48f
	val contentAlpha = if (enabled) 1f else 0.58f
	val containerColor = if (tintIcon) colors.container.copy(alpha = containerAlpha) else Color.White
	Box(
		modifier = Modifier
			.size(44.dp)
			.clip(CircleShape)
			.background(containerColor),
		contentAlignment = Alignment.Center,
	) {
		androidx.compose.foundation.Image(
			painter = rememberAnyDrawablePainter(iconRes),
			contentDescription = null,
			modifier = Modifier.size(if (tintIcon) 22.dp else 24.dp),
			colorFilter = if (tintIcon) ColorFilter.tint(colors.onContainer.copy(alpha = contentAlpha)) else null,
		)
	}
}

@Composable
internal fun SettingsIconPlain(
	@DrawableRes iconRes: Int,
	enabled: Boolean,
	tintOverride: Color? = null,
	tintIcon: Boolean = true,
	applyImageAlpha: Boolean = true,
) {
	val tint = (tintOverride ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = if (enabled) 1f else 0.5f)
	Box(
		modifier = Modifier.size(44.dp),
		contentAlignment = Alignment.Center,
	) {
		androidx.compose.foundation.Image(
			painter = rememberAnyDrawablePainter(iconRes),
			contentDescription = null,
			modifier = Modifier.size(24.dp),
			colorFilter = if (tintIcon) ColorFilter.tint(tint) else null,
			alpha = if (applyImageAlpha && !enabled) 0.5f else 1f,
		)
	}
}

@Composable
private fun textColor(enabled: Boolean): Color {
	val base = LocalContentColor.current
	return if (enabled) base else base.copy(alpha = 0.56f)
}

@Composable
private fun secondaryTextColor(enabled: Boolean): Color {
	val base = MaterialTheme.colorScheme.onSurfaceVariant
	return if (enabled) base else base.copy(alpha = 0.48f)
}