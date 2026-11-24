package org.koitharu.kotatsu.settings.journey

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.readerjourney.domain.ReaderAchievementId
import org.koitharu.kotatsu.readerjourney.domain.ReaderAchievementProgress
import org.koitharu.kotatsu.readerjourney.domain.ReaderAchievementRarity
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyRules
import org.koitharu.kotatsu.readerjourney.domain.ReaderProfileSettings
import org.koitharu.kotatsu.readerjourney.domain.ReaderRank
import org.koitharu.kotatsu.readerjourney.domain.ReadingPersonality
import org.koitharu.kotatsu.readerjourney.ui.titleRes
import org.koitharu.kotatsu.stats.domain.ReadingStats
import org.koitharu.kotatsu.stats.domain.StatsHeatmapDay
import org.koitharu.kotatsu.stats.domain.StatsInsight
import org.koitharu.kotatsu.stats.ui.formatDurationShort
import java.text.NumberFormat
import java.time.LocalDate

/** Visual models for the Settings Journey panel. One data set, several renderings. */
enum class JourneyPanelModel(
	val key: String,
	@StringRes val labelRes: Int,
) {
	NIGHT("night", R.string.journey_panel_model_night),
	DAWN("dawn", R.string.journey_panel_model_dawn),
	PASSPORT("passport", R.string.journey_panel_model_passport),
	LIBRARY_CARD("library_card", R.string.journey_panel_model_library_card);

	companion object {
		fun fromKey(key: String?): JourneyPanelModel = entries.firstOrNull { it.key == key } ?: NIGHT
	}
}

/** Tabs of the panel body, mirroring the sections of the full reading-statistics screen. */
private enum class JourneyPanelTab(
	@StringRes val titleRes: Int,
) {
	OVERVIEW(R.string.reader_journey_overview),
	STATISTICS(R.string.reader_journey_statistics),
	ACHIEVEMENTS(R.string.reader_journey_achievements),
}

/** Everything a panel model is allowed to draw. All models render this same content. */
data class JourneyPanelData(
	val displayName: String,
	val initial: String,
	val title: String?,
	val rank: ReaderRank,
	val rankName: String,
	val level: Int,
	val lifetimeXp: Long,
	val xpIntoLevel: Long,
	val xpForNextLevel: Long?,
	val levelFraction: Float,
	val personalityName: String,
	val chapters: Long,
	val titles: Long,
	val longestStreak: Int,
	val currentStreak: Int,
	val pages: Int,
	val totalDurationMs: Long,
	val sessionCount: Int,
	val activeDays: Int,
	val mangaChapters: Long,
	val novelChapters: Long,
	val achievements: List<ReaderAchievementProgress>,
	val heatmapDays: List<StatsHeatmapDay>,
	val topGenres: List<StatsInsight>,
	val topPickTitle: String?,
	val topPickChapters: Int,
	val revisitedTitle: String?,
	val revisitedChapters: Int,
	val isEmpty: Boolean,
)

