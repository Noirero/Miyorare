package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.signatureBorderBrush
import org.koitharu.kotatsu.core.util.ext.isRemoteCoverUrl
import org.koitharu.kotatsu.core.util.ext.mangaCoverDiskCacheKey
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.stableMangaCoverKey
import org.koitharu.kotatsu.parsers.model.Manga

@Composable
internal fun CoverCard(
	manga: Manga,
	coverUrl: String?,
	imageLoader: ImageLoader,
	modifier: Modifier,
	corner: Dp,
	nsfwLabel: String?,
	forceRefresh: Boolean,
	actions: DetailsExpressiveActions,
) {
	val ctx = LocalContext.current
	val palette = LocalMiyorareVisualPalette.current
	val shape = RoundedCornerShape(corner)
	val glowElevation = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 2.dp
			VisualEffectLevel.BALANCED -> 7.dp
			VisualEffectLevel.FULL -> 18.dp
		}
	} else {
		0.dp
	}
	val coverBorderAlpha = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.30f
			VisualEffectLevel.BALANCED -> 0.54f
			VisualEffectLevel.FULL -> 0.86f
		}
	} else {
		0f
	}
	val coverAmbientAlpha = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.16f
			VisualEffectLevel.BALANCED -> 0.36f
			VisualEffectLevel.FULL -> 0.58f
		}
	} else {
		0f
	}
	val coverSpotAlpha = if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> 0.24f
			VisualEffectLevel.BALANCED -> 0.56f
			VisualEffectLevel.FULL -> 0.90f
		}
	} else {
		0f
	}
	Surface(
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceVariant,
		border = if (palette.isModern) {
			BorderStroke(
				if (palette.effectLevel == VisualEffectLevel.FULL) 1.15.dp else 1.dp,
				palette.signatureBorderBrush(
					fallback = palette.primary,
					alpha = coverBorderAlpha,
				),
			)
		} else {
			null
		},
		tonalElevation = if (palette.isModern) 0.dp else 4.dp,
		shadowElevation = if (palette.isModern) 0.dp else 16.dp,
		modifier = if (palette.isModern) {
			modifier.shadow(
				elevation = glowElevation,
				shape = shape,
				clip = false,
				ambientColor = palette.primary.copy(alpha = coverAmbientAlpha),
				spotColor = androidx.compose.ui.graphics.lerp(palette.primary, palette.secondary, 0.32f).copy(alpha = coverSpotAlpha),
			)
		} else {
			modifier
		},
	) {
		val coverRequest = remember(coverUrl, manga.id, manga.source) {
			ImageRequest.Builder(ctx)
				.data(coverUrl)
				.crossfade(true)
				.mangaSourceExtra(manga.source)
				.stableMangaCoverKey(manga, coverUrl)
				.build()
		}
		if (forceRefresh && isRemoteCoverUrl(coverUrl)) {
			LaunchedEffect(manga.id, coverUrl) {
				imageLoader.enqueue(
					ImageRequest.Builder(ctx)
						.data(coverUrl)
						.mangaSourceExtra(manga.source)
						.diskCacheKey(mangaCoverDiskCacheKey(manga.id))
						.diskCachePolicy(CachePolicy.WRITE_ONLY)
						.memoryCachePolicy(CachePolicy.DISABLED)
						.networkCachePolicy(CachePolicy.ENABLED)
						.build(),
				)
			}
		}
		Box(modifier = Modifier.fillMaxSize()) {
			AsyncImage(
				model = coverRequest,
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.fillMaxSize()
					.clickable { actions.onCoverClick(manga) },
			)
			if (nsfwLabel != null) {
				Surface(
					shape = RoundedCornerShape(8.dp),
					color = Color.Black.copy(alpha = 0.6f),
					modifier = Modifier
						.align(Alignment.BottomEnd)
						.padding(6.dp),
				) {
					Text(
						text = nsfwLabel,
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.Bold,
						color = Color.White,
						modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
					)
				}
			}
		}
	}
}

@Composable
internal fun HeroTexts(
	centered: Boolean,
	manga: Manga,
	accent: Color,
	actions: DetailsExpressiveActions,
	showAuthors: Boolean = true,
) {
	val align = if (centered) TextAlign.Center else TextAlign.Start
	Text(
		text = manga.title,
		style = if (centered) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
		fontWeight = FontWeight.Bold,
		color = MaterialTheme.colorScheme.onSurface,
		textAlign = align,
		maxLines = 4,
		overflow = TextOverflow.Ellipsis,
		modifier = Modifier.clickable { actions.onTitleClick(manga.title) },
	)
	val altTitle = manga.altTitles.firstOrNull()?.takeIf { it.isNotBlank() }
	if (altTitle != null) {
		Spacer(Modifier.height(6.dp))
		Text(
			text = altTitle,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = align,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
	}
	val authors = manga.authors.filter { it.isNotBlank() }
	if (showAuthors && authors.isNotEmpty()) {
		Spacer(Modifier.height(8.dp))
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clickable { actions.onAuthorClick(authors.first()) },
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
		) {
			Text(
				text = authors.joinToString(", "),
				style = MaterialTheme.typography.labelLarge,
				color = accent,
				fontWeight = FontWeight.Medium,
				textAlign = align,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				modifier = if (centered) Modifier else Modifier.weight(1f),
			)
			Spacer(Modifier.width(6.dp))
			Icon(
				painter = painterResource(R.drawable.ic_chevron_right),
				contentDescription = null,
				tint = accent,
				modifier = Modifier.size(16.dp),
			)
		}
	}
}
