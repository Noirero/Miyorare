package org.koitharu.kotatsu.stats.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.stableMangaCoverKey
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyRules
import org.koitharu.kotatsu.readerjourney.domain.ReaderRank
import org.koitharu.kotatsu.stats.domain.ReadingStats
import org.koitharu.kotatsu.stats.domain.StatsContentScope
import org.koitharu.kotatsu.stats.domain.StatsHeatmapDay
import org.koitharu.kotatsu.stats.domain.StatsInsight
import org.koitharu.kotatsu.stats.domain.StatsMatureMode
import org.koitharu.kotatsu.stats.domain.StatsPeriod
import org.koitharu.kotatsu.stats.domain.StatsRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Modern Reader Journey dashboard.
 *
 * The visual hierarchy is deliberate: scope -> filters -> journey hero -> metrics -> discoveries.
 * Mature privacy is a quiet control rather than a prominent content category.
 */
@Composable
fun StatsScreen(
	stats: ReadingStats,
	isLoading: Boolean,
	period: StatsPeriod,
	scope: StatsContentScope,
	matureMode: StatsMatureMode,
	categories: List<FavouriteCategory>,
	selectedCategories: Set<Long>,
	imageLoader: ImageLoader,
	bottomInset: Dp,
	onPeriodChange: (StatsPeriod) -> Unit,
	onScopeChange: (StatsContentScope) -> Unit,
	onMatureModeChange: (StatsMatureMode) -> Unit,
	onCategoryToggle: (FavouriteCategory) -> Unit,
	onCategoriesClear: () -> Unit,
	onMangaClick: (Manga) -> Unit,
) {
	val visibleRevisited = remember(stats.revisited, matureMode) {
		stats.revisited.filter { record -> record.manga != null }
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.nestedScroll(rememberNestedScrollInteropConnection()),
	) {
		if (isLoading && stats.isEmpty) {
			LinearProgressIndicator(
				modifier = Modifier
					.fillMaxWidth()
					.align(Alignment.TopCenter),
			)
		}

		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(top = 10.dp, bottom = bottomInset + 36.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			item("scope") {
				ScopeSelector(
					selected = scope,
					onSelect = onScopeChange,
				)
			}
			item("filters") {
				StatsFilterRow(
					period = period,
					matureMode = matureMode,
					categories = categories,
					selectedCategories = selectedCategories,
					onPeriodChange = onPeriodChange,
					onMatureModeChange = onMatureModeChange,
					onCategoryToggle = onCategoryToggle,
					onCategoriesClear = onCategoriesClear,
				)
			}
			if (stats.isJourneyEnabled) {
				item("journey") {
					ReaderJourneyHero(stats)
				}
			}
			item("metrics") {
				MetricsGrid(stats)
			}
			if (stats.isEmpty) {
				item("empty") {
					StatsEmptyState()
				}
			}
			item("top-pick") {
				TopPickSection(
					stats = stats,
					imageLoader = imageLoader,
					onMangaClick = onMangaClick,
				)
			}
			item("heatmap") {
				ReadingHeatmapCard(stats.heatmapDays)
			}
			item("insights-header") {
				StatsSectionHeader(title = stringResource(R.string.stats_reading_insights))
			}
			item("genre-insight") {
				InsightCard(
					title = stringResource(R.string.stats_most_read_genres),
					items = stats.topGenres,
					icon = R.drawable.ic_grid,
				)
			}
			if (stats.formatBreakdown.size > 1) {
				item("format-insight") {
					InsightCard(
						title = stringResource(R.string.stats_format_breakdown),
						items = stats.formatBreakdown,
						icon = R.drawable.ic_book_page,
					)
				}
			}
			if (visibleRevisited.isNotEmpty()) {
				item("revisited-header") {
					StatsSectionHeader(title = stringResource(R.string.stats_revisited_most))
				}
				items(
					items = visibleRevisited,
					key = { record -> record.manga?.id ?: record.firstReadAt },
				) { record ->
					RevisitedRow(
						record = record,
						imageLoader = imageLoader,
						onMangaClick = onMangaClick,
					)
				}
			}
		}
	}
}

