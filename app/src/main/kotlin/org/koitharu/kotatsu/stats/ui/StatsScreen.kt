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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import org.koitharu.kotatsu.readerjourney.domain.ReaderAchievementId
import org.koitharu.kotatsu.readerjourney.domain.ReaderAchievementProgress
import org.koitharu.kotatsu.readerjourney.domain.ReaderAchievementRarity
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticPolicy
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyRules
import org.koitharu.kotatsu.readerjourney.domain.ReaderProfileSettings
import org.koitharu.kotatsu.readerjourney.domain.ReadingPersonality
import org.koitharu.kotatsu.readerjourney.domain.ReaderRank
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeRegistry
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVariant
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVisualRegistry
import org.koitharu.kotatsu.readerjourney.theme.ReferenceRankThemeVisualSpec
import org.koitharu.kotatsu.readerjourney.ui.ReferenceRankThemeBadge
import org.koitharu.kotatsu.readerjourney.ui.ReferenceRankThemeCard
import org.koitharu.kotatsu.readerjourney.ui.ReferenceRankThemeProgress
import org.koitharu.kotatsu.readerjourney.ui.ReferenceRankThemeWallpaper
import org.koitharu.kotatsu.readerjourney.ui.titleRes
import org.koitharu.kotatsu.stats.domain.ReadingStats
import org.koitharu.kotatsu.stats.domain.StatsContentScope
import org.koitharu.kotatsu.stats.domain.StatsHeatmapDay
import org.koitharu.kotatsu.stats.domain.StatsInsight
import org.koitharu.kotatsu.stats.domain.StatsMatureMode
import org.koitharu.kotatsu.stats.domain.StatsPeriod
import org.koitharu.kotatsu.stats.domain.StatsRecord
import org.koitharu.kotatsu.stats.domain.YearInReview
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
	profile: ReaderProfileSettings,
	yearInReview: YearInReview,
	bottomInset: Dp,
	onPeriodChange: (StatsPeriod) -> Unit,
	onScopeChange: (StatsContentScope) -> Unit,
	onMatureModeChange: (StatsMatureMode) -> Unit,
	onCategoryToggle: (FavouriteCategory) -> Unit,
	onCategoriesClear: () -> Unit,
	onProfileUpdate: (String, ReaderAchievementId?, List<ReaderAchievementId>) -> Unit,
	onCosmeticsUpdate: (ReaderJourneyCosmeticLoadout) -> Unit,
	onShareYearInReview: (YearInReview) -> Unit,
	onMangaClick: (Manga) -> Unit,
) {
	val visibleRevisited = remember(stats.revisited, matureMode) {
		stats.revisited.filter { record -> record.manga != null }
	}
	var journeySection by rememberSaveable { mutableStateOf(ReaderJourneySection.OVERVIEW) }
	var showProfileEditor by rememberSaveable { mutableStateOf(false) }
	var showCosmeticsEditor by rememberSaveable { mutableStateOf(false) }

	LaunchedEffect(stats.isJourneyEnabled) {
		if (!stats.isJourneyEnabled && journeySection == ReaderJourneySection.ACHIEVEMENTS) {
			journeySection = ReaderJourneySection.OVERVIEW
		}
	}

	// Some Reader Journey surfaces intentionally use translucent custom colors. Those colors are
	// not exact Material color-scheme tokens, so Material cannot always infer their content color.
	// Provide the semantic foreground explicitly at screen scope to prevent host/light-theme text
	// colors from leaking into dark glass cards.
	CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
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
			item("journey-section") {
				ReaderJourneySectionSelector(
					selected = journeySection,
					showAchievements = stats.isJourneyEnabled,
					onSelect = { next ->
						journeySection = next
						if (next != ReaderJourneySection.STATISTICS) {
							onScopeChange(StatsContentScope.OVERVIEW)
						}
					},
				)
			}
			when (journeySection) {
				ReaderJourneySection.OVERVIEW -> {
					if (stats.isJourneyEnabled) {
						item("profile") {
							ReaderProfileCard(
								stats = stats,
								profile = profile,
								onEdit = { showProfileEditor = true },
								onEditCosmetics = { showCosmeticsEditor = true },
							)
						}
					}
					item("year-in-review") {
						YearInReviewCard(
							review = yearInReview,
							onShare = { onShareYearInReview(yearInReview) },
						)
					}
					item("metrics") { MetricsGrid(stats) }
					if (stats.isEmpty) {
						item("empty") { StatsEmptyState() }
					}
					item("top-pick") {
						TopPickSection(stats = stats, imageLoader = imageLoader, onMangaClick = onMangaClick)
					}
					item("heatmap") { ReadingHeatmapCard(stats.heatmapDays) }
				}

				ReaderJourneySection.STATISTICS -> {
					item("scope") {
						ScopeSelector(selected = scope, onSelect = onScopeChange)
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
					item("metrics") { MetricsGrid(stats) }
					if (stats.isEmpty) {
						item("empty") { StatsEmptyState() }
					}
					item("top-pick") {
						TopPickSection(stats = stats, imageLoader = imageLoader, onMangaClick = onMangaClick)
					}
					item("heatmap") { ReadingHeatmapCard(stats.heatmapDays) }
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
							RevisitedRow(record = record, imageLoader = imageLoader, onMangaClick = onMangaClick)
						}
					}
				}

				ReaderJourneySection.ACHIEVEMENTS -> {
					if (stats.isJourneyEnabled) {
						item("journey") { ReaderJourneyHero(stats) }
					}
					item("achievement-summary") {
						AchievementSummary(stats.achievements)
					}
					items(
						items = stats.achievements,
						key = { progress -> progress.id.name },
					) { progress ->
						AchievementCard(progress)
					}
				}
			}
		}
			if (showProfileEditor && stats.isJourneyEnabled) {
				ReaderProfileEditorSheet(
					profile = profile,
					unlockedAchievements = stats.achievements.filter { it.isUnlocked }.map { it.id },
					onDismiss = { showProfileEditor = false },
					onSave = { displayName, title, showcase ->
						onProfileUpdate(displayName, title, showcase)
						showProfileEditor = false
					},
				)
			}
			if (showCosmeticsEditor && stats.isJourneyEnabled) {
				ReaderCosmeticsEditorSheet(
					currentRank = ReaderJourneyRules.progress(stats.lifetimeXp).rank,
					loadout = profile.cosmetics,
					onDismiss = { showCosmeticsEditor = false },
					onSave = { loadout ->
						onCosmeticsUpdate(loadout)
						showCosmeticsEditor = false
					},
				)
			}
		}
	}
}

