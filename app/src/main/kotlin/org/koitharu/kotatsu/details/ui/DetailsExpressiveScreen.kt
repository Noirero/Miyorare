@file:Suppress("DEPRECATION")

package org.koitharu.kotatsu.details.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DetailsUiMode
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.util.StatusBarScrim
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.details.ui.pager.ChapterOptionsTab
import org.koitharu.kotatsu.details.ui.related.RelatedKeywordCarousel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref

private const val KEY_GENRE_RECOMMENDATIONS_VISIBLE = "genre_recommendations_visible"

class DetailsExpressiveActions(
	val onCoverClick: (Manga) -> Unit,
	val onTitleClick: (String) -> Unit,
	val onSourceClick: (Manga) -> Unit,
	val onLocalClick: (Manga) -> Unit,
	val onFavoriteClick: (Manga) -> Unit,
	val onFavoriteLongClick: (Manga) -> Unit,
	val onAuthorClick: (String) -> Unit,
	val onTagClick: (MangaTag) -> Unit,
	val onScrobblingMore: () -> Unit,
	val onScrobblingCardClick: (Int) -> Unit,
	val onRelatedMore: (Manga) -> Unit,
	val onRelatedClick: (MangaListModel) -> Unit,
	val onRelatedMangaClick: (Manga) -> Unit,
	val onRelatedKeywordMore: (Manga, String) -> Unit,
	val onRelatedDiscoveryRequested: () -> Unit,
	val onGenreRecommendationsVisibilityChanged: (Boolean) -> Unit,
	val onReadClick: () -> Unit,
	val onIncognitoClick: () -> Unit,
	val onForgetHistoryClick: () -> Unit,
	val onChaptersClick: () -> Unit,
	val onChapterOptionsClick: (ChapterOptionsTab) -> Unit,
	val onChapterOptionsSetDefaultClick: () -> Unit,
	val onChapterOptionsResetClick: () -> Unit,
	val onChapterClick: (ChapterListItem) -> Unit,
	val onChapterDownloadClick: (ChapterListItem) -> Unit,
)

