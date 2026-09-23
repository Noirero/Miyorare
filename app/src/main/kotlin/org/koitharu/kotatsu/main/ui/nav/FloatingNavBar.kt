package org.koitharu.kotatsu.main.ui.nav

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceManager
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.ui.MiyorareFavouritesVisualSpec
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.luminousThemeBlend
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.getEnumValue
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect

data class FloatingNavBarItem(
	@IdRes val id: Int,
	val titleRes: Int,
	@DrawableRes val icon: Int,
	val badgeCount: Int = 0,
)

data class FloatingNavBarColors(
	val container: Int,
	val selectedContainer: Int,
	val selectedContent: Int,
	val unselectedContent: Int,
)

private val FloatSpec_Float = spring<Float>(dampingRatio = 0.9f, stiffness = 380f)
private val FloatSpec_Color = spring<Color>(dampingRatio = 0.9f, stiffness = 380f)
private val FloatSpec_Size = spring<IntSize>(dampingRatio = 0.9f, stiffness = 380f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingNavBar(
	items: List<FloatingNavBarItem>,
	selectedId: Int,
	showLabels: Boolean,
	colors: FloatingNavBarColors,
	onItemSelected: (Int) -> Unit,
	onItemReselected: (Int) -> Unit,
	modifier: Modifier = Modifier,
	onItemLongClick: (Int) -> Unit = {},
	showContinue: Boolean = false,
	emphasizeFavourites: Boolean = false,
	onContinueClick: () -> Unit = {},
	onContinueLongClick: () -> Unit = {},
) {
	if (items.isEmpty()) return
	val context = LocalContext.current
	val cs = MaterialTheme.colorScheme
	val isMiyorareModern = remember(context) {
		PreferenceManager.getDefaultSharedPreferences(context).getEnumValue(
			MiyorareAppearance.KEY_DESIGN_STYLE,
			MiyorareDesignStyle.CLASSIC,
		) == MiyorareDesignStyle.MODERN
	}
	val effectiveColors = if (isMiyorareModern) {
		val primary = cs.primary.toArgb()
		if (emphasizeFavourites) {
			val luminousAccent = luminousThemeBlend(primary, cs.secondary.toArgb(), 0.34f)
			val darkNavyBase = ColorUtils.blendARGB(
				Color.Black.toArgb(),
				luminousAccent,
				0.22f,
			)
			val glassBase = ColorUtils.blendARGB(
				darkNavyBase,
				luminousAccent,
				MiyorareFavouritesVisualSpec.BOTTOM_NAV_BASE_ACCENT_MIX,
			)
			val selectedBase = ColorUtils.blendARGB(
				darkNavyBase,
				luminousAccent,
				MiyorareFavouritesVisualSpec.BOTTOM_NAV_SELECTED_ACCENT_MIX,
			)
			FloatingNavBarColors(
				container = ColorUtils.setAlphaComponent(glassBase, MiyorareFavouritesVisualSpec.BOTTOM_NAV_CONTAINER_ALPHA),
				selectedContainer = ColorUtils.setAlphaComponent(selectedBase, MiyorareFavouritesVisualSpec.BOTTOM_NAV_SELECTED_ALPHA),
				selectedContent = Color.White.toArgb(),
				unselectedContent = ColorUtils.setAlphaComponent(
					Color.White.toArgb(),
					(MiyorareFavouritesVisualSpec.BOTTOM_NAV_INACTIVE_CONTENT_ALPHA * 255f).toInt(),
				),
			)
		} else {
			FloatingNavBarColors(
				container = ColorUtils.blendARGB(colors.container, primary, MiyorareVisualTokens.GLOW_ALPHA_LIGHT),
				selectedContainer = ColorUtils.blendARGB(
					colors.container,
					primary,
					MiyorareVisualTokens.ACTIVE_GRADIENT_MIX * 0.55f,
				),
				selectedContent = primary,
				unselectedContent = ColorUtils.blendARGB(colors.unselectedContent, cs.onSurface.toArgb(), 0.08f),
			)
		}
	} else {
		colors
	}
	val barShape = if (isMiyorareModern) {
		RoundedCornerShape(
			if (emphasizeFavourites) MiyorareFavouritesVisualSpec.BOTTOM_NAV_RADIUS_DP.dp
			else MiyorareVisualTokens.RADIUS_SURFACE_DP.dp,
		)
	} else {
		RoundedCornerShape(50)
	}
	val barOutline = if (isMiyorareModern) {
		val borderBase = if (emphasizeFavourites) {
			luminousThemeBlend(cs.primary.toArgb(), cs.secondary.toArgb(), 0.34f)
		} else {
			cs.primary.toArgb()
		}
		BorderStroke(
			1.dp,
			Color(
				ColorUtils.setAlphaComponent(
					borderBase,
					(
						if (emphasizeFavourites) MiyorareFavouritesVisualSpec.BOTTOM_NAV_BORDER_ALPHA
						else MiyorareVisualTokens.BORDER_ALPHA_LIGHT
					).times(255f).toInt().coerceIn(0, 255),
				),
			),
		)
	} else null
	val normalFavouritesGlassBrush = if (isMiyorareModern && emphasizeFavourites) {
		val primary = luminousThemeBlend(cs.primary.toArgb(), cs.secondary.toArgb(), 0.34f)
		val darkNavyBase = ColorUtils.blendARGB(
			Color.Black.toArgb(),
			primary,
			0.22f,
		)
		Brush.linearGradient(
			listOf(
				Color(
					ColorUtils.setAlphaComponent(
						ColorUtils.blendARGB(
						darkNavyBase,
						primary,
						MiyorareFavouritesVisualSpec.BOTTOM_NAV_GRADIENT_START_ACCENT_MIX,
					),
						MiyorareFavouritesVisualSpec.BOTTOM_NAV_GRADIENT_START_ALPHA,
					),
				),
				Color(
					ColorUtils.setAlphaComponent(
						ColorUtils.blendARGB(
						darkNavyBase,
						primary,
						MiyorareFavouritesVisualSpec.BOTTOM_NAV_GRADIENT_CENTER_ACCENT_MIX,
					),
						MiyorareFavouritesVisualSpec.BOTTOM_NAV_GRADIENT_CENTER_ALPHA,
					),
				),
				Color(
					ColorUtils.setAlphaComponent(
						ColorUtils.blendARGB(
						darkNavyBase,
						primary,
						MiyorareFavouritesVisualSpec.BOTTOM_NAV_GRADIENT_END_ACCENT_MIX,
					),
						MiyorareFavouritesVisualSpec.BOTTOM_NAV_GRADIENT_END_ALPHA,
					),
				),
			),
		)
	} else {
		null
	}
	val haptic = rememberHapticEffect()

	Row(
		modifier = modifier.wrapContentWidth(),
		horizontalArrangement = Arrangement.spacedBy(if (isMiyorareModern) 6.dp else 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		val normalFavouritesGlow = if (isMiyorareModern && emphasizeFavourites) {
			// One soft perimeter halo. Surface border owns the crisp luminous edge.
			Modifier.border(
				3.dp,
				Color(
					ColorUtils.setAlphaComponent(
						cs.primary.toArgb(),
						(MiyorareFavouritesVisualSpec.BOTTOM_NAV_OUTER_GLOW_ALPHA * 255f).toInt(),
					),
				),
				barShape,
			)
		} else {
			Modifier
		}
		Surface(
			modifier = Modifier
				.then(normalFavouritesGlow)
				.shadow(if (isMiyorareModern) if (emphasizeFavourites) 0.dp else 4.dp else 8.dp, barShape)
				.wrapContentWidth(),
			shape = barShape,
			color = if (normalFavouritesGlassBrush != null) Color.Transparent else Color(effectiveColors.container),
			contentColor = cs.onSurface,
			border = barOutline,
		) {
			Row(
				modifier = Modifier
					.heightIn(
						min = if (isMiyorareModern && emphasizeFavourites) {
							MiyorareFavouritesVisualSpec.BOTTOM_NAV_HEIGHT_DP.dp
						} else if (isMiyorareModern) {
							60.dp
						} else {
							64.dp
						},
					)
					.then(
						if (normalFavouritesGlassBrush != null) {
							Modifier.background(normalFavouritesGlassBrush, barShape)
						} else {
							Modifier
						},
					)
					.padding(
						horizontal = if (isMiyorareModern) 6.dp else 8.dp,
						vertical = if (isMiyorareModern) 6.dp else 8.dp,
					)
					.animateContentSize(animationSpec = FloatSpec_Size),
				horizontalArrangement = Arrangement.spacedBy(if (isMiyorareModern) 2.dp else 4.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				items.forEach { item ->
					FloatingNavItem(
						item = item,
						selected = item.id == selectedId,
						showLabel = showLabels,
						colors = effectiveColors,
						isMiyorareModern = isMiyorareModern,
						emphasizeFavourites = emphasizeFavourites,
						onClick = {
							if (item.id == selectedId) onItemReselected(item.id) else onItemSelected(item.id)
						},
						onLongClick = {
							haptic(HapticEffect.LONG_PRESS)
							onItemLongClick(item.id)
						},
					)
				}
			}
		}
		AnimatedVisibility(
			visible = showContinue,
			enter = fadeIn(animationSpec = FloatSpec_Float) +
				expandHorizontally(animationSpec = FloatSpec_Size, expandFrom = Alignment.Start),
			exit = fadeOut(animationSpec = FloatSpec_Float) +
				shrinkHorizontally(animationSpec = FloatSpec_Size, shrinkTowards = Alignment.Start),
		) {
			FloatingContinueButton(
				colors = effectiveColors,
				isMiyorareModern = isMiyorareModern,
				onClick = {
					haptic(HapticEffect.CONFIRM)
					onContinueClick()
				},
				onLongClick = {
					haptic(HapticEffect.LONG_PRESS)
					onContinueLongClick()
				},
			)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FloatingContinueButton(
	colors: FloatingNavBarColors,
	isMiyorareModern: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
) {
	val container by animateColorAsState(Color(colors.selectedContainer), FloatSpec_Color, label = "continueContainer")
	val content by animateColorAsState(Color(colors.selectedContent), FloatSpec_Color, label = "continueContent")
	val label = stringResource(R.string.continue_reading)
	val tooltipState = rememberTooltipState()
	TooltipBox(
		positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
		tooltip = { PlainTooltip { Text(label) } },
		state = tooltipState,
	) {
		Surface(
			shape = RoundedCornerShape(if (isMiyorareModern) MiyorareVisualTokens.RADIUS_CONTROL_DP.dp else 16.dp),
			color = container,
			contentColor = content,
			shadowElevation = if (isMiyorareModern) 4.dp else 8.dp,
			modifier = Modifier
				.size(if (isMiyorareModern) 54.dp else 56.dp)
				.semantics { contentDescription = label },
		) {
			Box(
				contentAlignment = Alignment.Center,
				modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
			) {
				Icon(
					painter = painterResource(R.drawable.ic_read),
					contentDescription = null,
					tint = content,
					modifier = Modifier.size(24.dp),
				)
			}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FloatingNavItem(
	item: FloatingNavBarItem,
	selected: Boolean,
	showLabel: Boolean,
	colors: FloatingNavBarColors,
	isMiyorareModern: Boolean,
	emphasizeFavourites: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
) {
	val container by animateColorAsState(
		targetValue = if (selected) Color(colors.selectedContainer) else Color.Transparent,
		animationSpec = FloatSpec_Color,
		label = "navItemContainer",
	)
	val content by animateColorAsState(
		targetValue = if (selected) Color(colors.selectedContent) else Color(colors.unselectedContent),
		animationSpec = FloatSpec_Color,
		label = "navItemContent",
	)
	val title = stringResource(item.titleRes)
	val itemShape = if (isMiyorareModern) {
		RoundedCornerShape(
			if (emphasizeFavourites) MiyorareFavouritesVisualSpec.BOTTOM_NAV_ITEM_RADIUS_DP.dp
			else MiyorareVisualTokens.RADIUS_CONTROL_DP.dp,
		)
	} else {
		CircleShape
	}

	val selectedAccent = Color(
		luminousThemeBlend(
			MaterialTheme.colorScheme.primary.toArgb(),
			MaterialTheme.colorScheme.secondary.toArgb(),
			0.34f,
		),
	)
	val selectedChrome = if (isMiyorareModern && emphasizeFavourites && selected) {
		Modifier.drawBehind {
			val radius = MiyorareFavouritesVisualSpec.BOTTOM_NAV_ITEM_RADIUS_DP.dp.toPx()
			drawRoundRect(
				color = selectedAccent.copy(alpha = MiyorareFavouritesVisualSpec.BOTTOM_NAV_SELECTED_HALO_ALPHA),
				cornerRadius = CornerRadius(radius, radius),
				style = Stroke(width = 5.dp.toPx()),
			)
			drawRoundRect(
				color = selectedAccent.copy(alpha = MiyorareFavouritesVisualSpec.BOTTOM_NAV_SELECTED_BORDER_ALPHA),
				cornerRadius = CornerRadius(radius, radius),
				style = Stroke(width = 1.dp.toPx()),
			)
		}
	} else {
		Modifier
	}
	val selectedBrush = if (isMiyorareModern && emphasizeFavourites && selected) {
		Brush.horizontalGradient(
			listOf(
				container,
				selectedAccent.copy(alpha = 0.66f),
				container,
			),
		)
	} else {
		null
	}

	Box(
		modifier = Modifier
			.then(selectedChrome)
			.height(
				if (isMiyorareModern && emphasizeFavourites) {
					MiyorareFavouritesVisualSpec.BOTTOM_NAV_SELECTED_HEIGHT_DP.dp
				} else if (isMiyorareModern) {
					44.dp
				} else {
					48.dp
				},
			)
			.then(
				if (selectedBrush != null) Modifier.background(selectedBrush, itemShape)
				else Modifier.background(container, itemShape),
			)
			.combinedClickable(onClick = onClick, onLongClick = onLongClick)
			.semantics {
				this.selected = selected
				role = Role.Tab
				contentDescription = title
			},
		contentAlignment = Alignment.Center,
	) {
		Row(
			modifier = Modifier.padding(horizontal = if (isMiyorareModern) 12.dp else 14.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center,
		) {
			BadgedBox(
				badge = {
					if (item.badgeCount > 0) Badge { Text(if (item.badgeCount > 99) "99+" else item.badgeCount.toString()) }
					else if (item.badgeCount < 0) Badge()
				},
			) {
				NavIcon(
					resId = item.icon,
					selected = selected,
					tint = content,
					modifier = Modifier.size(
						when {
							isMiyorareModern && emphasizeFavourites && selected ->
								MiyorareFavouritesVisualSpec.BOTTOM_NAV_SELECTED_ICON_DP.dp
							isMiyorareModern && emphasizeFavourites ->
								MiyorareFavouritesVisualSpec.BOTTOM_NAV_ICON_DP.dp
							isMiyorareModern -> 22.dp
							else -> 24.dp
						},
					),
				)
			}
			AnimatedVisibility(
				visible = selected && showLabel,
				enter = expandHorizontally(animationSpec = FloatSpec_Size, expandFrom = Alignment.Start) +
					fadeIn(animationSpec = FloatSpec_Float),
				exit = shrinkHorizontally(animationSpec = FloatSpec_Size, shrinkTowards = Alignment.Start) +
					fadeOut(animationSpec = FloatSpec_Float),
			) {
				Text(
					text = title,
					color = content,
					fontSize = when {
						isMiyorareModern && emphasizeFavourites ->
							MiyorareFavouritesVisualSpec.BOTTOM_NAV_LABEL_TEXT_SP.sp
						isMiyorareModern -> 13.sp
						else -> 14.sp
					},
					lineHeight = when {
						isMiyorareModern && emphasizeFavourites ->
							MiyorareFavouritesVisualSpec.BOTTOM_NAV_LABEL_LINE_HEIGHT_SP.sp
						isMiyorareModern -> 18.sp
						else -> 20.sp
					},
					maxLines = 1,
					modifier = Modifier.padding(start = if (isMiyorareModern) 6.dp else 8.dp),
				)
			}
		}
	}
}

private val SELECTOR_STATE_CHECKED = intArrayOf(android.R.attr.state_checked)
private val SELECTOR_STATE_UNCHECKED = intArrayOf(-android.R.attr.state_checked)

@Composable
private fun NavIcon(
	@DrawableRes resId: Int,
	selected: Boolean,
	tint: Color,
	modifier: Modifier = Modifier,
) {
	AndroidView(
		modifier = modifier,
		factory = { ctx ->
			ImageView(ctx).apply {
				scaleType = ImageView.ScaleType.FIT_CENTER
				setImageResource(resId)
				setImageState(IntArray(0), false)
				jumpDrawablesToCurrentState()
			}
		},
		update = { iv ->
			val targetResId = when {
				resId == R.drawable.ic_explore_selector && selected -> R.drawable.ic_explore_checked
				resId == R.drawable.ic_explore_selector -> R.drawable.ic_explore_normal
				else -> resId
			}
			val resourceChanged = iv.tag != targetResId
			if (resourceChanged) {
				iv.setImageResource(targetResId)
				iv.tag = targetResId
			}
			val targetState = if (selected) SELECTOR_STATE_CHECKED else SELECTOR_STATE_UNCHECKED
			if (resourceChanged || iv.isSelected != selected) {
				iv.isSelected = selected
				iv.isActivated = selected
				iv.setImageState(targetState, false)
			}
			val tintColor = tint.toArgb()
			iv.imageTintList = ColorStateList.valueOf(tintColor)
			iv.drawable?.mutate()?.setTint(tintColor)
			iv.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
		},
	)
}
