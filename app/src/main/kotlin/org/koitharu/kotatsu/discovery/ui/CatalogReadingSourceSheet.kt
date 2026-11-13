package org.koitharu.kotatsu.discovery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getLanguageCode
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.discovery.domain.CatalogSearchScope
import org.koitharu.kotatsu.explore.ui.adapter.exploreSourceOrigin
import org.koitharu.kotatsu.explore.ui.adapter.titleRes
import org.koitharu.kotatsu.kotatsu.model.KotatsuMangaSource
import org.koitharu.kotatsu.parsers.model.Manga

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogReadingSourceSheet(
	resolution: CatalogResolutionState,
	picker: CatalogSourcePickerState,
	imageLoader: ImageLoader,
	onScope: (CatalogSearchScope, String?) -> Unit,
	onOpenPicker: () -> Unit,
	onClosePicker: () -> Unit,
	onDismiss: () -> Unit,
	onRetry: () -> Unit,
	onGlobalSearch: () -> Unit,
	onSource: (Manga) -> Unit,
) {
	val colors = MaterialTheme.colorScheme
	val type = MaterialTheme.typography
	val scopeName = when (val scope = resolution.scope) {
		CatalogSearchScope.Preferred -> stringResource(R.string.hub_scope_preferred)
		CatalogSearchScope.AllEnabled -> stringResource(R.string.hub_scope_all_enabled)
		is CatalogSearchScope.Single -> resolution.sourceLabel ?: scope.sourceName
	}
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		modifier = Modifier.testTag("catalog_source_sheet"),
		shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
		containerColor = colors.surface,
		contentColor = colors.onSurface,
		scrimColor = colors.scrim.copy(alpha = 0.48f),
	) {
		if (picker.visible) {
			CatalogSourcePicker(picker, resolution.scope, imageLoader, onScope, onOpenPicker, onClosePicker)
		} else {
			LazyColumn(
				modifier = Modifier.fillMaxWidth(),
				contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				item {
					Text(stringResource(R.string.hub_source_choices), style = type.titleLarge, fontWeight = FontWeight.SemiBold)
				}
				item {
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						Text(stringResource(R.string.hub_scope_label), modifier = Modifier.weight(1f), style = type.labelLarge)
						OutlinedButton(
							onClick = onOpenPicker,
							modifier = Modifier.weight(2f, fill = false).heightIn(min = 48.dp).testTag("catalog_source_scope"),
							shape = CircleShape,
							border = androidx.compose.foundation.BorderStroke(1.dp, colors.primary),
							contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
						) {
							Text(scopeName, Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis)
							Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
						}
					}
				}
				if (resolution.searching) item {
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
						CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
						val message = if (resolution.scope is CatalogSearchScope.Single) {
							stringResource(R.string.hub_scope_searching_single, scopeName)
						} else {
							stringResource(R.string.hub_scope_searching, scopeName)
						}
						Text(message, style = type.bodyMedium)
					}
				}
				if (resolution.selectionUnavailable) item {
					Text(stringResource(R.string.hub_scope_selection_unavailable), color = colors.error, style = type.bodyMedium)
				}
				when {
					resolution.failedSources -> item {
						val message = when {
							resolution.scope is CatalogSearchScope.Single -> stringResource(R.string.hub_scope_source_error, scopeName)
							resolution.candidates.isEmpty() -> stringResource(R.string.hub_scope_incomplete)
							else -> stringResource(R.string.hub_source_partial)
						}
						Text(message, color = colors.error, style = type.bodyMedium)
						if (!resolution.searching) {
							OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
								Text(stringResource(R.string.hub_scope_retry))
							}
						}
					}
					!resolution.searching && resolution.noEligibleSources -> item {
						Text(stringResource(R.string.hub_scope_no_sources, scopeName), style = type.bodyMedium)
					}
					!resolution.searching && resolution.candidates.isEmpty() -> item {
						val message = if (resolution.scope is CatalogSearchScope.Single) {
							stringResource(R.string.hub_scope_empty_single, scopeName)
						} else {
							stringResource(R.string.hub_scope_empty, scopeName)
						}
						Text(message, Modifier.testTag("catalog_source_empty"), style = type.bodyMedium)
					}
				}
				items(resolution.candidates, key = { "${it.source.name}_${it.id}" }) { manga ->
					CatalogReadingCandidate(manga, imageLoader, onSource)
				}
				if (!resolution.searching && !resolution.failedSources && resolution.candidates.isEmpty() &&
					resolution.canExpand
				) item {
					OutlinedButton(
						onClick = { onScope(CatalogSearchScope.AllEnabled, null) },
						modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_all_sources"),
					) { Text(stringResource(R.string.hub_scope_try_all)) }
				}
				item {
					TextButton(onClick = onGlobalSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_global_search")) {
						Text(stringResource(R.string.hub_scope_edit_query))
					}
				}
			}
		}
	}
}