@Composable
fun DetailsExpressiveScreen(
	details: MangaDetails?,
	note: String?,
	tags: List<ChipsView.ChipModel>,
	historyInfo: HistoryInfo,
	chapters: List<ChapterListItem>,
	isChapterFilterActive: Boolean,
	isLoading: Boolean,
	favouriteCount: Int,
	favouriteLabel: String?,
	scrobblings: List<ScrobblingInfo>,
	genreRecommendations: List<MangaListModel>,
	expandedRelated: DetailsRelatedUiState,
	relatedDiscoveryEnabled: Boolean,
	localSize: Long,
	sourceTitle: String?,
	imageLoader: ImageLoader,
	coverUrl: String?,
	backdropUrl: String?,
	isBackdropEnabled: Boolean,
	backdropBlurAmount: Int,
	visualEffectLevel: VisualEffectLevel,
	style: DetailsUiMode,
	topInset: Dp,
	bottomContentPadding: Dp,
	onScroll: (Int) -> Unit,
	actions: DetailsExpressiveActions,
) {
	val manga = details?.toManga()
	var showRelatedSuggestions by rememberBooleanPref(
		AppSettings.KEY_RELATED_MANGA,
		relatedDiscoveryEnabled,
	)
	var showGenreRecommendations by rememberBooleanPref(
		KEY_GENRE_RECOMMENDATIONS_VISIBLE,
		true,
	)
	val visibleExpandedRelated = expandedRelated.groups

	LaunchedEffect(showGenreRecommendations) {
		actions.onGenreRecommendationsVisibilityChanged(showGenreRecommendations)
	}

	val baseScheme = MaterialTheme.colorScheme
	val typography = MaterialTheme.typography

	MaterialTheme(colorScheme = baseScheme, typography = typography) {
		val scheme = MaterialTheme.colorScheme
		val palette = LocalMiyorareVisualPalette.current
		val lightMode = scheme.background.luminance() >= 0.5f
		val accentColor = palette.exclusiveTheme?.details?.interactiveText
			?: if (palette.isModern && palette.adaptiveCustomBackground) {
			// Custom wallpaper colors should be unmistakable on Details without sacrificing contrast.
			if (lightMode) {
				when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> palette.primary
					VisualEffectLevel.BALANCED -> lerp(palette.primary, palette.accent, 0.24f)
					VisualEffectLevel.FULL -> lerp(palette.primary, palette.accent, 0.40f)
				}
			} else {
				when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> palette.primary
					VisualEffectLevel.BALANCED -> lerp(palette.primary, palette.secondary, 0.34f)
					VisualEffectLevel.FULL -> lerp(palette.secondary, palette.accent, 0.24f)
				}
			}
		} else if (palette.isModern) {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> scheme.primary
				VisualEffectLevel.BALANCED -> lerp(scheme.primary, palette.secondary, 0.28f)
				VisualEffectLevel.FULL -> palette.secondary
			}
		} else {
			scheme.primary
		}
		val screenSurface = if (palette.isModern) scheme.background else scheme.surface
		val listState = rememberLazyListState()
		val centered = style != DetailsUiMode.COMPACT
		val topContentSpacing = if (palette.isModern) {
			if (centered) 66.dp else 58.dp
		} else {
			if (centered) 84.dp else 72.dp
		}
		val statusBarBrush = remember(screenSurface, palette.isModern) {
			if (palette.isModern) {
				Brush.verticalGradient(
					0f to screenSurface.copy(alpha = 0.68f),
					0.72f to screenSurface.copy(alpha = 0.28f),
					1f to Color.Transparent,
				)
			} else {
				val stops = StatusBarScrim.alphas
				Brush.verticalGradient(
					*stops.mapIndexed { i, a ->
						i / stops.lastIndex.toFloat() to screenSurface.copy(alpha = a / 255f)
					}.toTypedArray(),
				)
			}
		}

		LaunchedEffect(listState) {
			snapshotFlow {
				if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) 0 else 1
			}.collect(onScroll)
		}

		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(if (palette.isModern) Color.Transparent else screenSurface),
		) {
			if (isBackdropEnabled && backdropUrl != null) {
				ExpressiveBackdrop(
					url = backdropUrl,
					manga = manga,
					imageLoader = imageLoader,
					surface = screenSurface,
					blurAmount = backdropBlurAmount,
				)
			}

			LazyColumn(
				state = listState,
				modifier = Modifier.fillMaxSize(),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				item(contentType = "top-spacer") {
					Spacer(Modifier.height(topInset + topContentSpacing))
				}

				if (manga == null) {
					item(contentType = "loading-hero") { LoadingHero() }
				} else {
					val favLabel = favouriteLabel ?: ""
					val isFavourite = favouriteCount > 0

					item(contentType = "hero") {
						ModernDetailsHero(
							centered = centered,
							manga = manga,
							details = details,
							sourceTitle = sourceTitle,
							accent = accentColor,
							imageLoader = imageLoader,
							coverUrl = coverUrl,
							actions = actions,
						)
					}

					item(contentType = "primary-actions") {
						Spacer(Modifier.height(if (palette.isModern) 10.dp else 20.dp))
						PrimaryDetailsActions(
							favouriteLabel = favLabel.ifBlank { stringResource(R.string.add_to_favourites) },
							isFavourite = isFavourite,
							historyInfo = historyInfo,
							isLoading = isLoading,
							accent = accentColor,
							onFavouriteClick = { actions.onFavoriteClick(manga) },
							onFavouriteLongClick = { actions.onFavoriteLongClick(manga) },
							onReadClick = actions.onReadClick,
						)
					}


					item(contentType = "progress") {
						Spacer(Modifier.height(if (palette.isModern) 4.dp else 8.dp))
						ProgressCard(
							historyInfo = historyInfo,
							isLoading = isLoading,
							accent = accentColor,
							onClick = actions.onChaptersClick,
						)
					}

					note?.trim()?.takeIf { it.isNotEmpty() }?.let { noteText ->
						item(contentType = "note") { NoteCard(noteText) }
					}

					item(contentType = "description") {
						DescriptionCard(
							description = details.displayDescription,
							manga = manga,
							details = details,
							accent = accentColor,
						)
					}

					if (tags.isNotEmpty()) {
						item(contentType = "genres") {
							TagsSection(
								tags = tags,
								accent = accentColor,
								onTagClick = actions.onTagClick,
							)
						}
					}

					if (details.isLoaded || historyInfo.totalChapters > 0 || chapters.isNotEmpty()) {
						item(contentType = "chapters-header") {
							InlineChapterHeader(
								visibleCount = chapters.size,
								totalCount = historyInfo.totalChapters.coerceAtLeast(chapters.size),
								isFilterActive = isChapterFilterActive,
								accent = accentColor,
								onFilter = { actions.onChapterOptionsClick(ChapterOptionsTab.FILTER) },
								onSort = { actions.onChapterOptionsClick(ChapterOptionsTab.SORT) },
								onDisplay = { actions.onChapterOptionsClick(ChapterOptionsTab.DISPLAY) },
								onManage = actions.onChaptersClick,
								onSetDefault = actions.onChapterOptionsSetDefaultClick,
								onReset = actions.onChapterOptionsResetClick,
							)
						}
						items(
							items = chapters,
							key = { it.detailsLazyListKey() },
							contentType = { "chapter" },
						) { chapter ->
							InlineChapterCard(
								item = chapter,
								visualEffectLevel = visualEffectLevel,
								accent = accentColor,
								onClick = { actions.onChapterClick(chapter) },
								onDownloadClick = { actions.onChapterDownloadClick(chapter) },
								onManageClick = actions.onChaptersClick,
							)
						}
					}

					if (scrobblings.isNotEmpty()) {
						item(contentType = "scrobbling") {
							ScrobblingSection(
								items = scrobblings,
								imageLoader = imageLoader,
								accent = accentColor,
								onMore = actions.onScrobblingMore,
								onCardClick = actions.onScrobblingCardClick,
							)
						}
					}

					item(key = "discovery-controls", contentType = "discovery-controls") {
						DiscoveryControlsCard(
							relatedVisible = showRelatedSuggestions,
							genreVisible = showGenreRecommendations,
							accent = accentColor,
							onRelatedToggle = { showRelatedSuggestions = !showRelatedSuggestions },
							onGenreToggle = { showGenreRecommendations = !showGenreRecommendations },
						)
					}

					if (showRelatedSuggestions) {
						item(key = "related-discovery-anchor", contentType = "related") {
							LaunchedEffect(manga.id, details.isLoaded) {
								if (details.isLoaded) {
									actions.onRelatedDiscoveryRequested()
								}
							}
							when {
								expandedRelated.isLoading && visibleExpandedRelated.isEmpty() -> RelatedDiscoveryLoading()
								expandedRelated.error != null && visibleExpandedRelated.isEmpty() ->
									RelatedDiscoveryRetry(actions.onRelatedDiscoveryRequested)
								else -> Spacer(Modifier.height(1.dp))
							}
						}
						items(
							items = visibleExpandedRelated,
							key = { "related-keyword:${it.keyword}" },
							contentType = { "related-keyword" },
						) { group ->
							RelatedKeywordCarousel(
								group = group,
								imageLoader = imageLoader,
								onMangaClick = actions.onRelatedMangaClick,
								onShowAll = { keyword -> actions.onRelatedKeywordMore(manga, keyword) },
							)
						}
						when {
							expandedRelated.isLoading && visibleExpandedRelated.isNotEmpty() -> {
								item(key = "related-discovery-loading", contentType = "related-loading") {
									RelatedDiscoveryLoading()
								}
							}
							expandedRelated.error != null && visibleExpandedRelated.isNotEmpty() -> {
								item(key = "related-discovery-retry", contentType = "related-retry") {
									RelatedDiscoveryRetry(actions.onRelatedDiscoveryRequested)
								}
							}
						}
					}

					if (showGenreRecommendations && genreRecommendations.isNotEmpty()) {
						item(key = "genre-recommendations", contentType = "genre-recommendations") {
							GenreRecommendationSection(
								items = genreRecommendations,
								imageLoader = imageLoader,
								onItemClick = actions.onRelatedClick,
							)
						}
					}

					if (localSize > 0L) {
						item(contentType = "local-size") {
							LocalSizeRow(size = localSize, manga = manga, onClick = actions.onLocalClick)
						}
					}
				}

				item(contentType = "bottom-spacer") {
					Spacer(Modifier.height(bottomContentPadding + 28.dp))
				}
			}

			if (topInset > 0.dp) {
				Box(
					modifier = Modifier
						.align(Alignment.TopCenter)
						.fillMaxWidth()
						.height(topInset * StatusBarScrim.HEIGHT_FACTOR)
						.background(statusBarBrush),
				)
			}
		}
	}
}

