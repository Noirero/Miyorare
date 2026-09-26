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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.MiyorareFavouritesVisualSpec
import org.koitharu.kotatsu.core.ui.normalFavouritesLuminousAccent
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeSignatureRegistry

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
	val palette = LocalMiyorareVisualPalette.current
	val eternalLibrary = palette.rankThemeId == RankThemeId.ETERNAL_LIBRARY.stableId
	val eternalSignature = if (eternalLibrary) {
		RankThemeSignatureRegistry.resolve(RankThemeId.ETERNAL_LIBRARY)
	} else {
		null
	}
	val eternalFullPrism = eternalSignature?.borderStops?.map { Color(it) }.orEmpty()
	val lightMode = MaterialTheme.colorScheme.background.luminance() >= 0.5f
	val luminousAccent = if (emphasizeFavourites && eternalSignature != null) {
		Color(
			ColorUtils.blendARGB(
				eternalSignature.borderStops[5].toInt(),
				eternalSignature.borderStops[3].toInt(),
				0.42f,
			),
		)
	} else if (emphasizeFavourites) {
		Color(
			normalFavouritesLuminousAccent(
				accent.toArgb(),
				MaterialTheme.colorScheme.secondary.toArgb(),
			),
		)
	} else {
		accent
	}
	val barShape = RoundedCornerShape(
		if (emphasizeFavourites) MiyorareFavouritesVisualSpec.BOTTOM_NAV_RADIUS_DP.dp else 30.dp,
	)
	val darkNavyBase = if (lightMode) {
		ColorUtils.blendARGB(Color.White.toArgb(), luminousAccent.toArgb(), LIGHT_NAV_BASE_ACCENT_MIX)
	} else {
		ColorUtils.blendARGB(
			Color.Black.toArgb(),
			luminousAccent.toArgb(),
			0.30f,
		)
	}
	val favouritesBase = ColorUtils.blendARGB(
		darkNavyBase,
		luminousAccent.toArgb(),
		MiyorareFavouritesVisualSpec.BOTTOM_NAV_BASE_ACCENT_MIX,
	)
	val barContainer = Color(
		if (emphasizeFavourites) {
			ColorUtils.setAlphaComponent(favouritesBase, MiyorareFavouritesVisualSpec.BOTTOM_NAV_CONTAINER_ALPHA)
		} else {
			ColorUtils.blendARGB(colors.container, accent.toArgb(), BAR_ACCENT_MIX)
		},
	)
	val favouritesGlass = if (emphasizeFavourites && eternalFullPrism.isNotEmpty()) {
		Brush.horizontalGradient(
			eternalFullPrism.map { stop ->
				Color(
					ColorUtils.setAlphaComponent(
						ColorUtils.blendARGB(darkNavyBase, stop.toArgb(), 0.18f),
						MiyorareFavouritesVisualSpec.BOTTOM_NAV_CONTAINER_ALPHA,
					),
				)
			},
		)
	} else if (emphasizeFavourites) {
		Brush.linearGradient(
			listOf(
				Color(
					ColorUtils.setAlphaComponent(
						ColorUtils.blendARGB(
							darkNavyBase,
							luminousAccent.toArgb(),
							MiyorareFavouritesVisualSpec.BOTTOM_NAV_GRADIENT_START_ACCENT_MIX,
						),
						MiyorareFavouritesVisualSpec.BOTTOM_NAV_GRADIENT_START_ALPHA,
					),
				),
				Color(
					ColorUtils.setAlphaComponent(
						ColorUtils.blendARGB(
							darkNavyBase,
							luminousAccent.toArgb(),
							MiyorareFavouritesVisualSpec.BOTTOM_NAV_GRADIENT_CENTER_ACCENT_MIX,
						),
						MiyorareFavouritesVisualSpec.BOTTOM_NAV_GRADIENT_CENTER_ALPHA,
					),
				),
				Color(
					ColorUtils.setAlphaComponent(
						ColorUtils.blendARGB(
							darkNavyBase,
							luminousAccent.toArgb(),
							MiyorareFavouritesVisualSpec.BOTTOM_NAV_GRADIENT_END_ACCENT_MIX,
						),
						MiyorareFavouritesVisualSpec.BOTTOM_NAV_GRADIENT_END_ALPHA,
					),
				),
			),
		)
	} else null
	val barCore = if (emphasizeFavourites) {
		if (lightMode) luminousAccent else Color(ColorUtils.blendARGB(luminousAccent.toArgb(), Color.White.toArgb(), 0.28f))
	} else {
		accent
	}

	// Legacy mode keeps the labelled layout while supporting the fixed fifth Reader Journey item. The renderer
	// owns its own glass treatment; callers only select whether Favourites emphasis is active.
	Box(
		modifier = modifier
			.then(
				if (emphasizeFavourites) {
					Modifier.drawBehind {
						val radius = MiyorareFavouritesVisualSpec.BOTTOM_NAV_RADIUS_DP.dp.toPx()
						if (eternalLibrary && eternalFullPrism.isNotEmpty()) {
							val prism = Brush.horizontalGradient(eternalFullPrism)
							drawRoundRect(
								brush = prism,
								alpha = if (lightMode) LIGHT_NAV_OUTER_GLOW_ALPHA else MiyorareFavouritesVisualSpec.BOTTOM_NAV_OUTER_GLOW_ALPHA,
								cornerRadius = CornerRadius(radius, radius),
								style = Stroke(width = 12.dp.toPx()),
							)
							drawRoundRect(
								brush = prism,
								alpha = if (lightMode) LIGHT_NAV_MID_GLOW_ALPHA else MiyorareFavouritesVisualSpec.BOTTOM_NAV_MID_GLOW_ALPHA,
								cornerRadius = CornerRadius(radius, radius),
								style = Stroke(width = 6.5.dp.toPx()),
							)
							drawRoundRect(
								brush = prism,
								alpha = if (lightMode) LIGHT_NAV_NEAR_GLOW_ALPHA else MiyorareFavouritesVisualSpec.BOTTOM_NAV_NEAR_GLOW_ALPHA,
								cornerRadius = CornerRadius(radius, radius),
								style = Stroke(width = 2.dp.toPx()),
							)
						} else {
							drawRoundRect(
								color = luminousAccent.copy(alpha = if (lightMode) LIGHT_NAV_OUTER_GLOW_ALPHA else MiyorareFavouritesVisualSpec.BOTTOM_NAV_OUTER_GLOW_ALPHA),
								cornerRadius = CornerRadius(radius, radius),
								style = Stroke(width = 12.dp.toPx()),
							)
							drawRoundRect(
								color = luminousAccent.copy(alpha = if (lightMode) LIGHT_NAV_MID_GLOW_ALPHA else MiyorareFavouritesVisualSpec.BOTTOM_NAV_MID_GLOW_ALPHA),
								cornerRadius = CornerRadius(radius, radius),
								style = Stroke(width = 6.5.dp.toPx()),
							)
							drawRoundRect(
								color = luminousAccent.copy(alpha = if (lightMode) LIGHT_NAV_NEAR_GLOW_ALPHA else MiyorareFavouritesVisualSpec.BOTTOM_NAV_NEAR_GLOW_ALPHA),
								cornerRadius = CornerRadius(radius, radius),
								style = Stroke(width = 2.dp.toPx()),
							)
						}
					}
				} else {
					Modifier.background(accent.copy(alpha = BAR_GLOW_ALPHA), barShape)
				},
			)
			.padding(2.dp),
	) {
		Surface(
			modifier = Modifier.fillMaxWidth(),
			shape = barShape,
			color = if (favouritesGlass != null) Color.Transparent else barContainer,
			contentColor = MaterialTheme.colorScheme.onSurface,
			border = if (emphasizeFavourites && eternalFullPrism.isNotEmpty()) {
				BorderStroke(
					1.dp,
					Brush.horizontalGradient(
						eternalFullPrism.map { it.copy(alpha = if (lightMode) 0.62f else 0.78f) },
					),
				)
			} else {
				BorderStroke(
					1.dp,
					barCore.copy(
						alpha = when {
							emphasizeFavourites && lightMode -> LIGHT_NAV_BORDER_ALPHA
							emphasizeFavourites -> MiyorareFavouritesVisualSpec.BOTTOM_NAV_BORDER_ALPHA
							else -> BAR_BORDER_ALPHA
						},
					),
				)
			},
			shadowElevation = 0.dp,
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.then(
						if (favouritesGlass != null) Modifier.background(favouritesGlass, barShape) else Modifier,
					)
					.then(
						if (emphasizeFavourites) {
							Modifier
								.height(MiyorareFavouritesVisualSpec.BOTTOM_NAV_HEIGHT_DP.dp)
								.padding(horizontal = 4.dp, vertical = 4.dp)
						} else {
							Modifier.padding(horizontal = 4.dp, vertical = 5.dp)
						},
					),
				horizontalArrangement = Arrangement.spacedBy(2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				visibleItems.forEach { item ->
					LegacyGlowNavItem(
						item = item,
						selected = item.id == selectedId,
						showLabel = showLabels,
						colors = colors,
						accent = luminousAccent,
						eternalLibrary = eternalLibrary,
						eternalFullPrism = eternalFullPrism,
						emphasizeFavourites = emphasizeFavourites,
						lightMode = lightMode,
						compactLabel = visibleItems.size >= MAX_LEGACY_ITEMS,
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
	eternalLibrary: Boolean,
	eternalFullPrism: List<Color>,
	emphasizeFavourites: Boolean,
	lightMode: Boolean,
	compactLabel: Boolean,
	modifier: Modifier,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
) {
	val title = androidx.compose.ui.res.stringResource(item.titleRes)
	val itemShape = RoundedCornerShape(
		if (emphasizeFavourites) MiyorareFavouritesVisualSpec.BOTTOM_NAV_ITEM_RADIUS_DP.dp else 24.dp,
	)
	val itemHeight = if (emphasizeFavourites) {
		if (showLabel) 52.dp else 46.dp
	} else {
		if (showLabel) 58.dp else 48.dp
	}
	val selectedContainer = if (emphasizeFavourites) {
		val selectedBase = if (lightMode) {
			ColorUtils.blendARGB(Color.White.toArgb(), accent.toArgb(), LIGHT_NAV_SELECTED_ACCENT_MIX)
		} else {
			val selectedDark = ColorUtils.blendARGB(
				Color.Black.toArgb(),
				accent.toArgb(),
				0.30f,
			)
			ColorUtils.blendARGB(
				selectedDark,
				accent.toArgb(),
				MiyorareFavouritesVisualSpec.BOTTOM_NAV_SELECTED_ACCENT_MIX,
			)
		}
		Color(
			ColorUtils.setAlphaComponent(
				selectedBase,
				MiyorareFavouritesVisualSpec.BOTTOM_NAV_SELECTED_ALPHA,
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
		selected && emphasizeFavourites && lightMode -> accent
		selected && emphasizeFavourites -> Color.White
		selected -> accent
		emphasizeFavourites && lightMode -> Color(colors.unselectedContent).copy(alpha = LIGHT_NAV_INACTIVE_CONTENT_ALPHA)
		emphasizeFavourites -> Color.White.copy(alpha = MiyorareFavouritesVisualSpec.BOTTOM_NAV_INACTIVE_CONTENT_ALPHA)
		else -> Color(colors.unselectedContent)
	}
	val selectedCore = if (lightMode && emphasizeFavourites) {
		Color(ColorUtils.blendARGB(Color.White.toArgb(), accent.toArgb(), LIGHT_NAV_SELECTED_CORE_MIX))
	} else {
		Color(ColorUtils.blendARGB(accent.toArgb(), Color.White.toArgb(), 0.42f))
	}

	Box(
		modifier = modifier
			.height(
				if (emphasizeFavourites) MiyorareFavouritesVisualSpec.BOTTOM_NAV_SELECTED_HEIGHT_DP.dp
				else itemHeight + 6.dp,
			)
			.then(
				if (selected && !emphasizeFavourites) {
					Modifier.background(accent.copy(alpha = SELECTED_GLOW_ALPHA), itemShape)
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
		val selectedBrush = if (selected && emphasizeFavourites) {
			Brush.horizontalGradient(
				listOf(
					selectedContainer,
					selectedCore.copy(alpha = MiyorareFavouritesVisualSpec.BOTTOM_NAV_SELECTED_CENTER_ILLUMINATION_ALPHA),
					selectedContainer,
				),
			)
		} else {
			null
		}
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(itemHeight)
				.then(
					if (selected && emphasizeFavourites) {
						Modifier
							.drawBehind {
								val radius = MiyorareFavouritesVisualSpec.BOTTOM_NAV_ITEM_RADIUS_DP.dp.toPx()
								drawRoundRect(
									color = accent.copy(alpha = MiyorareFavouritesVisualSpec.BOTTOM_NAV_SELECTED_HALO_ALPHA),
									cornerRadius = CornerRadius(radius, radius),
									style = Stroke(width = 12.dp.toPx()),
								)
								drawRoundRect(
									color = accent.copy(alpha = MiyorareFavouritesVisualSpec.BOTTOM_NAV_SELECTED_MID_HALO_ALPHA),
									cornerRadius = CornerRadius(radius, radius),
									style = Stroke(width = 6.5.dp.toPx()),
								)
								drawRoundRect(
									color = accent.copy(alpha = MiyorareFavouritesVisualSpec.BOTTOM_NAV_SELECTED_NEAR_HALO_ALPHA),
									cornerRadius = CornerRadius(radius, radius),
									style = Stroke(width = 2.2.dp.toPx()),
								)
								drawRoundRect(
									color = selectedCore.copy(alpha = MiyorareFavouritesVisualSpec.BOTTOM_NAV_SELECTED_BORDER_ALPHA),
									cornerRadius = CornerRadius(radius, radius),
									style = Stroke(width = 1.dp.toPx()),
								)
							}
							.then(Modifier.background(checkNotNull(selectedBrush), itemShape))
					} else if (selected) {
						Modifier
							.background(selectedContainer, itemShape)
							.border(
								1.dp,
								accent.copy(alpha = SELECTED_BORDER_ALPHA),
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
						modifier = Modifier.size(
							(if (selected) {
								MiyorareFavouritesVisualSpec.BOTTOM_NAV_SELECTED_ICON_DP
							} else {
								MiyorareFavouritesVisualSpec.BOTTOM_NAV_ICON_DP
							}).dp,
						),
					)
				}
				if (showLabel) {
					Text(
						text = title,
						color = content,
						style = MaterialTheme.typography.labelMedium.copy(
							fontSize = when {
								compactLabel -> 11.sp
								emphasizeFavourites -> MiyorareFavouritesVisualSpec.BOTTOM_NAV_LABEL_TEXT_SP.sp
								else -> MaterialTheme.typography.labelMedium.fontSize
							},
							lineHeight = when {
								compactLabel -> 14.sp
								emphasizeFavourites -> MiyorareFavouritesVisualSpec.BOTTOM_NAV_LABEL_LINE_HEIGHT_SP.sp
								else -> MaterialTheme.typography.labelMedium.lineHeight
							},
						),
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

private const val MAX_LEGACY_ITEMS = 5
private const val BAR_ACCENT_MIX = 0.07f
private const val BAR_GLOW_ALPHA = 0.10f
private const val BAR_BORDER_ALPHA = 0.55f
private const val SELECTED_ACCENT_MIX = 0.22f
private const val SELECTED_GLOW_ALPHA = 0.20f
private const val SELECTED_BORDER_ALPHA = 0.95f
private const val LIGHT_NAV_BASE_ACCENT_MIX = 0.055f
private const val LIGHT_NAV_SELECTED_ACCENT_MIX = 0.16f
private const val LIGHT_NAV_SELECTED_CORE_MIX = 0.26f
private const val LIGHT_NAV_INACTIVE_CONTENT_ALPHA = 0.82f
private const val LIGHT_NAV_BORDER_ALPHA = 0.56f
private const val LIGHT_NAV_OUTER_GLOW_ALPHA = 0.07f
private const val LIGHT_NAV_MID_GLOW_ALPHA = 0.11f
private const val LIGHT_NAV_NEAR_GLOW_ALPHA = 0.18f
