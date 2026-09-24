package org.koitharu.kotatsu.details.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.details.ui.scrobbling.labelResId

@Composable
internal fun DescriptionCard(
	description: CharSequence?,
	manga: Manga,
	details: MangaDetails?,
	accent: Color,
) {
	val text = description?.toString()?.trim().orEmpty()
	val displayText = text.ifEmpty { stringResource(R.string.no_description) }
	val formattedText = remember(displayText) { formatDescriptionMarkdown(displayText) }
	val collapseEnabled by rememberBooleanPref(AppSettings.KEY_COLLAPSE_DESCRIPTION, true)
	var expanded by rememberSaveable(collapseEnabled) { mutableStateOf(!collapseEnabled) }
	var canExpand by remember { mutableStateOf(false) }
	val palette = LocalMiyorareVisualPalette.current
	val actionColor = accent

	SectionCard {
		val locale = details?.getLocale()
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = stringResource(R.string.description),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Spacer(modifier = Modifier.weight(1f))
			locale?.let {
				Pill(
					text = it.getDisplayLanguage(it).replaceFirstChar { ch -> ch.titlecase(it) },
					accent = accent,
					highlighted = true,
				) {
					Icon(
						painter = painterResource(R.drawable.ic_language),
						contentDescription = null,
						tint = actionColor,
						modifier = Modifier.size(15.dp),
					)
				}
			}
		}
		Spacer(Modifier.height(10.dp))
		SelectionContainer {
			Text(
				text = formattedText,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.90f),
				maxLines = if (expanded) Int.MAX_VALUE else 4,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.fillMaxWidth(),
				onTextLayout = { result ->
					if (!expanded && result.hasVisualOverflow) canExpand = true
				},
			)
		}
		if (canExpand || expanded) {
			Spacer(Modifier.height(6.dp))
			Row(
				modifier = Modifier.clickable { expanded = !expanded },
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(5.dp),
			) {
				Text(
					text = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.details_read_more),
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.SemiBold,
					color = actionColor,
				)
				Icon(
					painter = painterResource(R.drawable.ic_expand_more),
					contentDescription = null,
					tint = actionColor,
					modifier = Modifier
						.size(18.dp)
						.rotate(if (expanded) 180f else 0f),
				)
			}
		}
	}
}

private fun formatDescriptionMarkdown(text: String) = buildAnnotatedString {
	var cursor = 0
	while (cursor < text.length) {
		val markerStart = text.indexOf("**", cursor)
		if (markerStart == -1) {
			append(text.substring(cursor))
			break
		}
		val markerEnd = text.indexOf("**", markerStart + 2)
		if (markerEnd == -1) {
			append(text.substring(cursor))
			break
		}
		append(text.substring(cursor, markerStart))
		withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
			append(text.substring(markerStart + 2, markerEnd))
		}
		cursor = markerEnd + 2
	}
}