@Composable
private fun CatalogSourcePicker(
	picker: CatalogSourcePickerState,
	active: CatalogSearchScope,
	imageLoader: ImageLoader,
	onScope: (CatalogSearchScope, String?) -> Unit,
	onRetry: () -> Unit,
	onClose: () -> Unit,
) {
	val context = LocalContext.current
	val colors = MaterialTheme.colorScheme
	val type = MaterialTheme.typography
	var query by remember { mutableStateOf("") }
	val visible = picker.sources.filter { it.source.getTitle(context).contains(query.trim(), ignoreCase = true) }
	val pinnedCount = visible.count { it.pinned }
	LazyColumn(
		modifier = Modifier.fillMaxWidth().testTag("catalog_scope_picker"),
		contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		item {
			Row(verticalAlignment = Alignment.CenterVertically) {
				IconButton(onClick = onClose, modifier = Modifier.testTag("catalog_scope_close")) {
					Icon(Icons.Default.Close, stringResource(R.string.back))
				}
				Text(stringResource(R.string.hub_scope_label), style = type.titleLarge, fontWeight = FontWeight.SemiBold)
			}
		}
		item {
			CatalogScopeOption(
				title = stringResource(R.string.hub_scope_preferred),
				subtitle = stringResource(R.string.hub_scope_preferred_description),
				selected = active == CatalogSearchScope.Preferred,
				onClick = { onScope(CatalogSearchScope.Preferred, null) },
			)
		}
		item {
			CatalogScopeOption(
				title = stringResource(R.string.hub_scope_all_enabled),
				subtitle = stringResource(R.string.hub_scope_all_description),
				selected = active == CatalogSearchScope.AllEnabled,
				onClick = { onScope(CatalogSearchScope.AllEnabled, null) },
			)
		}
		item {
			HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.outlineVariant)
			Text(stringResource(R.string.hub_scope_pick_specific), style = type.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
			OutlinedTextField(
				value = query,
				onValueChange = { query = it },
				modifier = Modifier.fillMaxWidth().testTag("catalog_scope_source_query"),
				label = { Text(stringResource(R.string.hub_scope_filter)) },
				leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
				singleLine = true,
				shape = RoundedCornerShape(16.dp),
			)
		}
		when {
			picker.loading -> item {
				LinearProgressIndicator(Modifier.fillMaxWidth())
				Text(stringResource(R.string.hub_scope_loading_sources), style = type.bodyMedium)
			}
			picker.failed -> item {
				Text(stringResource(R.string.hub_scope_sources_error), color = colors.error)
				OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.hub_scope_retry)) }
			}
			visible.isEmpty() -> item {
				Text(stringResource(if (picker.sources.isEmpty()) R.string.hub_scope_no_picker_sources else R.string.hub_scope_no_name_matches))
			}
		}
		itemsIndexed(visible, key = { _, entry -> entry.source.name }) { index, entry ->
			if (pinnedCount > 0 && (index == 0 || index == pinnedCount)) {
				Text(
					text = if (entry.pinned) stringResource(R.string.hub_scope_pinned_section, pinnedCount)
					else stringResource(R.string.hub_scope_other_section, visible.size - pinnedCount),
					style = type.labelLarge,
					color = colors.onSurfaceVariant,
					modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
				)
			}
			val name = entry.source.getTitle(context)
			val scope = CatalogSearchScope.Single(entry.source.name)
			val origin = if (entry.source.unwrap() is KotatsuMangaSource) {
				R.string.hub_scope_origin_kotatsu_runtime
			} else {
				entry.source.exploreSourceOrigin().titleRes()
			}
			val originLabel = stringResource(origin)
			val sourceLabel = stringResource(R.string.hub_scope_source_with_origin, name, originLabel)
			CatalogScopeOption(
				title = name,
				subtitle = entry.source.getLanguageCode(),
				originLabel = originLabel,
				modifier = Modifier.testTag("catalog_scope_option_${entry.source.name}"),
				selected = active == scope,
				onClick = { onScope(scope, sourceLabel) },
				sourceIcon = {
					val request = remember(context, entry.source) {
						ImageRequest.Builder(context).data(entry.source.faviconUri()).build()
					}
					val fallback = painterResource(R.drawable.ic_manga_source)
					AsyncImage(
						model = request,
						imageLoader = imageLoader,
						contentDescription = null,
						placeholder = fallback,
						error = fallback,
						fallback = fallback,
						contentScale = ContentScale.Fit,
						modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
							.background(colors.secondaryContainer).padding(3.dp),
					)
				},
				pinned = entry.pinned,
			)
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogScopeOption(
	title: String,
	subtitle: String?,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	originLabel: String? = null,
	sourceIcon: (@Composable () -> Unit)? = null,
	pinned: Boolean = false,
) {
	val colors = MaterialTheme.colorScheme
	val shape = RoundedCornerShape(14.dp)
	Surface(
		onClick = onClick,
		shape = shape,
		color = if (selected) colors.primaryContainer else colors.surface,
		contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
		modifier = modifier.fillMaxWidth()
			.semantics { this.selected = selected }
			.then(if (selected) Modifier.border(1.dp, colors.primary, shape) else Modifier),
	) {
		ListItem(
			headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
			supportingContent = if (originLabel == null) {
				subtitle?.let { label -> { Text(label) } }
			} else {
				{
					FlowRow(
						horizontalArrangement = Arrangement.spacedBy(6.dp),
						verticalArrangement = Arrangement.spacedBy(4.dp),
						modifier = Modifier.padding(top = 2.dp),
					) {
						subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, modifier = Modifier.align(Alignment.CenterVertically)) }
						Text(
							text = originLabel,
							style = MaterialTheme.typography.labelSmall,
							color = colors.onSecondaryContainer,
							modifier = Modifier.align(Alignment.CenterVertically)
								.background(colors.secondaryContainer, CircleShape)
								.border(1.dp, colors.outlineVariant, CircleShape)
								.padding(horizontal = 8.dp, vertical = 3.dp),
						)
					}
				}
			},
			leadingContent = sourceIcon,
			trailingContent = {
				if (pinned) Icon(Icons.Default.Star, stringResource(R.string.hub_scope_pinned), Modifier.size(18.dp), tint = colors.primary)
				if (sourceIcon == null || selected) RadioButton(selected = selected, onClick = null)
			},
			colors = ListItemDefaults.colors(
				containerColor = androidx.compose.ui.graphics.Color.Transparent,
				headlineColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
				supportingColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
			),
		)
	}
}

@Composable
private fun CatalogReadingCandidate(manga: Manga, imageLoader: ImageLoader, onSource: (Manga) -> Unit) {
	val context = LocalContext.current
	val colors = MaterialTheme.colorScheme
	val cover = remember(context, manga.id, manga.coverUrl, manga.source) {
		ImageRequest.Builder(context).data(manga.coverUrl).mangaSourceExtra(manga.source).build()
	}
	Card(
		onClick = { onSource(manga) },
		shape = RoundedCornerShape(16.dp),
		colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant, contentColor = colors.onSurfaceVariant),
		modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("catalog_source_${manga.source.name}_${manga.id}"),
	) {
		ListItem(
			headlineContent = { Text(manga.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis) },
			supportingContent = {
				Text(listOfNotNull(manga.source.getTitle(context), manga.source.getLanguageCode()).joinToString(" · "))
			},
			leadingContent = {
				AsyncImage(
					model = cover,
					imageLoader = imageLoader,
					contentDescription = null,
					contentScale = ContentScale.Crop,
					modifier = Modifier.size(width = 36.dp, height = 52.dp)
						.clip(RoundedCornerShape(8.dp)).background(colors.surface),
				)
			},
			trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp)) },
			colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
		)
	}
}
