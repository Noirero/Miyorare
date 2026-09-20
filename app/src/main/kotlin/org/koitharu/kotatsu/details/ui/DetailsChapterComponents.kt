package org.koitharu.kotatsu.details.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
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
				nsfwLabel = nsfwLabel,
				forceRefresh = details?.isLoaded == true,
				actions = actions,
			)
			Spacer(Modifier.height(if (palette.isModern) 16.dp else 20.dp))
			HeroTexts(centered = true, manga = manga, accent = accent, actions = actions, showAuthors = false)
			CreatorMetaText(centered = true, manga = manga, details = details, accent = accent, actions = actions)
			if (!manga.isLocal || manga.state != null) {
				Spacer(Modifier.height(if (palette.isModern) 12.dp else 14.dp))
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.height(82.dp),
					horizontalArrangement = Arrangement.spacedBy(10.dp),
				) {
					if (!manga.isLocal) {
						HeroSourceCard(
							manga = manga,
							sourceTitle = sourceTitle,
							imageLoader = imageLoader,
							onSourceClick = { actions.onSourceClick(manga) },
							modifier = Modifier
								.weight(if (manga.state != null) 0.40f else 1f)
								.fillMaxHeight(),
						)
					}
					manga.state?.let { state ->
						HeroStatusCard(
							status = stringResource(state.titleResId),
							showActiveRelease = state.titleResId == R.string.state_ongoing,
							accent = accent,
							modifier = Modifier
								.weight(if (!manga.isLocal) 0.60f else 1f)
								.fillMaxHeight(),
						)
					}
				}
			}
		}
	} else {
		BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
			val coverWidth = (maxWidth * 0.29f).coerceIn(116.dp, 132.dp)
			val coverHeight = coverWidth * 1.34f
			val heroGap = (maxWidth * 0.034f).coerceIn(12.dp, 16.dp)
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = SCREEN_PADDING),
				horizontalArrangement = Arrangement.spacedBy(heroGap),
				verticalAlignment = Alignment.Top,
			) {
			CoverCard(
				manga = manga,
				coverUrl = coverUrl,
				imageLoader = imageLoader,
				modifier = Modifier
					.width(coverWidth)
					.height(coverHeight),
				corner = if (palette.isModern) MiyorareVisualTokens.RADIUS_CARD_DP.dp else 20.dp,
				nsfwLabel = nsfwLabel,
				forceRefresh = details?.isLoaded == true,
				actions = actions,
			)
			Column(modifier = Modifier.weight(1f)) {
				HeroTexts(centered = false, manga = manga, accent = accent, actions = actions, showAuthors = false)
				CreatorMetaText(centered = false, manga = manga, details = details, accent = accent, actions = actions)
				if (!manga.isLocal || manga.state != null) {
					Spacer(Modifier.height(if (palette.isModern) 8.dp else 14.dp))
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.height(78.dp),
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						if (!manga.isLocal) {
							HeroSourceCard(
								manga = manga,
								sourceTitle = sourceTitle,
								imageLoader = imageLoader,
								onSourceClick = { actions.onSourceClick(manga) },
								modifier = Modifier
									.weight(if (manga.state != null) 0.40f else 1f)
									.fillMaxHeight(),
							)
						}
						manga.state?.let { state ->
							HeroStatusCard(
								status = stringResource(state.titleResId),
								showActiveRelease = state.titleResId == R.string.state_ongoing,
								accent = accent,
								modifier = Modifier
									.weight(if (!manga.isLocal) 0.60f else 1f)
									.fillMaxHeight(),
							)
						}
					}
				}
			}
			}
		}
	}
}

