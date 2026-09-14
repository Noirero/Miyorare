package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.list.ui.model.MangaListModel

/** Carousel body for the contextual/genre recommendation lane. The visibility header lives in Details. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenreRecommendationSection(
	items: List<MangaListModel>,
	imageLoader: ImageLoader,
	onItemClick: (MangaListModel) -> Unit,
) {
	if (items.isEmpty()) return
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
		Column(modifier = Modifier.clickable { onItemClick(item) }) {
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