@StringRes
private fun journeyAchievementTitleRes(id: ReaderAchievementId): Int = when (id) {
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

@StringRes
private fun journeyAchievementDescRes(id: ReaderAchievementId): Int = when (id) {
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

@StringRes
private fun journeyRarityTitleRes(rarity: ReaderAchievementRarity): Int = when (rarity) {
	ReaderAchievementRarity.COMMON -> R.string.reader_journey_rarity_common
	ReaderAchievementRarity.UNCOMMON -> R.string.reader_journey_rarity_uncommon
	ReaderAchievementRarity.RARE -> R.string.reader_journey_rarity_rare
	ReaderAchievementRarity.EPIC -> R.string.reader_journey_rarity_epic
	ReaderAchievementRarity.LEGENDARY -> R.string.reader_journey_rarity_legendary
}

private fun journeyRarityColor(rarity: ReaderAchievementRarity): Color = when (rarity) {
	ReaderAchievementRarity.COMMON -> Color(0xFFB9C4CE)
	ReaderAchievementRarity.UNCOMMON -> Color(0xFF7ED492)
	ReaderAchievementRarity.RARE -> Color(0xFF5EA8E8)
	ReaderAchievementRarity.EPIC -> Color(0xFFB388E8)
	ReaderAchievementRarity.LEGENDARY -> Color(0xFFE9CE7A)
}

@StringRes
private fun readingPersonalityTitleRes(personality: ReadingPersonality): Int = when (personality) {
	ReadingPersonality.DISCOVERING -> R.string.reader_journey_personality_discovering
	ReadingPersonality.STEADY_READER -> R.string.reader_journey_personality_steady
	ReadingPersonality.EXPLORER -> R.string.reader_journey_personality_explorer
	ReadingPersonality.MANGA_READER -> R.string.reader_journey_personality_manga
	ReadingPersonality.NOVEL_READER -> R.string.reader_journey_personality_novel
	ReadingPersonality.BALANCED -> R.string.reader_journey_personality_balanced
}

@Composable
private fun rememberJourneyPanelData(
	stats: ReadingStats,
	profile: ReaderProfileSettings,
): JourneyPanelData {
	val progress = ReaderJourneyRules.progress(stats.lifetimeXp)
	val selectedTitle = profile.selectedTitle?.takeIf { selected ->
		stats.achievements.any { it.id == selected && it.isUnlocked }
	}
	val topPick = stats.records.maxByOrNull { it.chapters }
	val revisited = stats.revisited.firstOrNull()
	return JourneyPanelData(
		displayName = profile.displayName.ifBlank {
			stringResource(R.string.reader_journey_default_profile_name)
		},
		initial = profile.initial,
		title = selectedTitle?.let { stringResource(journeyAchievementTitleRes(it)) },
		rank = progress.rank,
		rankName = stringResource(progress.rank.titleRes),
		level = progress.level,
		lifetimeXp = stats.lifetimeXp,
		xpIntoLevel = progress.xpIntoLevel,
		xpForNextLevel = progress.xpForNextLevel,
		levelFraction = progress.levelFraction,
		personalityName = stringResource(readingPersonalityTitleRes(stats.readingPersonality)),
		chapters = stats.journeyCompletedChapters,
		titles = stats.journeyTitleCount,
		longestStreak = stats.longestStreak,
		currentStreak = stats.currentStreak,
		pages = stats.pages,
		totalDurationMs = stats.totalDuration,
		sessionCount = stats.sessionCount,
		activeDays = stats.activeDays,
		mangaChapters = stats.journeyMangaChapters,
		novelChapters = stats.journeyNovelChapters,
		achievements = stats.achievements,
		heatmapDays = stats.heatmapDays,
		topGenres = stats.topGenres,
		topPickTitle = topPick?.manga?.title,
		topPickChapters = topPick?.chapters ?: 0,
		revisitedTitle = revisited?.manga?.title,
		revisitedChapters = revisited?.chapters ?: 0,
		isEmpty = stats.isEmpty,
	)
}

/* ------------------------------------------------------------------ style */

/**
 * Palette and shape language of one visual model, applied to the shared tab body.
 * The heroes stay hand-built per model; everything below them is shared and only
 * recolored through this style.
 */
private data class JourneyPanelStyle(
	val cardColor: Color,
	val cardBorder: Color?,
	val onCard: Color,
	val onCardMuted: Color,
	val accent: Color,
	val trackColor: Color,
	val tabContainer: Color,
	val tabSelected: Color,
	val tabOnSelected: Color,
	val tabOn: Color,
	val labelColor: Color,
	val serif: Boolean,
	val cardShape: RoundedCornerShape,
	val ladderMarker: LadderMarker,
	val ladderDone: Color,
	val ladderCurrent: Color,
	val ladderLocked: Color,
	val ladderName: Color,
	val ladderLockedName: Color,
	val ladderDivider: Color?,
	val ladderCurrentContainer: Color?,
	val heatSteps: List<Color>,
)

@Composable
private fun rememberJourneyPanelStyle(model: JourneyPanelModel): JourneyPanelStyle {
	val scheme = MaterialTheme.colorScheme
	return when (model) {
		JourneyPanelModel.NIGHT -> {
			val night = lerp(scheme.primary, Color.Black, 0.86f)
			val accent = lerp(scheme.tertiary, Color.White, 0.35f)
			JourneyPanelStyle(
				cardColor = night,
				cardBorder = null,
				onCard = Color.White,
				onCardMuted = Color.White.copy(alpha = 0.66f),
				accent = accent,
				trackColor = Color.White.copy(alpha = 0.16f),
				tabContainer = night,
				tabSelected = accent,
				tabOnSelected = Color(0xFF1A1026),
				tabOn = Color.White.copy(alpha = 0.75f),
				labelColor = scheme.onSurfaceVariant,
				serif = true,
				cardShape = RoundedCornerShape(20.dp),
				ladderMarker = LadderMarker.DOT,
				ladderDone = scheme.primary,
				ladderCurrent = scheme.tertiary,
				ladderLocked = scheme.outlineVariant,
				ladderName = scheme.onBackground,
				ladderLockedName = scheme.onSurfaceVariant,
				ladderDivider = scheme.outlineVariant.copy(alpha = 0.5f),
				ladderCurrentContainer = null,
				heatSteps = listOf(
					scheme.primary.copy(alpha = 0.14f),
					scheme.primary.copy(alpha = 0.34f),
					scheme.primary.copy(alpha = 0.55f),
					scheme.primary.copy(alpha = 0.78f),
					accent,
				),
			)
		}

		JourneyPanelModel.DAWN -> JourneyPanelStyle(
			cardColor = scheme.surface,
			cardBorder = scheme.outlineVariant.copy(alpha = 0.5f),
			onCard = scheme.onSurface,
			onCardMuted = scheme.onSurfaceVariant,
			accent = scheme.primary,
			trackColor = scheme.onSurface.copy(alpha = 0.12f),
			tabContainer = scheme.primaryContainer,
			tabSelected = scheme.primary,
			tabOnSelected = scheme.onPrimary,
			tabOn = scheme.onPrimaryContainer,
			labelColor = scheme.onSurfaceVariant,
			serif = false,
			cardShape = RoundedCornerShape(22.dp),
			ladderMarker = LadderMarker.DOT,
			ladderDone = scheme.primary,
			ladderCurrent = scheme.primary,
			ladderLocked = scheme.outlineVariant,
			ladderName = scheme.onBackground,
			ladderLockedName = scheme.onSurfaceVariant,
			ladderDivider = null,
			ladderCurrentContainer = scheme.secondaryContainer,
			heatSteps = listOf(
				scheme.primary.copy(alpha = 0.14f),
				scheme.primary.copy(alpha = 0.34f),
				scheme.primary.copy(alpha = 0.55f),
				scheme.primary.copy(alpha = 0.78f),
				scheme.primary,
			),
		)

		JourneyPanelModel.PASSPORT -> {
			val paper = Color(0xFFFFFDF8)
			JourneyPanelStyle(
				cardColor = paper,
				cardBorder = Color(0xFFD9D2C0),
				onCard = Color(0xFF33291D),
				onCardMuted = Color(0xFFA08B6D),
				accent = StampBrown,
				trackColor = Color(0xFFE9E1CD),
				tabContainer = paper,
				tabSelected = StampBrown,
				tabOnSelected = Color.White,
				tabOn = Color(0xFF8A7657),
				labelColor = Color(0xFFA08B6D),
				serif = true,
				cardShape = RoundedCornerShape(6.dp),
				ladderMarker = LadderMarker.DIAMOND,
				ladderDone = StampBrown,
				ladderCurrent = StampBrown,
				ladderLocked = scheme.outlineVariant,
				ladderName = scheme.onBackground,
				ladderLockedName = scheme.onSurfaceVariant,
				ladderDivider = scheme.outlineVariant.copy(alpha = 0.6f),
				ladderCurrentContainer = null,
				heatSteps = listOf(
					Color(0xFFEDE4CE),
					Color(0xFFD9BE8C),
					Color(0xFFC49A5C),
					Color(0xFFA4713A),
					Color(0xFF7C4F22),
				),
			)
		}

		JourneyPanelModel.LIBRARY_CARD -> JourneyPanelStyle(
			cardColor = scheme.surface,
			cardBorder = scheme.outlineVariant.copy(alpha = 0.5f),
			onCard = scheme.onSurface,
			onCardMuted = scheme.onSurfaceVariant,
			accent = scheme.primary,
			trackColor = scheme.onSurface.copy(alpha = 0.12f),
			tabContainer = scheme.primaryContainer,
			tabSelected = scheme.primary,
			tabOnSelected = scheme.onPrimary,
			tabOn = scheme.onPrimaryContainer,
			labelColor = scheme.onSurfaceVariant,
			serif = false,
			cardShape = RoundedCornerShape(16.dp),
			ladderMarker = LadderMarker.DOT,
			ladderDone = scheme.primary,
			ladderCurrent = Color(0xFFB8860B),
			ladderLocked = scheme.outlineVariant,
			ladderName = scheme.onBackground,
			ladderLockedName = scheme.onSurfaceVariant,
			ladderDivider = scheme.outlineVariant.copy(alpha = 0.5f),
			ladderCurrentContainer = null,
			heatSteps = listOf(
				scheme.primary.copy(alpha = 0.14f),
				scheme.primary.copy(alpha = 0.34f),
				scheme.primary.copy(alpha = 0.55f),
				scheme.primary.copy(alpha = 0.78f),
				scheme.primary,
			),
		)
	}
}

/* ------------------------------------------------------------------ screen */

@Composable
fun SettingsJourneyPanelScreen(
	stats: ReadingStats,
	profile: ReaderProfileSettings,
	model: JourneyPanelModel,
	onModelChange: (JourneyPanelModel) -> Unit,
	weeklyGoal: WeeklyGoalState,
	onWeeklyGoalChange: (Int) -> Unit,
	showModelPicker: Boolean = true,
	bottomInset: Dp = 0.dp,
	overviewFooter: (@Composable () -> Unit)? = null,
) {
	val data = rememberJourneyPanelData(stats, profile)
	val numberFormat = remember { NumberFormat.getNumberInstance() }
	val resources = LocalContext.current.resources
	val style = rememberJourneyPanelStyle(model)
	var tab by remember { mutableStateOf(JourneyPanelTab.OVERVIEW) }
	var goalEditorOpen by remember { mutableStateOf(false) }
	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background),
	) {
		if (showModelPicker) {
			JourneyPanelModelPicker(selected = model, onSelect = onModelChange)
		}
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 8.dp),
		) {
			JourneyPanelTabs(selected = tab, onSelect = { tab = it }, style = style)
		}
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.weight(1f)
				.verticalScroll(rememberScrollState()),
		) {
			when (model) {
				JourneyPanelModel.NIGHT -> JourneyNightHero(data, numberFormat)
				JourneyPanelModel.DAWN -> JourneyDawnHero(data, numberFormat)
				JourneyPanelModel.PASSPORT -> JourneyPassportHero(data, numberFormat)
				JourneyPanelModel.LIBRARY_CARD -> JourneyLibraryHero(data, numberFormat)
			}
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp),
			) {
				Spacer(Modifier.height(8.dp))
				when (tab) {
					JourneyPanelTab.OVERVIEW -> {
						JourneyOverviewTab(
							data = data,
							style = style,
							numberFormat = numberFormat,
							durationText = { formatDurationShort(resources, it) },
							weeklyGoal = weeklyGoal,
							onGoalClick = { goalEditorOpen = true },
						)
						overviewFooter?.invoke()
					}

					JourneyPanelTab.STATISTICS -> JourneyStatisticsTab(
						data = data,
						style = style,
						numberFormat = numberFormat,
						durationText = { formatDurationShort(resources, it) },
					)

					JourneyPanelTab.ACHIEVEMENTS -> JourneyAchievementsTab(
						data = data,
						style = style,
						numberFormat = numberFormat,
					)
				}
			}
			Spacer(Modifier.height(28.dp + bottomInset))
		}
	}
	if (goalEditorOpen) {
		JourneyGoalEditorDialog(
			current = weeklyGoal.goal,
			style = style,
			onDismiss = { goalEditorOpen = false },
			onSelect = { value ->
				onWeeklyGoalChange(value)
				goalEditorOpen = false
			},
		)
	}
}