@Composable
private fun HeroSourceCard(
	manga: Manga,
	sourceTitle: String?,
	imageLoader: ImageLoader,
	onSourceClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val palette = LocalMiyorareVisualPalette.current
	val srcText = sourceTitle?.takeUnless { it.isBlank() } ?: manga.source.getTitle(context)
	val faviconRequest = remember(manga.source) {
		ImageRequest.Builder(context)
			.data(manga.source.faviconUri())
			.mangaSourceExtra(manga.source)
			.crossfade(true)
			.build()
	}
	val shape = RoundedCornerShape(if (palette.isModern) MiyorareVisualTokens.RADIUS_CONTROL_DP.dp else 18.dp)
	val sourceTintMix = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.008f
			VisualEffectLevel.BALANCED -> 0.032f
			VisualEffectLevel.FULL -> 0.060f
		}
	} else {
		0f
	}
	val sourceSurfaceAlpha = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.66f
			VisualEffectLevel.BALANCED -> 0.72f
			VisualEffectLevel.FULL -> 0.79f
		}
	} else {
		1f
	}
	val sourceBorderAlpha = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.20f
			VisualEffectLevel.BALANCED -> 0.32f
			VisualEffectLevel.FULL -> 0.50f
		}
	} else {
		0.6f
	}
	val sourceGlow = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.dp
			VisualEffectLevel.BALANCED -> 2.dp
			VisualEffectLevel.FULL -> 5.dp
		}
	} else {
		0.dp
	}
	Surface(
		onClick = onSourceClick,
		shape = shape,
		color = if (palette.isModern) {
			lerp(MaterialTheme.colorScheme.surfaceContainer, palette.secondary, sourceTintMix).copy(alpha = sourceSurfaceAlpha)
		} else {
			MaterialTheme.colorScheme.surfaceContainerHigh
		},
		border = BorderStroke(
			if (palette.isModern && palette.effectLevel == VisualEffectLevel.FULL) 1.dp else 0.75.dp,
			if (palette.isModern) {
				lerp(
					palette.primary,
					palette.secondary,
					if (palette.effectLevel == VisualEffectLevel.FULL) 0.30f else 0.22f,
				).copy(alpha = sourceBorderAlpha)
			} else {
				MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
			},
		),
		tonalElevation = 0.dp,
		shadowElevation = 0.dp,
		modifier = if (palette.isModern) {
			modifier.shadow(
				elevation = sourceGlow,
				shape = shape,
				clip = false,
				ambientColor = palette.primary.copy(alpha = if (palette.effectLevel == VisualEffectLevel.FULL) 0.24f else 0.12f),
				spotColor = palette.secondary.copy(alpha = if (palette.effectLevel == VisualEffectLevel.FULL) 0.40f else 0.18f),
			)
		} else {
			modifier
		},
	) {
		Column(
			modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp),
			verticalArrangement = Arrangement.Center,
		) {
			Text(
				text = stringResource(R.string.details_source),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(5.dp))
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(5.dp),
			) {
				AsyncImage(
					model = faviconRequest,
					imageLoader = imageLoader,
					contentDescription = null,
					error = painterResource(R.drawable.ic_manga_source),
					fallback = painterResource(R.drawable.ic_manga_source),
					modifier = Modifier.size(17.dp),
				)
				Text(
					text = srcText,
					style = MaterialTheme.typography.labelMedium,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				Icon(
					painter = painterResource(R.drawable.ic_chevron_right),
					contentDescription = null,
					tint = if (palette.isModern) palette.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(12.dp),
				)
			}
		}
	}
}

