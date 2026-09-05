package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag

@Composable
internal fun ModernDetailsHero(
	centered: Boolean,
	manga: Manga,
	details: MangaDetails?,
	sourceTitle: String?,
	tags: List<ChipsView.ChipModel>,
	accent: Color,
	imageLoader: ImageLoader,
	coverUrl: String?,
	actions: DetailsExpressiveActions,
) {
	val palette = LocalMiyorareVisualPalette.current
	val nsfwLabel = when (manga.contentRating) {
		ContentRating.SUGGESTIVE -> "16+"
		ContentRating.ADULT -> "18+"
		else -> null
	}
	if (centered) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = SCREEN_PADDING),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			CoverCard(
				manga = manga,
				coverUrl = coverUrl,
				imageLoader = imageLoader,
				modifier = Modifier
					.width(158.dp)
					.height(236.dp),
				corner = if (palette.isModern) MiyorareVisualTokens.RADIUS_SURFACE_DP.dp else 24.dp,
				nsfwLabel = null,
				forceRefresh = details?.isLoaded == true,
				actions = actions,
			)
			Spacer(Modifier.height(20.dp))
			HeroTexts(centered = true, manga = manga, accent = accent, actions = actions)
			if (tags.isNotEmpty()) {
				Spacer(Modifier.height(if (palette.isModern) MiyorareVisualTokens.SPACING_M_DP.dp else 12.dp))
				HeroTagPills(centered = true, tags = tags, accent = accent, onTagClick = actions.onTagClick)
			}
			Spacer(Modifier.height(if (palette.isModern) MiyorareVisualTokens.SPACING_M_DP.dp else 12.dp))
			StatPills(
				centered = true,
				showContentRating = true,
				manga = manga,
				sourceTitle = sourceTitle,
				accent = accent,
				imageLoader = imageLoader,
				onSourceClick = { actions.onSourceClick(manga) },
			)
		}
	} else {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = SCREEN_PADDING),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
			verticalAlignment = Alignment.Top,
		) {
			CoverCard(
				manga = manga,
				coverUrl = coverUrl,
				imageLoader = imageLoader,
				modifier = Modifier
					.width(120.dp)
					.height(178.dp),
				corner = if (palette.isModern) MiyorareVisualTokens.RADIUS_CARD_DP.dp else 20.dp,
				nsfwLabel = nsfwLabel,
				forceRefresh = details?.isLoaded == true,
				actions = actions,
			)
			Column(modifier = Modifier.weight(1f)) {
				HeroTexts(centered = false, manga = manga, accent = accent, actions = actions)
				if (tags.isNotEmpty()) {
					Spacer(Modifier.height(if (palette.isModern) MiyorareVisualTokens.SPACING_M_DP.dp else 10.dp))
					HeroTagPills(centered = false, tags = tags, accent = accent, onTagClick = actions.onTagClick)
				}
				Spacer(Modifier.height(if (palette.isModern) MiyorareVisualTokens.SPACING_M_DP.dp else 10.dp))
				StatPills(
					centered = false,
					showContentRating = false,
					manga = manga,
					sourceTitle = sourceTitle,
					accent = accent,
					imageLoader = imageLoader,
					onSourceClick = { actions.onSourceClick(manga) },
				)
			}
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroTagPills(
	centered: Boolean,
	tags: List<ChipsView.ChipModel>,
	accent: Color,
	onTagClick: (MangaTag) -> Unit,
) {
	val palette = LocalMiyorareVisualPalette.current
	FlowRow(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = if (centered) {
			Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
		} else {
			Arrangement.spacedBy(8.dp)
		},
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		tags.forEach { tag ->
			val mangaTag = tag.data as? MangaTag
			val warningColor = if (tag.tint != 0) colorResource(tag.tint) else null
			val tagColor = warningColor ?: if (palette.isModern) palette.primary else accent
			val shape = if (palette.isModern) {
				RoundedCornerShape(MiyorareVisualTokens.RADIUS_SMALL_DP.dp)
			} else {
				RoundedCornerShape(13.dp)
			}
			Surface(
				shape = shape,
				color = if (palette.isModern) {
					MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.84f)
				} else {
					tagColor.copy(alpha = 0.16f)
				},
				border = if (palette.isModern) {
					BorderStroke(
						1.dp,
						if (warningColor != null) {
							warningColor.copy(alpha = palette.borderHighlight.alpha.coerceAtLeast(0.14f) * 0.48f)
						} else {
							palette.borderHighlight.copy(alpha = palette.borderHighlight.alpha * 0.44f)
						},
					)
				} else {
					null
				},
				onClick = { if (mangaTag != null) onTagClick(mangaTag) },
			) {
				Text(
					text = tag.title?.toString().orEmpty(),
					style = MaterialTheme.typography.labelMedium,
					fontWeight = FontWeight.SemiBold,
					color = if (palette.isModern) {
						if (warningColor != null) {
							warningColor.copy(alpha = 0.82f)
						} else {
							lerp(MaterialTheme.colorScheme.onSurfaceVariant, palette.primary, 0.24f).copy(alpha = 0.88f)
						}
					} else {
						tagColor
					},
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
				)
			}
		}
	}
}