@Composable
private fun ReaderProfileCard(
	stats: ReadingStats,
	profile: ReaderProfileSettings,
	onEdit: () -> Unit,
	onEditCosmetics: () -> Unit,
) {
	val context = LocalContext.current
	val progress = ReaderJourneyRules.progress(stats.lifetimeXp)
	val selectedTitle = profile.selectedTitle
		?.takeIf { selected -> stats.achievements.any { it.id == selected && it.isUnlocked } }
	fun effectiveCosmeticRank(selected: ReaderRank?): ReaderRank? = when (profile.cosmetics.mode) {
		ReaderJourneyCosmeticMode.DEFAULT -> null
		ReaderJourneyCosmeticMode.AUTO -> progress.rank
		ReaderJourneyCosmeticMode.FULL_SET,
		ReaderJourneyCosmeticMode.CUSTOM -> selected
	}
	val frameStage = effectiveCosmeticRank(profile.cosmetics.frame)?.cosmeticStage ?: 0f
	val glowStage = effectiveCosmeticRank(profile.cosmetics.glow)?.cosmeticStage ?: 0f
	val backgroundStage = effectiveCosmeticRank(profile.cosmetics.background)?.cosmeticStage ?: 0f
	val progressStage = effectiveCosmeticRank(profile.cosmetics.progressBar)?.cosmeticStage ?: 0f
	val frameAccent = lerp(
		MaterialTheme.colorScheme.primary,
		MaterialTheme.colorScheme.tertiary,
		frameStage * 0.72f,
	)
	val backgroundAccent = lerp(
		MaterialTheme.colorScheme.primaryContainer,
		MaterialTheme.colorScheme.tertiaryContainer,
		backgroundStage * 0.68f,
	)
	val progressAccent = lerp(
		MaterialTheme.colorScheme.primary,
		MaterialTheme.colorScheme.tertiary,
		progressStage * 0.82f,
	)
	val shape = RoundedCornerShape(28.dp)
	val frameWidth = (1f + frameStage * 1.35f).dp
	val glowElevation = (1f + glowStage * 8f).dp

	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING)
			.shadow(
				elevation = glowElevation,
				shape = shape,
				clip = false,
			)
			.clip(shape)
			.background(
				Brush.linearGradient(
					listOf(
						backgroundAccent.copy(alpha = 0.64f + backgroundStage * 0.12f),
						MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f),
						MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.34f + backgroundStage * 0.18f),
					),
				),
			)
			.border(
				width = frameWidth,
				color = frameAccent.copy(alpha = 0.24f + frameStage * 0.28f),
				shape = shape,
			)
			.padding(18.dp),
	) {
		Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(14.dp),
			) {
				Box(
					modifier = Modifier
						.size(58.dp)
						.clip(CircleShape)
						.background(backgroundAccent.copy(alpha = 0.15f + backgroundStage * 0.08f))
						.border(
							width = frameWidth,
							color = frameAccent.copy(alpha = 0.30f + frameStage * 0.24f),
							shape = CircleShape,
						),
					contentAlignment = Alignment.Center,
				) {
					Text(
						text = profile.initial,
						style = MaterialTheme.typography.headlineSmall,
						fontWeight = FontWeight.Bold,
						color = frameAccent,
					)
				}
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = profile.displayName.ifBlank { stringResource(R.string.reader_journey_default_profile_name) },
						style = MaterialTheme.typography.titleLarge,
						fontWeight = FontWeight.Bold,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						text = selectedTitle?.let { stringResource(it.titleRes) }
							?: stringResource(R.string.reader_journey_no_title),
						style = MaterialTheme.typography.bodyMedium,
						color = frameAccent,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						text = stringResource(progress.rank.titleRes),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				TextButton(onClick = onEdit) {
					Text(stringResource(R.string.reader_journey_edit_profile))
				}
			}

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				ProfileFact(
					label = stringResource(R.string.reader_journey_profile_level),
					value = "Lv." + progress.level,
					modifier = Modifier.weight(1f),
				)
				ProfileFact(
					label = stringResource(R.string.reader_journey_profile_lifetime_xp),
					value = stats.lifetimeXp.toString() + " XP",
					modifier = Modifier.weight(1f),
				)
			}
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				ProfileFact(
					label = stringResource(R.string.reader_journey_profile_verified_chapters),
					value = stats.journeyCompletedChapters.toString(),
					modifier = Modifier.weight(1f),
				)
				ProfileFact(
					label = stringResource(R.string.reader_journey_profile_verified_titles),
					value = stats.journeyTitleCount.toString(),
					modifier = Modifier.weight(1f),
				)
			}

			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = stringResource(R.string.stats_level_progress),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.weight(1f),
					)
					Text(
						text = progress.xpForNextLevel?.let { next ->
							"${progress.xpIntoLevel} / $next XP"
						} ?: stringResource(R.string.reader_journey_lifetime_xp, progress.lifetimeXp),
						style = MaterialTheme.typography.labelMedium,
						fontWeight = FontWeight.SemiBold,
						color = progressAccent,
					)
				}
				LinearProgressIndicator(
					progress = { progress.levelFraction },
					color = progressAccent,
					trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.70f),
					modifier = Modifier
						.fillMaxWidth()
						.height(8.dp)
						.clip(RoundedCornerShape(8.dp)),
				)
			}

			Surface(
				shape = RoundedCornerShape(18.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.56f),
			) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 14.dp, vertical = 11.dp),
					verticalArrangement = Arrangement.spacedBy(3.dp),
				) {
					Text(
						text = stringResource(stats.readingPersonality.titleRes),
						style = MaterialTheme.typography.labelLarge,
						fontWeight = FontWeight.SemiBold,
						color = frameAccent,
					)
					Text(
						text = stringResource(
							R.string.reader_journey_profile_manga_novel,
							stats.journeyMangaChapters,
							stats.journeyNovelChapters,
						),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}

			val showcased = profile.showcase.mapNotNull { id ->
				stats.achievements.firstOrNull { it.id == id && it.isUnlocked }?.id
			}
			if (showcased.isNotEmpty()) {
				Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
					Text(
						text = stringResource(R.string.reader_journey_showcase),
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Text(
						text = showcased.joinToString(" • ") { id -> context.getString(id.titleRes) },
						style = MaterialTheme.typography.bodyMedium,
					)
				}
			}

			ReaderJourneyCosmeticPolicy.nextLockedTheme(progress.rank)?.let { next ->
				Surface(
					shape = RoundedCornerShape(18.dp),
					color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.56f),
				) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 14.dp, vertical = 11.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(10.dp),
					) {
						Column(modifier = Modifier.weight(1f)) {
							Text(
								text = stringResource(R.string.reader_journey_next_reward),
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
							Text(
								text = next.theme.displayName,
								style = MaterialTheme.typography.bodyLarge,
								fontWeight = FontWeight.SemiBold,
							)
						}
						Text(
							text = stringResource(R.string.reader_journey_unlock_at_level, next.unlockLevel),
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.primary,
						)
					}
				}
			}

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.End,
			) {
				TextButton(onClick = onEditCosmetics) {
					Text(stringResource(R.string.reader_journey_theme_collection_open))
				}
			}
		}
	}
}