@Composable
private fun HeroStatusCard(
	status: String,
	showActiveRelease: Boolean,
	accent: Color,
	modifier: Modifier = Modifier,
) {
	val palette = LocalMiyorareVisualPalette.current
	val statusColor = if (palette.isModern) {
		if (palette.effectLevel == VisualEffectLevel.LIGHT) palette.primary else accent
	} else {
		accent
	}
	val shape = RoundedCornerShape(if (palette.isModern) MiyorareVisualTokens.RADIUS_CONTROL_DP.dp else 18.dp)
	val statusSurfaceAlpha = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.56f
			VisualEffectLevel.BALANCED -> 0.69f
			VisualEffectLevel.FULL -> 0.83f
		}
	} else {
		1f
	}
	val statusTintMix = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.015f
			VisualEffectLevel.BALANCED -> 0.060f
			VisualEffectLevel.FULL -> 0.14f
		}
	} else {
		0f
	}
	val statusBorderAlpha = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.30f
			VisualEffectLevel.BALANCED -> 0.50f
			VisualEffectLevel.FULL -> 0.78f
		}
	} else {
		0.34f
	}
	val statusGlow = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.dp
			VisualEffectLevel.BALANCED -> 4.dp
			VisualEffectLevel.FULL -> 10.dp
		}
	} else {
		0.dp
	}
	Surface(
		shape = shape,
		color = if (palette.isModern) {
			lerp(palette.selectedSurface, palette.secondary, statusTintMix).copy(alpha = statusSurfaceAlpha)
		} else {
			MaterialTheme.colorScheme.surfaceContainerHigh
		},
		border = BorderStroke(
			if (palette.isModern && palette.effectLevel == VisualEffectLevel.FULL) 1.1.dp else 0.9.dp,
			if (palette.isModern) lerp(statusColor, palette.secondary, 0.18f).copy(alpha = statusBorderAlpha) else accent.copy(alpha = 0.34f),
		),
		tonalElevation = 0.dp,
		shadowElevation = 0.dp,
		modifier = if (palette.isModern) {
			modifier.shadow(
				elevation = statusGlow,
				shape = shape,
				clip = false,
				ambientColor = statusColor.copy(alpha = if (palette.effectLevel == VisualEffectLevel.FULL) 0.45f else 0.18f),
				spotColor = palette.secondary.copy(alpha = if (palette.effectLevel == VisualEffectLevel.FULL) 0.72f else 0.28f),
			)
		} else {
			modifier
		},
	) {
		Row(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Surface(
				shape = RoundedCornerShape(50),
				color = statusColor.copy(
					alpha = if (palette.isModern && palette.effectLevel == VisualEffectLevel.FULL) 0.20f else 0.11f,
				),
				border = BorderStroke(
					if (palette.isModern && palette.effectLevel == VisualEffectLevel.FULL) 1.15.dp else 1.dp,
					statusColor.copy(
						alpha = if (palette.isModern && palette.effectLevel == VisualEffectLevel.FULL) 0.94f else 0.66f,
					),
				),
				modifier = Modifier.size(40.dp),
			) {
				Box(contentAlignment = Alignment.Center) {
					Icon(
						painter = painterResource(if (showActiveRelease) R.drawable.ic_infinity else R.drawable.ic_timelapse),
						contentDescription = null,
						tint = statusColor,
						modifier = Modifier.size(22.dp),
					)
				}
			}
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.status),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Text(
					text = status,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.fillMaxWidth(),
				)
				if (showActiveRelease) {
					Spacer(Modifier.height(2.dp))
					Text(
						text = stringResource(R.string.details_release_active),
						style = MaterialTheme.typography.labelSmall,
						color = statusColor,
						maxLines = 1,
					)
				}
			}
		}
	}
}

