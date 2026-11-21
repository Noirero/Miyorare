package org.koitharu.kotatsu.settings.journey

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.readerjourney.domain.ReaderAchievementId
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyRules
import org.koitharu.kotatsu.readerjourney.domain.ReaderProfileSettings
import org.koitharu.kotatsu.readerjourney.domain.ReaderRank
import org.koitharu.kotatsu.readerjourney.domain.ReadingPersonality
import org.koitharu.kotatsu.readerjourney.ui.titleRes
import org.koitharu.kotatsu.stats.domain.ReadingStats
import java.text.NumberFormat

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
	)
}

@Composable
fun SettingsJourneyPanelScreen(
	stats: ReadingStats,
	profile: ReaderProfileSettings,
	model: JourneyPanelModel,
	onModelChange: (JourneyPanelModel) -> Unit,
) {
	val data = rememberJourneyPanelData(stats, profile)
	val numberFormat = remember { NumberFormat.getNumberInstance() }
	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background),
	) {
		JourneyPanelModelPicker(selected = model, onSelect = onModelChange)
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.weight(1f)
				.verticalScroll(rememberScrollState()),
		) {
			when (model) {
				JourneyPanelModel.NIGHT -> JourneyPanelNight(data, numberFormat)
				JourneyPanelModel.DAWN -> JourneyPanelDawn(data, numberFormat)
				JourneyPanelModel.PASSPORT -> JourneyPanelPassport(data, numberFormat)
				JourneyPanelModel.LIBRARY_CARD -> JourneyPanelLibraryCard(data, numberFormat)
			}
			Spacer(Modifier.height(28.dp))
		}
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
private fun JourneyStatStrip(
	data: JourneyPanelData,
	numberFormat: NumberFormat,
	serifValues: Boolean,
	valueColor: Color,
	labelColor: Color,
) {
	Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
		JourneyStatCell(
			value = numberFormat.format(data.chapters),
			label = stringResource(R.string.chapters),
			serif = serifValues,
			valueColor = valueColor,
			labelColor = labelColor,
			modifier = Modifier.weight(1f),
		)
		JourneyStatCell(
			value = numberFormat.format(data.titles),
			label = stringResource(R.string.journey_panel_stat_titles),
			serif = serifValues,
			valueColor = valueColor,
			labelColor = labelColor,
			modifier = Modifier.weight(1f),
		)
		JourneyStatCell(
			value = data.longestStreak.toString(),
			label = stringResource(R.string.journey_panel_stat_streak_short),
			serif = serifValues,
			valueColor = valueColor,
			labelColor = labelColor,
			modifier = Modifier.weight(1f),
		)
	}
}