@Composable
private fun ScopeSelector(
	selected: StatsContentScope,
	onSelect: (StatsContentScope) -> Unit,
) {
	val containerShape = RoundedCornerShape(22.dp)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING)
			.clip(containerShape)
			.background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f))
			.border(
				width = 1.dp,
				color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
				shape = containerShape,
			)
			.padding(4.dp),
		horizontalArrangement = Arrangement.spacedBy(4.dp),
	) {
		StatsContentScope.entries.forEach { entry ->
			val selectedItem = selected == entry
			val itemShape = RoundedCornerShape(18.dp)
			Box(
				modifier = Modifier
					.weight(1f)
					.clip(itemShape)
					.background(
						if (selectedItem) {
							MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
						} else {
							MaterialTheme.colorScheme.surface.copy(alpha = 0f)
						},
					)
					.clickable { onSelect(entry) }
					.padding(horizontal = 8.dp, vertical = 10.dp),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = stringResource(entry.titleRes),
					style = MaterialTheme.typography.labelLarge,
					fontWeight = if (selectedItem) FontWeight.Bold else FontWeight.Medium,
					color = if (selectedItem) {
						MaterialTheme.colorScheme.onPrimaryContainer
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					textAlign = TextAlign.Center,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

private val StatsContentScope.titleRes: Int
	@StringRes get() = when (this) {
		StatsContentScope.OVERVIEW -> R.string.stats_scope_overview
		StatsContentScope.MANGA -> R.string.stats_scope_manga
		StatsContentScope.NOVEL -> R.string.stats_scope_novel
	}

@Composable
private fun StatsFilterRow(
	period: StatsPeriod,
	matureMode: StatsMatureMode,
	categories: List<FavouriteCategory>,
	selectedCategories: Set<Long>,
	onPeriodChange: (StatsPeriod) -> Unit,
	onMatureModeChange: (StatsMatureMode) -> Unit,
	onCategoryToggle: (FavouriteCategory) -> Unit,
	onCategoriesClear: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		if (categories.isNotEmpty()) {
			CategoryFilterChip(
				categories = categories,
				selected = selectedCategories,
				onToggle = onCategoryToggle,
				onClear = onCategoriesClear,
				modifier = Modifier.widthIn(max = 156.dp),
			)
		}
		LazyRow(
			modifier = Modifier.weight(1f),
			contentPadding = PaddingValues(horizontal = 1.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			items(StatsPeriod.entries, key = { it.name }) { entry ->
				FilterChip(
					selected = entry == period,
					onClick = { onPeriodChange(entry) },
					label = {
						Text(
							text = stringResource(entry.titleResId),
							maxLines = 1,
						)
					},
				)
			}
		}
		MaturePrivacyButton(
			mode = matureMode,
			onSelect = onMatureModeChange,
		)
	}
}

@Composable
private fun MaturePrivacyButton(
	mode: StatsMatureMode,
	onSelect: (StatsMatureMode) -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	Box {
		val shape = CircleShape
		Box(
			modifier = Modifier
				.size(44.dp)
				.clip(shape)
				.background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f))
				.border(
					width = 1.dp,
					color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
					shape = shape,
				)
				.clickable { expanded = true },
			contentAlignment = Alignment.Center,
		) {
			Icon(
				painter = painterResource(R.drawable.ic_lock),
				contentDescription = stringResource(R.string.stats_mature_content),
				tint = if (mode == StatsMatureMode.INCLUDE) {
					MaterialTheme.colorScheme.onSurfaceVariant
				} else {
					MaterialTheme.colorScheme.primary
				},
				modifier = Modifier.size(19.dp),
			)
		}
		DropdownMenu(
			expanded = expanded,
			onDismissRequest = { expanded = false },
		) {
			StatsMatureMode.entries.forEach { option ->
				DropdownMenuItem(
					text = {
						Column {
							Text(
								text = stringResource(option.labelRes),
								style = MaterialTheme.typography.bodyLarge,
								fontWeight = if (option == mode) FontWeight.SemiBold else FontWeight.Normal,
							)
							Text(
								text = stringResource(option.summaryRes),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					},
					trailingIcon = {
						if (option == mode) {
							Icon(
								painter = painterResource(R.drawable.ic_check),
								contentDescription = null,
							)
						}
					},
					onClick = {
						onSelect(option)
						expanded = false
					},
				)
			}
		}
	}
}

private val StatsMatureMode.labelRes: Int
	@StringRes get() = when (this) {
		StatsMatureMode.PRIVATE -> R.string.stats_privacy_private
		StatsMatureMode.EXCLUDE -> R.string.stats_privacy_exclude
		StatsMatureMode.INCLUDE -> R.string.stats_privacy_include
	}

private val StatsMatureMode.summaryRes: Int
	@StringRes get() = when (this) {
		StatsMatureMode.PRIVATE -> R.string.stats_privacy_summary_private
		StatsMatureMode.EXCLUDE -> R.string.stats_privacy_summary_exclude
		StatsMatureMode.INCLUDE -> R.string.stats_privacy_summary_include
	}

@Composable
private fun ReaderJourneyHero(stats: ReadingStats) {
	val journey = remember(stats.lifetimeXp) { ReaderJourneyRules.progress(stats.lifetimeXp) }
	val shape = RoundedCornerShape(30.dp)
	val accent = MaterialTheme.colorScheme.primary
	val secondary = MaterialTheme.colorScheme.tertiary
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING)
			.clip(shape)
			.background(
				Brush.linearGradient(
					listOf(
						MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f),
						MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
						MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.58f),
					),
				),
			)
			.border(
				width = 1.dp,
				color = accent.copy(alpha = 0.28f),
				shape = shape,
			)
			.padding(20.dp),
	) {
		Column {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(13.dp),
			) {
				Box(
					modifier = Modifier
						.size(50.dp)
						.clip(CircleShape)
						.background(accent.copy(alpha = 0.16f))
						.border(1.dp, accent.copy(alpha = 0.28f), CircleShape),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						painter = painterResource(R.drawable.ic_auto_stories),
						contentDescription = null,
						tint = accent,
						modifier = Modifier.size(26.dp),
					)
				}
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = stringResource(R.string.reader_journey_level_label),
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Text(
						text = stringResource(journey.rank.titleRes),
						style = MaterialTheme.typography.headlineSmall,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onSurface,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
				Surface(
					shape = RoundedCornerShape(18.dp),
					color = accent.copy(alpha = 0.14f),
				) {
					Text(
						text = stringResource(R.string.reader_journey_level, journey.level),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
						color = accent,
						modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
					)
				}
			}

			Spacer(Modifier.height(20.dp))

			Row(verticalAlignment = Alignment.Bottom) {
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = stringResource(R.string.stats_level_progress),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Spacer(Modifier.height(2.dp))
					Text(
						text = journey.xpForNextLevel?.let { next ->
							"${journey.xpIntoLevel} / $next XP"
						} ?: stringResource(R.string.reader_journey_lifetime_xp, journey.lifetimeXp),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onSurface,
					)
				}
				if (journey.xpForNextLevel != null) {
					Text(
						text = stringResource(R.string.reader_journey_lifetime_xp, journey.lifetimeXp),
						style = MaterialTheme.typography.labelMedium,
						color = secondary,
					)
				}
			}
			Spacer(Modifier.height(9.dp))
			LinearProgressIndicator(
				progress = { journey.levelFraction },
				modifier = Modifier
					.fillMaxWidth()
					.height(9.dp)
					.clip(RoundedCornerShape(9.dp)),
			)
			if (stats.lifetimeXp == 0L && !stats.isEmpty) {
				Spacer(Modifier.height(10.dp))
				Text(
					text = stringResource(R.string.reader_journey_xp_starts_now),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

private val ReaderRank.titleRes: Int
	@StringRes get() = when (this) {
		ReaderRank.NEWCOMER -> R.string.reader_rank_newcomer
		ReaderRank.READER -> R.string.reader_rank_reader
		ReaderRank.BOOKWORM -> R.string.reader_rank_bookworm
		ReaderRank.EXPLORER -> R.string.reader_rank_explorer
		ReaderRank.COLLECTOR -> R.string.reader_rank_collector
		ReaderRank.SCHOLAR -> R.string.reader_rank_scholar
		ReaderRank.ARCHIVIST -> R.string.reader_rank_archivist
		ReaderRank.BIBLIOPHILE -> R.string.reader_rank_bibliophile
		ReaderRank.VETERAN_READER -> R.string.reader_rank_veteran_reader
		ReaderRank.MASTER_READER -> R.string.reader_rank_master_reader
		ReaderRank.GRAND_READER -> R.string.reader_rank_grand_reader
		ReaderRank.LEGEND -> R.string.reader_rank_legend
	}

@Composable
private fun MetricsGrid(stats: ReadingStats) {
	val resources = LocalContext.current.resources
	Column(
		modifier = Modifier.padding(horizontal = STATS_PADDING),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			ModernMetricCard(
				label = stringResource(R.string.stats_read_time),
				value = formatDurationShort(resources, stats.totalDuration),
				icon = R.drawable.ic_timelapse,
				modifier = Modifier.weight(1f),
			)
			ModernMetricCard(
				label = stringResource(R.string.stats_titles_read),
				value = stats.titleCount.toString(),
				icon = R.drawable.ic_book_page,
				modifier = Modifier.weight(1f),
			)
		}
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			ModernMetricCard(
				label = stringResource(R.string.stats_days),
				value = stats.activeDays.toString(),
				icon = R.drawable.ic_grid,
				modifier = Modifier.weight(1f),
			)
			ModernMetricCard(
				label = stringResource(R.string.stats_avg_session),
				value = formatDurationShort(resources, stats.averageSessionDuration),
				icon = R.drawable.ic_timer,
				modifier = Modifier.weight(1f),
			)
		}
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			ModernMetricCard(
				label = stringResource(R.string.stats_chapters),
				value = stats.chapters.toString(),
				icon = R.drawable.ic_auto_stories,
				modifier = Modifier.weight(1f),
			)
			ModernMetricCard(
				label = stringResource(R.string.stats_reading_streak),
				value = stats.currentStreak.toString(),
				supporting = stringResource(R.string.stats_streak_best, stats.longestStreak),
				icon = R.drawable.ic_local_fire,
				modifier = Modifier.weight(1f),
			)
		}
	}
}

