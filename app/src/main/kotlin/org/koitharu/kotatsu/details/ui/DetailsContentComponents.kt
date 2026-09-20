package org.koitharu.kotatsu.details.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.details.ui.scrobbling.labelResId
import java.util.Locale

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
	val actionColor = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> palette.primary
			VisualEffectLevel.BALANCED -> androidx.compose.ui.graphics.lerp(palette.primary, palette.secondary, 0.06f)
			VisualEffectLevel.FULL -> androidx.compose.ui.graphics.lerp(palette.primary, palette.secondary, 0.24f)
		}
	} else {
		accent
	}

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
				color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("DEPRECATION")
internal fun TagsSection(tags: List<ChipsView.ChipModel>, accent: Color, onTagClick: (MangaTag) -> Unit) {
	if (tags.isEmpty()) return
	var expanded by rememberSaveable { mutableStateOf(false) }
	val palette = LocalMiyorareVisualPalette.current
	val actionColor = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> palette.primary
			VisualEffectLevel.BALANCED -> androidx.compose.ui.graphics.lerp(palette.primary, palette.secondary, 0.06f)
			VisualEffectLevel.FULL -> androidx.compose.ui.graphics.lerp(palette.primary, palette.secondary, 0.24f)
		}
	} else {
		accent
	}
	val collapsedTags = tags.take(6)

	SectionCard {
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
	Surface(
		modifier = modifier,
		shape = RoundedCornerShape(if (palette.isModern) 10.dp else 15.dp),
		color = if (palette.isModern) {
			if (warningColor != null) {
				warningColor.copy(
					alpha = when (palette.effectLevel) {
						VisualEffectLevel.LIGHT -> 0.10f
						VisualEffectLevel.BALANCED -> 0.13f
						VisualEffectLevel.FULL -> 0.22f
					},
				)
			} else {
				androidx.compose.ui.graphics.lerp(
					MaterialTheme.colorScheme.surfaceContainer,
					Color.Black,
					if (palette.effectLevel == VisualEffectLevel.FULL) 0.18f else 0.10f,
				).copy(
					alpha = when (palette.effectLevel) {
						VisualEffectLevel.LIGHT -> 0.62f
						VisualEffectLevel.BALANCED -> 0.68f
						VisualEffectLevel.FULL -> 0.74f
					},
				)
			}
		} else {
			semanticColor.copy(alpha = 0.16f)
		},
		border = BorderStroke(
			if (palette.isModern && palette.effectLevel == VisualEffectLevel.FULL) 1.dp else 0.75.dp,
			if (warningColor != null) {
				warningColor.copy(
					alpha = when (palette.effectLevel) {
						VisualEffectLevel.LIGHT -> 0.48f
						VisualEffectLevel.BALANCED -> 0.58f
						VisualEffectLevel.FULL -> 0.88f
					},
				)
			} else if (palette.isModern) {
				MaterialTheme.colorScheme.onSurfaceVariant.copy(
					alpha = when (palette.effectLevel) {
						VisualEffectLevel.LIGHT -> 0.20f
						VisualEffectLevel.BALANCED -> 0.26f
						VisualEffectLevel.FULL -> 0.34f
					},
				)
			} else {
				semanticColor.copy(alpha = 0.38f)
			},
		),
		onClick = { if (mangaTag != null) onTagClick(mangaTag) },
	) {
		Text(
			text = tag.title?.toString().orEmpty(),
			style = if (palette.isModern) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
			fontWeight = FontWeight.Medium,
			color = if (warningColor != null) warningColor else MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(
				horizontal = if (palette.isModern) 9.dp else 14.dp,
				vertical = if (palette.isModern) 6.dp else 8.dp,
			),
		)
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
		if (palette.effectLevel == VisualEffectLevel.FULL) {
			androidx.compose.ui.graphics.lerp(palette.primary, palette.secondary, 0.22f)
		} else {
			palette.primary
		}
	} else {
		accent
	}
	Surface(
		modifier = modifier,
		shape = RoundedCornerShape(if (palette.isModern) 10.dp else 15.dp),
		color = if (palette.isModern) {
			androidx.compose.ui.graphics.lerp(
				MaterialTheme.colorScheme.surfaceContainer,
				chipColor,
				if (palette.effectLevel == VisualEffectLevel.FULL) 0.10f else 0.06f,
			).copy(
				alpha = when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> 0.62f
					VisualEffectLevel.BALANCED -> 0.68f
					VisualEffectLevel.FULL -> 0.74f
				},
			)
		} else {
			Color.Transparent
		},
		border = BorderStroke(
			if (palette.isModern && palette.effectLevel == VisualEffectLevel.FULL) 1.dp else if (palette.isModern) 0.75.dp else 1.dp,
			chipColor.copy(
				alpha = if (palette.isModern) {
					when (palette.effectLevel) {
						VisualEffectLevel.LIGHT -> 0.34f
						VisualEffectLevel.BALANCED -> 0.46f
						VisualEffectLevel.FULL -> 0.70f
					}
				} else {
					0.6f
				},
			),
		),
		tonalElevation = 0.dp,
		shadowElevation = 0.dp,
		onClick = onClick,
	) {
		Row(
			modifier = Modifier.padding(
				start = if (palette.isModern) 10.dp else 14.dp,
				end = if (palette.isModern) 8.dp else 10.dp,
				top = if (palette.isModern) 6.dp else 8.dp,
				bottom = if (palette.isModern) 6.dp else 8.dp,
			),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text(
				text = text,
				style = if (palette.isModern) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.SemiBold,
				color = chipColor,
			)
			Icon(
				painter = painterResource(R.drawable.ic_expand_more),
				contentDescription = null,
				tint = chipColor,
				modifier = Modifier
					.size(18.dp)
					.rotate(if (expanded) 180f else 0f),
			)
		}
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelatedSection(
	items: List<MangaListModel>,
	imageLoader: ImageLoader,
	accent: Color,
	onMore: () -> Unit,
	onItemClick: (MangaListModel) -> Unit,
) {
	SectionHeader(title = stringResource(R.string.related_manga), action = stringResource(R.string.show_all), accent = accent, onAction = onMore)
	val carouselState = rememberCarouselState { items.size }
	HorizontalMultiBrowseCarousel(
		state = carouselState,
		preferredItemWidth = 150.dp,
		itemSpacing = 10.dp,
		flingBehavior = CarouselDefaults.multiBrowseFlingBehavior(state = carouselState),
		contentPadding = PaddingValues(horizontal = SCREEN_PADDING),
		modifier = Modifier
			.fillMaxWidth()
			.height(232.dp),
	) { i ->
		val item = items.getOrNull(i) ?: return@HorizontalMultiBrowseCarousel
		val context = LocalContext.current
		Column(
			modifier = Modifier.clickable { onItemClick(item) },
		) {
			AsyncImage(
				model = remember(item.coverUrl, item.source) {
					ImageRequest.Builder(context)
						.data(item.coverUrl)
						.crossfade(true)
						.mangaSourceExtra(item.source)
						.build()
				},
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.height(200.dp)
					.fillMaxWidth()
					.maskClip(RoundedCornerShape(20.dp)),
			)
			Spacer(Modifier.height(8.dp))
			Text(
				text = item.title,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(start = 8.dp, end = 4.dp),
			)
		}
	}
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