private val ReaderRank.cosmeticStage: Float
	get() = if (ReaderRank.entries.size <= 1) 0f else ordinal.toFloat() / ReaderRank.entries.lastIndex.toFloat()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderCosmeticsEditorSheet(
	currentRank: ReaderRank,
	loadout: ReaderJourneyCosmeticLoadout,
	onDismiss: () -> Unit,
	onSave: (ReaderJourneyCosmeticLoadout) -> Unit,
) {
	val collection = remember(currentRank) { ReaderJourneyCosmeticPolicy.collection(currentRank) }
	val unlockedSpecs = remember(collection) { collection.filter { it.unlocked }.map { it.visualSpec } }
	val currentTheme = remember(currentRank) { RankThemeId.entries.first { it.rank == currentRank } }
	val nextReward = remember(currentRank) { ReaderJourneyCosmeticPolicy.nextLockedTheme(currentRank) }
	var draft by remember(loadout, currentRank) {
		mutableStateOf(ReaderJourneyCosmeticPolicy.sanitizeForRank(loadout, currentRank))
	}
	var previewThemeId by rememberSaveable(loadout.selectedThemeId, currentRank.name) {
		mutableStateOf(loadout.selectedThemeId ?: currentTheme.stableId)
	}

	fun switchMode(mode: ReaderJourneyCosmeticMode) {
		draft = when (mode) {
			ReaderJourneyCosmeticMode.DEFAULT -> ReaderJourneyCosmeticPolicy.equipDefault(draft, currentRank)
			ReaderJourneyCosmeticMode.AUTO -> ReaderJourneyCosmeticPolicy.equipAuto(draft, currentRank)
			ReaderJourneyCosmeticMode.FULL_SET -> ReaderJourneyCosmeticPolicy.equipFullSet(
				draft,
				currentTheme,
				currentRank,
			)
			ReaderJourneyCosmeticMode.CUSTOM -> ReaderJourneyCosmeticPolicy.equipCustom(draft, currentRank)
		}
	}

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
	) {
		LazyColumn(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(max = 720.dp),
			contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp),
		) {
			item("collection-title") {
				Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
					Text(
						text = stringResource(R.string.reader_journey_theme_collection_title),
						style = MaterialTheme.typography.headlineSmall,
						fontWeight = FontWeight.Bold,
					)
					Text(
						text = stringResource(
							R.string.reader_journey_cosmetics_summary,
							collection.count { it.unlocked },
							collection.size,
						),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					nextReward?.let { next ->
						Text(
							text = stringResource(
								R.string.reader_journey_next_reward_detail,
								next.theme.displayName,
								next.unlockLevel,
							),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.primary,
						)
					}
				}
			}

			item("collection-modes") {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(
						text = stringResource(R.string.reader_journey_cosmetic_mode),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
					)
					LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						items(ReaderJourneyCosmeticMode.entries, key = { it.name }) { mode ->
							FilterChip(
								selected = draft.mode == mode,
								onClick = { switchMode(mode) },
								label = { Text(stringResource(mode.labelRes)) },
							)
						}
					}
					Text(
						text = stringResource(
							if (draft.mode == ReaderJourneyCosmeticMode.AUTO) {
								R.string.reader_journey_cosmetics_auto_summary
							} else {
								R.string.reader_journey_cosmetic_mode_summary
							},
						),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}

			item("auto-equip-rank-theme") {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 2.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(12.dp),
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = stringResource(R.string.reader_journey_auto_equip),
							style = MaterialTheme.typography.bodyLarge,
							fontWeight = FontWeight.SemiBold,
						)
						Text(
							text = stringResource(R.string.reader_journey_auto_equip_summary),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					Switch(
						checked = draft.autoEquipNewRankTheme,
						onCheckedChange = { enabled ->
							draft = draft.copy(autoEquipNewRankTheme = enabled)
						},
					)
				}
			}

			if (draft.mode == ReaderJourneyCosmeticMode.CUSTOM) {
				item("custom-title") {
					Text(
						text = stringResource(R.string.reader_journey_mix_match),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
					)
				}

				item("custom-theme") {
					CustomThemeComponentPicker(
						label = stringResource(R.string.reader_journey_cosmetic_theme),
						specs = unlockedSpecs,
						selectedThemeId = draft.selectedThemeId,
						onSelect = { spec ->
							draft = ReaderJourneyCosmeticPolicy.sanitizeForRank(
								draft.copy(
									mode = ReaderJourneyCosmeticMode.CUSTOM,
									selectedThemeId = spec?.themeId?.stableId,
								),
								currentRank,
							)
						},
					)
				}
				item("custom-badge") {
					CustomThemeComponentPicker(
						label = stringResource(R.string.reader_journey_cosmetic_badge),
						specs = unlockedSpecs,
						selectedThemeId = unlockedSpecs.firstOrNull { it.badgeId == draft.selectedBadgeId }?.themeId?.stableId,
						onSelect = { spec ->
							draft = ReaderJourneyCosmeticPolicy.sanitizeForRank(
								draft.copy(selectedBadgeId = spec?.badgeId),
								currentRank,
							)
						},
					)
				}
				item("custom-frame") {
					CustomThemeComponentPicker(
						label = stringResource(R.string.reader_journey_cosmetic_frame),
						specs = unlockedSpecs,
						selectedThemeId = draft.frame?.toRankThemeId()?.stableId,
						onSelect = { spec ->
							draft = ReaderJourneyCosmeticPolicy.sanitizeForRank(
								draft.copy(frame = spec?.themeId?.rank),
								currentRank,
							)
						},
					)
				}
				item("custom-wallpaper") {
					CustomThemeComponentPicker(
						label = stringResource(R.string.reader_journey_cosmetic_wallpaper),
						specs = unlockedSpecs,
						selectedThemeId = unlockedSpecs.firstOrNull { it.wallpaperId == draft.selectedWallpaperId }?.themeId?.stableId,
						onSelect = { spec ->
							draft = ReaderJourneyCosmeticPolicy.sanitizeForRank(
								draft.copy(
									selectedWallpaperId = spec?.wallpaperId,
									background = spec?.themeId?.rank,
								),
								currentRank,
							)
						},
					)
				}
				item("custom-card") {
					CustomThemeComponentPicker(
						label = stringResource(R.string.reader_journey_cosmetic_card),
						specs = unlockedSpecs,
						selectedThemeId = unlockedSpecs.firstOrNull { it.cardId == draft.selectedReaderCardId }?.themeId?.stableId,
						onSelect = { spec ->
							draft = ReaderJourneyCosmeticPolicy.sanitizeForRank(
								draft.copy(selectedReaderCardId = spec?.cardId),
								currentRank,
							)
						},
					)
				}
				item("custom-glow") {
					CustomThemeComponentPicker(
						label = stringResource(R.string.reader_journey_cosmetic_glow),
						specs = unlockedSpecs,
						selectedThemeId = draft.glow?.toRankThemeId()?.stableId,
						onSelect = { spec ->
							draft = ReaderJourneyCosmeticPolicy.sanitizeForRank(
								draft.copy(glow = spec?.themeId?.rank),
								currentRank,
							)
						},
					)
				}
				item("custom-progress") {
					CustomThemeComponentPicker(
						label = stringResource(R.string.reader_journey_cosmetic_progress),
						specs = unlockedSpecs,
						selectedThemeId = unlockedSpecs.firstOrNull { it.progressId == draft.selectedProgressStyleId }?.themeId?.stableId,
						onSelect = { spec ->
							draft = ReaderJourneyCosmeticPolicy.sanitizeForRank(
								draft.copy(
									selectedProgressStyleId = spec?.progressId,
									progressBar = spec?.themeId?.rank,
								),
								currentRank,
							)
						},
					)
				}
			}

			item("collection-section") {
				Text(
					text = stringResource(R.string.reader_journey_rank_journey),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Bold,
				)
			}

			items(collection, key = { it.theme.stableId }) { entry ->
				RankThemeCollectionCard(
					entry = entry,
					isPreviewed = previewThemeId == entry.theme.stableId,
					isEquipped = draft.mode == ReaderJourneyCosmeticMode.FULL_SET &&
						draft.selectedThemeId == entry.theme.stableId,
					isFavorite = entry.theme.stableId in draft.favoriteThemeIds,
					onPreview = { previewThemeId = entry.theme.stableId },
					onFavorite = {
						draft = ReaderJourneyCosmeticPolicy.toggleFavorite(draft, entry.theme, currentRank)
					},
					onEquipFullSet = {
						draft = ReaderJourneyCosmeticPolicy.equipFullSet(draft, entry.theme, currentRank)
					},
				)
			}

			item("collection-save") {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.End,
				) {
					TextButton(onClick = onDismiss) {
						Text(stringResource(android.R.string.cancel))
					}
					Button(
						onClick = {
							onSave(ReaderJourneyCosmeticPolicy.sanitizeForRank(draft, currentRank))
						},
					) {
						Text(stringResource(R.string.save))
					}
				}
			}
		}
	}
}

