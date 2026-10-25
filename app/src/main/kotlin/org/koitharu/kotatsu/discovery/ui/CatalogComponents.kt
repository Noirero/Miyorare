package org.koitharu.kotatsu.discovery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.miyorareAccentSurface
import org.koitharu.kotatsu.core.ui.miyorareIconSurface
import org.koitharu.kotatsu.core.ui.miyorareSurface
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun catalogProviderName(service: ScrobblerService): String = when (service) {
	ScrobblerService.ANILIST -> "AniList"
	ScrobblerService.MAL -> "MyAnimeList"
	ScrobblerService.KITSU -> "Kitsu"
	else -> service.name
}

@Composable
fun catalogKindName(kind: CatalogMediaKind): String =
	stringResource(if (kind == CatalogMediaKind.MANGA) R.string.hub_manga else R.string.hub_novel)

@Composable
fun catalogSectionTitle(key: CatalogSectionKey): String {
	val title = stringResource(
		when (key.type) {
			CatalogSectionType.RECENT -> R.string.hub_recent
			CatalogSectionType.TRENDING -> R.string.hub_trending
			CatalogSectionType.POPULAR -> R.string.hub_popular
			CatalogSectionType.TOP_RATED -> R.string.hub_top_rated
			CatalogSectionType.TOP_RANKED -> R.string.hub_top_ranked
			CatalogSectionType.MOST_FAVORITED -> R.string.hub_most_favorited
		},
	)
	return stringResource(R.string.hub_section_title, title, catalogKindName(key.kind))
}

@Composable
fun CatalogHomeHeader(
	service: ScrobblerService,
	services: List<ScrobblerService>,
	refreshing: Boolean,
	refreshEnabled: Boolean,
	showLocalSearch: Boolean,
	onProvider: (ScrobblerService) -> Unit,
	onRefresh: () -> Unit,
	onSearch: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val palette = LocalMiyorareVisualPalette.current
	var expanded by remember { mutableStateOf(false) }
	Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(stringResource(R.string.discovery_hub), style = MaterialTheme.typography.headlineSmall)
				Text(
					stringResource(R.string.hub_discovery_subtitle),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
			}
			Box {
				val providerShape = RoundedCornerShape(18.dp)
				Surface(
					onClick = { expanded = true },
					modifier = Modifier
						.heightIn(min = 48.dp)
						.miyorareSurface(palette, providerShape)
						.testTag("catalog_provider_selector"),
					shape = providerShape,
					color = if (palette.isModern) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
					contentColor = MaterialTheme.colorScheme.onSurface,
				) {
					Row(
						Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(4.dp),
					) {
						Text(
							catalogProviderName(service),
							style = MaterialTheme.typography.labelLarge,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						Icon(
							painterResource(R.drawable.ic_expand_more),
							stringResource(R.string.hub_provider),
							modifier = Modifier.size(18.dp),
						)
					}
				}
				DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
					services.forEach { candidate ->
						DropdownMenuItem(
							text = { Text(catalogProviderName(candidate)) },
							onClick = { expanded = false; onProvider(candidate) },
							modifier = Modifier.heightIn(min = 48.dp).testTag("catalog_provider_${candidate.name}"),
						)
					}
				}
			}
			val actionShape = CircleShape
			IconButton(
				onClick = onRefresh,
				enabled = refreshEnabled,
				modifier = Modifier
					.size(48.dp)
					.miyorareIconSurface(palette, actionShape, alpha = if (refreshEnabled) 0.9f else 0.45f)
					.testTag("catalog_refresh"),
			) {
				if (refreshing) {
					CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
				} else {
					Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.hub_refresh))
				}
			}
			if (showLocalSearch) {
				IconButton(
					onClick = onSearch,
					modifier = Modifier
						.size(48.dp)
						.miyorareIconSurface(palette, actionShape, alpha = 0.9f)
						.testTag("catalog_search_action"),
				) {
					Icon(painterResource(R.drawable.ic_search), stringResource(R.string.hub_search_catalogue))
				}
			}
		}
	}
}