@Composable
private fun ModernMetricCard(
	label: String,
	value: String,
	icon: Int,
	modifier: Modifier = Modifier,
	supporting: String? = null,
) {
	val shape = RoundedCornerShape(24.dp)
	Surface(
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
		modifier = modifier
			.heightIn(min = 122.dp)
			.border(
				width = 1.dp,
				color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
				shape = shape,
			),
	) {
		Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp)) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Box(
					modifier = Modifier
						.size(30.dp)
						.clip(CircleShape)
						.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						painter = painterResource(icon),
						contentDescription = null,
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(16.dp),
					)
				}
				Text(
					text = label,
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
				)
			}
			Spacer(Modifier.height(10.dp))
			Text(
				text = value,
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (supporting != null) {
				Spacer(Modifier.height(2.dp))
				Text(
					text = supporting,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
private fun TopPickSection(
	stats: ReadingStats,
	imageLoader: ImageLoader,
	onMangaClick: (Manga) -> Unit,
) {
	val record = stats.records.firstOrNull { it.manga != null }
	val manga = record?.manga
	val resources = LocalContext.current.resources
	Column {
		StatsSectionHeader(title = stringResource(R.string.stats_top_pick))
		if (record == null || manga == null) {
			EmptyMiniCard()
		} else {
			val shape = RoundedCornerShape(28.dp)
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = STATS_PADDING)
					.clip(shape)
					.background(
						Brush.linearGradient(
							listOf(
								MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f),
								MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f),
							),
						),
					)
					.border(
						width = 1.dp,
						color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
						shape = shape,
					)
					.clickable { onMangaClick(manga) }
					.padding(14.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(15.dp),
			) {
				Box(
					modifier = Modifier
						.size(width = 82.dp, height = 112.dp)
						.clip(RoundedCornerShape(18.dp))
						.background(MaterialTheme.colorScheme.surfaceContainerHighest),
				) {
					MangaCover(manga, imageLoader)
				}
				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.Center,
				) {
					Text(
						text = manga.title,
						style = MaterialTheme.typography.titleLarge,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onSurface,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
					Spacer(Modifier.height(7.dp))
					Text(
						text = stringResource(
							if (record.isNovel) R.string.stats_scope_novel else R.string.stats_scope_manga,
						),
						style = MaterialTheme.typography.labelLarge,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.primary,
					)
					Spacer(Modifier.height(4.dp))
					Text(
						text = stringResource(
							R.string.stats_spent,
							formatDurationShort(resources, record.duration),
						),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}

@Composable
private fun ReadingHeatmapCard(days: List<StatsHeatmapDay>) {
	val today = remember { LocalDate.now() }
	val byDay = remember(days) { days.associateBy { it.epochDay } }
	val todayStats = byDay[today.toEpochDay()]
	Column {
		StatsSectionHeader(title = stringResource(R.string.stats_reading_heatmap))
		StatsCard {
			Text(
				text = stringResource(R.string.stats_heatmap_subtitle),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(14.dp))
			ReadingHeatmapGrid(days)
			Spacer(Modifier.height(14.dp))
			Surface(
				shape = RoundedCornerShape(18.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.58f),
			) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 14.dp, vertical = 11.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = today.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault())),
						style = MaterialTheme.typography.bodyMedium,
						fontWeight = FontWeight.SemiBold,
						modifier = Modifier.weight(1f),
					)
					Text(
						text = if ((todayStats?.sessions ?: 0) == 0) {
							stringResource(R.string.stats_no_activity)
						} else {
							stringResource(R.string.stats_activity_count, todayStats?.sessions ?: 0)
						},
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
	}
}

@Composable
private fun ReadingHeatmapGrid(days: List<StatsHeatmapDay>) {
	val today = remember { LocalDate.now() }
	val start = remember(today) { today.minusWeeks(19).minusDays(today.dayOfWeek.value.toLong() - 1L) }
	val values = remember(days) { days.associateBy { it.epochDay } }
	val maxDuration = remember(days) { days.maxOfOrNull { it.duration }?.coerceAtLeast(1L) ?: 1L }
	val empty = MaterialTheme.colorScheme.surfaceContainerHighest
	val active = MaterialTheme.colorScheme.primary
	Canvas(
		modifier = Modifier
			.fillMaxWidth()
			.height(126.dp),
	) {
		val columns = 20
		val rows = 7
		val gap = 3.5.dp.toPx()
		val cell = minOf(
			(size.width - gap * (columns - 1)) / columns,
			(size.height - gap * (rows - 1)) / rows,
		)
		val gridWidth = cell * columns + gap * (columns - 1)
		val left = (size.width - gridWidth) / 2f
		for (column in 0 until columns) {
			for (row in 0 until rows) {
				val day = start.plusDays((column * rows + row).toLong())
				val value = values[day.toEpochDay()]?.duration ?: 0L
				val ratio = (value.toFloat() / maxDuration).coerceIn(0f, 1f)
				val color = if (value <= 0L) {
					empty.copy(alpha = 0.36f)
				} else {
					lerp(active.copy(alpha = 0.22f), active, ratio.coerceAtLeast(0.2f))
				}
				drawRoundRect(
					color = color,
					topLeft = Offset(
						x = left + column * (cell + gap),
						y = row * (cell + gap),
					),
					size = Size(cell, cell),
					cornerRadius = CornerRadius(cell * 0.24f, cell * 0.24f),
				)
			}
		}
	}
}

@Composable
private fun InsightCard(
	title: String,
	items: List<StatsInsight>,
	icon: Int,
) {
	val max = items.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
	val shape = RoundedCornerShape(26.dp)
	Surface(
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING)
			.border(
				width = 1.dp,
				color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f),
				shape = shape,
			),
	) {
		Column(modifier = Modifier.padding(17.dp)) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(9.dp),
			) {
				Box(
					modifier = Modifier
						.size(34.dp)
						.clip(CircleShape)
						.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						painter = painterResource(icon),
						contentDescription = null,
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(18.dp),
					)
				}
				Text(
					text = title,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Bold,
				)
			}
			Spacer(Modifier.height(14.dp))
			if (items.isEmpty()) {
				Text(
					text = stringResource(R.string.stats_unlock_insight),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			} else {
				items.take(4).forEachIndexed { index, insight ->
					if (index > 0) Spacer(Modifier.height(12.dp))
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(
							text = insight.label,
							style = MaterialTheme.typography.bodyMedium,
							fontWeight = FontWeight.SemiBold,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f),
						)
						Text(
							text = stringResource(R.string.stats_title_count_short, insight.count),
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					Spacer(Modifier.height(5.dp))
					LinearProgressIndicator(
						progress = { insight.count.toFloat() / max },
						modifier = Modifier
							.fillMaxWidth()
							.height(6.dp)
							.clip(RoundedCornerShape(6.dp)),
					)
				}
			}
		}
	}
}