@Composable
private fun JourneyPanelModelPicker(
	selected: JourneyPanelModel,
	onSelect: (JourneyPanelModel) -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 12.dp),
	) {
		Text(
			text = stringResource(R.string.journey_panel_model_label).uppercase(),
			style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.7.sp),
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(
			modifier = Modifier
				.padding(top = 8.dp)
				.horizontalScroll(rememberScrollState()),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			JourneyPanelModel.entries.forEach { entry ->
				val isSelected = entry == selected
				Surface(
					onClick = { onSelect(entry) },
					shape = RoundedCornerShape(999.dp),
					color = if (isSelected) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.surfaceVariant
					},
					contentColor = if (isSelected) {
						MaterialTheme.colorScheme.onPrimary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
				) {
					Text(
						text = stringResource(entry.labelRes),
						style = MaterialTheme.typography.labelLarge,
						modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun JourneyPanelTabs(
	selected: JourneyPanelTab,
	onSelect: (JourneyPanelTab) -> Unit,
	style: JourneyPanelStyle,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(22.dp))
			.background(style.tabContainer)
			.then(
				if (style.cardBorder != null) {
					Modifier.border(1.dp, style.cardBorder, RoundedCornerShape(22.dp))
				} else {
					Modifier
				},
			)
			.padding(4.dp),
		horizontalArrangement = Arrangement.spacedBy(4.dp),
	) {
		JourneyPanelTab.entries.forEach { entry ->
			val isSelected = entry == selected
			Box(
				modifier = Modifier
					.weight(1f)
					.clip(RoundedCornerShape(18.dp))
					.background(if (isSelected) style.tabSelected else Color.Transparent)
					.clickable { onSelect(entry) }
					.padding(horizontal = 2.dp, vertical = 10.dp),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = stringResource(entry.titleRes),
					style = MaterialTheme.typography.labelMedium,
					fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
					color = if (isSelected) style.tabOnSelected else style.tabOn,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
private fun JourneyGoalEditorDialog(
	current: Int,
	style: JourneyPanelStyle,
	onDismiss: () -> Unit,
	onSelect: (Int) -> Unit,
) {
	val options = listOf(0, 5, 10, 15, 20, 25, 30, 40, 50)
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(text = stringResource(R.string.journey_panel_goal_editor_title)) },
		text = {
			Column {
				options.forEach { value ->
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clip(RoundedCornerShape(10.dp))
							.clickable { onSelect(value) }
							.padding(vertical = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						RadioButton(
							selected = value == current,
							onClick = { onSelect(value) },
							colors = RadioButtonDefaults.colors(selectedColor = style.accent),
						)
						Text(
							text = if (value == 0) {
								stringResource(R.string.journey_panel_goal_off)
							} else {
								stringResource(R.string.journey_panel_goal_option, value)
							},
							style = MaterialTheme.typography.bodyLarge,
						)
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) {
				Text(text = stringResource(android.R.string.cancel))
			}
		},
	)
}


/* ------------------------------------------------------------------ shared */

private val Gold = Color(0xFFC9A86A)
private val GoldLight = Color(0xFFE8CF95)
private val StampBrown = Color(0xFFA4713A)

@Composable
private fun JourneySectionLabel(text: String, color: Color) {
	Text(
		text = text.uppercase(),
		style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.7.sp),
		color = color,
		fontWeight = FontWeight.Bold,
		modifier = Modifier.padding(top = 22.dp, bottom = 4.dp),
	)
}

@Composable
private fun JourneyXpProgressText(data: JourneyPanelData, numberFormat: NumberFormat): String =
	data.xpForNextLevel?.let { next ->
		"${numberFormat.format(data.xpIntoLevel)} / ${numberFormat.format(next)} XP"
	} ?: stringResource(R.string.reader_journey_lifetime_xp, data.lifetimeXp)

@Composable
private fun JourneyXpTargetText(data: JourneyPanelData): String =
	data.xpForNextLevel?.let {
		stringResource(R.string.journey_panel_to_level, data.level + 1)
	}.orEmpty()

@Composable
private fun JourneyCard(
	style: JourneyPanelStyle,
	modifier: Modifier = Modifier,
	content: @Composable ColumnScope.() -> Unit,
) {
	Column(
		modifier = modifier
			.fillMaxWidth()
			.clip(style.cardShape)
			.background(style.cardColor)
			.then(
				if (style.cardBorder != null) {
					Modifier.border(1.dp, style.cardBorder, style.cardShape)
				} else {
					Modifier
				},
			)
			.padding(16.dp),
		content = content,
	)
}

@Composable
private fun JourneyProgressBar(
	fraction: Float,
	accent: Color,
	track: Color,
	height: androidx.compose.ui.unit.Dp = 5.dp,
) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(height)
			.clip(RoundedCornerShape(99.dp))
			.background(track),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth(fraction.coerceIn(0f, 1f))
				.height(height)
				.background(accent),
		)
	}
}

@Composable
private fun JourneyMetricGrid(
	items: List<Pair<String, String>>,
	style: JourneyPanelStyle,
) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		items.chunked(4).forEach { rowItems ->
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				rowItems.forEach { (value, label) ->
					Column(
						modifier = Modifier
							.weight(1f)
							.clip(RoundedCornerShape(14.dp))
							.background(style.cardColor)
							.then(
								if (style.cardBorder != null) {
									Modifier.border(1.dp, style.cardBorder, RoundedCornerShape(14.dp))
								} else {
									Modifier
								},
							)
							.padding(horizontal = 4.dp, vertical = 10.dp),
						horizontalAlignment = Alignment.CenterHorizontally,
					) {
						Text(
							text = value,
							fontFamily = if (style.serif) FontFamily.Serif else FontFamily.Default,
							fontSize = 15.5.sp,
							fontWeight = FontWeight.Bold,
							color = style.onCard,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						Text(
							text = label.uppercase(),
							style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
							fontSize = 9.sp,
							color = style.onCardMuted,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.padding(top = 3.dp),
						)
					}
				}
				repeat(4 - rowItems.size) {
					Spacer(Modifier.weight(1f))
				}
			}
		}
	}
}

private enum class LadderMarker { DOT, DIAMOND }

@Composable
private fun JourneyRankLadder(
	currentRank: ReaderRank,
	marker: LadderMarker,
	doneColor: Color,
	currentColor: Color,
	lockedColor: Color,
	nameColor: Color,
	lockedNameColor: Color,
	dividerColor: Color?,
	currentContainerColor: Color? = null,
) {
	Column(modifier = Modifier.fillMaxWidth()) {
		ReaderRank.entries.forEachIndexed { index, rank ->
			val isCurrent = rank == currentRank
			val isDone = rank.minLevel < currentRank.minLevel
			val markerColor = when {
				isCurrent -> currentColor
				isDone -> doneColor
				else -> lockedColor
			}
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.then(
						if (isCurrent && currentContainerColor != null) {
							Modifier
								.background(currentContainerColor, RoundedCornerShape(14.dp))
								.padding(horizontal = 12.dp)
						} else {
							Modifier
						},
					)
					.padding(vertical = 9.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				when (marker) {
					LadderMarker.DOT -> Box(
						modifier = Modifier
							.size(8.dp)
							.clip(CircleShape)
							.background(markerColor)
							.then(
								if (isCurrent) {
									Modifier.border(2.dp, currentColor.copy(alpha = 0.35f), CircleShape)
								} else {
									Modifier
								},
							),
					)

					LadderMarker.DIAMOND -> Box(
						modifier = Modifier
							.size(8.dp)
							.rotate(45f)
							.background(markerColor),
					)
				}
				Spacer(Modifier.width(12.dp))
				Text(
					text = if (isCurrent) {
						stringResource(rank.titleRes) + " — " +
							stringResource(R.string.journey_panel_you_are_here)
					} else {
						stringResource(rank.titleRes)
					},
					fontSize = 13.5.sp,
					fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
					color = if (isCurrent || isDone) nameColor else lockedNameColor,
					modifier = Modifier.weight(1f),
				)
				Text(
					text = stringResource(R.string.reader_journey_level, rank.minLevel),
					fontSize = 12.sp,
					fontWeight = FontWeight.SemiBold,
					color = if (isCurrent) currentColor else lockedNameColor,
				)
			}
			if (dividerColor != null && index < ReaderRank.entries.lastIndex &&
				!(isCurrent && currentContainerColor != null)
			) {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height(1.dp)
						.background(dividerColor),
				)
			}
		}
	}
}