@Composable
private fun JourneyStatCell(
	value: String,
	label: String,
	serif: Boolean,
	valueColor: Color,
	labelColor: Color,
	modifier: Modifier,
) {
	Column(modifier = modifier) {
		Text(
			text = value,
			fontFamily = if (serif) FontFamily.Serif else FontFamily.Default,
			fontSize = if (serif) 20.sp else 17.sp,
			fontWeight = if (serif) FontWeight.Normal else FontWeight.Bold,
			color = valueColor,
		)
		Text(
			text = label.uppercase(),
			style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
			color = labelColor,
			modifier = Modifier.padding(top = 3.dp),
		)
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
private fun JourneyNoteRows(
	data: JourneyPanelData,
	numberFormat: NumberFormat,
	diamondColor: Color,
	labelColor: Color,
	valueColor: Color,
	dividerColor: Color,
) {
	val rows = listOf(
		stringResource(R.string.chapters) to numberFormat.format(data.chapters),
		stringResource(R.string.journey_panel_stat_titles) to numberFormat.format(data.titles),
		stringResource(R.string.journey_panel_stat_streak) to
			stringResource(R.string.journey_panel_days, data.longestStreak),
	)
	Column(modifier = Modifier.fillMaxWidth()) {
		rows.forEachIndexed { index, (label, value) ->
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(vertical = 9.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Box(
					modifier = Modifier
						.size(7.dp)
						.rotate(45f)
						.background(diamondColor),
				)
				Spacer(Modifier.width(12.dp))
				Text(
					text = label,
					fontSize = 13.5.sp,
					color = labelColor,
					modifier = Modifier.weight(1f),
				)
				Text(
					text = value,
					fontFamily = FontFamily.Serif,
					fontSize = 15.sp,
					color = valueColor,
				)
			}
			if (index < rows.lastIndex) {
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
private fun JourneyPanelNight(data: JourneyPanelData, numberFormat: NumberFormat) {
	// Malam Anggun keeps its dark hero, but the night itself is tinted by the active
	// theme: the hero is a deepened primary and the hairline / XP accents come from
	// the theme's tertiary, lightened enough to stay readable on the dark surface.
	val scheme = MaterialTheme.colorScheme
	val night = lerp(scheme.primary, Color.Black, 0.84f)
	val nightDeep = lerp(scheme.primary, Color.Black, 0.91f)
	val accent = lerp(scheme.tertiary, Color.White, 0.30f)
	val accentLight = lerp(scheme.tertiary, Color.White, 0.55f)
	Column(modifier = Modifier.fillMaxWidth()) {
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
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 24.dp),
		) {
			JourneySectionLabel(
				text = stringResource(R.string.journey_panel_notes),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			JourneyStatStrip(
				data = data,
				numberFormat = numberFormat,
				serifValues = true,
				valueColor = MaterialTheme.colorScheme.onBackground,
				labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			JourneySectionLabel(
				text = stringResource(R.string.journey_panel_rank_ladder),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			JourneyRankLadder(
				currentRank = data.rank,
				marker = LadderMarker.DOT,
				doneColor = MaterialTheme.colorScheme.primary,
				currentColor = MaterialTheme.colorScheme.tertiary,
				lockedColor = MaterialTheme.colorScheme.outlineVariant,
				nameColor = MaterialTheme.colorScheme.onBackground,
				lockedNameColor = MaterialTheme.colorScheme.onSurfaceVariant,
				dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
			)
		}
	}
}

/* --------------------------------------------------- Model L · Ungu Fajar */

@Composable
private fun JourneyPanelDawn(data: JourneyPanelData, numberFormat: NumberFormat) {
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
		Surface(
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = 14.dp),
			shape = RoundedCornerShape(22.dp),
			color = MaterialTheme.colorScheme.surface,
			tonalElevation = 1.dp,
			shadowElevation = 2.dp,
		) {
			JourneyStatStrip(
				data = data,
				numberFormat = numberFormat,
				serifValues = false,
				valueColor = MaterialTheme.colorScheme.onSurface,
				labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		JourneySectionLabel(
			text = stringResource(R.string.journey_panel_rank_ladder),
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		JourneyRankLadder(
			currentRank = data.rank,
			marker = LadderMarker.DOT,
			doneColor = MaterialTheme.colorScheme.primary,
			currentColor = MaterialTheme.colorScheme.primary,
			lockedColor = MaterialTheme.colorScheme.outlineVariant,
			nameColor = MaterialTheme.colorScheme.onBackground,
			lockedNameColor = MaterialTheme.colorScheme.onSurfaceVariant,
			dividerColor = null,
			currentContainerColor = MaterialTheme.colorScheme.secondaryContainer,
		)
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
private fun JourneyPanelPassport(data: JourneyPanelData, numberFormat: NumberFormat) {
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
			Box(modifier = Modifier.fillMaxWidth()) {
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
					data.title?.let { title ->
						Spacer(Modifier.height(13.dp))
						Row(modifier = Modifier.fillMaxWidth()) {
							PassportField(
								label = stringResource(R.string.journey_panel_field_title),
								value = title,
								modifier = Modifier.weight(1f),
							)
							Spacer(Modifier.weight(1f))
						}
					}
					Spacer(Modifier.height(30.dp))
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
				PassportStamp(
					data = data,
					modifier = Modifier
						.align(Alignment.BottomEnd)
						.padding(bottom = 34.dp),
				)
			}
		}
		JourneySectionLabel(
			text = stringResource(R.string.journey_panel_notes),
			color = Color(0xFFA08B6D),
		)
		JourneyNoteRows(
			data = data,
			numberFormat = numberFormat,
			diamondColor = StampBrown,
			labelColor = MaterialTheme.colorScheme.onBackground,
			valueColor = MaterialTheme.colorScheme.onBackground,
			dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
		)
		JourneySectionLabel(
			text = stringResource(R.string.journey_panel_rank_history),
			color = Color(0xFFA08B6D),
		)
		JourneyRankLadder(
			currentRank = data.rank,
			marker = LadderMarker.DIAMOND,
			doneColor = StampBrown,
			currentColor = StampBrown,
			lockedColor = MaterialTheme.colorScheme.outlineVariant,
			nameColor = MaterialTheme.colorScheme.onBackground,
			lockedNameColor = MaterialTheme.colorScheme.onSurfaceVariant,
			dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
		)
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
private fun JourneyPanelLibraryCard(data: JourneyPanelData, numberFormat: NumberFormat) {
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
		JourneySectionLabel(
			text = stringResource(R.string.journey_panel_notes),
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		JourneyNoteRows(
			data = data,
			numberFormat = numberFormat,
			diamondColor = MaterialTheme.colorScheme.primary,
			labelColor = MaterialTheme.colorScheme.onBackground,
			valueColor = MaterialTheme.colorScheme.onBackground,
			dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
		)
		JourneySectionLabel(
			text = stringResource(R.string.journey_panel_rank_ladder),
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		JourneyRankLadder(
			currentRank = data.rank,
			marker = LadderMarker.DOT,
			doneColor = MaterialTheme.colorScheme.primary,
			currentColor = Color(0xFFB8860B),
			lockedColor = MaterialTheme.colorScheme.outlineVariant,
			nameColor = MaterialTheme.colorScheme.onBackground,
			lockedNameColor = MaterialTheme.colorScheme.onSurfaceVariant,
			dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
		)
	}
}