@Composable
private fun RankThemeCollectionCard(
	entry: org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyThemeCollectionEntry,
	isPreviewed: Boolean,
	isEquipped: Boolean,
	isFavorite: Boolean,
	onPreview: () -> Unit,
	onFavorite: () -> Unit,
	onEquipFullSet: () -> Unit,
) {
	val darkTheme = isSystemInDarkTheme()
	val variant = if (darkTheme) RankThemeVariant.DARK else RankThemeVariant.LIGHT
	val tokens = remember(entry.theme, variant) {
		RankThemeRegistry.resolveOrDefault(entry.theme.stableId).tokens(variant)
	}
	val border = if (isEquipped) MaterialTheme.colorScheme.primary
	else MaterialTheme.colorScheme.outlineVariant
	val shape = RoundedCornerShape(22.dp)

	Surface(
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
		modifier = Modifier
			.fillMaxWidth()
			.border(
				width = if (isEquipped) 2.dp else 1.dp,
				color = border,
				shape = shape,
			),
	) {
		Column(
			modifier = Modifier.padding(14.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				ReferenceRankThemeBadge(
					spec = entry.visualSpec,
					tokens = tokens,
					modifier = Modifier.size(46.dp),
				)
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = stringResource(entry.theme.rank.titleRes),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Text(
						text = entry.theme.displayName,
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
					)
					Text(
						text = if (entry.unlocked) {
							stringResource(R.string.reader_journey_unlocked)
						} else {
							stringResource(R.string.reader_journey_unlock_at_level, entry.unlockLevel)
						},
						style = MaterialTheme.typography.bodySmall,
						color = if (entry.unlocked) MaterialTheme.colorScheme.primary
						else MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				if (isEquipped) {
					Text(
						text = stringResource(R.string.reader_journey_equipped),
						style = MaterialTheme.typography.labelMedium,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.primary,
					)
				}
			}

			if (isPreviewed) {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height(122.dp)
						.clip(RoundedCornerShape(18.dp)),
				) {
					ReferenceRankThemeWallpaper(
						spec = entry.visualSpec,
						tokens = tokens,
						modifier = Modifier.fillMaxSize(),
					)
					ReferenceRankThemeBadge(
						spec = entry.visualSpec,
						tokens = tokens,
						modifier = Modifier
							.align(Alignment.Center)
							.size(58.dp),
					)
				}
				ReferenceRankThemeProgress(
					spec = entry.visualSpec,
					tokens = tokens,
					progress = 0.68f,
					modifier = Modifier
						.fillMaxWidth()
						.height(8.dp),
				)
				ReferenceRankThemeCard(
					spec = entry.visualSpec,
					tokens = tokens,
					modifier = Modifier.fillMaxWidth(),
				) {
					Text(
						text = stringResource(R.string.reader_journey_preview_sample),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurface,
					)
				}
			}

			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.End,
			) {
				TextButton(onClick = onPreview) {
					Text(stringResource(R.string.reader_journey_preview))
				}
				if (entry.unlocked) {
					TextButton(onClick = onFavorite) {
						Text(
							stringResource(
								if (isFavorite) R.string.reader_journey_favorited
								else R.string.reader_journey_favorite,
							),
						)
					}
				}
			}
			Button(
				onClick = onEquipFullSet,
				enabled = entry.unlocked,
				modifier = Modifier.fillMaxWidth(),
			) {
				Text(stringResource(R.string.reader_journey_equip_full_set))
			}
		}
	}
}