@Composable
private fun GenreGlassSection(content: @Composable ColumnScope.() -> Unit) {
	val palette = LocalMiyorareVisualPalette.current
	if (!palette.isModern) {
		SectionCard(content = content)
		return
	}

	val shape = RoundedCornerShape(MiyorareVisualTokens.RADIUS_SURFACE_DP.dp)
	val lightSurface = MaterialTheme.colorScheme.background.luminanceIsLight()
	val adaptiveCustom = palette.adaptiveCustomBackground
	val neutralTarget = if (lightSurface) Color.White else Color.Black
	val neutralBase = androidx.compose.ui.graphics.lerp(
		MaterialTheme.colorScheme.surfaceContainerHigh,
		neutralTarget,
		if (adaptiveCustom) 0.08f else 0.14f,
	)
	val glowAccent = if (adaptiveCustom) {
		if (lightSurface) {
			androidx.compose.ui.graphics.lerp(palette.primary, palette.accent, 0.32f)
		} else {
			androidx.compose.ui.graphics.lerp(palette.primary, palette.secondary, 0.52f)
		}
	} else {
		androidx.compose.ui.graphics.lerp(palette.primary, palette.secondary, 0.58f)
	}
	val glowElevation = when (palette.effectLevel) {
		VisualEffectLevel.LIGHT -> 0.dp
		VisualEffectLevel.BALANCED -> if (adaptiveCustom) 4.dp else 3.dp
		VisualEffectLevel.FULL -> if (adaptiveCustom) 9.dp else 7.dp
	}
	val leftMix = if (adaptiveCustom) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.06f
			VisualEffectLevel.BALANCED -> 0.09f
			VisualEffectLevel.FULL -> 0.13f
		}
	} else {
		0.018f
	}
	val rightMix = if (adaptiveCustom) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.04f
			VisualEffectLevel.BALANCED -> 0.07f
			VisualEffectLevel.FULL -> 0.11f
		}
	} else if (palette.effectLevel == VisualEffectLevel.FULL) {
		0.055f
	} else {
		0.025f
	}
	val baseBrush = Brush.horizontalGradient(
		0f to androidx.compose.ui.graphics.lerp(
			neutralBase,
			if (adaptiveCustom && lightSurface) palette.accent else palette.primary,
			leftMix,
		).copy(
			alpha = when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> if (adaptiveCustom && lightSurface) 0.76f else 0.78f
				VisualEffectLevel.BALANCED -> 0.84f
				VisualEffectLevel.FULL -> 0.90f
			},
		),
		0.64f to neutralBase.copy(
			alpha = when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> if (adaptiveCustom && lightSurface) 0.75f else 0.77f
				VisualEffectLevel.BALANCED -> 0.83f
				VisualEffectLevel.FULL -> 0.89f
			},
		),
		1f to androidx.compose.ui.graphics.lerp(
			neutralBase,
			palette.secondary,
			rightMix,
		).copy(
			alpha = when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> if (adaptiveCustom && lightSurface) 0.77f else 0.79f
				VisualEffectLevel.BALANCED -> 0.85f
				VisualEffectLevel.FULL -> 0.91f
			},
		),
	)
	val innerSheen = Brush.verticalGradient(
		0f to Color.White.copy(
			alpha = if (adaptiveCustom && lightSurface) {
				if (palette.effectLevel == VisualEffectLevel.FULL) 0.065f else 0.038f
			} else if (palette.effectLevel == VisualEffectLevel.FULL) {
				0.042f
			} else {
				0.022f
			},
		),
		0.34f to Color.Transparent,
		1f to glowAccent.copy(
			alpha = if (adaptiveCustom) {
				if (palette.effectLevel == VisualEffectLevel.FULL) 0.060f else 0.026f
			} else if (palette.effectLevel == VisualEffectLevel.FULL) {
				0.030f
			} else {
				0.012f
			},
		),
	)
	val edgeBrush = Brush.horizontalGradient(
		0f to glowAccent.copy(
			alpha = when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> if (adaptiveCustom) 0.28f else 0.20f
				VisualEffectLevel.BALANCED -> if (adaptiveCustom) 0.46f else 0.34f
				VisualEffectLevel.FULL -> if (adaptiveCustom) 0.70f else 0.56f
			},
		),
		0.46f to palette.borderHighlight.copy(
			alpha = when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> if (adaptiveCustom) 0.22f else 0.16f
				VisualEffectLevel.BALANCED -> if (adaptiveCustom) 0.34f else 0.24f
				VisualEffectLevel.FULL -> if (adaptiveCustom) 0.50f else 0.34f
			},
		),
		1f to (if (adaptiveCustom) palette.accent else palette.secondary).copy(
			alpha = when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> if (adaptiveCustom) 0.20f else 0.14f
				VisualEffectLevel.BALANCED -> if (adaptiveCustom) 0.34f else 0.25f
				VisualEffectLevel.FULL -> if (adaptiveCustom) 0.56f else 0.43f
			},
		),
	)

	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SCREEN_PADDING, vertical = 4.dp)
			.shadow(
				elevation = glowElevation,
				shape = shape,
				clip = false,
				ambientColor = glowAccent.copy(alpha = if (palette.effectLevel == VisualEffectLevel.FULL) 0.22f else 0.10f),
				spotColor = palette.secondary.copy(alpha = if (palette.effectLevel == VisualEffectLevel.FULL) 0.36f else 0.16f),
			)
			.clip(shape)
			.background(baseBrush)
			.background(innerSheen)
			.border(
				BorderStroke(if (palette.effectLevel == VisualEffectLevel.FULL) 1.dp else 0.75.dp, edgeBrush),
				shape,
			),
	) {
		Column(
			modifier = Modifier.padding(12.dp),
			content = content,
		)
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("DEPRECATION")
internal fun TagsSection(tags: List<ChipsView.ChipModel>, accent: Color, onTagClick: (MangaTag) -> Unit) {
	if (tags.isEmpty()) return
	var expanded by rememberSaveable { mutableStateOf(false) }
	val palette = LocalMiyorareVisualPalette.current
	val actionColor = accent
	val collapsedTags = tags.take(6)

	GenreGlassSection {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = stringResource(R.string.genres),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
			)
			Row(
				modifier = Modifier.clickable { expanded = !expanded },
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(
					text = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.details_show_all),
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.SemiBold,
					color = actionColor,
				)
				Icon(
					painter = painterResource(R.drawable.ic_chevron_right),
					contentDescription = null,
					tint = actionColor,
					modifier = Modifier
						.size(14.dp)
						.rotate(if (expanded) 90f else 0f),
				)
			}
		}
		Spacer(Modifier.height(8.dp))

		Column(
			modifier = Modifier
				.fillMaxWidth()
				.animateContentSize(),
			verticalArrangement = Arrangement.spacedBy(6.dp),
		) {
			if (expanded) {
				FlowRow(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(5.dp),
					verticalArrangement = Arrangement.spacedBy(7.dp),
				) {
					tags.forEach { tag ->
						GenreTagChip(
							tag = tag,
							actionColor = actionColor,
							onTagClick = onTagClick,
						)
					}
					TagToggleChip(
						text = stringResource(R.string.collapse),
						accent = accent,
						expanded = true,
					) {
						expanded = false
					}
				}
			} else {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(5.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					collapsedTags.take(4).forEach { tag ->
						GenreTagChip(
							tag = tag,
							actionColor = actionColor,
							onTagClick = onTagClick,
							modifier = Modifier.weight(1f, fill = false),
						)
					}
				}
				FlowRow(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(5.dp),
					verticalArrangement = Arrangement.spacedBy(7.dp),
				) {
					collapsedTags.drop(4).forEach { tag ->
						GenreTagChip(
							tag = tag,
							actionColor = actionColor,
							onTagClick = onTagClick,
						)
					}
					TagToggleChip(
						text = stringResource(R.string.expand),
						accent = accent,
						expanded = false,
					) {
						expanded = true
					}
				}
			}
		}
	}
}

