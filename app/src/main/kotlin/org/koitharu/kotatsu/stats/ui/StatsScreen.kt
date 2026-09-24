package org.koitharu.kotatsu.stats.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
 * Miyorare's unified reading dashboard. Overview/Manga/Novel are views over the same local stats
 * store; mature-content privacy changes only what is revealed, never creates a parallel history.
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
			contentPadding = PaddingValues(top = 10.dp, bottom = bottomInset + 32.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp),
		) {
			item("intro") { StatsIntroCard() }
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
			item("level") {
				ReaderLevelCard(stats = stats, scope = scope)
			}
			item("metrics") {
				MetricsGrid(stats)
			}
			if (stats.privateTitles > 0 && matureMode == StatsMatureMode.PRIVATE) {
				item("private-notice") {
					PrivateNotice(stats.privateTitles)
				}
			}
			if (stats.isEmpty) {
				item("empty") { StatsEmptyState() }
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
			item("insights") {
				InsightsRow(
					genres = stats.topGenres,
					formats = stats.formatBreakdown,
				)
			}
			if (stats.revisited.isNotEmpty()) {
				item("revisited-header") {
					StatsSectionHeader(title = stringResource(R.string.stats_revisited_most))
				}
				items(stats.revisited, key = { record ->
					when {
						record.isPrivate -> "private"
						record.manga != null -> "manga-${record.manga.id}"
						else -> "other-${record.firstReadAt}"
					}
				}) { record ->
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
private fun StatsIntroCard() {
	Surface(
		shape = RoundedCornerShape(30.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(14.dp),
		) {
			Box(
				modifier = Modifier
					.size(44.dp)
					.clip(CircleShape)
					.background(MaterialTheme.colorScheme.primaryContainer),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painter = painterResource(R.drawable.ic_timelapse),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onPrimaryContainer,
					modifier = Modifier.size(23.dp),
				)
			}
			Column {
				Text(
					text = stringResource(R.string.stats_dashboard_title),
					style = MaterialTheme.typography.titleLarge,
					fontWeight = FontWeight.Bold,
				)
				Text(
					text = stringResource(R.string.stats_dashboard_subtitle),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun ScopeSelector(
	selected: StatsContentScope,
	onSelect: (StatsContentScope) -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		StatsContentScope.entries.forEach { scope ->
			FilterChip(
				selected = selected == scope,
				onClick = { onSelect(scope) },
				label = {
					Text(
						text = stringResource(scope.titleRes),
						textAlign = TextAlign.Center,
						modifier = Modifier.fillMaxWidth(),
					)
				},
				modifier = Modifier.weight(1f),
			)
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
	LazyRow(
		modifier = Modifier.fillMaxWidth(),
		contentPadding = PaddingValues(horizontal = STATS_PADDING),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		item("privacy") {
			MatureModeChip(
				mode = matureMode,
				onSelect = onMatureModeChange,
			)
		}
		if (categories.isNotEmpty()) {
			item("category") {
				CategoryDropdownChip(
					categories = categories,
					selected = selectedCategories,
					onToggle = onCategoryToggle,
					onClear = onCategoriesClear,
				)
			}
		}
		items(StatsPeriod.entries, key = { it.name }) { entry ->
			FilterChip(
				selected = entry == period,
				onClick = { onPeriodChange(entry) },
				label = { Text(stringResource(entry.titleResId)) },
			)
		}
	}
}

@Composable
private fun MatureModeChip(
	mode: StatsMatureMode,
	onSelect: (StatsMatureMode) -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	Box {
		FilterChip(
			selected = mode != StatsMatureMode.INCLUDE,
			onClick = { expanded = true },
			leadingIcon = {
				Icon(
					painter = painterResource(R.drawable.ic_lock),
					contentDescription = null,
					modifier = Modifier.size(FilterChipDefaults.IconSize),
				)
			},
			label = { Text(stringResource(mode.labelRes)) },
			trailingIcon = {
				Icon(
					painter = painterResource(R.drawable.ic_expand_more),
					contentDescription = null,
					modifier = Modifier.size(FilterChipDefaults.IconSize),
				)
			},
		)
		DropdownMenu(
			expanded = expanded,
			onDismissRequest = { expanded = false },
		) {
			StatsMatureMode.entries.forEach { option ->
				DropdownMenuItem(
					text = {
						Column {
							Text(stringResource(option.labelRes))
							Text(
								text = stringResource(option.summaryRes),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					},
					trailingIcon = {
						if (option == mode) {
							Icon(painterResource(R.drawable.ic_check), contentDescription = null)
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
private fun ReaderLevelCard(stats: ReadingStats, scope: StatsContentScope) {
	val xp = stats.lifetimeXp
	val tier = remember(xp, scope) { ReaderTier.resolve(xp, scope) }
	val next = tier.nextThreshold
	val progress = if (next == null) {
		1f
	} else {
		((xp - tier.threshold).toFloat() / (next - tier.threshold).coerceAtLeast(1)).coerceIn(0f, 1f)
	}
	StatsCard {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Box(
				modifier = Modifier
					.size(46.dp)
					.clip(CircleShape)
					.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painter = painterResource(
						if (scope == StatsContentScope.NOVEL) R.drawable.ic_auto_stories else R.drawable.ic_book_page,
					),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(25.dp),
				)
			}
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(tier.titleRes),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Bold,
					color = MaterialTheme.colorScheme.primary,
				)
				Text(
					text = stringResource(scope.profileRes),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Surface(
				shape = RoundedCornerShape(18.dp),
				color = MaterialTheme.colorScheme.primaryContainer,
			) {
				Text(
					text = stringResource(R.string.stats_xp, xp),
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.Bold,
					color = MaterialTheme.colorScheme.onPrimaryContainer,
					modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
				)
			}
		}
		Spacer(Modifier.height(16.dp))
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = stringResource(R.string.stats_level_progress),
				style = MaterialTheme.typography.labelMedium,
				fontWeight = FontWeight.SemiBold,
				modifier = Modifier.weight(1f),
			)
			Text(
				text = if (next == null) "$xp XP" else "$xp / $next XP",
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Spacer(Modifier.height(7.dp))
		LinearProgressIndicator(
			progress = { progress },
			modifier = Modifier
				.fillMaxWidth()
				.height(8.dp)
				.clip(RoundedCornerShape(8.dp)),
		)
	}
}

private val StatsContentScope.profileRes: Int
	@StringRes get() = when (this) {
		StatsContentScope.OVERVIEW -> R.string.stats_profile_overview
		StatsContentScope.MANGA -> R.string.stats_profile_manga
		StatsContentScope.NOVEL -> R.string.stats_profile_novel
	}

private data class ReaderTier(
	val threshold: Int,
	val nextThreshold: Int?,
	@StringRes val titleRes: Int,
) {
	companion object {
		fun resolve(xp: Int, scope: StatsContentScope): ReaderTier {
			val thresholds = intArrayOf(0, 25, 100, 300, 750, 1500)
			val index = thresholds.indexOfLast { xp >= it }.coerceAtLeast(0)
			val title = when (index) {
				0 -> R.string.stats_rank_rising_explorer
				1 -> R.string.stats_rank_page_wanderer
				2 -> when (scope) {
					StatsContentScope.OVERVIEW -> R.string.stats_rank_reading_regular
					StatsContentScope.MANGA -> R.string.stats_rank_manga_tracker
					StatsContentScope.NOVEL -> R.string.stats_rank_story_seeker
				}
				3 -> R.string.stats_rank_story_sage
				4 -> R.string.stats_rank_archive_master
				else -> R.string.stats_rank_library_legend
			}
			return ReaderTier(
				threshold = thresholds[index],
				nextThreshold = thresholds.getOrNull(index + 1),
				titleRes = title,
			)
		}
	}
}

@Composable
private fun MetricsGrid(stats: ReadingStats) {
	val resources = LocalContext.current.resources
	Column(
		modifier = Modifier.padding(horizontal = STATS_PADDING),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			CompactMetric(
				label = stringResource(R.string.stats_read_time),
				value = formatDurationShort(resources, stats.totalDuration),
				icon = R.drawable.ic_timelapse,
				modifier = Modifier.weight(1f),
			)
			CompactMetric(
				label = stringResource(R.string.stats_titles_read),
				value = stats.titleCount.toString(),
				icon = R.drawable.ic_book_page,
				modifier = Modifier.weight(1f),
			)
			CompactMetric(
				label = stringResource(R.string.stats_days),
				value = stats.activeDays.toString(),
				icon = R.drawable.ic_grid,
				modifier = Modifier.weight(1f),
			)
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			CompactMetric(
				label = stringResource(R.string.stats_avg_session),
				value = formatDurationShort(resources, stats.averageSessionDuration),
				icon = R.drawable.ic_timer,
				modifier = Modifier.weight(1f),
			)
			CompactMetric(
				label = stringResource(R.string.stats_chapters),
				value = stats.chapters.toString(),
				icon = R.drawable.ic_auto_stories,
				modifier = Modifier.weight(1f),
			)
			CompactMetric(
				label = stringResource(R.string.stats_reading_streak),
				value = stats.currentStreak.toString(),
				icon = R.drawable.ic_local_fire,
				modifier = Modifier.weight(1f),
			)
		}
	}
}

@Composable
private fun CompactMetric(
	label: String,
	value: String,
	icon: Int,
	modifier: Modifier = Modifier,
) {
	Surface(
		shape = RoundedCornerShape(22.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
		modifier = modifier,
	) {
		Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(5.dp),
			) {
				Icon(
					painter = painterResource(icon),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(14.dp),
				)
				Text(
					text = label,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			Spacer(Modifier.height(5.dp))
			Text(
				text = value,
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
private fun PrivateNotice(count: Int) {
	Surface(
		shape = RoundedCornerShape(20.dp),
		color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(9.dp),
		) {
			Icon(
				painter = painterResource(R.drawable.ic_lock),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSecondaryContainer,
				modifier = Modifier.size(18.dp),
			)
			Text(
				text = stringResource(R.string.stats_private_notice, count),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSecondaryContainer,
			)
		}
	}
}

@Composable
private fun TopPickSection(
	stats: ReadingStats,
	imageLoader: ImageLoader,
	onMangaClick: (Manga) -> Unit,
) {
	val record = stats.records.firstOrNull { it.manga != null || it.isPrivate }
	Column {
		StatsSectionHeader(title = stringResource(R.string.stats_top_pick))
		if (record == null) {
			EmptyMiniCard()
		} else {
			val manga = record.manga
			val modifier = if (manga != null) {
				Modifier.clickable { onMangaClick(manga) }
			} else {
				Modifier
			}
			Surface(
				shape = RoundedCornerShape(26.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = STATS_PADDING)
					.then(modifier),
			) {
				Row(
					modifier = Modifier.height(96.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Column(
						modifier = Modifier
							.weight(1f)
							.padding(horizontal = 18.dp),
					) {
						Text(
							text = if (record.isPrivate) {
								stringResource(R.string.stats_private_reading).uppercase(Locale.getDefault())
							} else {
								stringResource(R.string.stats_top_pick).uppercase(Locale.getDefault())
							},
							style = MaterialTheme.typography.labelSmall,
							fontWeight = FontWeight.Bold,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						Spacer(Modifier.height(5.dp))
						Text(
							text = if (record.isPrivate) {
								stringResource(R.string.stats_hidden_title)
							} else {
								manga?.title.orEmpty()
							},
							style = MaterialTheme.typography.titleMedium,
							fontWeight = FontWeight.Bold,
							maxLines = 2,
							overflow = TextOverflow.Ellipsis,
						)
						Spacer(Modifier.height(3.dp))
						Text(
							text = stringResource(
								R.string.stats_spent,
								formatDurationShort(LocalContext.current.resources, record.duration),
							),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					Box(
						modifier = Modifier
							.width(116.dp)
							.fillMaxSize()
							.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
						contentAlignment = Alignment.Center,
					) {
						if (manga != null) {
							MangaCover(manga, imageLoader)
						} else {
							Icon(
								painter = painterResource(R.drawable.ic_lock),
								contentDescription = null,
								tint = MaterialTheme.colorScheme.primary,
								modifier = Modifier.size(32.dp),
							)
						}
					}
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
			ReadingHeatmapGrid(days)
			Spacer(Modifier.height(14.dp))
			Surface(
				shape = RoundedCornerShape(18.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
				modifier = Modifier.fillMaxWidth(),
			) {
				Row(
					modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
					empty.copy(alpha = 0.58f)
				} else {
					lerp(active.copy(alpha = 0.28f), active, ratio.coerceAtLeast(0.2f))
				}
				drawRoundRect(
					color = color,
					topLeft = Offset(
						x = left + column * (cell + gap),
						y = row * (cell + gap),
					),
					size = Size(cell, cell),
					cornerRadius = CornerRadius(cell * 0.22f, cell * 0.22f),
				)
			}
		}
	}
}

@Composable
private fun InsightsRow(
	genres: List<StatsInsight>,
	formats: List<StatsInsight>,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING),
		horizontalArrangement = Arrangement.spacedBy(10.dp),
		verticalAlignment = Alignment.Top,
	) {
		InsightCard(
			title = stringResource(R.string.stats_most_read_genres),
			items = genres,
			modifier = Modifier.weight(1f),
		)
		InsightCard(
			title = stringResource(R.string.stats_format_breakdown),
			items = formats,
			modifier = Modifier.weight(1f),
		)
	}
}

@Composable
private fun InsightCard(
	title: String,
	items: List<StatsInsight>,
	modifier: Modifier = Modifier,
) {
	val max = items.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
	Surface(
		shape = RoundedCornerShape(24.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
		modifier = modifier,
	) {
		Column(modifier = Modifier.padding(14.dp)) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(7.dp),
			) {
				Icon(
					painter = painterResource(R.drawable.ic_grid),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(18.dp),
				)
				Text(
					text = title,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Bold,
					maxLines = 2,
				)
			}
			Spacer(Modifier.height(12.dp))
			if (items.isEmpty()) {
				Text(
					text = stringResource(R.string.stats_unlock_insight),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			} else {
				items.take(3).forEachIndexed { index, insight ->
					if (index > 0) Spacer(Modifier.height(10.dp))
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(
							text = insight.label,
							style = MaterialTheme.typography.bodySmall,
							fontWeight = FontWeight.SemiBold,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f),
						)
						Text(
							text = insight.count.toString(),
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					Spacer(Modifier.height(4.dp))
					LinearProgressIndicator(
						progress = { insight.count.toFloat() / max },
						modifier = Modifier
							.fillMaxWidth()
							.height(5.dp)
							.clip(RoundedCornerShape(5.dp)),
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
	val manga = record.manga
	val clickable = if (manga != null) Modifier.clickable { onMangaClick(manga) } else Modifier
	Surface(
		shape = RoundedCornerShape(24.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING)
			.then(clickable),
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
					.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
				contentAlignment = Alignment.Center,
			) {
				if (manga != null) {
					MangaCover(manga, imageLoader)
				} else {
					Icon(
						painter = painterResource(R.drawable.ic_lock),
						contentDescription = null,
						tint = MaterialTheme.colorScheme.primary,
					)
				}
			}
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = if (record.isPrivate) {
						stringResource(R.string.stats_hidden_title)
					} else {
						manga?.title.orEmpty()
					},
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
	Surface(
		shape = RoundedCornerShape(24.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f),
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING),
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
				modifier = Modifier.size(56.dp),
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

@Composable
private fun CategoryDropdownChip(
	categories: List<FavouriteCategory>,
	selected: Set<Long>,
	onToggle: (FavouriteCategory) -> Unit,
	onClear: () -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	val label = when (selected.size) {
		0 -> stringResource(R.string.stats_categories_all)
		1 -> categories.firstOrNull { it.id in selected }?.title
			?: stringResource(R.string.stats_categories_all)
		else -> stringResource(R.string.stats_title_count_short, selected.size)
	}
	Box {
		FilterChip(
			selected = selected.isNotEmpty(),
			onClick = { expanded = true },
			label = { Text(label) },
			trailingIcon = {
				Icon(
					painter = painterResource(R.drawable.ic_expand_more),
					contentDescription = null,
					modifier = Modifier.size(FilterChipDefaults.IconSize),
				)
			},
		)
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			DropdownMenuItem(
				text = { Text(stringResource(R.string.stats_categories_all)) },
				trailingIcon = {
					if (selected.isEmpty()) {
						Icon(painterResource(R.drawable.ic_check), contentDescription = null)
					}
				},
				onClick = {
					onClear()
					expanded = false
				},
			)
			categories.forEach { category ->
				DropdownMenuItem(
					text = { Text(category.title) },
					trailingIcon = {
						if (category.id in selected) {
							Icon(painterResource(R.drawable.ic_check), contentDescription = null)
						}
					},
					onClick = { onToggle(category) },
				)
			}
		}
	}
}

@Composable
private fun MangaCover(manga: Manga, imageLoader: ImageLoader) {
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