@Composable
private fun RevisitedRow(
	record: StatsRecord,
	imageLoader: ImageLoader,
	onMangaClick: (Manga) -> Unit,
) {
	val manga = record.manga ?: return
	val shape = RoundedCornerShape(24.dp)
	Surface(
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.76f),
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING)
			.border(
				width = 1.dp,
				color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f),
				shape = shape,
			)
			.clickable { onMangaClick(manga) },
	) {
		Row(
			modifier = Modifier.padding(12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(13.dp),
		) {
			Box(
				modifier = Modifier
					.size(width = 54.dp, height = 72.dp)
					.clip(RoundedCornerShape(14.dp))
					.background(MaterialTheme.colorScheme.surfaceContainerHighest),
			) {
				MangaCover(manga, imageLoader)
			}
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = manga.title,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				Spacer(Modifier.height(4.dp))
				Text(
					text = buildString {
						append(
							stringResource(
								if (record.isNovel) R.string.stats_scope_novel else R.string.stats_scope_manga,
							),
						)
						append(" · ")
						append(stringResource(R.string.stats_revisited_times, record.sessionCount))
					},
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun EmptyMiniCard() {
	val shape = RoundedCornerShape(24.dp)
	Surface(
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING)
			.border(
				width = 1.dp,
				color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f),
				shape = shape,
			),
	) {
		Text(
			text = stringResource(R.string.stats_no_activity),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(18.dp),
		)
	}
}

@Composable
private fun StatsEmptyState() {
	StatsCard {
		Column(
			modifier = Modifier.fillMaxWidth(),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Icon(
				painter = painterResource(R.drawable.ic_empty_history),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
				modifier = Modifier.size(52.dp),
			)
			Spacer(Modifier.height(12.dp))
			Text(
				text = stringResource(R.string.stats_empty_title),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
				textAlign = TextAlign.Center,
			)
			Spacer(Modifier.height(5.dp))
			Text(
				text = stringResource(R.string.stats_empty_dashboard),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
			)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFilterChip(
	categories: List<FavouriteCategory>,
	selected: Set<Long>,
	onToggle: (FavouriteCategory) -> Unit,
	onClear: () -> Unit,
	modifier: Modifier = Modifier,
) {
	var sheetVisible by remember { mutableStateOf(false) }
	val label = when (selected.size) {
		0 -> stringResource(R.string.stats_categories_all)
		1 -> categories.firstOrNull { it.id in selected }?.title
			?: stringResource(R.string.stats_categories_all)
		else -> stringResource(R.string.stats_categories_selected_short, selected.size)
	}
	FilterChip(
		selected = selected.isNotEmpty(),
		onClick = { sheetVisible = true },
		label = {
			Text(
				text = label,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		},
		trailingIcon = {
			Icon(
				painter = painterResource(R.drawable.ic_expand_more),
				contentDescription = null,
				modifier = Modifier.size(FilterChipDefaults.IconSize),
			)
		},
		modifier = modifier,
	)
	if (sheetVisible) {
		CategoryFilterSheet(
			categories = categories,
			selected = selected,
			onToggle = onToggle,
			onClear = onClear,
			onDismiss = { sheetVisible = false },
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFilterSheet(
	categories: List<FavouriteCategory>,
	selected: Set<Long>,
	onToggle: (FavouriteCategory) -> Unit,
	onClear: () -> Unit,
	onDismiss: () -> Unit,
) {
	var query by remember { mutableStateOf("") }
	val visible = remember(categories, query) {
		val needle = query.trim()
		if (needle.isEmpty()) {
			categories
		} else {
			categories.filter { it.title.contains(needle, ignoreCase = true) }
		}
	}
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
		shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 20.dp),
		) {
			Text(
				text = stringResource(R.string.stats_filter_categories),
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight.Bold,
			)
			Spacer(Modifier.height(12.dp))
			OutlinedTextField(
				value = query,
				onValueChange = { query = it },
				label = { Text(stringResource(R.string.search)) },
				singleLine = true,
				modifier = Modifier.fillMaxWidth(),
			)
			Spacer(Modifier.height(12.dp))
			LazyColumn(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 520.dp),
				contentPadding = PaddingValues(bottom = 28.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				if (query.isBlank()) {
					item("all") {
						CategorySheetRow(
							title = stringResource(R.string.stats_categories_all),
							selected = selected.isEmpty(),
							onClick = onClear,
						)
					}
				}
				items(visible, key = { it.id }) { category ->
					CategorySheetRow(
						title = category.title,
						selected = category.id in selected,
						onClick = { onToggle(category) },
					)
				}
			}
		}
	}
}

@Composable
private fun CategorySheetRow(
	title: String,
	selected: Boolean,
	onClick: () -> Unit,
) {
	val shape = RoundedCornerShape(18.dp)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clip(shape)
			.background(
				if (selected) {
					MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
				} else {
					MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
				},
			)
			.clickable(onClick = onClick)
			.padding(horizontal = 16.dp, vertical = 14.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.bodyLarge,
			fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
			modifier = Modifier.weight(1f),
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
		if (selected) {
			Icon(
				painter = painterResource(R.drawable.ic_check),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
			)
		}
	}
}

@Composable
private fun MangaCover(
	manga: Manga,
	imageLoader: ImageLoader,
) {
	val context = LocalContext.current
	AsyncImage(
		model = remember(manga) {
			ImageRequest.Builder(context)
				.data(manga.coverUrl)
				.crossfade(true)
				.mangaSourceExtra(manga.source)
				.stableMangaCoverKey(manga, manga.coverUrl)
				.build()
		},
		imageLoader = imageLoader,
		contentDescription = null,
		contentScale = ContentScale.Crop,
		modifier = Modifier.fillMaxSize(),
	)
}