@Composable
private fun RelatedDiscoveryLoading() {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 20.dp),
		contentAlignment = Alignment.Center,
	) {
		CircularProgressIndicator()
	}
}

@Composable
private fun RelatedDiscoveryRetry(onRetry: () -> Unit) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 8.dp),
		contentAlignment = Alignment.Center,
	) {
		TextButton(onClick = onRetry) {
			Text(stringResource(R.string.retry))
		}
	}
}

private fun ChapterListItem.detailsLazyListKey(): String = with(chapter) {
	// A chapter ID is normally stable, but third-party sources can occasionally reuse one.
	// Include the rest of the chapter identity so distinct chapters survive an ID collision.
	val chapterTitle = title.orEmpty()
	val chapterScanlator = scanlator.orEmpty()
	val chapterBranch = branch.orEmpty()
	buildString {
		append(id)
		append(':').append(url.length).append(':').append(url)
		append(':').append(chapterTitle.length).append(':').append(chapterTitle)
		append(':').append(number)
		append(':').append(volume)
		append(':').append(chapterScanlator.length).append(':').append(chapterScanlator)
		append(':').append(uploadDate)
		append(':').append(chapterBranch.length).append(':').append(chapterBranch)
	}
}

@Composable
private fun NoteCard(note: String) {
	SectionCard {
		Text(
			text = note,
			style = MaterialTheme.typography.bodyLarge,
			color = MaterialTheme.colorScheme.onSurface,
		)
	}
}

