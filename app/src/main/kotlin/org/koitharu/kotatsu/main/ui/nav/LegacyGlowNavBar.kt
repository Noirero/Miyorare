package org.koitharu.kotatsu.main.ui.nav

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import org.koitharu.kotatsu.R

/**
 * Lightweight restyle for the "legacy navigation bar" preference.
 *
 * The glow is deliberately finite: translucent accent layers and static outlines only. There is
 * no backdrop blur, shader, pulsing effect or continuously-running animation, so the selected item
 * reads as bright without turning the bottom bar into a persistent GPU effect.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LegacyGlowNavBar(
	items: List<FloatingNavBarItem>,
	selectedId: Int,
	showLabels: Boolean,
	colors: FloatingNavBarColors,
	onItemSelected: (Int) -> Unit,
	onItemReselected: (Int) -> Unit,
	modifier: Modifier = Modifier,
	onItemLongClick: (Int) -> Unit = {},
	emphasizeFavourites: Boolean = false,
) {
	val visibleItems = items.take(MAX_LEGACY_ITEMS)
	if (visibleItems.isEmpty()) return

	val accent = MaterialTheme.colorScheme.primary
	val barShape = RoundedCornerShape(30.dp)
	val barContainer = Color(
		ColorUtils.blendARGB(
			colors.container,
			accent.toArgb(),
			if (emphasizeFavourites) 0.72f else BAR_ACCENT_MIX,
		),
	)
	val favouritesGlass = if (emphasizeFavourites) {
		Brush.linearGradient(
			listOf(
				Color(
					ColorUtils.setAlphaComponent(
						ColorUtils.blendARGB(colors.container, accent.toArgb(), 0.82f),
						108,
					),
				),
				Color(
					ColorUtils.setAlphaComponent(
						ColorUtils.blendARGB(colors.container, accent.toArgb(), 0.68f),
						88,
					),
				),
				Color(
					ColorUtils.setAlphaComponent(
						ColorUtils.blendARGB(colors.container, accent.toArgb(), 0.78f),
						100,
					),
				),
			),
		)
	} else null

	// Legacy mode is the four-labelled-item layout used by the Favourites mockup. The renderer
	// owns its own glass treatment; callers only select whether Favourites emphasis is active.
	Box(
		modifier = modifier
			.background(
				accent.copy(alpha = if (emphasizeFavourites) 0.14f else BAR_GLOW_ALPHA),
				barShape,
			)
			.padding(if (emphasizeFavourites) 2.dp else 2.dp),
	) {
		Surface(
			modifier = Modifier.fillMaxWidth(),
			shape = barShape,
			color = if (favouritesGlass != null) Color.Transparent else barContainer,
			contentColor = MaterialTheme.colorScheme.onSurface,
			border = BorderStroke(
				1.dp,
				accent.copy(alpha = if (emphasizeFavourites) 0.82f else BAR_BORDER_ALPHA),
			),
			shadowElevation = 0.dp,
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.then(
						if (favouritesGlass != null) Modifier.background(favouritesGlass, barShape) else Modifier,
					)
					.padding(horizontal = 4.dp, vertical = 5.dp),
				horizontalArrangement = Arrangement.spacedBy(2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				visibleItems.forEach { item ->
					LegacyGlowNavItem(
						item = item,
						selected = item.id == selectedId,
						showLabel = showLabels,
						colors = colors,
						accent = accent,
						emphasizeFavourites = emphasizeFavourites,
						modifier = Modifier.weight(1f),
						onClick = {
							if (item.id == selectedId) onItemReselected(item.id) else onItemSelected(item.id)
						},
						onLongClick = { onItemLongClick(item.id) },
					)
				}
			}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LegacyGlowNavItem(
	item: FloatingNavBarItem,
	selected: Boolean,
	showLabel: Boolean,
	colors: FloatingNavBarColors,
	accent: Color,
	emphasizeFavourites: Boolean,
	modifier: Modifier,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
) {
	val title = androidx.compose.ui.res.stringResource(item.titleRes)
	val itemShape = RoundedCornerShape(24.dp)
	val itemHeight = if (showLabel) 58.dp else 48.dp
	val selectedContainer = if (emphasizeFavourites) {
		Color(
			ColorUtils.setAlphaComponent(
				ColorUtils.blendARGB(colors.container, accent.toArgb(), 0.92f),
				190,
			),
		)
	} else {
		Color(
			ColorUtils.blendARGB(
				colors.container,
				accent.toArgb(),
				SELECTED_ACCENT_MIX,
			),
		)
	}
	val content = when {
		selected && emphasizeFavourites -> MaterialTheme.colorScheme.onSurface
		selected -> accent
		emphasizeFavourites -> Color(colors.unselectedContent).copy(alpha = 0.96f)
		else -> Color(colors.unselectedContent)
	}

	Box(
		modifier = modifier
			.height(itemHeight + 6.dp)
			.then(
				if (selected) {
					Modifier.background(
						accent.copy(alpha = if (emphasizeFavourites) 0.28f else SELECTED_GLOW_ALPHA),
						itemShape,
					)
				} else {
					Modifier
				},
			)
			.combinedClickable(onClick = onClick, onLongClick = onLongClick)
			.semantics {
				this.selected = selected
				role = Role.Tab
				contentDescription = title
			}
			.padding(3.dp),
		contentAlignment = Alignment.Center,
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(itemHeight)
				.then(
					if (selected) {
						Modifier
							.background(selectedContainer, itemShape)
							.border(
								1.dp,
								accent.copy(alpha = if (emphasizeFavourites) 1f else SELECTED_BORDER_ALPHA),
								itemShape,
							)
					} else {
						Modifier
					},
				),
			contentAlignment = Alignment.Center,
		) {
			Column(
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(if (showLabel) 2.dp else 0.dp),
			) {
				androidx.compose.material3.BadgedBox(
					badge = {
						if (item.badgeCount > 0) {
							Badge {
								Text(if (item.badgeCount > 99) "99+" else item.badgeCount.toString())
							}
						} else if (item.badgeCount < 0) {
							Badge()
						}
					},
				) {
					LegacyNavIcon(
						resId = item.icon,
						selected = selected,
						tint = content,
						modifier = Modifier.size(if (selected) 25.dp else 24.dp),
					)
				}
				if (showLabel) {
					Text(
						text = title,
						color = content,
						style = MaterialTheme.typography.labelMedium,
						fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}

private val LEGACY_SELECTOR_STATE_CHECKED = intArrayOf(android.R.attr.state_checked)
private val LEGACY_SELECTOR_STATE_UNCHECKED = intArrayOf(-android.R.attr.state_checked)

@Composable
private fun LegacyNavIcon(
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
			val targetState = if (selected) LEGACY_SELECTOR_STATE_CHECKED else LEGACY_SELECTOR_STATE_UNCHECKED
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

private const val MAX_LEGACY_ITEMS = 4
private const val BAR_ACCENT_MIX = 0.07f
private const val BAR_GLOW_ALPHA = 0.10f
private const val BAR_BORDER_ALPHA = 0.55f
private const val SELECTED_ACCENT_MIX = 0.22f
private const val SELECTED_GLOW_ALPHA = 0.20f
private const val SELECTED_BORDER_ALPHA = 0.95f