@Composable
private fun DottedLine(color: Color, modifier: Modifier = Modifier) {
	Canvas(
		modifier = modifier
			.fillMaxWidth()
			.height(2.dp),
	) {
		drawLine(
			color = color,
			start = Offset(0f, size.height / 2f),
			end = Offset(size.width, size.height / 2f),
			strokeWidth = 1.4.dp.toPx(),
			pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.5f, 5.dp.toPx())),
		)
	}
}


/* ------------------------------------------------- Model I · Malam Anggun */

@Composable
private fun JourneyNightHero(data: JourneyPanelData, numberFormat: NumberFormat) {
	// Malam Anggun keeps its dark hero, but the night itself is tinted by the active
	// theme: the hero is a deepened primary and the hairline / XP accents come from
	// the theme's tertiary, lightened enough to stay readable on the dark surface.
	val scheme = MaterialTheme.colorScheme
	val night = lerp(scheme.primary, Color.Black, 0.84f)
	val nightDeep = lerp(scheme.primary, Color.Black, 0.91f)
	val accent = lerp(scheme.tertiary, Color.White, 0.30f)
	val accentLight = lerp(scheme.tertiary, Color.White, 0.55f)
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp))
			.background(Brush.linearGradient(listOf(night, nightDeep)))
			.padding(horizontal = 24.dp, vertical = 24.dp),
	) {
		Column {
			Text(
				text = stringResource(R.string.reader_journey).uppercase(),
				style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.7.sp),
				color = accentLight,
				fontWeight = FontWeight.Bold,
			)
			Text(
				text = data.displayName,
				fontFamily = FontFamily.Serif,
				fontSize = 26.sp,
				color = Color.White,
				modifier = Modifier.padding(top = 10.dp),
			)
			data.title?.let { title ->
				Text(
					text = title,
					fontStyle = FontStyle.Italic,
					fontSize = 13.sp,
					color = Color.White.copy(alpha = 0.85f),
					modifier = Modifier.padding(top = 3.dp),
				)
			}
			Text(
				text = "${data.rankName} · ${stringResource(R.string.reader_journey_level, data.level)}" +
					" · ${data.personalityName}",
				fontSize = 13.sp,
				color = Color.White.copy(alpha = 0.7f),
				modifier = Modifier.padding(top = 4.dp),
			)
			Box(
				modifier = Modifier
					.padding(top = 16.dp)
					.fillMaxWidth()
					.height(1.dp)
					.background(
						Brush.horizontalGradient(listOf(accent, Color.Transparent)),
					),
			)
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 14.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Text(
					text = JourneyXpProgressText(data, numberFormat),
					fontSize = 12.sp,
					fontWeight = FontWeight.SemiBold,
					color = Color.White.copy(alpha = 0.75f),
				)
				Text(
					text = JourneyXpTargetText(data),
					fontSize = 12.sp,
					fontWeight = FontWeight.SemiBold,
					color = Color.White.copy(alpha = 0.75f),
				)
			}
			Box(
				modifier = Modifier
					.padding(top = 7.dp)
					.fillMaxWidth()
					.height(4.dp)
					.clip(RoundedCornerShape(99.dp))
					.background(Color.White.copy(alpha = 0.16f)),
			) {
				Box(
					modifier = Modifier
						.fillMaxWidth(data.levelFraction)
						.height(4.dp)
						.background(Brush.horizontalGradient(listOf(accent, accentLight))),
				)
			}
		}
	}
}

/* --------------------------------------------------- Model L · Ungu Fajar */