@Composable
private fun ExpressiveBackdrop(
	url: String,
	manga: Manga?,
	imageLoader: ImageLoader,
	surface: Color,
	blurAmount: Int,
) {
	val context = LocalContext.current
	val palette = LocalMiyorareVisualPalette.current
	val request = remember(url, manga?.source) {
		ImageRequest.Builder(context)
			.data(url)
			.crossfade(true)
			.apply { if (manga != null) mangaSourceExtra(manga.source) }
			.build()
	}
	val adaptiveCustom = palette.isModern && palette.adaptiveCustomBackground
	val lightSurface = surface.luminance() >= 0.5f
	val topAlpha = if (adaptiveCustom) {
		if (lightSurface) {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.48f
				VisualEffectLevel.BALANCED -> 0.51f
				VisualEffectLevel.FULL -> 0.54f
			}
		} else {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.58f
				VisualEffectLevel.BALANCED -> 0.60f
				VisualEffectLevel.FULL -> 0.62f
			}
		}
	} else if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.64f
			VisualEffectLevel.BALANCED -> 0.60f
			VisualEffectLevel.FULL -> 0.60f
		}
	} else {
		0.50f
	}
	val middleAlpha = if (adaptiveCustom) {
		if (lightSurface) {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.68f
				VisualEffectLevel.BALANCED -> 0.71f
				VisualEffectLevel.FULL -> 0.74f
			}
		} else {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.76f
				VisualEffectLevel.BALANCED -> 0.78f
				VisualEffectLevel.FULL -> 0.80f
			}
		}
	} else if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.82f
			VisualEffectLevel.BALANCED -> 0.76f
			VisualEffectLevel.FULL -> 0.78f
		}
	} else {
		0.78f
	}
	val lowerAlpha = if (adaptiveCustom) {
		if (lightSurface) {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.88f
				VisualEffectLevel.BALANCED -> 0.90f
				VisualEffectLevel.FULL -> 0.92f
			}
		} else {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.92f
				VisualEffectLevel.BALANCED -> 0.93f
				VisualEffectLevel.FULL -> 0.94f
			}
		}
	} else if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.95f
			VisualEffectLevel.BALANCED -> 0.93f
			VisualEffectLevel.FULL -> 0.92f
		}
	} else {
		0.94f
	}
	val neutralSurface = if (adaptiveCustom) {
		if (lightSurface) {
			lerp(surface, Color.White, 0.10f)
		} else {
			lerp(
				surface,
				Color.Black,
				when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> 0.20f
					VisualEffectLevel.BALANCED -> 0.25f
					VisualEffectLevel.FULL -> 0.32f
				},
			)
		}
	} else if (palette.isModern) {
		lerp(
			surface,
			Color.Black,
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.20f
				VisualEffectLevel.BALANCED -> 0.25f
				VisualEffectLevel.FULL -> 0.38f
			},
		)
	} else {
		surface
	}
	val upperTintMix = if (adaptiveCustom) {
		if (lightSurface) {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.10f
				VisualEffectLevel.BALANCED -> 0.14f
				VisualEffectLevel.FULL -> 0.18f
			}
		} else {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.07f
				VisualEffectLevel.BALANCED -> 0.10f
				VisualEffectLevel.FULL -> 0.14f
			}
		}
	} else if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.006f
			VisualEffectLevel.BALANCED -> 0.010f
			VisualEffectLevel.FULL -> 0.012f
		}
	} else {
		0f
	}
	val middleTintMix = if (adaptiveCustom) {
		if (lightSurface) {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.08f
				VisualEffectLevel.BALANCED -> 0.12f
				VisualEffectLevel.FULL -> 0.16f
			}
		} else {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.05f
				VisualEffectLevel.BALANCED -> 0.08f
				VisualEffectLevel.FULL -> 0.12f
			}
		}
	} else if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.004f
			VisualEffectLevel.BALANCED -> 0.007f
			VisualEffectLevel.FULL -> 0.009f
		}
	} else {
		0f
	}
	val lowerTintMix = if (adaptiveCustom) {
		if (lightSurface) {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.04f
				VisualEffectLevel.BALANCED -> 0.06f
				VisualEffectLevel.FULL -> 0.08f
			}
		} else {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.03f
				VisualEffectLevel.BALANCED -> 0.05f
				VisualEffectLevel.FULL -> 0.07f
			}
		}
	} else {
		0f
	}
	val upperTint = if (palette.isModern) {
		lerp(neutralSurface, palette.primary, upperTintMix)
	} else {
		surface
	}
	val middleTint = if (palette.isModern) {
		lerp(neutralSurface, palette.secondary, middleTintMix)
	} else {
		surface
	}
	val lowerTint = if (adaptiveCustom) lerp(surface, palette.accent, lowerTintMix) else surface
	val bottomTint = if (adaptiveCustom) {
		lerp(neutralSurface, palette.primary, if (lightSurface) 0.025f else 0.035f)
	} else {
		neutralSurface
	}
	val bottomAlpha = if (adaptiveCustom) {
		if (lightSurface) {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.94f
				VisualEffectLevel.BALANCED -> 0.95f
				VisualEffectLevel.FULL -> 0.96f
			}
		} else {
			when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> 0.97f
				VisualEffectLevel.BALANCED -> 0.975f
				VisualEffectLevel.FULL -> 0.98f
			}
		}
	} else if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.95f
			VisualEffectLevel.BALANCED -> 0.955f
			VisualEffectLevel.FULL -> 0.965f
		}
	} else {
		1f
	}
	val overlayBrush = remember(
		upperTint,
		middleTint,
		lowerTint,
		bottomTint,
		topAlpha,
		middleAlpha,
		lowerAlpha,
		bottomAlpha,
	) {
		Brush.verticalGradient(
			0f to upperTint.copy(alpha = topAlpha),
			0.34f to middleTint.copy(alpha = middleAlpha),
			0.70f to lowerTint.copy(alpha = lowerAlpha),
			1f to bottomTint.copy(alpha = bottomAlpha),
		)
	}
	Box(modifier = Modifier.fillMaxSize()) {
		AsyncImage(
			model = request,
			imageLoader = imageLoader,
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.fillMaxSize()
				.graphicsLayer {
					scaleX = 1.07f
					scaleY = 1.07f
				}
				.then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurAmount > 0) Modifier.blur(blurAmount.dp) else Modifier),
		)
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(overlayBrush),
		)
	}
}