@Composable
private fun CustomThemeComponentPicker(
	label: String,
	specs: List<ReferenceRankThemeVisualSpec>,
	selectedThemeId: String?,
	onSelect: (ReferenceRankThemeVisualSpec?) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text(
			text = label,
			style = MaterialTheme.typography.titleSmall,
			fontWeight = FontWeight.SemiBold,
		)
		LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			item("default") {
				FilterChip(
					selected = selectedThemeId == null,
					onClick = { onSelect(null) },
					label = { Text(stringResource(R.string.reader_journey_miyorare_default)) },
				)
			}
			items(specs, key = { it.themeId.stableId }) { spec ->
				FilterChip(
					selected = selectedThemeId == spec.themeId.stableId,
					onClick = { onSelect(spec) },
					label = { Text(spec.themeId.displayName) },
				)
			}
		}
	}
}

private fun ReaderRank.toRankThemeId(): RankThemeId? =
	RankThemeId.entries.firstOrNull { it.rank == this }

private val ReaderJourneyCosmeticMode.labelRes: Int
	@StringRes get() = when (this) {
		ReaderJourneyCosmeticMode.DEFAULT -> R.string.reader_journey_mode_default
		ReaderJourneyCosmeticMode.AUTO -> R.string.reader_journey_mode_auto
		ReaderJourneyCosmeticMode.FULL_SET -> R.string.reader_journey_mode_full_set
		ReaderJourneyCosmeticMode.CUSTOM -> R.string.reader_journey_mode_custom
	}