@Composable
private fun JourneyDawnHero(data: JourneyPanelData, numberFormat: NumberFormat) {
	// Ungu Fajar follows the active app theme: its dawn gradient and accents are built
	// from the current color scheme instead of fixed purples.
	val scheme = MaterialTheme.colorScheme
	val ink = scheme.onPrimaryContainer
	val muted = scheme.onPrimaryContainer.copy(alpha = 0.72f)
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 14.dp),
	) {
		Spacer(Modifier.height(6.dp))
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.clip(RoundedCornerShape(26.dp))
				.background(
					Brush.linearGradient(
						listOf(
							scheme.primaryContainer,
							scheme.secondaryContainer,
							scheme.tertiaryContainer,
						),
					),
				)
				.padding(horizontal = 20.dp, vertical = 20.dp),
		) {
			Column {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Box(
						modifier = Modifier
							.size(54.dp)
							.clip(CircleShape)
							.background(scheme.surface)
							.border(1.5.dp, scheme.tertiary, CircleShape),
						contentAlignment = Alignment.Center,
					) {
						Text(
							text = data.initial,
							fontSize = 21.sp,
							fontWeight = FontWeight.Bold,
							color = scheme.primary,
						)
					}
					Spacer(Modifier.width(13.dp))
					Column {
						Text(
							text = data.displayName,
							fontSize = 18.5.sp,
							fontWeight = FontWeight.Bold,
							color = ink,
						)
						Text(
							text = "${data.rankName} · " +
								stringResource(R.string.reader_journey_level, data.level) +
								" · ${data.personalityName}",
							fontSize = 12.5.sp,
							fontWeight = FontWeight.SemiBold,
							color = muted,
							modifier = Modifier.padding(top = 2.dp),
						)
						data.title?.let { title ->
							Text(
								text = title,
								fontStyle = FontStyle.Italic,
								fontSize = 12.sp,
								color = muted,
								modifier = Modifier.padding(top = 2.dp),
							)
						}
					}
				}
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = 18.dp),
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Text(
						text = JourneyXpProgressText(data, numberFormat),
						fontSize = 12.sp,
						fontWeight = FontWeight.Bold,
						color = muted,
					)
					Text(
						text = JourneyXpTargetText(data),
						fontSize = 12.sp,
						fontWeight = FontWeight.Bold,
						color = muted,
					)
				}
				Box(
					modifier = Modifier
						.padding(top = 7.dp)
						.fillMaxWidth()
						.height(7.dp)
						.clip(RoundedCornerShape(99.dp))
						.background(scheme.onPrimaryContainer.copy(alpha = 0.18f)),
				) {
					Box(
						modifier = Modifier
							.fillMaxWidth(data.levelFraction)
							.height(7.dp)
							.background(scheme.primary),
					)
				}
			}
		}
	}
}


/* -------------------------------------------------- Model N · Paspor Baca */

@Composable
private fun PassportField(label: String, value: String, modifier: Modifier) {
	Column(modifier = modifier) {
		Text(
			text = label.uppercase(),
			style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
			fontSize = 9.5.sp,
			fontWeight = FontWeight.Bold,
			color = Color(0xFFA08B6D),
		)
		Text(
			text = value,
			fontFamily = FontFamily.Serif,
			fontSize = 16.5.sp,
			color = Color(0xFF33291D),
			modifier = Modifier.padding(top = 3.dp),
		)
		DottedLine(color = Color(0xFFCFC4A8), modifier = Modifier.padding(top = 4.dp))
	}
}

@Composable
private fun PassportStamp(data: JourneyPanelData, modifier: Modifier) {
	Box(
		modifier = modifier
			.size(86.dp)
			.rotate(-10f)
			.border(2.dp, StampBrown.copy(alpha = 0.55f), CircleShape)
			.padding(3.dp)
			.border(1.dp, StampBrown.copy(alpha = 0.4f), CircleShape),
		contentAlignment = Alignment.Center,
	) {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			Text(
				text = data.rankName.uppercase(),
				fontSize = 9.5.sp,
				fontWeight = FontWeight.ExtraBold,
				letterSpacing = 1.2.sp,
				color = StampBrown.copy(alpha = 0.85f),
				textAlign = TextAlign.Center,
			)
			Text(
				text = stringResource(R.string.reader_journey_level, data.level).uppercase(),
				fontSize = 9.5.sp,
				fontWeight = FontWeight.ExtraBold,
				letterSpacing = 1.2.sp,
				color = StampBrown.copy(alpha = 0.85f),
				textAlign = TextAlign.Center,
			)
			Text(
				text = stringResource(R.string.journey_panel_verified).uppercase(),
				fontSize = 8.sp,
				fontWeight = FontWeight.ExtraBold,
				letterSpacing = 1.2.sp,
				color = StampBrown.copy(alpha = 0.85f),
				textAlign = TextAlign.Center,
			)
		}
	}
}

@Composable
private fun JourneyPassportHero(data: JourneyPanelData, numberFormat: NumberFormat) {
	val paper = Color(0xFFFFFDF8)
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
	) {
		Spacer(Modifier.height(14.dp))
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.clip(RoundedCornerShape(6.dp))
				.background(paper)
				.border(1.dp, Color(0xFFD9D2C0), RoundedCornerShape(6.dp))
				.padding(5.dp)
				.border(1.dp, Color(0xFFE6DFCD), RoundedCornerShape(4.dp))
				.padding(horizontal = 18.dp, vertical = 18.dp),
		) {
			Column {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Box(
						modifier = Modifier
							.size(36.dp)
							.clip(CircleShape)
							.border(1.5.dp, StampBrown, CircleShape),
						contentAlignment = Alignment.Center,
					) {
						Box(
							modifier = Modifier
								.size(10.dp)
								.rotate(45f)
								.background(StampBrown),
						)
					}
					Spacer(Modifier.width(10.dp))
					Column {
						Text(
							text = stringResource(R.string.journey_panel_passport_title).uppercase(),
							fontSize = 12.5.sp,
							fontWeight = FontWeight.ExtraBold,
							letterSpacing = 1.6.sp,
							color = Color(0xFF4A3F30),
						)
						Text(
							text = stringResource(R.string.journey_panel_passport_subtitle).uppercase(),
							fontSize = 10.5.sp,
							fontWeight = FontWeight.Bold,
							letterSpacing = 1.2.sp,
							color = Color(0xFFA08B6D),
							modifier = Modifier.padding(top = 2.dp),
						)
					}
				}
				Spacer(Modifier.height(18.dp))
				Row(modifier = Modifier.fillMaxWidth()) {
					PassportField(
						label = stringResource(R.string.journey_panel_field_name),
						value = data.displayName,
						modifier = Modifier.weight(1f),
					)
					Spacer(Modifier.width(16.dp))
					PassportField(
						label = stringResource(R.string.journey_panel_field_rank),
						value = data.rankName,
						modifier = Modifier.weight(1f),
					)
				}
				Spacer(Modifier.height(13.dp))
				Row(modifier = Modifier.fillMaxWidth()) {
					PassportField(
						label = stringResource(R.string.journey_panel_field_level),
						value = data.level.toString(),
						modifier = Modifier.weight(1f),
					)
					Spacer(Modifier.width(16.dp))
					PassportField(
						label = stringResource(R.string.journey_panel_field_personality),
						value = data.personalityName,
						modifier = Modifier.weight(1f),
					)
				}
				Spacer(Modifier.height(16.dp))
				// The stamp sits in the flow at the bottom end instead of floating
				// over the fields: earlier it covered the Personality value.
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.Bottom,
				) {
					Column(modifier = Modifier.weight(1f)) {
						data.title?.let { title ->
							PassportField(
								label = stringResource(R.string.journey_panel_field_title),
								value = title,
								modifier = Modifier.fillMaxWidth(),
							)
							Spacer(Modifier.height(14.dp))
						}
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.SpaceBetween,
						) {
							Text(
								text = stringResource(
									R.string.journey_panel_xp_short,
									numberFormat.format(data.lifetimeXp),
								),
								fontSize = 11.5.sp,
								fontWeight = FontWeight.Bold,
								color = Color(0xFFA08B6D),
							)
							data.xpForNextLevel?.let { next ->
								Text(
									text = stringResource(
										R.string.journey_panel_next_level_short,
										data.level + 1,
										numberFormat.format(next),
									),
									fontSize = 11.5.sp,
									fontWeight = FontWeight.Bold,
									color = Color(0xFFA08B6D),
								)
							}
						}
						Box(
							modifier = Modifier
								.padding(top = 6.dp)
								.fillMaxWidth()
								.height(3.dp)
								.clip(RoundedCornerShape(99.dp))
								.background(Color(0xFFE9E1CD)),
						) {
							Box(
								modifier = Modifier
									.fillMaxWidth(data.levelFraction)
									.height(3.dp)
									.background(StampBrown),
							)
						}
					}
					Spacer(Modifier.width(14.dp))
					PassportStamp(data = data, modifier = Modifier)
				}
			}
		}
	}
}

