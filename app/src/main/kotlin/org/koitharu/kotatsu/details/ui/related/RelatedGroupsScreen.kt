package org.koitharu.kotatsu.details.ui.related

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.details.domain.RelatedMangaGroup
import org.koitharu.kotatsu.parsers.model.Manga

@Composable
internal fun RelatedGroupsScreen(
	state: RelatedGroupsUiState,
	imageLoader: ImageLoader,
	onRetry: () -> Unit,
	onMangaClick: (Manga) -> Unit,
	onShowAll: (String) -> Unit,
) {
	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background),
	) {
		when {
			state.groups.isNotEmpty() -> RelatedGroupsContent(
				groups = state.groups,
				isLoading = state.isLoading,
				imageLoader = imageLoader,
				onMangaClick = onMangaClick,
				onShowAll = onShowAll,
			)
			state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
			state.error != null -> Column(
				modifier = Modifier
					.align(Alignment.Center)
					.padding(24.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Text(
					text = state.error.localizedMessage ?: stringResource(R.string.nothing_found),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
			}
			else -> Text(
				text = stringResource(R.string.nothing_found),
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.align(Alignment.Center),
			)
		}
	}
}

@Composable
private fun RelatedGroupsContent(
	groups: List<RelatedMangaGroup>,
	isLoading: Boolean,
	imageLoader: ImageLoader,
	onMangaClick: (Manga) -> Unit,
	onShowAll: (String) -> Unit,
) {
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(vertical = 12.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		items(
			items = groups,
			key = { it.keyword ?: "__related__" },
		) { group ->
			RelatedKeywordCarousel(
				group = group,
				imageLoader = imageLoader,
				onMangaClick = onMangaClick,
				onShowAll = onShowAll,
			)
		}
		if (isLoading) {
			item(key = "__related_loading__") {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 20.dp),
					contentAlignment = Alignment.Center,
				) {
					CircularProgressIndicator()
				}
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelatedKeywordCarousel(
	group: RelatedMangaGroup,
	imageLoader: ImageLoader,
	onMangaClick: (Manga) -> Unit,
	onShowAll: (String) -> Unit,
) {
	val keyword = group.keyword
	RelatedGroupHeader(
		title = keyword ?: stringResource(R.string.related_manga),
		onShowAll = keyword?.let { { onShowAll(it) } },
	)
	val carouselState = rememberCarouselState { group.manga.size }
	HorizontalMultiBrowseCarousel(
		state = carouselState,
		preferredItemWidth = 150.dp,
		itemSpacing = 10.dp,
		flingBehavior = CarouselDefaults.multiBrowseFlingBehavior(state = carouselState),
		contentPadding = PaddingValues(horizontal = 20.dp),
		modifier = Modifier
			.fillMaxWidth()
			.height(242.dp),
	) { index ->
		val manga = group.manga.getOrNull(index) ?: return@HorizontalMultiBrowseCarousel
		RelatedMangaCard(
			manga = manga,
			imageLoader = imageLoader,
			onClick = { onMangaClick(manga) },
		)
	}
}

@Composable
private fun RelatedGroupHeader(
	title: String,
	onShowAll: (() -> Unit)?,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = 20.dp, end = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.weight(1f),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		if (onShowAll != null) {
			TextButton(onClick = onShowAll) {
				Text(stringResource(R.string.show_all))
			}
		}
	}
}

@Composable
private fun RelatedMangaCard(
	manga: Manga,
	imageLoader: ImageLoader,
	onClick: () -> Unit,
) {
	val context = LocalContext.current
	Column(
		modifier = Modifier.clickable(onClick = onClick),
	) {
		AsyncImage(
			model = remember(manga.id, manga.coverUrl, manga.source) {
				ImageRequest.Builder(context)
					.data(manga.coverUrl)
					.crossfade(true)
					.mangaSourceExtra(manga.source)
					.build()
			},
			imageLoader = imageLoader,
			contentDescription = manga.title,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.height(200.dp)
				.fillMaxWidth()
				.clip(RoundedCornerShape(20.dp)),
		)
		Spacer(Modifier.height(8.dp))
		Text(
			text = manga.title,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurface,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(horizontal = 6.dp),
		)
	}
}