@Composable
private fun ProfileFact(label: String, value: String, modifier: Modifier = Modifier) {
	Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = value,
			style = MaterialTheme.typography.labelLarge,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderProfileEditorSheet(
	profile: ReaderProfileSettings,
	unlockedAchievements: List<ReaderAchievementId>,
	onDismiss: () -> Unit,
	onSave: (String, ReaderAchievementId?, List<ReaderAchievementId>) -> Unit,
) {
	var displayName by remember(profile) { mutableStateOf(profile.displayName) }
	var selectedTitle by remember(profile) { mutableStateOf(profile.selectedTitle?.takeIf { it in unlockedAchievements }) }
	var showcase by remember(profile) {
		mutableStateOf(profile.showcase.filter { it in unlockedAchievements }.take(3))
	}
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
	) {
		LazyColumn(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(max = 620.dp),
			contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			item("profile-title") {
				Text(
					text = stringResource(R.string.reader_journey_edit_profile),
					style = MaterialTheme.typography.headlineSmall,
					fontWeight = FontWeight.Bold,
				)
			}
			item("display-name") {
				OutlinedTextField(
					value = displayName,
					onValueChange = { displayName = it.take(40) },
					modifier = Modifier.fillMaxWidth(),
					label = { Text(stringResource(R.string.reader_journey_display_name)) },
					singleLine = true,
				)
			}
			item("title-heading") {
				Text(
					text = stringResource(R.string.reader_journey_reader_title),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
				)
			}
			item("title-none") {
				FilterChip(
					selected = selectedTitle == null,
					onClick = { selectedTitle = null },
					label = { Text(stringResource(R.string.reader_journey_no_title)) },
				)
			}
			items(unlockedAchievements, key = { "title_" + it.name }) { id ->
				FilterChip(
					selected = selectedTitle == id,
					onClick = { selectedTitle = id },
					label = { Text(stringResource(id.titleRes)) },
				)
			}
			item("showcase-heading") {
				Text(
					text = stringResource(R.string.reader_journey_showcase_hint),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
				)
			}
			items(unlockedAchievements, key = { "showcase_" + it.name }) { id ->
				val selected = id in showcase
				FilterChip(
					selected = selected,
					onClick = {
						showcase = when {
							selected -> showcase - id
							showcase.size < 3 -> showcase + id
							else -> showcase
						}
					},
					label = { Text(stringResource(id.titleRes)) },
				)
			}
			item("save") {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.End,
					verticalAlignment = Alignment.CenterVertically,
				) {
					TextButton(onClick = onDismiss) {
						Text(stringResource(android.R.string.cancel))
					}
					Button(onClick = { onSave(displayName, selectedTitle, showcase) }) {
						Text(stringResource(R.string.save))
					}
				}
			}
		}
	}
}

private val ReadingPersonality.titleRes: Int
	@StringRes get() = when (this) {
		ReadingPersonality.DISCOVERING -> R.string.reader_journey_personality_discovering
		ReadingPersonality.STEADY_READER -> R.string.reader_journey_personality_steady
		ReadingPersonality.EXPLORER -> R.string.reader_journey_personality_explorer
		ReadingPersonality.MANGA_READER -> R.string.reader_journey_personality_manga
		ReadingPersonality.NOVEL_READER -> R.string.reader_journey_personality_novel
		ReadingPersonality.BALANCED -> R.string.reader_journey_personality_balanced
	}

private enum class ReaderJourneySection {
	OVERVIEW,
	STATISTICS,
	ACHIEVEMENTS,
}

@Composable
private fun ReaderJourneySectionSelector(
	selected: ReaderJourneySection,
	showAchievements: Boolean,
	onSelect: (ReaderJourneySection) -> Unit,
) {
	val shape = RoundedCornerShape(22.dp)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING)
			.clip(shape)
			.background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f))
			.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f), shape)
			.padding(4.dp),
		horizontalArrangement = Arrangement.spacedBy(4.dp),
	) {
		val visibleEntries = ReaderJourneySection.entries.filter {
			showAchievements || it != ReaderJourneySection.ACHIEVEMENTS
		}
		visibleEntries.forEach { entry ->
			val active = entry == selected
			Box(
				modifier = Modifier
					.weight(1f)
					.clip(RoundedCornerShape(18.dp))
					.background(
						if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
						else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
					)
					.clickable { onSelect(entry) }
					.padding(horizontal = if (visibleEntries.size >= 3) 2.dp else 6.dp, vertical = 10.dp),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = stringResource(entry.titleRes),
					style = if (visibleEntries.size >= 3) {
						MaterialTheme.typography.labelMedium
					} else {
						MaterialTheme.typography.labelLarge
					},
					fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
					color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
					else MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					textAlign = TextAlign.Center,
				)
			}
		}
	}
}

private val ReaderJourneySection.titleRes: Int
	@StringRes get() = when (this) {
		ReaderJourneySection.OVERVIEW -> R.string.reader_journey_overview
		ReaderJourneySection.STATISTICS -> R.string.reader_journey_statistics
		ReaderJourneySection.ACHIEVEMENTS -> R.string.reader_journey_achievements
	}