/* ------------------------------------------- Model P · Kartu Perpustakaan */

@Composable
private fun LibraryCardField(label: String, value: String) {
	Column {
		Text(
			text = label.uppercase(),
			fontSize = 8.5.sp,
			fontWeight = FontWeight.Bold,
			letterSpacing = 1.6.sp,
			color = Color.White.copy(alpha = 0.55f),
		)
		Text(
			text = value,
			fontSize = 13.sp,
			fontWeight = FontWeight.Bold,
			color = Color.White,
			modifier = Modifier.padding(top = 2.dp),
		)
	}
}

@Composable
private fun LibraryBarcode(modifier: Modifier) {
	Canvas(modifier = modifier) {
		var x = 0f
		var seed = 42L
		val barColor = Color.White.copy(alpha = 0.92f)
		while (x < size.width) {
			seed = (seed * 31 + 17) % 997
			val barWidth = (1 + (seed % 3).toInt()).dp.toPx()
			if (seed % 4L != 0L) {
				drawRect(
					color = barColor,
					topLeft = Offset(x, 0f),
					size = Size(barWidth, size.height * 0.78f),
				)
			}
			x += barWidth + (1 + (seed % 2).toInt()).dp.toPx()
		}
	}
}

@Composable
private fun JourneyLibraryHero(data: JourneyPanelData, numberFormat: NumberFormat) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
	) {
		Spacer(Modifier.height(12.dp))
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.clip(RoundedCornerShape(20.dp))
				.background(
					Brush.linearGradient(listOf(Color(0xFF4A2D8F), Color(0xFF31205F))),
				)
				.padding(horizontal = 20.dp, vertical = 18.dp),
		) {
			Column {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = stringResource(R.string.journey_panel_member_card).uppercase(),
						fontSize = 10.5.sp,
						fontWeight = FontWeight.ExtraBold,
						letterSpacing = 2.sp,
						color = GoldLight,
					)
					Box(
						modifier = Modifier
							.size(30.dp)
							.clip(CircleShape)
							.border(1.5.dp, Gold, CircleShape),
						contentAlignment = Alignment.Center,
					) {
						Box(
							modifier = Modifier
								.size(9.dp)
								.rotate(45f)
								.background(GoldLight),
						)
					}
				}
				Text(
					text = data.displayName,
					fontFamily = FontFamily.Serif,
					fontSize = 23.sp,
					color = Color.White,
					modifier = Modifier.padding(top = 16.dp),
				)
				data.title?.let { title ->
					Text(
						text = title,
						fontStyle = FontStyle.Italic,
						fontSize = 12.5.sp,
						color = Color.White.copy(alpha = 0.85f),
						modifier = Modifier.padding(top = 3.dp),
					)
				}
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = 12.dp),
					horizontalArrangement = Arrangement.spacedBy(20.dp),
				) {
					LibraryCardField(
						label = stringResource(R.string.journey_panel_field_rank),
						value = data.rankName,
					)
					LibraryCardField(
						label = stringResource(R.string.journey_panel_field_level),
						value = data.level.toString(),
					)
					LibraryCardField(
						label = stringResource(R.string.journey_panel_field_personality),
						value = data.personalityName,
					)
				}
				LibraryBarcode(
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = 16.dp)
						.height(30.dp),
				)
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = 3.dp),
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Text(
						text = JourneyXpProgressText(data, numberFormat),
						fontSize = 9.5.sp,
						fontWeight = FontWeight.Bold,
						letterSpacing = 1.4.sp,
						color = Color.White.copy(alpha = 0.6f),
					)
					Text(
						text = stringResource(
							R.string.journey_panel_chapters_read,
							numberFormat.format(data.chapters),
						).uppercase(),
						fontSize = 9.5.sp,
						fontWeight = FontWeight.Bold,
						letterSpacing = 1.4.sp,
						color = Color.White.copy(alpha = 0.6f),
					)
				}
			}
		}
	}
}


/* ------------------------------------------------------------------ tabs */