@Composable
@Suppress("DEPRECATION")
private fun GenreTagChip(
	tag: ChipsView.ChipModel,
	actionColor: Color,
	onTagClick: (MangaTag) -> Unit,
	modifier: Modifier = Modifier,
) {
	val palette = LocalMiyorareVisualPalette.current
	val mangaTag = tag.data as? MangaTag
	val warningColor = if (tag.tint != 0) colorResource(tag.tint) else null
	val semanticColor = warningColor ?: actionColor
	val chipShape = RoundedCornerShape(if (palette.isModern) 10.dp else 15.dp)
	val chipContent: @Composable () -> Unit = {
		Text(
			text = tag.title?.toString().orEmpty(),
			style = if (palette.isModern) {
				MaterialTheme.typography.labelMedium.copy(
					fontSize = 13.sp,
					lineHeight = 16.sp,
					letterSpacing = 0.15.sp,
				)
			} else {
				MaterialTheme.typography.labelLarge
			},
			fontWeight = FontWeight.Medium,
			color = if (palette.isModern) {
				warningColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
			} else if (warningColor != null) {
				warningColor
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant
			},
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(
				horizontal = if (palette.isModern) 8.dp else 14.dp,
				vertical = if (palette.isModern) 5.dp else 8.dp,
			),
		)
	}

	if (!palette.isModern) {
		Surface(
			modifier = modifier,
			shape = chipShape,
			color = semanticColor.copy(alpha = 0.16f),
			border = BorderStroke(0.75.dp, semanticColor.copy(alpha = 0.38f)),
			onClick = { if (mangaTag != null) onTagClick(mangaTag) },
		) {
			chipContent()
		}
		return
	}

	val neutralTarget = if (MaterialTheme.colorScheme.background.luminanceIsLight()) Color.White else Color.Black
	val neutralFill = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceContainer, neutralTarget, 0.10f)
	val glowElevation = if (warningColor != null && palette.effectLevel == VisualEffectLevel.FULL) 4.dp else 0.dp
	val fillBrush = if (warningColor != null) {
		Brush.horizontalGradient(
			0f to androidx.compose.ui.graphics.lerp(neutralFill, warningColor, if (palette.effectLevel == VisualEffectLevel.FULL) 0.18f else 0.10f).copy(alpha = 0.92f),
			1f to androidx.compose.ui.graphics.lerp(neutralFill, warningColor, if (palette.effectLevel == VisualEffectLevel.FULL) 0.09f else 0.05f).copy(alpha = 0.88f),
		)
	} else {
		Brush.horizontalGradient(
			0f to neutralFill.copy(
				alpha = when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> 0.72f
					VisualEffectLevel.BALANCED -> 0.79f
					VisualEffectLevel.FULL -> 0.86f
				},
			),
			1f to MaterialTheme.colorScheme.surfaceContainerHigh.copy(
				alpha = when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> 0.68f
					VisualEffectLevel.BALANCED -> 0.76f
					VisualEffectLevel.FULL -> 0.83f
				},
			),
		)
	}
	val borderBrush = if (warningColor != null) {
		Brush.horizontalGradient(
			0f to warningColor.copy(
				alpha = when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> 0.50f
					VisualEffectLevel.BALANCED -> 0.66f
					VisualEffectLevel.FULL -> 0.92f
				},
			),
			1f to warningColor.copy(
				alpha = when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> 0.28f
					VisualEffectLevel.BALANCED -> 0.42f
					VisualEffectLevel.FULL -> 0.64f
				},
			),
		)
	} else {
		Brush.horizontalGradient(
			0f to MaterialTheme.colorScheme.onSurfaceVariant.copy(
				alpha = when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> 0.22f
					VisualEffectLevel.BALANCED -> 0.32f
					VisualEffectLevel.FULL -> 0.44f
				},
			),
			1f to MaterialTheme.colorScheme.outlineVariant.copy(
				alpha = when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> 0.18f
					VisualEffectLevel.BALANCED -> 0.26f
					VisualEffectLevel.FULL -> 0.34f
				},
			),
		)
	}

	Box(
		modifier = modifier
			.shadow(
				elevation = glowElevation,
				shape = chipShape,
				clip = false,
				ambientColor = (warningColor ?: Color.Transparent).copy(alpha = if (warningColor != null) 0.24f else 0f),
				spotColor = (warningColor ?: Color.Transparent).copy(alpha = if (warningColor != null) 0.36f else 0f),
			)
			.clip(chipShape)
			.background(fillBrush)
			.border(
				BorderStroke(if (palette.effectLevel == VisualEffectLevel.FULL) 1.dp else 0.75.dp, borderBrush),
				chipShape,
			)
			.clickable { if (mangaTag != null) onTagClick(mangaTag) },
	) {
		chipContent()
	}
}

