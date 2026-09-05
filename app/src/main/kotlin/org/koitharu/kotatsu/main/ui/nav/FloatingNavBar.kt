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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
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

// Material 3 "expressive" default spatial spring — snappier than the standard Compose default,
// keeps icon, color, label-expand, and sibling-resize all on the same beat.
private val FloatSpec_Float = spring<Float>(
	dampingRatio = 0.9f,
	stiffness = 380f,
)
private val FloatSpec_Color = spring<Color>(
	dampingRatio = 0.9f,
	stiffness = 380f,
)
private val FloatSpec_Size = spring<IntSize>(
	dampingRatio = 0.9f,
	stiffness = 380f,
)

@Composable
fun FloatingNavBar(
	items: List<FloatingNavBarItem>,
	selectedId: Int,
	showLabels: Boolean,
	colors: FloatingNavBarColors,
	onItemSelected: (Int) -> Unit,
	onItemReselected: (Int) -> Unit,
	modifier: Modifier = Modifier,
	showContinue: Boolean = false,
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
		FloatingNavBarColors(
			container = ColorUtils.blendARGB(
				colors.container,
				primary,
				MiyorareVisualTokens.GLOW_ALPHA_LIGHT,
			),
			selectedContainer = ColorUtils.blendARGB(
				colors.container,
				primary,
				MiyorareVisualTokens.ACTIVE_GRADIENT_MIX * 0.55f,
			),
			selectedContent = primary,
			unselectedContent = ColorUtils.blendARGB(
				colors.unselectedContent,
				cs.onSurface.toArgb(),
				0.08f,
			),
		)
	} else {
		colors
	}
	val barColor = Color(effectiveColors.container)
	val barShape = if (isMiyorareModern) {
		RoundedCornerShape(MiyorareVisualTokens.RADIUS_SURFACE_DP.dp)
	} else {
		RoundedCornerShape(50)
	}
	val barOutline = if (isMiyorareModern) {
		BorderStroke(
			1.dp,
			Color(
				ColorUtils.setAlphaComponent(
					cs.primary.toArgb(),
					(MiyorareVisualTokens.BORDER_ALPHA_LIGHT * 255f).toInt().coerceIn(0, 255),
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
		Surface(
			modifier = Modifier
				.shadow(if (isMiyorareModern) 4.dp else 8.dp, barShape)
				.wrapContentWidth(),
			shape = barShape,
			color = barColor,
			contentColor = cs.onSurface,
			border = barOutline,
		) {
			Row(
				modifier = Modifier
					.heightIn(min = if (isMiyorareModern) 60.dp else 64.dp)
					.padding(
						horizontal = if (isMiyorareModern) 6.dp else 8.dp,
						vertical = if (isMiyorareModern) 6.dp else 8.dp,
					)
					// Smoothly relayout siblings when one pill grows/shrinks horizontally.
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
						onClick = {
							// Selection is routed through the host NavigationBarView, whose
							// listener (MainNavigationDelegate) already performs the CONFIRM
							// haptic — firing one here too would double-buzz. Reselecting the
							// current tab stays silent.
							if (item.id == selectedId) {
								onItemReselected(item.id)
							} else {
								onItemSelected(item.id)
							}
						},
					)
				}
			}
		}
		// A standalone, pill-coloured circular "continue reading" button living next to the
		// floating bar (like the search FAB in Tomato). It animates in/out smoothly and slides
		// along as the bar resizes, so it always feels part of the same floating toolbar.
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
	val container by animateColorAsState(
		targetValue = Color(colors.selectedContainer),
		animationSpec = FloatSpec_Color,
		label = "continueContainer",
	)
	val content by animateColorAsState(
		targetValue = Color(colors.selectedContent),
		animationSpec = FloatSpec_Color,
		label = "continueContent",
	)
	val label = stringResource(R.string.continue_reading)
	val tooltipState = rememberTooltipState()
	TooltipBox(
		positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
		tooltip = { PlainTooltip { Text(label) } },
		state = tooltipState,
	) {
		Surface(
			shape = RoundedCornerShape(
				if (isMiyorareModern) MiyorareVisualTokens.RADIUS_CONTROL_DP.dp else 16.dp,
			),
			color = container,
			contentColor = content,
			shadowElevation = if (isMiyorareModern) 4.dp else 8.dp,
			modifier = Modifier
				.size(if (isMiyorareModern) 54.dp else 56.dp)
				.semantics { contentDescription = label },
		) {
			Box(
				contentAlignment = Alignment.Center,
				modifier = Modifier.combinedClickable(
					onClick = onClick,
					onLongClick = onLongClick,
				),
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

@Composable
private fun FloatingNavItem(
	item: FloatingNavBarItem,
	selected: Boolean,
	showLabel: Boolean,
	colors: FloatingNavBarColors,
	isMiyorareModern: Boolean,
	onClick: () -> Unit,
) {
	val container by animateColorAsState(
		targetValue = if (selected) {
			Color(colors.selectedContainer)
		} else {
			Color.Transparent
		},
		animationSpec = FloatSpec_Color,
		label = "navItemContainer",
	)
	val content by animateColorAsState(
		targetValue = if (selected) {
			Color(colors.selectedContent)
		} else {
			Color(colors.unselectedContent)
		},
		animationSpec = FloatSpec_Color,
		label = "navItemContent",
	)
	val title = stringResource(item.titleRes)
	val interactionSource = remember { MutableInteractionSource() }
	val itemShape = if (isMiyorareModern) {
		RoundedCornerShape(MiyorareVisualTokens.RADIUS_CONTROL_DP.dp)
	} else {
		CircleShape
	}

	Box(
		modifier = Modifier
			.height(if (isMiyorareModern) 44.dp else 48.dp)
			.background(color = container, shape = itemShape)
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
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
					if (item.badgeCount > 0) {
						Badge { Text(text = if (item.badgeCount > 99) "99+" else item.badgeCount.toString()) }
					} else if (item.badgeCount < 0) {
						Badge()
					}
				},
			) {
				// Use a real ImageView so the AnimatedStateListDrawable's enter/leave morphs
				// (avd_*_enter / avd_*_leave) actually tick. Painting an AVD onto Compose's
				// canvas via a custom Painter doesn't reliably drive the platform animator —
				// ImageView does, because it's the same path the native BottomNavigationView uses.
				NavIcon(
					resId = item.icon,
					selected = selected,
					tint = content,
					modifier = Modifier.size(if (isMiyorareModern) 22.dp else 24.dp),
				)
			}
			AnimatedVisibility(
				visible = selected && showLabel,
				enter = expandHorizontally(
					animationSpec = FloatSpec_Size,
					expandFrom = Alignment.Start,
				) + fadeIn(animationSpec = FloatSpec_Float),
				exit = shrinkHorizontally(
					animationSpec = FloatSpec_Size,
					shrinkTowards = Alignment.Start,
				) + fadeOut(animationSpec = FloatSpec_Float),
			) {
				Text(
					text = title,
					color = content,
					fontSize = if (isMiyorareModern) 13.sp else 14.sp,
					lineHeight = if (isMiyorareModern) 18.sp else 20.sp,
					maxLines = 1,
					modifier = Modifier.padding(start = if (isMiyorareModern) 6.dp else 8.dp),
				)
			}
		}
	}
}

private val SELECTOR_STATE_CHECKED = intArrayOf(android.R.attr.state_checked)
private val SELECTOR_STATE_UNCHECKED = intArrayOf(-android.R.attr.state_checked)

/**
 * Renders a selector drawable (state-list with `<animated-selector>` transitions) inside
 * Compose by hosting a real [ImageView]. We use ImageView rather than a custom [Painter]
 * because `AnimatedVectorDrawable`'s animator pipeline doesn't reliably tick when painted
 * onto Compose's generic canvas — wrapping in a View matches the path the platform
 * `BottomNavigationView` uses, where these morphs are known to work.
 *
 * The [selected] flag drives the drawable state via [ImageView.setImageState], which is
 * what triggers the `<transition>` AVD between the normal and checked items.
 */
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
				// Prime initial state without a transition. Setting state twice (empty,
				// then the real state) suppresses the first-paint morph that would
				// otherwise fire on inflate.
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