@Composable
private fun JourneyOverviewTab(
	data: JourneyPanelData,
	style: JourneyPanelStyle,
	numberFormat: NumberFormat,
	durationText: (Long) -> String,
	weeklyGoal: WeeklyGoalState,
	onGoalClick: () -> Unit,
) {
	Column(modifier = Modifier.fillMaxWidth()) {
		if (data.isEmpty) {
			Spacer(Modifier.height(12.dp))
			JourneyCard(style) {
				Text(
					text = stringResource(R.string.journey_panel_empty_title),
					fontSize = 15.sp,
					fontWeight = FontWeight.Bold,
					color = style.onCard,
				)
				Text(
					text = stringResource(R.string.journey_panel_empty_text),
					fontSize = 12.5.sp,
					color = style.onCardMuted,
					modifier = Modifier.padding(top = 4.dp),
				)
			}
		}
		JourneySectionLabel(
			text = stringResource(R.string.journey_panel_goal_title),
			color = style.labelColor,
		)
		JourneyGoalCard(
			state = weeklyGoal,
			style = style,
			numberFormat = numberFormat,
			onClick = onGoalClick,
		)
		if (!data.isEmpty) {
			JourneySectionLabel(
				text = stringResource(R.string.journey_panel_next_milestone),
				color = style.labelColor,
			)
			val next = data.achievements.firstOrNull { !it.isUnlocked }
			if (next != null) {
				JourneyCard(style) {
					JourneyAchievementRow(next, style, numberFormat)
				}
			} else {
				JourneyCard(style) {
					Text(
						text = stringResource(R.string.reader_journey_all_milestones_unlocked),
						fontSize = 13.5.sp,
						fontWeight = FontWeight.SemiBold,
						color = style.onCard,
					)
				}
			}
			JourneySectionLabel(
				text = stringResource(R.string.journey_panel_at_a_glance),
				color = style.labelColor,
			)
			JourneyMetricGrid(
				items = listOf(
					numberFormat.format(data.chapters) to stringResource(R.string.chapters),
					numberFormat.format(data.titles) to stringResource(R.string.journey_panel_stat_titles),
					durationText(data.totalDurationMs) to stringResource(R.string.stats_read_time),
					data.currentStreak.toString() to stringResource(R.string.journey_panel_current_streak),
				),
				style = style,
			)
			if (data.mangaChapters + data.novelChapters > 0) {
				JourneySectionLabel(
					text = stringResource(R.string.journey_panel_manga_vs_novel),
					color = style.labelColor,
				)
				JourneyCard(style) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Text(
							text = stringResource(R.string.journey_panel_manga),
							fontSize = 12.5.sp,
							color = style.onCardMuted,
						)
						Text(
							text = stringResource(
								R.string.journey_panel_chapters_read,
								numberFormat.format(data.mangaChapters),
							),
							fontSize = 12.5.sp,
							fontWeight = FontWeight.Bold,
							color = style.onCard,
						)
					}
					Row(
						modifier = Modifier
							.padding(vertical = 9.dp)
							.fillMaxWidth()
							.height(12.dp)
							.clip(RoundedCornerShape(99.dp)),
					) {
						val total = (data.mangaChapters + data.novelChapters).toFloat()
						Box(
							modifier = Modifier
								.weight((data.mangaChapters / total).coerceIn(0.01f, 0.99f))
								.height(12.dp)
								.background(style.accent),
						)
						Box(
							modifier = Modifier
								.weight((data.novelChapters / total).coerceIn(0.01f, 0.99f))
								.height(12.dp)
								.background(style.accent.copy(alpha = 0.38f)),
						)
					}
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Text(
							text = stringResource(R.string.journey_panel_novel),
							fontSize = 12.5.sp,
							color = style.onCardMuted,
						)
						Text(
							text = stringResource(
								R.string.journey_panel_chapters_read,
								numberFormat.format(data.novelChapters),
							),
							fontSize = 12.5.sp,
							fontWeight = FontWeight.Bold,
							color = style.onCard,
						)
					}
				}
			}
			data.topPickTitle?.let { title ->
				JourneySectionLabel(
					text = stringResource(R.string.journey_panel_top_pick),
					color = style.labelColor,
				)
				JourneyTitleCard(
					title = title,
					chapters = data.topPickChapters,
					style = style,
					numberFormat = numberFormat,
				)
			}
		}
	}
}

@Composable
private fun JourneyGoalCard(
	state: WeeklyGoalState,
	style: JourneyPanelStyle,
	numberFormat: NumberFormat,
	onClick: () -> Unit,
) {
	JourneyCard(style = style, modifier = Modifier.clickable(onClick = onClick)) {
		when {
			state.goal <= 0 -> {
				Text(
					text = stringResource(R.string.journey_panel_goal_unset),
					fontSize = 13.sp,
					color = style.onCardMuted,
				)
				Text(
					text = stringResource(R.string.journey_panel_goal_set),
					fontSize = 13.5.sp,
					fontWeight = FontWeight.Bold,
					color = style.accent,
					modifier = Modifier.padding(top = 8.dp),
				)
			}

			state.reached -> {
				Text(
					text = stringResource(R.string.journey_panel_goal_reached),
					fontSize = 16.sp,
					fontWeight = FontWeight.Bold,
					color = style.accent,
				)
				Text(
					text = stringResource(
						R.string.journey_panel_goal_reached_sub,
						numberFormat.format(state.thisWeek),
					),
					fontSize = 12.5.sp,
					color = style.onCardMuted,
					modifier = Modifier.padding(top = 3.dp),
				)
				Spacer(Modifier.height(10.dp))
				JourneyProgressBar(
					fraction = 1f,
					accent = style.accent,
					track = style.trackColor,
				)
			}

			else -> {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = "${numberFormat.format(state.thisWeek)} / ${state.goal}",
						fontFamily = if (style.serif) FontFamily.Serif else FontFamily.Default,
						fontSize = 17.sp,
						fontWeight = FontWeight.Bold,
						color = style.onCard,
					)
					state.lastWeek?.takeIf { it > 0 }?.let { lastWeek ->
						val pct = ((state.thisWeek - lastWeek) * 100 / lastWeek).toInt()
						Box(
							modifier = Modifier
								.clip(RoundedCornerShape(99.dp))
								.background(style.accent.copy(alpha = 0.18f))
								.padding(horizontal = 8.dp, vertical = 3.dp),
						) {
							Text(
								text = stringResource(
									R.string.journey_panel_goal_trend,
									(if (pct >= 0) "+" else "") + pct + "%",
								),
								fontSize = 11.sp,
								fontWeight = FontWeight.Bold,
								color = style.accent,
							)
						}
					}
				}
				Text(
					text = stringResource(R.string.journey_panel_goal_this_week),
					fontSize = 11.5.sp,
					color = style.onCardMuted,
					modifier = Modifier.padding(top = 2.dp),
				)
				Spacer(Modifier.height(10.dp))
				JourneyProgressBar(
					fraction = state.fraction,
					accent = style.accent,
					track = style.trackColor,
				)
				Text(
					text = stringResource(
						R.string.journey_panel_goal_remaining,
						numberFormat.format(state.goal - state.thisWeek),
					),
					fontSize = 12.5.sp,
					color = style.onCardMuted,
					modifier = Modifier.padding(top = 8.dp),
				)
			}
		}
	}
}

@Composable
private fun JourneyTitleCard(
	title: String,
	chapters: Int,
	style: JourneyPanelStyle,
	numberFormat: NumberFormat,
) {
	JourneyCard(style) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Box(
				modifier = Modifier
					.size(44.dp)
					.clip(RoundedCornerShape(8.dp))
					.background(style.accent.copy(alpha = 0.2f)),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = title.firstOrNull()?.uppercase() ?: "",
					fontFamily = FontFamily.Serif,
					fontSize = 18.sp,
					fontWeight = FontWeight.Bold,
					color = style.accent,
				)
			}
			Spacer(Modifier.width(12.dp))
			Column {
				Text(
					text = title,
					fontSize = 14.5.sp,
					fontWeight = FontWeight.Bold,
					color = style.onCard,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					text = stringResource(
						R.string.journey_panel_chapters_read,
						numberFormat.format(chapters),
					),
					fontSize = 12.sp,
					color = style.onCardMuted,
					modifier = Modifier.padding(top = 2.dp),
				)
			}
		}
	}
}