@Composable
private fun CreatorMetaText(
	centered: Boolean,
	manga: Manga,
	details: MangaDetails?,
	accent: Color,
	actions: DetailsExpressiveActions,
) {
	val authors = manga.authors
		.map { it.trim() }
		.filter { it.isNotEmpty() }
		.distinct()
	val artist = details?.artist?.trim()?.takeIf { it.isNotEmpty() }
	val artistMatchesAuthor = artist != null && authors.any { it.equals(artist, ignoreCase = true) }
	val creatorText = when {
		authors.isNotEmpty() && artistMatchesAuthor ->
			stringResource(R.string.details_creator_story_art, authors.joinToString(", "))
		authors.isNotEmpty() && artist != null ->
			stringResource(R.string.details_creator_split, authors.joinToString(", "), artist)
		authors.isNotEmpty() -> authors.joinToString(", ")
		artist != null -> stringResource(R.string.details_creator_art, artist)
		else -> return
	}
	val clickTarget = authors.firstOrNull() ?: artist ?: return

	Spacer(Modifier.height(5.dp))
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable { actions.onAuthorClick(clickTarget) },
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
	) {
		if (centered) {
			Text(
				text = creatorText,
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.Medium,
				color = accent,
				textAlign = androidx.compose.ui.text.style.TextAlign.Center,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		} else {
			Text(
				text = creatorText,
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.Medium,
				color = accent,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
		}
		Spacer(Modifier.width(6.dp))
		Icon(
			painter = painterResource(R.drawable.ic_chevron_right),
			contentDescription = null,
			tint = accent,
			modifier = Modifier.size(16.dp),
		)
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PrimaryDetailsActions(
	favouriteLabel: String,
	isFavourite: Boolean,
	historyInfo: HistoryInfo,
	isLoading: Boolean,
	accent: Color,
	onFavouriteClick: () -> Unit,
	onFavouriteLongClick: () -> Unit,
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
			VisualEffectLevel.BALANCED -> 0.97f
			VisualEffectLevel.FULL -> 1f
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
			shape = controlShape,
			color = if (palette.isModern) {
				if (isFavourite) {
					palette.selectedSurface.copy(
						alpha = if (palette.effectLevel == VisualEffectLevel.FULL) 0.76f else 0.64f,
					)
				} else {
					lerp(MaterialTheme.colorScheme.surfaceContainer, Color.Black, 0.16f).copy(
						alpha = if (palette.effectLevel == VisualEffectLevel.FULL) 0.72f else 0.68f,
					)
				}
			} else if (isFavourite) {
				accent.copy(alpha = 0.20f)
			} else {
				MaterialTheme.colorScheme.surfaceContainerHigh
			},
			border = if (palette.isModern) {
				BorderStroke(
					1.dp,
					accent.copy(
						alpha = if (palette.effectLevel == VisualEffectLevel.FULL) {
							if (isFavourite) 0.72f else 0.52f
						} else {
							if (isFavourite) 0.60f else 0.42f
						},
					),
				)
			} else {
				null
			},
			tonalElevation = 0.dp,
			shadowElevation = if (palette.isModern && palette.effectLevel == VisualEffectLevel.FULL && isFavourite) 1.dp else 0.dp,
			modifier = Modifier
				.weight(0.5f)
				.height(52.dp)
				.combinedClickable(
					onClick = onFavouriteClick,
					onLongClick = onFavouriteLongClick,
				),
		) {
			Row(
				modifier = Modifier.padding(horizontal = 14.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.Center,
			) {
				Icon(
					painter = painterResource(if (isFavourite) R.drawable.ic_heart else R.drawable.ic_heart_outline),
					contentDescription = null,
					tint = accent,
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

		val readShadow = if (palette.isModern && readEnabled) {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 2.dp
				VisualEffectLevel.BALANCED -> 7.dp
				VisualEffectLevel.FULL -> 18.dp
			}
		} else {
			0.dp
		}
		val readBrush = if (palette.isModern) {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> Brush.horizontalGradient(
					0f to palette.primary.copy(alpha = readGradientAlpha),
					1f to lerp(palette.primary, palette.secondary, 0.18f).copy(alpha = readGradientAlpha),
				)
				VisualEffectLevel.BALANCED -> Brush.horizontalGradient(
					0f to palette.primary.copy(alpha = readGradientAlpha),
					0.62f to lerp(palette.primary, palette.secondary, 0.28f).copy(alpha = readGradientAlpha),
					1f to lerp(palette.primary, palette.secondary, 0.48f).copy(alpha = readGradientAlpha),
				)
				VisualEffectLevel.FULL -> Brush.horizontalGradient(
					0f to lerp(palette.primary, accent, 0.72f),
					0.48f to lerp(accent, Color.White, 0.08f),
					1f to lerp(palette.secondary, Color.White, 0.10f),
				)
			}
		} else {
			Brush.linearGradient(listOf(readContainer, readContainer))
		}
		Box(
			modifier = Modifier
				.weight(0.5f)
				.height(52.dp)
				.shadow(
					elevation = readShadow,
					shape = controlShape,
					clip = false,
					ambientColor = if (palette.isModern) {
						accent.copy(alpha = if (palette.effectLevel == VisualEffectLevel.FULL) 0.66f else 0.36f)
					} else {
						Color.Transparent
					},
					spotColor = if (palette.isModern) {
						lerp(accent, palette.secondary, 0.35f).copy(
							alpha = if (palette.effectLevel == VisualEffectLevel.FULL) 0.92f else 0.58f,
						)
					} else {
						Color.Transparent
					},
				)
				.clip(controlShape)
				.background(readBrush)
				.border(
					if (palette.isModern) 1.dp else 0.dp,
					if (palette.isModern) {
						lerp(palette.primary, palette.secondary, 0.18f).copy(
							alpha = when (palette.effectLevel) {
								VisualEffectLevel.LIGHT -> 0.50f
								VisualEffectLevel.BALANCED -> 0.72f
								VisualEffectLevel.FULL -> 0.88f
							},
						)
					} else {
						Color.Transparent
					},
					controlShape,
				)
				.clickable(enabled = readEnabled, onClick = onReadClick),
			contentAlignment = Alignment.Center,
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
internal fun InlineChapterHeader(
	visibleCount: Int,
	totalCount: Int,
	isFilterActive: Boolean,
	accent: Color,
	onFilter: () -> Unit,
	onSort: () -> Unit,
	onDisplay: () -> Unit,
	onManage: () -> Unit,
	onSetDefault: () -> Unit,
	onReset: () -> Unit,
) {
	val palette = LocalMiyorareVisualPalette.current
	var moreExpanded by remember { mutableStateOf(false) }
	val safeTotal = totalCount.coerceAtLeast(visibleCount)
	val title = if (isFilterActive && safeTotal > 0 && visibleCount != safeTotal) {
		stringResource(R.string.chapter_options_visible_count, visibleCount, safeTotal)
	} else {
		pluralStringResource(R.plurals.chapters, safeTotal, safeTotal)
	}
	val toolbarAccent = accent
	Spacer(Modifier.height(if (palette.isModern) 6.dp else 8.dp))
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SCREEN_PADDING),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
			)
			Text(
				text = stringResource(R.string.manage),
				color = toolbarAccent,
				fontWeight = FontWeight.SemiBold,
				modifier = Modifier
					.clickable(onClick = onManage)
					.padding(horizontal = 4.dp, vertical = 6.dp),
			)
		}
		if (palette.isModern) {
			Spacer(Modifier.height(2.dp))
			Surface(
				shape = RoundedCornerShape(MiyorareVisualTokens.RADIUS_CONTROL_DP.dp),
				color = when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.64f)
					VisualEffectLevel.BALANCED -> lerp(MaterialTheme.colorScheme.surfaceContainer, palette.primary, 0.025f).copy(alpha = 0.74f)
					VisualEffectLevel.FULL -> lerp(MaterialTheme.colorScheme.surfaceContainerHigh, palette.secondary, 0.030f).copy(alpha = 0.82f)
				},
				border = BorderStroke(
					if (palette.effectLevel == VisualEffectLevel.FULL) 1.dp else 0.75.dp,
					palette.borderHighlight.copy(
						alpha = when (palette.effectLevel) {
							VisualEffectLevel.LIGHT -> 0.14f
							VisualEffectLevel.BALANCED -> 0.28f
							VisualEffectLevel.FULL -> 0.36f
						},
					),
				),
				tonalElevation = 0.dp,
				shadowElevation = 0.dp,
				modifier = Modifier.fillMaxWidth(),
			) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.height(48.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					ChapterToolbarItem(
						iconRes = R.drawable.ic_filter_funnel,
						label = stringResource(R.string.chapter_options_filter),
						active = isFilterActive,
						color = toolbarAccent,
						onClick = onFilter,
						modifier = Modifier.weight(1f),
					)
					ChapterToolbarDivider()
					ChapterToolbarItem(
						iconRes = R.drawable.ic_sort,
						label = stringResource(R.string.chapter_options_sort),
						active = false,
						color = toolbarAccent,
						onClick = onSort,
						modifier = Modifier.weight(1f),
					)
					ChapterToolbarDivider()
					ChapterToolbarItem(
						iconRes = R.drawable.ic_grid,
						label = stringResource(R.string.chapter_options_display),
						active = false,
						color = toolbarAccent,
						onClick = onDisplay,
						modifier = Modifier.weight(1f),
					)
					ChapterToolbarDivider()
					Box(
						modifier = Modifier
							.fillMaxHeight()
							.width(48.dp),
						contentAlignment = Alignment.Center,
					) {
						IconButton(onClick = { moreExpanded = true }) {
							Icon(
								painter = painterResource(R.drawable.ic_more_vert),
								contentDescription = stringResource(R.string.more),
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						DropdownMenu(
							expanded = moreExpanded,
							onDismissRequest = { moreExpanded = false },
						) {
							DropdownMenuItem(
								text = { Text(stringResource(R.string.manage)) },
								onClick = {
									moreExpanded = false
									onManage()
								},
							)
							DropdownMenuItem(
								text = { Text(stringResource(R.string.chapter_options_set_default)) },
								onClick = {
									moreExpanded = false
									onSetDefault()
								},
							)
							DropdownMenuItem(
								text = { Text(stringResource(R.string.reset)) },
								onClick = {
									moreExpanded = false
									onReset()
								},
							)
						}
					}
				}
			}
		} else {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.End,
			) {
				TextButton(onClick = onFilter) {
					Icon(
						painter = painterResource(R.drawable.ic_filter_funnel),
						contentDescription = null,
						tint = if (isFilterActive) accent else MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(18.dp),
					)
					Spacer(Modifier.width(6.dp))
					Text(
						text = stringResource(R.string.chapter_options_filter_sort_display),
						color = if (isFilterActive) accent else MaterialTheme.colorScheme.onSurfaceVariant,
						fontWeight = FontWeight.SemiBold,
					)
				}
			}
		}
	}
}