@Composable
internal fun TagToggleChip(
	text: String,
	accent: Color,
	expanded: Boolean,
	modifier: Modifier = Modifier,
	onClick: () -> Unit,
) {
	val palette = LocalMiyorareVisualPalette.current
	val chipColor = if (palette.isModern) {
		if (palette.effectLevel == VisualEffectLevel.LIGHT) palette.primary else accent
	} else {
		accent
	}
	val chipShape = RoundedCornerShape(if (palette.isModern) 10.dp else 15.dp)
	val chipContent: @Composable () -> Unit = {
		Row(
			modifier = Modifier.padding(
				start = if (palette.isModern) 9.dp else 14.dp,
				end = if (palette.isModern) 7.dp else 10.dp,
				top = if (palette.isModern) 5.dp else 8.dp,
				bottom = if (palette.isModern) 5.dp else 8.dp,
			),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text(
				text = text,
				style = if (palette.isModern) {
					MaterialTheme.typography.labelMedium.copy(
						fontSize = 13.sp,
						lineHeight = 16.sp,
						letterSpacing = 0.15.sp,
					)
				} else {
					MaterialTheme.typography.labelLarge
				},
				fontWeight = FontWeight.Medium,
				color = chipColor,
			)
			Icon(
				painter = painterResource(R.drawable.ic_expand_more),
				contentDescription = null,
				tint = chipColor,
				modifier = Modifier
					.size(16.dp)
					.rotate(if (expanded) 180f else 0f),
			)
		}
	}

	if (!palette.isModern) {
		Surface(
			modifier = modifier,
			shape = chipShape,
			color = Color.Transparent,
			border = BorderStroke(1.dp, chipColor.copy(alpha = 0.6f)),
			tonalElevation = 0.dp,
			shadowElevation = 0.dp,
			onClick = onClick,
		) {
			chipContent()
		}
		return
	}

	val neutralTarget = if (MaterialTheme.colorScheme.background.luminanceIsLight()) Color.White else Color.Black
	val neutralFill = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceContainer, neutralTarget, 0.08f)
	val fillBrush = Brush.horizontalGradient(
		0f to androidx.compose.ui.graphics.lerp(
			neutralFill,
			chipColor,
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.08f
				VisualEffectLevel.BALANCED -> 0.14f
				VisualEffectLevel.FULL -> 0.24f
			},
		).copy(alpha = 0.90f),
		1f to androidx.compose.ui.graphics.lerp(
			neutralFill,
			palette.secondary,
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.04f
				VisualEffectLevel.BALANCED -> 0.09f
				VisualEffectLevel.FULL -> 0.16f
			},
		).copy(alpha = 0.86f),
	)
	val borderBrush = Brush.horizontalGradient(
		0f to chipColor.copy(
			alpha = when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.38f
				VisualEffectLevel.BALANCED -> 0.58f
				VisualEffectLevel.FULL -> 0.84f
			},
		),
		1f to palette.secondary.copy(
			alpha = when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.24f
				VisualEffectLevel.BALANCED -> 0.40f
				VisualEffectLevel.FULL -> 0.62f
			},
		),
	)

	Box(
		modifier = modifier
			.shadow(
				elevation = when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> 0.dp
					VisualEffectLevel.BALANCED -> 2.dp
					VisualEffectLevel.FULL -> 5.dp
				},
				shape = chipShape,
				clip = false,
				ambientColor = chipColor.copy(alpha = if (palette.effectLevel == VisualEffectLevel.FULL) 0.24f else 0.10f),
				spotColor = palette.secondary.copy(alpha = if (palette.effectLevel == VisualEffectLevel.FULL) 0.38f else 0.16f),
			)
			.clip(chipShape)
			.background(fillBrush)
			.border(
				BorderStroke(if (palette.effectLevel == VisualEffectLevel.FULL) 1.dp else 0.75.dp, borderBrush),
				chipShape,
			)
			.clickable(onClick = onClick),
	) {
		chipContent()
	}
}