@Composable
internal fun PrimaryDetailsActions(
	favouriteLabel: String,
	isFavourite: Boolean,
	historyInfo: HistoryInfo,
	isLoading: Boolean,
	accent: Color,
	onFavouriteClick: () -> Unit,
	onReadClick: () -> Unit,
) {
	val palette = LocalMiyorareVisualPalette.current
	val isChaptersLoading = isLoading && (historyInfo.totalChapters <= 0 || historyInfo.isChapterMissing)
	val readEnabled = !isChaptersLoading && historyInfo.isValid
	val readLabel = when {
		isChaptersLoading -> stringResource(R.string.loading_)
		historyInfo.canContinue -> stringResource(R.string._continue)
		else -> stringResource(R.string.read)
	}
	val readContainer = if (readEnabled) accent else accent.copy(alpha = 0.38f)
	val readContent = if (palette.isModern) {
		palette.onButton
	} else if (accent.luminanceIsLight()) {
		Color.Black
	} else {
		Color.White
	}
	val controlShape = RoundedCornerShape(MiyorareVisualTokens.RADIUS_CONTROL_DP.dp)
	val readGradientAlpha = if (!readEnabled) {
		0.52f
	} else {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.88f
			VisualEffectLevel.BALANCED -> 0.94f
			VisualEffectLevel.FULL -> 0.98f
		}
	}

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SCREEN_PADDING),
		horizontalArrangement = Arrangement.spacedBy(10.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Surface(
			onClick = onFavouriteClick,
			shape = controlShape,
			color = if (palette.isModern) {
				if (isFavourite) palette.selectedSurface.copy(alpha = 0.78f) else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f)
			} else if (isFavourite) {
				accent.copy(alpha = 0.20f)
			} else {
				MaterialTheme.colorScheme.surfaceContainerHigh
			},
			border = if (palette.isModern) {
				BorderStroke(
					1.dp,
					palette.borderHighlight.copy(
						alpha = palette.borderHighlight.alpha * if (isFavourite) 0.82f else 0.34f,
					),
				)
			} else {
				null
			},
			tonalElevation = 0.dp,
			shadowElevation = if (palette.isModern && palette.effectLevel == VisualEffectLevel.FULL && isFavourite) 1.dp else 0.dp,
			modifier = Modifier
				.weight(0.42f)
				.height(56.dp),
		) {
			Row(
				modifier = Modifier.padding(horizontal = 14.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.Center,
			) {
				Icon(
					painter = painterResource(if (isFavourite) R.drawable.ic_heart else R.drawable.ic_heart_outline),
					contentDescription = null,
					tint = if (palette.isModern) palette.primary else accent,
					modifier = Modifier.size(20.dp),
				)
				Spacer(Modifier.width(8.dp))
				Text(
					text = favouriteLabel,
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}

		val readModifier = Modifier
			.weight(0.58f)
			.height(56.dp)
			.let { modifier ->
				if (palette.isModern) {
					modifier
						.background(
							brush = Brush.horizontalGradient(
								0f to palette.primary.copy(alpha = readGradientAlpha),
								0.68f to lerp(palette.primary, palette.secondary, 0.28f).copy(alpha = readGradientAlpha),
								0.93f to lerp(palette.primary, palette.secondary, 0.48f).copy(alpha = readGradientAlpha),
								1f to palette.secondary.copy(alpha = readGradientAlpha * 0.72f),
							),
							shape = controlShape,
						)
						.border(
							1.dp,
							palette.borderHighlight.copy(alpha = palette.borderHighlight.alpha * 0.72f),
							controlShape,
						)
				} else {
					modifier
				}
			}
		Surface(
			onClick = onReadClick,
			enabled = readEnabled,
			shape = controlShape,
			color = if (palette.isModern) Color.Transparent else readContainer,
			shadowElevation = if (palette.isModern) {
				if (!readEnabled) 0.dp else when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> 0.dp
					VisualEffectLevel.BALANCED -> 2.dp
					VisualEffectLevel.FULL -> 4.dp
				}
			} else if (readEnabled) {
				3.dp
			} else {
				0.dp
			},
			modifier = readModifier,
		) {
			Row(
				modifier = Modifier.padding(horizontal = 16.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.Center,
			) {
				Icon(
					painter = painterResource(R.drawable.ic_play),
					contentDescription = null,
					tint = readContent.copy(alpha = if (readEnabled) 1f else 0.72f),
					modifier = Modifier.size(22.dp),
				)
				Spacer(Modifier.width(8.dp))
				Text(
					text = readLabel,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Bold,
					color = readContent.copy(alpha = if (readEnabled) 1f else 0.72f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
internal fun InlineChapterHeader(count: Int, accent: Color, onManage: () -> Unit) {
	SectionHeader(
		title = pluralStringResource(R.plurals.chapters, count, count),
		action = stringResource(R.string.manage),
		accent = accent,
		onAction = onManage,
	)
}

@Composable
internal fun InlineChapterCard(
	item: ChapterListItem,
	visualEffectLevel: VisualEffectLevel,
	accent: Color,
	onClick: () -> Unit,
	onDownloadClick: () -> Unit,
	onManageClick: () -> Unit,
) {
	val context = LocalContext.current
	val palette = LocalMiyorareVisualPalette.current
	val container = if (palette.isModern) {
		when (visualEffectLevel) {
			VisualEffectLevel.LIGHT -> MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
			VisualEffectLevel.BALANCED -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f)
			VisualEffectLevel.FULL -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.98f)
		}
	} else {
		when (visualEffectLevel) {
			VisualEffectLevel.LIGHT -> MaterialTheme.colorScheme.surfaceContainerLow
			VisualEffectLevel.BALANCED -> MaterialTheme.colorScheme.surfaceContainer
			VisualEffectLevel.FULL -> MaterialTheme.colorScheme.surfaceContainerHigh
		}
	}
	val rowColor = if (item.isCurrent) {
		if (palette.isModern) {
			palette.selectedSurface.copy(
				alpha = when (visualEffectLevel) {
					VisualEffectLevel.LIGHT -> 0.58f
					VisualEffectLevel.BALANCED -> 0.70f
					VisualEffectLevel.FULL -> 0.80f
				},
			)
		} else {
			accent.copy(alpha = if (visualEffectLevel == VisualEffectLevel.LIGHT) 0.10f else 0.16f)
		}
	} else {
		container
	}
	val mainColor = if (item.isUnread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
	val secondaryColor = if (item.isUnread) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline
	val border = if (palette.isModern) {
		if (item.isCurrent && visualEffectLevel != VisualEffectLevel.LIGHT) {
			BorderStroke(
				1.dp,
				palette.borderHighlight.copy(alpha = palette.borderHighlight.alpha * 0.82f),
			)
		} else {
			null
		}
	} else if (visualEffectLevel == VisualEffectLevel.FULL) {
		BorderStroke(1.dp, accent.copy(alpha = 0.14f))
	} else {
		null
	}

	Surface(
		shape = RoundedCornerShape(MiyorareVisualTokens.RADIUS_CARD_DP.dp),
		color = rowColor,
		border = border,
		tonalElevation = if (palette.isModern) {
			if (item.isCurrent && visualEffectLevel == VisualEffectLevel.FULL) 1.dp else 0.dp
		} else {
			when (visualEffectLevel) {
				VisualEffectLevel.LIGHT -> 0.dp
				VisualEffectLevel.BALANCED -> 1.dp
				VisualEffectLevel.FULL -> 2.dp
			}
		},
		shadowElevation = if (palette.isModern) 0.dp else if (visualEffectLevel == VisualEffectLevel.FULL) 1.dp else 0.dp,
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SCREEN_PADDING, vertical = if (palette.isModern) 3.dp else 4.dp)
			.clickable(onClick = onClick),
	) {
		Row(
			modifier = Modifier.padding(
				start = 14.dp,
				end = 6.dp,
				top = if (palette.isModern) 10.dp else 11.dp,
				bottom = if (palette.isModern) 10.dp else 11.dp,
			),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (item.isCurrent) {
				Box(
					modifier = Modifier
						.width(4.dp)
						.height(36.dp)
						.background(if (palette.isModern) palette.primary else accent, RoundedCornerShape(50)),
				)
				Spacer(Modifier.width(10.dp))
			}

			Column(modifier = Modifier.weight(1f)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = item.getTitle(context.resources),
						style = MaterialTheme.typography.bodyLarge,
						fontWeight = if (item.isCurrent) FontWeight.Bold else FontWeight.Medium,
						color = mainColor,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f, fill = false),
					)
					if (item.isNew) {
						Spacer(Modifier.width(6.dp))
						Icon(
							painter = painterResource(R.drawable.ic_new),
							contentDescription = null,
							tint = if (palette.isModern) palette.primary else accent,
							modifier = Modifier.size(16.dp),
						)
					}
				}
				item.description?.takeIf { it.isNotBlank() }?.let { description ->
					Spacer(Modifier.height(3.dp))
					Text(
						text = description,
						style = MaterialTheme.typography.bodySmall,
						color = secondaryColor,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}

			if (item.isBookmarked) {
				Icon(
					painter = painterResource(R.drawable.ic_bookmark),
					contentDescription = null,
					tint = if (palette.isModern) palette.primary else accent,
					modifier = Modifier
						.padding(horizontal = 4.dp)
						.size(19.dp),
				)
			}

			when {
				item.isDownloaded -> IconButton(onClick = onManageClick) {
					Icon(
						painter = painterResource(R.drawable.ic_eye_check),
						contentDescription = null,
						tint = if (palette.isModern) palette.primary else accent,
					)
				}
				item.isDownloading -> Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
					CircularProgressIndicator(
						modifier = Modifier.size(20.dp),
						strokeWidth = 2.dp,
						color = if (palette.isModern) palette.primary else accent,
					)
				}
				else -> IconButton(onClick = onDownloadClick) {
					Icon(
						painter = painterResource(R.drawable.ic_save),
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
	}
}
