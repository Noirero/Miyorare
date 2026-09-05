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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import org.koitharu.kotatsu.core.prefs.DetailsUiMode
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.util.StatusBarScrim
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo

class DetailsExpressiveActions(
	val onCoverClick: (Manga) -> Unit,
	val onTitleClick: (String) -> Unit,
	val onSourceClick: (Manga) -> Unit,
	val onLocalClick: (Manga) -> Unit,
	val onFavoriteClick: (Manga) -> Unit,
	val onAuthorClick: (String) -> Unit,
	val onTagClick: (MangaTag) -> Unit,
	val onScrobblingMore: () -> Unit,
	val onScrobblingCardClick: (Int) -> Unit,
	val onRelatedMore: (Manga) -> Unit,
	val onRelatedClick: (MangaListModel) -> Unit,
	val onReadClick: () -> Unit,
	val onIncognitoClick: () -> Unit,
	val onForgetHistoryClick: () -> Unit,
	val onChaptersClick: () -> Unit,
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
	isLoading: Boolean,
	favouriteCount: Int,
	favouriteLabel: String?,
	scrobblings: List<ScrobblingInfo>,
	related: List<MangaListModel>,
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
	val baseScheme = MaterialTheme.colorScheme
	val typography = MaterialTheme.typography

	MaterialTheme(colorScheme = baseScheme, typography = typography) {
		val scheme = MaterialTheme.colorScheme
		val palette = LocalMiyorareVisualPalette.current
		val accentColor = scheme.primary
		val screenSurface = if (palette.isModern) scheme.background else scheme.surface
		val listState = rememberLazyListState()
		val centered = style != DetailsUiMode.COMPACT

		LaunchedEffect(listState) {
			snapshotFlow {
				if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) 0 else 1
			}.collect(onScroll)
		}

		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(screenSurface),
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
					Spacer(Modifier.height(topInset + if (centered) 84.dp else 72.dp))
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
							tags = tags,
							accent = accentColor,
							imageLoader = imageLoader,
							coverUrl = coverUrl,
							actions = actions,
						)
					}

					item(contentType = "primary-actions") {
						Spacer(Modifier.height(20.dp))
						PrimaryDetailsActions(
							favouriteLabel = favLabel.ifBlank { stringResource(R.string.add_to_favourites) },
							isFavourite = isFavourite,
							historyInfo = historyInfo,
							isLoading = isLoading,
							accent = accentColor,
							onFavouriteClick = { actions.onFavoriteClick(manga) },
							onReadClick = actions.onReadClick,
						)
					}

					details.artist?.let { artist ->
						item(contentType = "artist") {
							Spacer(Modifier.height(12.dp))
							Text(
								text = stringResource(R.string.override_artist_display, artist),
								style = MaterialTheme.typography.labelLarge,
								color = accentColor,
								modifier = Modifier.padding(horizontal = SCREEN_PADDING),
							)
						}
					}

					item(contentType = "progress") {
						Spacer(Modifier.height(8.dp))
						ProgressCard(historyInfo = historyInfo, isLoading = isLoading, accent = accentColor)
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

					if (historyInfo.totalChapters > 0 || chapters.isNotEmpty()) {
						item(contentType = "chapters-header") {
							InlineChapterHeader(
								count = historyInfo.totalChapters.coerceAtLeast(chapters.size),
								accent = accentColor,
								onManage = actions.onChaptersClick,
							)
						}
						items(
							items = chapters,
							key = { it.chapter.id },
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

					if (related.isNotEmpty()) {
						item(contentType = "related") {
							RelatedSection(
								items = related,
								imageLoader = imageLoader,
								accent = accentColor,
								onMore = { actions.onRelatedMore(manga) },
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
				val stops = StatusBarScrim.alphas
				Box(
					modifier = Modifier
						.align(Alignment.TopCenter)
						.fillMaxWidth()
						.height(topInset * StatusBarScrim.HEIGHT_FACTOR)
						.background(
							Brush.verticalGradient(
								*stops.mapIndexed { i, a ->
									i / stops.lastIndex.toFloat() to screenSurface.copy(alpha = a / 255f)
								}.toTypedArray(),
							),
						),
				)
			}
		}
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
	val topAlpha = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.30f
			VisualEffectLevel.BALANCED -> 0.22f
			VisualEffectLevel.FULL -> 0.16f
		}
	} else {
		0.50f
	}
	val middleAlpha = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.63f
			VisualEffectLevel.BALANCED -> 0.57f
			VisualEffectLevel.FULL -> 0.52f
		}
	} else {
		0.78f
	}
	val lowerAlpha = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.94f
			VisualEffectLevel.BALANCED -> 0.93f
			VisualEffectLevel.FULL -> 0.92f
		}
	} else {
		0.94f
	}
	Box(modifier = Modifier.fillMaxSize()) {
		AsyncImage(
			model = request,
			imageLoader = imageLoader,
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.fillMaxSize()
				.then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurAmount > 0) Modifier.blur(blurAmount.dp) else Modifier),
		)
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(
					Brush.verticalGradient(
						0f to surface.copy(alpha = topAlpha),
						0.34f to surface.copy(alpha = middleAlpha),
						0.70f to surface.copy(alpha = lowerAlpha),
						1f to surface,
					),
				),
		)
	}
}