@Composable
fun CatalogFilterRow(filter: CatalogFilter, onFilter: (CatalogFilter) -> Unit, modifier: Modifier = Modifier) {
	val palette = LocalMiyorareVisualPalette.current
	val outerShape = RoundedCornerShape(22.dp)
	Surface(
		modifier = modifier.fillMaxWidth().miyorareSurface(palette, outerShape),
		shape = outerShape,
		color = if (palette.isModern) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
	) {
		Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			CatalogFilter.entries.forEach { choice ->
				val isSelected = choice == filter
				val segmentShape = RoundedCornerShape(18.dp)
				val segmentModifier = Modifier
					.weight(1f)
					.heightIn(min = 48.dp)
					.semantics { selected = isSelected; role = Role.RadioButton }
					.let { if (palette.isModern && isSelected) it.miyorareAccentSurface(palette, segmentShape) else it }
					.testTag("catalog_filter_${choice.name}")
				Surface(
					onClick = { onFilter(choice) },
					modifier = segmentModifier,
					shape = segmentShape,
					color = when {
						palette.isModern && isSelected -> Color.Transparent
						isSelected -> MaterialTheme.colorScheme.primaryContainer
						else -> Color.Transparent
					},
					contentColor = when {
						palette.isModern && isSelected -> palette.onButton
						isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
						else -> MaterialTheme.colorScheme.onSurfaceVariant
					},
				) {
					Row(
						Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
						horizontalArrangement = Arrangement.Center,
						verticalAlignment = Alignment.CenterVertically,
					) {
						if (isSelected) {
							Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp))
							Spacer(Modifier.width(5.dp))
						}
						Text(
							stringResource(
								when (choice) {
									CatalogFilter.ALL -> R.string.hub_all
									CatalogFilter.MANGA -> R.string.hub_manga
									CatalogFilter.NOVEL -> R.string.hub_novel
								},
							),
							style = MaterialTheme.typography.labelLarge,
							fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
				}
			}
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogSpotlight(
	spotlight: DiscoverySpotlight,
	imageLoader: ImageLoader,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val palette = LocalMiyorareVisualPalette.current
	val shape = RoundedCornerShape(26.dp)
	val item = spotlight.item
	Box(
		modifier = modifier
			.fillMaxWidth()
			.heightIn(min = 210.dp, max = 280.dp)
			.clip(shape)
			.miyorareSurface(palette, shape)
			.clickable(onClick = onClick)
			.testTag("catalog_spotlight"),
	) {
		if (item.coverUrl != null) {
			AsyncImage(
				model = item.coverUrl,
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
		} else {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
				Icon(
					painterResource(R.drawable.ic_discovery_hub),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
					modifier = Modifier.padding(end = 32.dp).size(88.dp),
				)
			}
		}
		Box(
			Modifier.fillMaxSize().background(
				Brush.horizontalGradient(
					listOf(
						MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
						MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
						MaterialTheme.colorScheme.surface.copy(alpha = 0.36f),
					),
				),
			),
		)
		Column(
			Modifier.fillMaxHeight().fillMaxWidth(0.82f).padding(20.dp),
			verticalArrangement = Arrangement.Center,
		) {
			Text(
				catalogSectionTitle(spotlight.section.key),
				style = MaterialTheme.typography.labelLarge,
				color = if (palette.isModern) palette.primary else MaterialTheme.colorScheme.primary,
			)
			Spacer(Modifier.height(8.dp))
			Text(item.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
			Spacer(Modifier.height(8.dp))
			FlowRow(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) {
				Text(catalogProviderName(item.key.service), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
				Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
				Text(catalogKindName(item.key.kind), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
				item.score?.let {
					Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
					Text(stringResource(R.string.hub_score_compact, String.format(Locale.getDefault(), "%.1f", it)), style = MaterialTheme.typography.labelMedium)
				}
			}
			Spacer(Modifier.height(12.dp))
			Text(
				stringResource(R.string.hub_view_details),
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.SemiBold,
				color = if (palette.isModern) palette.primary else MaterialTheme.colorScheme.primary,
			)
		}
	}
}

@Composable
fun CatalogSectionHeader(key: CatalogSectionKey, onSeeAll: () -> Unit) {
	val palette = LocalMiyorareVisualPalette.current
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
		Box(
			Modifier
				.width(4.dp)
				.height(24.dp)
				.background(
					if (palette.isModern) palette.primary else MaterialTheme.colorScheme.primary,
					RoundedCornerShape(50),
				),
		)
		Text(catalogSectionTitle(key), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
		TextButton(onClick = onSeeAll, modifier = Modifier.heightIn(min = 48.dp).testTag("catalog_see_all_${key.kind}_${key.type}")) {
			Text(stringResource(R.string.hub_see_all))
		}
	}
}

@Composable
fun CatalogCover(item: TrackerCatalogItem, imageLoader: ImageLoader, modifier: Modifier = Modifier) {
	val shape = RoundedCornerShape(14.dp)
	Box(
		modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh, shape),
		contentAlignment = Alignment.Center,
	) {
		if (item.coverUrl != null) {
			AsyncImage(
				model = item.coverUrl,
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
		} else {
			Icon(
				painterResource(R.drawable.ic_discovery_hub),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(32.dp),
			)
		}
	}
}

@Composable
fun catalogHomeCardWidth(): Dp = when {
	LocalConfiguration.current.screenWidthDp < 360 -> 128.dp
	LocalConfiguration.current.screenWidthDp < 600 -> 136.dp
	else -> 144.dp
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogCard(
	item: TrackerCatalogItem,
	imageLoader: ImageLoader,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	ordinal: Int? = null,
) {
	val palette = LocalMiyorareVisualPalette.current
	val shape = RoundedCornerShape(18.dp)
	Surface(
		onClick = onClick,
		modifier = modifier
			.miyorareSurface(palette, shape)
			.testTag("catalog_card_${item.key.service.name}_${item.key.kind.name}_${item.key.remoteId}"),
		shape = shape,
		color = if (palette.isModern) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
	) {
		Column {
			Box {
				CatalogCover(item, imageLoader, Modifier.fillMaxWidth().aspectRatio(0.68f))
				ordinal?.let {
					Surface(
						modifier = Modifier.padding(8.dp),
						shape = RoundedCornerShape(50),
						color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
					) {
						Text("#$it", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
					}
				}
			}
			Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
				Text(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
				FlowRow(
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalArrangement = Arrangement.spacedBy(2.dp),
				) {
					Text(catalogKindName(item.key.kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
					item.score?.let {
						Text("•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
						Text(stringResource(R.string.hub_score_compact, String.format(Locale.getDefault(), "%.1f", it)), style = MaterialTheme.typography.labelSmall)
					}
				}
			}
		}
	}
}

@Composable
fun CatalogHomeLoading(modifier: Modifier = Modifier) {
	val placeholder = MaterialTheme.colorScheme.surfaceContainerHigh
	Column(modifier.fillMaxWidth().testTag("catalog_loading"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
		Box(Modifier.fillMaxWidth().height(220.dp).background(placeholder, RoundedCornerShape(26.dp)))
		LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			items(3) {
				Column(Modifier.width(128.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Box(Modifier.fillMaxWidth().height(176.dp).background(placeholder, RoundedCornerShape(16.dp)))
					Box(Modifier.fillMaxWidth(0.82f).height(14.dp).background(placeholder, RoundedCornerShape(7.dp)))
					Box(Modifier.fillMaxWidth(0.55f).height(10.dp).background(placeholder, RoundedCornerShape(5.dp)))
				}
			}
		}
		Text(stringResource(R.string.hub_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

@Composable
fun CatalogLoading(modifier: Modifier = Modifier) {
	Column(
		modifier.fillMaxWidth().padding(32.dp).testTag("catalog_loading"),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		CircularProgressIndicator()
		Text(stringResource(R.string.hub_loading))
	}
}

@Composable
fun CatalogSectionFailure(onRetry: () -> Unit) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Text(
			stringResource(R.string.hub_partial_update),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.weight(1f),
		)
		TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.hub_retry)) }
	}
}

@Composable
fun CatalogFeedback(failure: CatalogFailure, service: ScrobblerService, onRetry: () -> Unit, modifier: Modifier = Modifier) {
	val message = when (failure.kind) {
		CatalogFailureKind.OFFLINE -> R.string.hub_offline
		CatalogFailureKind.RATE_LIMITED -> R.string.hub_rate_limited
		CatalogFailureKind.INVALID_DATA -> R.string.hub_invalid_data
		CatalogFailureKind.UNSUPPORTED -> R.string.hub_unsupported
		CatalogFailureKind.PROVIDER -> R.string.hub_provider_error
	}
	Surface(modifier.testTag("catalog_error_${failure.kind.name}"), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
		Column(Modifier.padding(16.dp)) {
			Text(if (failure.kind == CatalogFailureKind.UNSUPPORTED) stringResource(message) else stringResource(message, catalogProviderName(service)))
			failure.retryAt?.let {
				Text(stringResource(R.string.hub_retry_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))), style = MaterialTheme.typography.bodySmall)
			}
			TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("catalog_retry")) { Text(stringResource(R.string.hub_retry)) }
		}
	}
}