@Composable
private fun ChapterToolbarItem(
	@androidx.annotation.DrawableRes iconRes: Int,
	label: String,
	active: Boolean,
	color: Color,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val contentColor = if (active) color else MaterialTheme.colorScheme.onSurfaceVariant
	Row(
		modifier = modifier
			.fillMaxHeight()
			.clickable(onClick = onClick)
			.padding(horizontal = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.Center,
	) {
		Icon(
			painter = painterResource(iconRes),
			contentDescription = null,
			tint = contentColor,
			modifier = Modifier.size(18.dp),
		)
		Spacer(Modifier.width(6.dp))
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium,
			fontWeight = FontWeight.Medium,
			color = contentColor,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun ChapterToolbarDivider() {
	Box(
		modifier = Modifier
			.width(1.dp)
			.height(26.dp)
			.background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)),
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
	var showDownloadMenu by remember(item.chapter.id, item.chapter.url) { mutableStateOf(false) }
	val container = if (palette.isModern) {
		when (visualEffectLevel) {
			VisualEffectLevel.LIGHT -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.64f)
			VisualEffectLevel.BALANCED -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.74f)
			VisualEffectLevel.FULL -> lerp(MaterialTheme.colorScheme.surfaceContainerHigh, palette.secondary, 0.020f).copy(alpha = 0.80f)
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
					VisualEffectLevel.LIGHT -> 0.54f
					VisualEffectLevel.BALANCED -> 0.64f
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
		BorderStroke(
			if (item.isCurrent) 1.dp else if (visualEffectLevel == VisualEffectLevel.FULL) 0.75.dp else 0.5.dp,
			palette.borderHighlight.copy(
				alpha = if (item.isCurrent) {
					when (visualEffectLevel) {
						VisualEffectLevel.LIGHT -> 0.40f
						VisualEffectLevel.BALANCED -> 0.58f
						VisualEffectLevel.FULL -> 0.88f
					}
				} else {
					when (visualEffectLevel) {
						VisualEffectLevel.LIGHT -> 0.14f
						VisualEffectLevel.BALANCED -> 0.24f
						VisualEffectLevel.FULL -> 0.32f
					}
				},
			),
		)
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
			0.dp
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
			.padding(horizontal = SCREEN_PADDING, vertical = if (palette.isModern) 2.dp else 4.dp)
			.clickable(onClick = onClick),
	) {
		Row(
			modifier = Modifier.padding(
				start = if (palette.isModern) 12.dp else 14.dp,
				end = if (palette.isModern) 4.dp else 6.dp,
				top = if (palette.isModern) 8.dp else 11.dp,
				bottom = if (palette.isModern) 8.dp else 11.dp,
			),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (item.isCurrent) {
				Box(
					modifier = Modifier
						.width(if (palette.isModern) 3.dp else 4.dp)
						.height(if (palette.isModern) 32.dp else 36.dp)
						.background(
							if (palette.isModern && visualEffectLevel == VisualEffectLevel.FULL) {
								lerp(palette.primary, palette.secondary, 0.22f)
							} else if (palette.isModern) {
								palette.primary
							} else {
								accent
							},
							RoundedCornerShape(50),
						),
				)
				Spacer(Modifier.width(if (palette.isModern) 8.dp else 10.dp))
			}

			Column(modifier = Modifier.weight(1f)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = item.getTitle(context.resources),
						style = MaterialTheme.typography.bodyMedium,
						fontWeight = when {
							item.isCurrent -> FontWeight.Bold
							palette.isModern && item.isUnread -> FontWeight.SemiBold
							else -> FontWeight.Medium
						},
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
							modifier = Modifier.size(if (palette.isModern) 15.dp else 16.dp),
						)
					}
				}
				item.description?.takeIf { it.isNotBlank() }?.let { description ->
					Spacer(Modifier.height(if (palette.isModern) 2.dp else 3.dp))
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
						.size(if (palette.isModern) 18.dp else 19.dp),
				)
			}

			when {
				item.isDownloaded -> IconButton(onClick = onManageClick) {
					Icon(
						painter = painterResource(R.drawable.ic_eye_check),
						contentDescription = null,
						tint = if (palette.isModern) palette.primary else accent,
						modifier = Modifier.size(if (palette.isModern) 22.dp else 24.dp),
					)
				}
				item.isDownloading -> Box {
					Box(
						modifier = Modifier
							.size(48.dp)
							.clickable { showDownloadMenu = true },
						contentAlignment = Alignment.Center,
					) {
						CircularProgressIndicator(
							modifier = Modifier.size(if (palette.isModern) 18.dp else 20.dp),
							strokeWidth = 2.dp,
							color = if (palette.isModern) palette.primary else accent,
						)
					}
					DropdownMenu(
						expanded = showDownloadMenu,
						onDismissRequest = { showDownloadMenu = false },
					) {
						DropdownMenuItem(
							text = { Text(stringResource(R.string.chapter_download_start_now)) },
							onClick = {
								showDownloadMenu = false
								onDownloadClick()
							},
						)
						DropdownMenuItem(
							text = { Text(stringResource(android.R.string.cancel)) },
							onClick = { showDownloadMenu = false },
						)
					}
				}
				else -> IconButton(onClick = onDownloadClick) {
					Icon(
						painter = painterResource(R.drawable.ic_save),
						contentDescription = stringResource(R.string.download),
						tint = if (palette.isModern) {
							MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.86f)
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						modifier = Modifier.size(if (palette.isModern) 22.dp else 24.dp),
					)
				}
			}
		}
	}
}