@Composable
private fun AchievementSummary(achievements: List<ReaderAchievementProgress>) {
	val unlocked = achievements.count { it.isUnlocked }
	val total = achievements.size
	val next = achievements
		.asSequence()
		.filterNot { it.isUnlocked }
		.minWithOrNull(
			compareBy<ReaderAchievementProgress> { it.target - it.progress }
				.thenBy { it.target },
		)
	val completion = if (total == 0) 0f else unlocked.toFloat() / total

	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING),
		shape = RoundedCornerShape(26.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
		border = androidx.compose.foundation.BorderStroke(
			1.dp,
			MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
		),
	) {
		Column(
			modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Box(
					modifier = Modifier
						.size(42.dp)
						.clip(CircleShape)
						.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						painter = painterResource(R.drawable.ic_check),
						contentDescription = null,
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(21.dp),
					)
				}
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = stringResource(R.string.reader_journey_achievements),
						style = MaterialTheme.typography.titleLarge,
						fontWeight = FontWeight.Bold,
					)
					Text(
						text = stringResource(R.string.reader_journey_achievement_summary, unlocked, total),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			LinearProgressIndicator(
				progress = { completion },
				modifier = Modifier.fillMaxWidth(),
			)
			if (next == null) {
				Text(
					text = stringResource(R.string.reader_journey_all_milestones_unlocked),
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.primary,
				)
			} else {
				Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(
						text = stringResource(R.string.reader_journey_next_milestone),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Text(
						text = stringResource(next.id.titleRes),
						style = MaterialTheme.typography.titleSmall,
						fontWeight = FontWeight.SemiBold,
					)
					Text(
						text = stringResource(
							R.string.reader_journey_achievement_progress,
							next.progress,
							next.target,
						),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
	}
}

@Composable
private fun AchievementCard(progress: ReaderAchievementProgress) {
	val unlocked = progress.isUnlocked
	val accent = if (unlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING),
		shape = RoundedCornerShape(22.dp),
		color = if (unlocked) {
			MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
		} else {
			MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f)
		},
		border = androidx.compose.foundation.BorderStroke(
			1.dp,
			if (unlocked) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
			else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
		),
	) {
		Column(
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Box(
					modifier = Modifier
						.size(42.dp)
						.clip(CircleShape)
						.background(
							if (unlocked) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
							else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
						),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						painter = painterResource(
							if (unlocked) R.drawable.ic_check else R.drawable.ic_lock,
						),
						contentDescription = null,
						tint = accent,
						modifier = Modifier.size(20.dp),
					)
				}
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = stringResource(progress.id.titleRes),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
					)
					Text(
						text = stringResource(progress.id.descriptionRes),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				Surface(
					shape = RoundedCornerShape(999.dp),
					color = if (unlocked) {
						MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
					} else {
						MaterialTheme.colorScheme.surfaceContainerHighest
					},
				) {
					Text(
						text = stringResource(progress.id.rarity.titleRes),
						modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.Bold,
						color = accent,
					)
				}
			}
			LinearProgressIndicator(
				progress = { progress.fraction },
				modifier = Modifier.fillMaxWidth(),
			)
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = if (unlocked) {
						stringResource(R.string.reader_journey_achievement_unlocked)
					} else {
						stringResource(R.string.reader_journey_achievement_locked)
					},
					style = MaterialTheme.typography.labelMedium,
					fontWeight = FontWeight.SemiBold,
					color = accent,
				)
				Text(
					text = stringResource(
						R.string.reader_journey_achievement_progress,
						progress.progress,
						progress.target,
					),
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

private val ReaderAchievementId.titleRes: Int
	@StringRes get() = when (this) {
		ReaderAchievementId.FIRST_CHAPTER -> R.string.reader_journey_achievement_first_chapter
		ReaderAchievementId.CHAPTERS_100 -> R.string.reader_journey_achievement_chapters_100
		ReaderAchievementId.CHAPTERS_1000 -> R.string.reader_journey_achievement_chapters_1000
		ReaderAchievementId.FIRST_NOVEL -> R.string.reader_journey_achievement_first_novel
		ReaderAchievementId.TITLES_10 -> R.string.reader_journey_achievement_titles_10
		ReaderAchievementId.TITLES_50 -> R.string.reader_journey_achievement_titles_50
		ReaderAchievementId.STREAK_7 -> R.string.reader_journey_achievement_streak_7
		ReaderAchievementId.STREAK_30 -> R.string.reader_journey_achievement_streak_30
		ReaderAchievementId.STREAK_100 -> R.string.reader_journey_achievement_streak_100
	}

private val ReaderAchievementId.descriptionRes: Int
	@StringRes get() = when (this) {
		ReaderAchievementId.FIRST_CHAPTER -> R.string.reader_journey_achievement_first_chapter_desc
		ReaderAchievementId.CHAPTERS_100 -> R.string.reader_journey_achievement_chapters_100_desc
		ReaderAchievementId.CHAPTERS_1000 -> R.string.reader_journey_achievement_chapters_1000_desc
		ReaderAchievementId.FIRST_NOVEL -> R.string.reader_journey_achievement_first_novel_desc
		ReaderAchievementId.TITLES_10 -> R.string.reader_journey_achievement_titles_10_desc
		ReaderAchievementId.TITLES_50 -> R.string.reader_journey_achievement_titles_50_desc
		ReaderAchievementId.STREAK_7 -> R.string.reader_journey_achievement_streak_7_desc
		ReaderAchievementId.STREAK_30 -> R.string.reader_journey_achievement_streak_30_desc
		ReaderAchievementId.STREAK_100 -> R.string.reader_journey_achievement_streak_100_desc
	}

private val ReaderAchievementRarity.titleRes: Int
	@StringRes get() = when (this) {
		ReaderAchievementRarity.COMMON -> R.string.reader_journey_rarity_common
		ReaderAchievementRarity.UNCOMMON -> R.string.reader_journey_rarity_uncommon
		ReaderAchievementRarity.RARE -> R.string.reader_journey_rarity_rare
		ReaderAchievementRarity.EPIC -> R.string.reader_journey_rarity_epic
		ReaderAchievementRarity.LEGENDARY -> R.string.reader_journey_rarity_legendary
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
	val rankStage = if (ReaderRank.entries.size <= 1) {
		0f
	} else {
		journey.rank.ordinal.toFloat() / ReaderRank.entries.lastIndex.toFloat()
	}
	val frameWidth = (1f + rankStage * 1.35f).dp
	val glowElevation = (1f + rankStage * 8f).dp
	val progressAccent = lerp(accent, secondary, rankStage * 0.58f)
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING)
			.shadow(
				elevation = glowElevation,
				shape = shape,
				clip = false,
			)
			.clip(shape)
			.background(
				Brush.linearGradient(
					listOf(
						MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.76f + rankStage * 0.10f),
						MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f),
						MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.46f + rankStage * 0.20f),
					),
				),
			)
			.border(
				width = frameWidth,
				color = progressAccent.copy(alpha = 0.26f + rankStage * 0.30f),
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
						.background(progressAccent.copy(alpha = 0.13f + rankStage * 0.08f))
						.border(
							frameWidth,
							progressAccent.copy(alpha = 0.26f + rankStage * 0.24f),
							CircleShape,
						),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						painter = painterResource(R.drawable.ic_auto_stories),
						contentDescription = null,
						tint = progressAccent,
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
					color = progressAccent.copy(alpha = 0.12f + rankStage * 0.06f),
					border = androidx.compose.foundation.BorderStroke(
						frameWidth,
						progressAccent.copy(alpha = 0.18f + rankStage * 0.18f),
					),
				) {
					Text(
						text = stringResource(R.string.reader_journey_level, journey.level),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
						color = progressAccent,
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
						color = progressAccent,
					)
				}
			}
			Spacer(Modifier.height(9.dp))
			LinearProgressIndicator(
				progress = { journey.levelFraction },
				color = progressAccent,
				trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f),
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