@Composable
internal fun ScrobblingSection(
	items: List<ScrobblingInfo>,
	imageLoader: ImageLoader,
	accent: Color,
	onMore: () -> Unit,
	onCardClick: (Int) -> Unit,
) {
	SectionHeader(title = stringResource(R.string.tracking), action = stringResource(R.string.manage), accent = accent, onAction = onMore)
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SCREEN_PADDING),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		items.forEachIndexed { index, info ->
			Surface(
				shape = RoundedCornerShape(20.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				onClick = { onCardClick(index) },
				modifier = Modifier.fillMaxWidth(),
			) {
				Row(
					modifier = Modifier
						.padding(16.dp)
						.height(IntrinsicSize.Min),
					verticalAlignment = Alignment.Top,
				) {
					AsyncImage(
						model = info.coverUrl,
						imageLoader = imageLoader,
						contentDescription = null,
						contentScale = ContentScale.Crop,
						modifier = Modifier
							.size(80.dp, 116.dp)
							.clip(RoundedCornerShape(14.dp)),
					)
					Spacer(Modifier.width(16.dp))
					Column(
						modifier = Modifier
							.weight(1f)
							.fillMaxHeight(),
						verticalArrangement = Arrangement.SpaceBetween,
					) {
						Column {
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.SpaceBetween,
								verticalAlignment = Alignment.CenterVertically,
							) {
								Row(
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(6.dp),
								) {
									Icon(
										painter = painterResource(
											if (info.scrobbler == ScrobblerService.SHIKIMORI) {
												R.drawable.ic_shikimori_raw
											} else {
												info.scrobbler.iconResId
											},
										),
										contentDescription = null,
										tint = Color.Unspecified,
										modifier = Modifier.size(16.dp),
									)
									Text(
										text = stringResource(info.scrobbler.titleResId),
										style = MaterialTheme.typography.labelMedium,
										color = MaterialTheme.colorScheme.onSurfaceVariant,
									)
								}
								info.status?.let { status ->
									Text(
										text = stringResource(status.labelResId),
										style = MaterialTheme.typography.labelMedium,
										color = accent,
									)
								}
							}
							Spacer(Modifier.height(10.dp))
							Text(
								text = info.title,
								style = MaterialTheme.typography.bodyLarge,
								color = MaterialTheme.colorScheme.onSurface,
								maxLines = 2,
								overflow = TextOverflow.Ellipsis,
							)
						}
						if (info.chapter > 0 || info.rating > 0f) {
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.SpaceBetween,
								verticalAlignment = Alignment.CenterVertically,
							) {
								// Whatever the tracker itself reports — the app's own chapter list
								// may well disagree with it, and this card speaks for the tracker.
								Text(
									text = when {
										info.chapter <= 0 -> ""
										info.totalChapters > 0 -> stringResource(
											R.string.chapters_read_d_of_d,
											info.chapter,
											info.totalChapters,
										)
										else -> stringResource(R.string.chapters_read_d, info.chapter)
									},
									style = MaterialTheme.typography.labelLarge,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
									modifier = Modifier.weight(1f, fill = false),
								)
								if (info.rating > 0f) {
									Row(verticalAlignment = Alignment.CenterVertically) {
										Icon(
											painter = painterResource(R.drawable.ic_star_small),
											contentDescription = null,
											tint = accent,
											modifier = Modifier.size(20.dp),
										)
										Spacer(Modifier.width(4.dp))
										Text(
											text = "${"%.1f".format(info.rating * 5)} / 5",
											style = MaterialTheme.typography.titleSmall,
											color = MaterialTheme.colorScheme.onSurface,
										)
									}
								}
							}
						}
					}
				}
			}
		}
	}
	Spacer(Modifier.height(8.dp))
}

@Composable
internal fun LocalSizeRow(size: Long, manga: Manga, onClick: (Manga) -> Unit) {
	val ctx = LocalContext.current
	SectionCard(onClick = { onClick(manga) }) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Icon(
				painter = painterResource(R.drawable.ic_storage_checked),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(20.dp),
			)
			Spacer(Modifier.width(12.dp))
			Text(
				text = FileSize.BYTES.format(ctx, size),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurface,
			)
		}
	}
}