@Composable
private fun JourneyStatisticsTab(
	data: JourneyPanelData,
	style: JourneyPanelStyle,
	numberFormat: NumberFormat,
	durationText: (Long) -> String,
) {
	Column(modifier = Modifier.fillMaxWidth()) {
		Spacer(Modifier.height(12.dp))
		JourneyMetricGrid(
			items = listOf(
				numberFormat.format(data.chapters) to stringResource(R.string.chapters),
				numberFormat.format(data.pages) to stringResource(R.string.journey_panel_pages),
				numberFormat.format(data.titles) to stringResource(R.string.journey_panel_stat_titles),
				durationText(data.totalDurationMs) to stringResource(R.string.stats_read_time),
				numberFormat.format(data.sessionCount) to stringResource(R.string.journey_panel_sessions),
				data.activeDays.toString() to stringResource(R.string.stats_active_days),
				data.currentStreak.toString() to stringResource(R.string.journey_panel_current_streak),
				data.longestStreak.toString() to stringResource(R.string.journey_panel_stat_streak),
			),
			style = style,
		)
		JourneySectionLabel(
			text = stringResource(R.string.journey_panel_heatmap_title),
			color = style.labelColor,
		)
		JourneyCard(style) {
			JourneyHeatmap(data = data, style = style)
		}
		if (data.topGenres.isNotEmpty()) {
			JourneySectionLabel(
				text = stringResource(R.string.journey_panel_genres_title),
				color = style.labelColor,
			)
			JourneyCard(style) {
				val maxGenre = data.topGenres.maxOf { it.count }
				data.topGenres.take(4).forEachIndexed { index, genre ->
					if (index > 0) {
						Spacer(Modifier.height(11.dp))
					}
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Text(
							text = genre.label,
							fontSize = 12.5.sp,
							fontWeight = FontWeight.SemiBold,
							color = style.onCard,
						)
						Text(
							text = numberFormat.format(genre.count),
							fontSize = 12.sp,
							color = style.onCardMuted,
						)
					}
					Spacer(Modifier.height(5.dp))
					JourneyProgressBar(
						fraction = if (maxGenre > 0) genre.count.toFloat() / maxGenre else 0f,
						accent = style.accent,
						track = style.trackColor,
						height = 6.dp,
					)
				}
			}
		}
	}
}

@Composable
private fun JourneyHeatmap(
	data: JourneyPanelData,
	style: JourneyPanelStyle,
) {
	val durations = remember(data.heatmapDays) {
		val byDay = data.heatmapDays.associateBy { it.epochDay }
		val today = LocalDate.now().toEpochDay()
		(today - 125..today).map { day -> byDay[day]?.duration ?: 0L }
	}
	val maxDuration = durations.maxOrNull() ?: 0L
	if (maxDuration <= 0L) {
		Text(
			text = stringResource(R.string.journey_panel_heatmap_empty),
			fontSize = 12.5.sp,
			color = style.onCardMuted,
		)
		return
	}
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(3.dp),
	) {
		durations.chunked(7).forEach { week ->
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(3.dp),
			) {
				week.forEach { duration ->
					val step = when {
						duration <= 0L -> 0
						duration <= maxDuration / 4 -> 1
						duration <= maxDuration / 2 -> 2
						duration <= maxDuration * 3 / 4 -> 3
						else -> 4
					}
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.aspectRatio(1f)
							.clip(RoundedCornerShape(3.dp))
							.background(style.heatSteps[step]),
					)
				}
				repeat(7 - week.size) {
					Spacer(
						Modifier
							.fillMaxWidth()
							.aspectRatio(1f),
					)
				}
			}
		}
	}
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 9.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = stringResource(
				R.string.journey_panel_heatmap_caption,
				durations.count { it > 0 },
			),
			fontSize = 11.sp,
			color = style.onCardMuted,
		)
		Row(
			horizontalArrangement = Arrangement.spacedBy(3.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			style.heatSteps.forEach { color ->
				Box(
					modifier = Modifier
						.size(10.dp)
						.clip(RoundedCornerShape(3.dp))
						.background(color),
				)
			}
		}
	}
}

@Composable
private fun JourneyAchievementsTab(
	data: JourneyPanelData,
	style: JourneyPanelStyle,
	numberFormat: NumberFormat,
) {
	Column(modifier = Modifier.fillMaxWidth()) {
		val unlocked = data.achievements.count { it.isUnlocked }
		JourneySectionLabel(
			text = stringResource(
				R.string.reader_journey_achievement_summary,
				unlocked,
				data.achievements.size,
			),
			color = style.labelColor,
		)
		JourneyCard(style) {
			data.achievements.forEachIndexed { index, progress ->
				if (index > 0) {
					Box(
						modifier = Modifier
							.padding(vertical = 10.dp)
							.fillMaxWidth()
							.height(1.dp)
							.background(style.trackColor),
					)
				}
				JourneyAchievementRow(progress, style, numberFormat)
			}
		}
		data.revisitedTitle?.let { title ->
			JourneySectionLabel(
				text = stringResource(R.string.journey_panel_most_revisited),
				color = style.labelColor,
			)
			JourneyTitleCard(
				title = title,
				chapters = data.revisitedChapters,
				style = style,
				numberFormat = numberFormat,
			)
		}
		JourneySectionLabel(
			text = stringResource(R.string.journey_panel_rank_ladder),
			color = style.labelColor,
		)
		JourneyRankLadder(
			currentRank = data.rank,
			marker = style.ladderMarker,
			doneColor = style.ladderDone,
			currentColor = style.ladderCurrent,
			lockedColor = style.ladderLocked,
			nameColor = style.ladderName,
			lockedNameColor = style.ladderLockedName,
			dividerColor = style.ladderDivider,
			currentContainerColor = style.ladderCurrentContainer,
		)
	}
}

@Composable
private fun JourneyAchievementRow(
	progress: ReaderAchievementProgress,
	style: JourneyPanelStyle,
	numberFormat: NumberFormat,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.Top,
	) {
		Box(
			modifier = Modifier
				.padding(top = 4.dp)
				.size(10.dp)
				.clip(CircleShape)
				.background(journeyRarityColor(progress.id.rarity)),
		)
		Spacer(Modifier.width(11.dp))
		Column(modifier = Modifier.weight(1f)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = stringResource(journeyAchievementTitleRes(progress.id)),
					fontSize = 13.5.sp,
					fontWeight = FontWeight.Bold,
					color = style.onCard,
					modifier = Modifier.weight(1f),
				)
				Text(
					text = if (progress.isUnlocked) {
						stringResource(R.string.reader_journey_achievement_unlocked)
					} else {
						"${numberFormat.format(progress.progress)} / ${numberFormat.format(progress.target)}"
					},
					fontSize = 11.sp,
					fontWeight = FontWeight.Bold,
					color = if (progress.isUnlocked) style.accent else style.onCardMuted,
				)
			}
			Text(
				text = stringResource(journeyAchievementDescRes(progress.id)) + " · " +
					stringResource(journeyRarityTitleRes(progress.id.rarity)),
				fontSize = 11.5.sp,
				color = style.onCardMuted,
				modifier = Modifier.padding(top = 1.dp),
			)
			if (!progress.isUnlocked) {
				Spacer(Modifier.height(6.dp))
				JourneyProgressBar(
					fraction = progress.fraction,
					accent = style.accent,
					track = style.trackColor,
					height = 4.dp,
				)
			}
		}
	}
}