@Composable
private fun YearInReviewCard(
	review: YearInReview,
	onShare: () -> Unit,
) {
	val resources = LocalContext.current.resources
	val shape = RoundedCornerShape(28.dp)
	Surface(
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING)
			.border(
				width = 1.dp,
				color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f),
				shape = shape,
			),
	) {
		Column(
			modifier = Modifier.padding(18.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp),
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Box(
					modifier = Modifier
						.size(40.dp)
						.clip(CircleShape)
						.background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						painter = painterResource(R.drawable.ic_auto_stories),
						contentDescription = null,
						tint = MaterialTheme.colorScheme.tertiary,
						modifier = Modifier.size(21.dp),
					)
				}
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = stringResource(R.string.reader_journey_year_in_review, review.year),
						style = MaterialTheme.typography.titleLarge,
						fontWeight = FontWeight.Bold,
					)
					Text(
						text = stringResource(R.string.reader_journey_year_in_review_private),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}

			if (review.isEmpty) {
				Text(
					text = stringResource(R.string.reader_journey_year_in_review_empty),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			} else {
				YearReviewMetricRow(
					firstLabel = stringResource(R.string.stats_read_time),
					firstValue = formatDurationShort(resources, review.totalDuration),
					secondLabel = stringResource(R.string.stats_chapters),
					secondValue = review.chapters.toString(),
				)
				YearReviewMetricRow(
					firstLabel = stringResource(R.string.stats_days),
					firstValue = review.activeDays.toString(),
					secondLabel = stringResource(R.string.stats_titles_read),
					secondValue = review.titleCount.toString(),
				)
				YearReviewMetricRow(
					firstLabel = stringResource(R.string.stats_scope_manga),
					firstValue = review.mangaChapters.toString(),
					secondLabel = stringResource(R.string.stats_scope_novel),
					secondValue = review.novelChapters.toString(),
				)
				Text(
					text = stringResource(
						R.string.reader_journey_year_in_review_streak,
						review.longestStreak,
					),
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			Button(
				onClick = onShare,
				enabled = !review.isEmpty,
				modifier = Modifier.fillMaxWidth(),
			) {
				Text(stringResource(R.string.reader_journey_share_card))
			}
		}
	}
}

@Composable
private fun YearReviewMetricRow(
	firstLabel: String,
	firstValue: String,
	secondLabel: String,
	secondValue: String,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		YearReviewMetric(
			label = firstLabel,
			value = firstValue,
			modifier = Modifier.weight(1f),
		)
		YearReviewMetric(
			label = secondLabel,
			value = secondValue,
			modifier = Modifier.weight(1f),
		)
	}
}

@Composable
private fun YearReviewMetric(
	label: String,
	value: String,
	modifier: Modifier = Modifier,
) {
	Surface(
		shape = RoundedCornerShape(18.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.62f),
		modifier = modifier,
	) {
		Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
			Text(
				text = value,
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
			)
			Text(
				text = label,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
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
