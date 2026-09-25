package org.koitharu.kotatsu.stats.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticPolicy
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyThemeCollectionEntry
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyRewardAccess
import org.koitharu.kotatsu.readerjourney.domain.ReaderRank
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeRegistry
import org.koitharu.kotatsu.readerjourney.theme.RankThemeTokens
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVariant
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVisualRegistry
import org.koitharu.kotatsu.readerjourney.theme.ReferenceRankThemeVisualSpec
import org.koitharu.kotatsu.readerjourney.ui.ReferenceRankThemeBadge
import org.koitharu.kotatsu.readerjourney.ui.ReferenceRankThemeCard
import org.koitharu.kotatsu.readerjourney.ui.ReferenceRankThemeFrame
import org.koitharu.kotatsu.readerjourney.ui.ReferenceRankThemeNameplate
import org.koitharu.kotatsu.readerjourney.ui.ReferenceRankThemeProgress
import org.koitharu.kotatsu.readerjourney.ui.ReferenceRankThemeWallpaper
import org.koitharu.kotatsu.readerjourney.ui.titleRes
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref

internal enum class ReaderJourneyCollectionFilter(@get:StringRes val labelRes: Int) {
	ALL(R.string.reader_journey_collection_filter_all),
	THEMES(R.string.reader_journey_collection_filter_themes),
	FRAMES(R.string.reader_journey_collection_filter_frames),
	BADGES(R.string.reader_journey_collection_filter_badges),
	WALLPAPERS(R.string.reader_journey_collection_filter_wallpapers),
}

private enum class ReaderJourneyCustomizeTab(@get:StringRes val labelRes: Int) {
	PROFILE_CARD(R.string.reader_journey_customize_profile_card),
	NAVIGATION(R.string.reader_journey_customize_navigation),
	READER(R.string.reader_journey_customize_reader),
}

@Composable
internal fun ReaderJourneyExclusiveCollection(
	currentRank: ReaderRank,
	loadout: ReaderJourneyCosmeticLoadout,
	onOpenTheme: (String) -> Unit,
	modifier: Modifier = Modifier,
) {
	var filter by rememberSaveable { mutableStateOf(ReaderJourneyCollectionFilter.ALL) }
	val accessRank = remember(currentRank) { ReaderJourneyRewardAccess.cosmeticAccessRank(currentRank) }
	val collection = remember(accessRank) { ReaderJourneyCosmeticPolicy.collection(accessRank) }
	val equippedThemeId = remember(loadout, currentRank) {
		when (loadout.mode) {
			ReaderJourneyCosmeticMode.DEFAULT -> null
			ReaderJourneyCosmeticMode.AUTO -> RankThemeId.forRank(currentRank).stableId
			ReaderJourneyCosmeticMode.FULL_SET,
			ReaderJourneyCosmeticMode.CUSTOM -> loadout.selectedThemeId
		}
	}

	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 14.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.Bottom,
		) {
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.reader_journey_collection),
					style = MaterialTheme.typography.headlineSmall,
					fontWeight = FontWeight.Bold,
				)
				Text(
					text = stringResource(R.string.reader_journey_collection_exclusive_subtitle),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Text(
				text = "${collection.count { it.unlocked }} / ${collection.size}",
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.primary,
			)
		}

		LazyRow(
			horizontalArrangement = Arrangement.spacedBy(7.dp),
			contentPadding = PaddingValues(end = 4.dp),
		) {
			items(ReaderJourneyCollectionFilter.entries, key = { it.name }) { entry ->
				ExclusiveFilterPill(
					label = stringResource(entry.labelRes),
					selected = filter == entry,
					onClick = { filter = entry },
				)
			}
		}

		collection.forEach { entry ->
			ExclusiveRewardRow(
				entry = entry,
				filter = filter,
				equipped = equippedThemeId == entry.theme.stableId,
				onClick = { onOpenTheme(entry.theme.stableId) },
			)
		}
	}
}

@Composable
private fun ExclusiveFilterPill(
	label: String,
	selected: Boolean,
	onClick: () -> Unit,
) {
	val shape = RoundedCornerShape(50)
	Box(
		modifier = Modifier
			.clip(shape)
			.background(
				if (selected) {
					Brush.horizontalGradient(
						listOf(
							MaterialTheme.colorScheme.primary.copy(alpha = 0.96f),
							MaterialTheme.colorScheme.tertiary.copy(alpha = 0.86f),
						),
					)
				} else {
					Brush.horizontalGradient(
						listOf(
							MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
							MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f),
						),
					)
				},
			)
			.border(
				1.dp,
				if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.64f)
				else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.54f),
				shape,
			)
			.clickable(onClick = onClick)
			.padding(horizontal = 13.dp, vertical = 7.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium,
			fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
			color = if (selected) MaterialTheme.colorScheme.onPrimary
			else MaterialTheme.colorScheme.onSurface,
			maxLines = 1,
		)
	}
}

@Composable
private fun ExclusiveRewardRow(
	entry: ReaderJourneyThemeCollectionEntry,
	filter: ReaderJourneyCollectionFilter,
	equipped: Boolean,
	onClick: () -> Unit,
) {
	val spec = entry.visualSpec
	val tokens = remember(spec.themeId) {
		RankThemeRegistry.resolveOrDefault(spec.themeId.stableId).tokens(RankThemeVariant.DARK)
	}
	val primary = Color(tokens.primaryAccent.toInt())
	val secondary = Color(tokens.secondaryAccent.toInt())
	val shape = RoundedCornerShape(17.dp)
	val baseModifier = Modifier
		.fillMaxWidth()
		.height(76.dp)
		.shadow(
			elevation = if (equipped) 9.dp else 3.dp,
			shape = shape,
			clip = false,
		)
		.clip(shape)
		.border(
			BorderStroke(
				if (equipped) 1.5.dp else 1.dp,
				Brush.horizontalGradient(
					listOf(
						primary.copy(alpha = if (equipped) .90f else .46f),
						secondary.copy(alpha = if (equipped) .72f else .34f),
						Color.White.copy(alpha = if (equipped) .24f else .08f),
					),
				),
			),
			shape,
		)
	val modifier = if (entry.unlocked) baseModifier.clickable(onClick = onClick) else baseModifier

	Box(modifier = modifier) {
		ReferenceRankThemeWallpaper(
			spec = spec,
			tokens = tokens,
			modifier = Modifier.fillMaxSize(),
		)
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(
					Brush.horizontalGradient(
						listOf(
							Color(tokens.background.toInt()).copy(alpha = .94f),
							Color(tokens.background.toInt()).copy(alpha = .68f),
							Color.Black.copy(alpha = .24f),
						),
					),
				),
		)
		Row(
			modifier = Modifier
				.fillMaxSize()
				.padding(horizontal = 10.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			ExclusiveRewardPreview(
				spec = spec,
				tokens = tokens,
				filter = filter,
				unlocked = entry.unlocked,
			)
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				Text(
					text = stringResource(entry.theme.rank.titleRes),
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Bold,
					color = Color.White.copy(alpha = if (entry.unlocked) 1f else .62f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					text = if (entry.unlocked) {
						stringResource(R.string.reader_journey_exclusive_theme_set, entry.theme.displayName)
					} else {
						stringResource(R.string.reader_journey_unlock_at_level, entry.unlockLevel)
					},
					style = MaterialTheme.typography.labelSmall,
					color = Color.White.copy(alpha = if (entry.unlocked) .72f else .48f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			when {
				equipped -> ExclusiveStatusPill(
					text = stringResource(R.string.reader_journey_equipped),
					accent = secondary,
				)
				entry.unlocked -> Icon(
					painter = painterResource(R.drawable.ic_arrow_forward),
					contentDescription = null,
					tint = Color.White.copy(alpha = .72f),
					modifier = Modifier.size(18.dp),
				)
				else -> Icon(
					painter = painterResource(R.drawable.ic_lock),
					contentDescription = null,
					tint = Color.White.copy(alpha = .58f),
					modifier = Modifier.size(19.dp),
				)
			}
		}
		if (!entry.unlocked) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(Color.Black.copy(alpha = .24f)),
			)
		}
	}
}

@Composable
private fun ExclusiveRewardPreview(
	spec: ReferenceRankThemeVisualSpec,
	tokens: RankThemeTokens,
	filter: ReaderJourneyCollectionFilter,
	unlocked: Boolean,
) {
	val alpha = if (unlocked) 1f else .52f
	Box(
		modifier = Modifier
			.size(52.dp)
			.clip(RoundedCornerShape(14.dp))
			.background(Color(tokens.surface.toInt()).copy(alpha = .86f))
			.border(
				1.dp,
				Color(tokens.primaryAccent.toInt()).copy(alpha = .56f),
				RoundedCornerShape(14.dp),
			),
		contentAlignment = Alignment.Center,
	) {
		when (filter) {
			ReaderJourneyCollectionFilter.ALL,
			ReaderJourneyCollectionFilter.THEMES,
			ReaderJourneyCollectionFilter.BADGES -> ReferenceRankThemeBadge(
				spec = spec,
				tokens = tokens,
				modifier = Modifier.size(42.dp),
			)
			ReaderJourneyCollectionFilter.FRAMES -> ReferenceRankThemeFrame(
				spec = spec,
				tokens = tokens,
				modifier = Modifier.size(43.dp),
			) {
				Box(
					modifier = Modifier
						.fillMaxSize()
						.clip(CircleShape)
						.background(Color(tokens.surfaceVariant.toInt())),
				)
			}
			ReaderJourneyCollectionFilter.WALLPAPERS -> ReferenceRankThemeWallpaper(
				spec = spec,
				tokens = tokens,
				modifier = Modifier.fillMaxSize(),
			)
		}
		if (alpha < 1f) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(Color.Black.copy(alpha = 1f - alpha)),
			)
		}
	}
}

@Composable
private fun ExclusiveStatusPill(
	text: String,
	accent: Color,
) {
	Surface(
		shape = RoundedCornerShape(50),
		color = Color.Black.copy(alpha = .32f),
		border = BorderStroke(1.dp, accent.copy(alpha = .72f)),
	) {
		Text(
			text = text,
			style = MaterialTheme.typography.labelSmall,
			fontWeight = FontWeight.Bold,
			color = Color.White,
			modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
		)
	}
}

@Composable
internal fun ReaderJourneyExclusiveCustomizerDialog(
	currentRank: ReaderRank,
	loadout: ReaderJourneyCosmeticLoadout,
	initialThemeId: String?,
	onDismiss: () -> Unit,
	onApply: (ReaderJourneyCosmeticLoadout) -> Unit,
) {
	val accessRank = remember(currentRank) { ReaderJourneyRewardAccess.cosmeticAccessRank(currentRank) }
	val collection = remember(accessRank) { ReaderJourneyCosmeticPolicy.collection(accessRank) }
	val unlockedSpecs = remember(collection) { collection.filter { it.unlocked }.map { it.visualSpec } }
	val initialTheme = remember(loadout, currentRank, accessRank, initialThemeId) {
		RankThemeId.fromStableId(initialThemeId)
			?.takeIf { ReaderJourneyCosmeticPolicy.owns(it, accessRank) }
			?: RankThemeId.fromStableId(loadout.selectedThemeId)
				?.takeIf { ReaderJourneyCosmeticPolicy.owns(it, accessRank) }
			?: RankThemeId.forRank(currentRank)
	}
	var previewThemeId by rememberSaveable(initialTheme.stableId, currentRank.name) {
		mutableStateOf(initialTheme.stableId)
	}
	var draft by remember(loadout, accessRank, initialTheme.stableId) {
		mutableStateOf(seedExclusiveCustomLoadout(loadout, accessRank, initialTheme))
	}
	var tab by rememberSaveable { mutableStateOf(ReaderJourneyCustomizeTab.PROFILE_CARD) }
	var rankThemeEnabled by rememberBooleanPref(AppSettings.KEY_RANK_THEME_ENABLED, false)
	val previewTheme = RankThemeId.fromStableId(previewThemeId) ?: initialTheme
	val previewSpec = RankThemeVisualRegistry.resolve(previewTheme)
		?: RankThemeVisualRegistry.resolve(initialTheme)
		?: return
	val previewTokens = remember(previewTheme.stableId) {
		RankThemeRegistry.resolveOrDefault(previewTheme.stableId).tokens(RankThemeVariant.DARK)
	}

	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(
			usePlatformDefaultWidth = false,
			decorFitsSystemWindows = false,
		),
	) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(Color(previewTokens.background.toInt())),
		) {
			ReferenceRankThemeWallpaper(
				spec = previewSpec,
				tokens = previewTokens,
				modifier = Modifier.fillMaxSize(),
			)
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(
						Brush.verticalGradient(
							listOf(
								Color(previewTokens.background.toInt()).copy(alpha = .78f),
								Color(previewTokens.background.toInt()).copy(alpha = .90f),
								Color(previewTokens.background.toInt()),
							),
						),
					),
			)
			Column(
				modifier = Modifier
					.fillMaxSize()
					.statusBarsPadding()
					.navigationBarsPadding(),
			) {
				ExclusiveCustomizerHeader(
					themeName = previewTheme.displayName,
					onDismiss = onDismiss,
				)
				LazyColumn(
					modifier = Modifier.fillMaxSize(),
					contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
					verticalArrangement = Arrangement.spacedBy(14.dp),
				) {
					item("hero") {
						ExclusiveThemeHeroPreview(
							spec = previewSpec,
							tokens = previewTokens,
						)
					}
					item("tabs") {
						ExclusiveCustomizerTabs(
							selected = tab,
							onSelect = { tab = it },
						)
					}
					when (tab) {
						ReaderJourneyCustomizeTab.PROFILE_CARD -> {
							item("frame") {
								ExclusiveFrameSelector(
									specs = unlockedSpecs,
									selectedRank = draft.frame,
									onSelect = { spec ->
										draft = draft.copy(
											mode = ReaderJourneyCosmeticMode.CUSTOM,
											frame = spec.themeId.rank,
										)
										previewThemeId = spec.themeId.stableId
									},
								)
							}
							item("nameplate") {
								ExclusiveNameplateSelector(
									specs = unlockedSpecs,
									selectedCardId = draft.selectedReaderCardId,
									onSelect = { spec ->
										draft = draft.copy(
											mode = ReaderJourneyCosmeticMode.CUSTOM,
											selectedReaderCardId = spec.cardId,
										)
										previewThemeId = spec.themeId.stableId
									},
								)
							}
							item("wallpaper") {
								ExclusiveWallpaperSelector(
									specs = unlockedSpecs,
									selectedWallpaperId = draft.selectedWallpaperId,
									onSelect = { spec ->
										draft = draft.copy(
											mode = ReaderJourneyCosmeticMode.CUSTOM,
											selectedWallpaperId = spec.wallpaperId,
											background = spec.themeId.rank,
										)
										previewThemeId = spec.themeId.stableId
									},
								)
							}
							item("accent") {
								ExclusiveAccentSelector(
									specs = unlockedSpecs,
									selectedThemeId = previewThemeId,
									onSelect = { spec ->
										draft = draft.copy(
											mode = ReaderJourneyCosmeticMode.CUSTOM,
											selectedThemeId = spec.themeId.stableId,
											selectedBadgeId = spec.badgeId,
											selectedProgressStyleId = spec.progressId,
											glow = spec.themeId.rank,
											progressBar = spec.themeId.rank,
										)
										previewThemeId = spec.themeId.stableId
									},
								)
							}
						}
						ReaderJourneyCustomizeTab.NAVIGATION -> {
							item("navigation-preview") {
								ExclusiveNavigationPreview(
									spec = previewSpec,
									tokens = previewTokens,
								)
							}
							item("navigation-accent") {
								ExclusiveAccentSelector(
									specs = unlockedSpecs,
									selectedThemeId = previewThemeId,
									onSelect = { spec ->
										draft = draft.copy(
											mode = ReaderJourneyCosmeticMode.CUSTOM,
											selectedThemeId = spec.themeId.stableId,
											glow = spec.themeId.rank,
										)
										previewThemeId = spec.themeId.stableId
									},
								)
							}
						}
						ReaderJourneyCustomizeTab.READER -> {
							item("reader-preview") {
								ExclusiveReaderCompatibilityPreview(
									spec = previewSpec,
									tokens = previewTokens,
								)
							}
						}
					}
					item("exclusive-switch") {
						ExclusiveThemeSwitchCard(
							enabled = rankThemeEnabled,
							onEnabledChange = { rankThemeEnabled = it },
						)
					}
					item("apply") {
						ExclusiveApplyButton(
							tokens = previewTokens,
							onClick = {
								val finalSpec = RankThemeVisualRegistry.resolve(previewTheme) ?: previewSpec
								val finalDraft = draft.copy(
									mode = ReaderJourneyCosmeticMode.CUSTOM,
									selectedThemeId = previewTheme.stableId,
									selectedBadgeId = draft.selectedBadgeId ?: finalSpec.badgeId,
									selectedWallpaperId = draft.selectedWallpaperId ?: finalSpec.wallpaperId,
									selectedReaderCardId = draft.selectedReaderCardId ?: finalSpec.cardId,
									selectedProgressStyleId = draft.selectedProgressStyleId ?: finalSpec.progressId,
									frame = draft.frame ?: previewTheme.rank,
									glow = draft.glow ?: previewTheme.rank,
									background = draft.background ?: previewTheme.rank,
									progressBar = draft.progressBar ?: previewTheme.rank,
								)
								onApply(ReaderJourneyCosmeticPolicy.sanitizeForRank(finalDraft, accessRank))
							},
						)
					}
				}
			}
		}
	}
}

private fun seedExclusiveCustomLoadout(
	loadout: ReaderJourneyCosmeticLoadout,
	currentRank: ReaderRank,
	theme: RankThemeId,
): ReaderJourneyCosmeticLoadout {
	val spec = RankThemeVisualRegistry.resolve(theme) ?: return loadout
	return ReaderJourneyCosmeticPolicy.sanitizeForRank(
		loadout.copy(
			mode = ReaderJourneyCosmeticMode.CUSTOM,
			selectedThemeId = loadout.selectedThemeId ?: theme.stableId,
			selectedBadgeId = loadout.selectedBadgeId ?: spec.badgeId,
			selectedWallpaperId = loadout.selectedWallpaperId ?: spec.wallpaperId,
			selectedReaderCardId = loadout.selectedReaderCardId ?: spec.cardId,
			selectedProgressStyleId = loadout.selectedProgressStyleId ?: spec.progressId,
			frame = loadout.frame ?: theme.rank,
			glow = loadout.glow ?: theme.rank,
			background = loadout.background ?: theme.rank,
			progressBar = loadout.progressBar ?: theme.rank,
		),
		currentRank,
	)
}

@Composable
private fun ExclusiveCustomizerHeader(
	themeName: String,
	onDismiss: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 10.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Box(
			modifier = Modifier
				.size(42.dp)
				.clip(CircleShape)
				.background(Color.Black.copy(alpha = .30f))
				.clickable(onClick = onDismiss),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				painter = painterResource(R.drawable.ic_arrow_back),
				contentDescription = null,
				tint = Color.White,
				modifier = Modifier.size(22.dp),
			)
		}
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = stringResource(R.string.reader_journey_customize_title),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
				color = Color.White,
			)
			Text(
				text = themeName,
				style = MaterialTheme.typography.labelSmall,
				color = Color.White.copy(alpha = .66f),
			)
		}
	}
}

@Composable
private fun ExclusiveThemeHeroPreview(
	spec: ReferenceRankThemeVisualSpec,
	tokens: RankThemeTokens,
) {
	val shape = RoundedCornerShape(20.dp)
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(154.dp)
			.shadow(12.dp, shape, clip = false)
			.clip(shape)
			.border(
				BorderStroke(
					1.5.dp,
					Brush.horizontalGradient(
						listOf(
							Color(tokens.primaryAccent.toInt()).copy(alpha = .90f),
							Color(tokens.secondaryAccent.toInt()).copy(alpha = .82f),
							Color.White.copy(alpha = .30f),
						),
					),
				),
				shape,
			),
	) {
		ReferenceRankThemeWallpaper(
			spec = spec,
			tokens = tokens,
			modifier = Modifier.fillMaxSize(),
		)
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(
					Brush.verticalGradient(
						listOf(Color.Transparent, Color.Black.copy(alpha = .18f), Color.Black.copy(alpha = .76f)),
					),
				),
		)
		Row(
			modifier = Modifier
				.align(Alignment.BottomStart)
				.fillMaxWidth()
				.padding(12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			ReferenceRankThemeBadge(
				spec = spec,
				tokens = tokens,
				modifier = Modifier.size(42.dp),
			)
			Column {
				Text(
					text = spec.themeId.displayName,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Bold,
					color = Color.White,
				)
				Text(
					text = stringResource(R.string.reader_journey_exclusive_preview),
					style = MaterialTheme.typography.labelSmall,
					color = Color.White.copy(alpha = .70f),
				)
			}
		}
	}
}

@Composable
private fun ExclusiveCustomizerTabs(
	selected: ReaderJourneyCustomizeTab,
	onSelect: (ReaderJourneyCustomizeTab) -> Unit,
) {
	val shape = RoundedCornerShape(18.dp)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clip(shape)
			.background(Color.Black.copy(alpha = .30f))
			.border(1.dp, Color.White.copy(alpha = .12f), shape)
			.padding(3.dp),
		horizontalArrangement = Arrangement.spacedBy(3.dp),
	) {
		ReaderJourneyCustomizeTab.entries.forEach { tab ->
			val active = tab == selected
			Box(
				modifier = Modifier
					.weight(1f)
					.clip(RoundedCornerShape(15.dp))
					.background(
						if (active) {
							Brush.horizontalGradient(
								listOf(
									MaterialTheme.colorScheme.primary.copy(alpha = .96f),
									MaterialTheme.colorScheme.tertiary.copy(alpha = .88f),
								),
							)
						} else {
							Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
						},
					)
					.clickable { onSelect(tab) }
					.padding(horizontal = 4.dp, vertical = 10.dp),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = stringResource(tab.labelRes),
					style = MaterialTheme.typography.labelMedium,
					fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
					color = Color.White.copy(alpha = if (active) 1f else .70f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
private fun ExclusiveSectionTitle(
	title: String,
	subtitle: String? = null,
) {
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.Bold,
				color = Color.White,
				modifier = Modifier.weight(1f),
			)
			Text(
				text = "›",
				style = MaterialTheme.typography.titleLarge,
				color = Color.White.copy(alpha = .42f),
			)
		}
		if (subtitle != null) {
			Text(
				text = subtitle,
				style = MaterialTheme.typography.labelSmall,
				color = Color.White.copy(alpha = .56f),
			)
		}
	}
}

@Composable
private fun ExclusiveFrameSelector(
	specs: List<ReferenceRankThemeVisualSpec>,
	selectedRank: ReaderRank?,
	onSelect: (ReferenceRankThemeVisualSpec) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
		ExclusiveSectionTitle(stringResource(R.string.reader_journey_customize_profile_frame))
		LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			items(specs, key = { it.frameId }) { spec ->
				val tokens = remember(spec.themeId) {
					RankThemeRegistry.resolveOrDefault(spec.themeId.stableId).tokens(RankThemeVariant.DARK)
				}
				val selected = selectedRank == spec.themeId.rank
				Box(
					modifier = Modifier
						.size(60.dp)
						.clip(RoundedCornerShape(13.dp))
						.background(Color.Black.copy(alpha = .24f))
						.border(
							if (selected) 2.dp else 1.dp,
							if (selected) Color(tokens.primaryAccent.toInt())
							else Color.White.copy(alpha = .16f),
							RoundedCornerShape(13.dp),
						)
						.clickable { onSelect(spec) }
						.padding(7.dp),
				) {
					ReferenceRankThemeFrame(
						spec = spec,
						tokens = tokens,
						modifier = Modifier.fillMaxSize(),
					) {
						Box(
							modifier = Modifier
								.fillMaxSize()
								.clip(CircleShape)
								.background(Color(tokens.surfaceVariant.toInt())),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun ExclusiveNameplateSelector(
	specs: List<ReferenceRankThemeVisualSpec>,
	selectedCardId: String?,
	onSelect: (ReferenceRankThemeVisualSpec) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
		ExclusiveSectionTitle(stringResource(R.string.reader_journey_customize_nameplate))
		LazyRow(
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			contentPadding = PaddingValues(horizontal = 3.dp, vertical = 3.dp),
		) {
			items(specs, key = { it.nameplateId }) { spec ->
				val tokens = remember(spec.themeId) {
					RankThemeRegistry.resolveOrDefault(spec.themeId.stableId).tokens(RankThemeVariant.DARK)
				}
				val selected = selectedCardId == spec.cardId
				Box(
					modifier = Modifier
						.width(154.dp)
						.height(56.dp)
						.shadow(if (selected) 10.dp else 3.dp, RoundedCornerShape(14.dp), clip = false)
						.clickable { onSelect(spec) },
					contentAlignment = Alignment.Center,
				) {
					ReferenceRankThemeNameplate(
						spec = spec,
						tokens = tokens,
						modifier = Modifier.fillMaxSize(),
					) {
						Text(
							text = stringResource(spec.themeId.rank.titleRes),
							style = MaterialTheme.typography.labelMedium,
							fontWeight = FontWeight.Bold,
							color = Color.White,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							textAlign = TextAlign.Center,
						)
					}
					if (selected) {
						Box(
							modifier = Modifier
								.align(Alignment.TopEnd)
								.size(15.dp)
								.clip(CircleShape)
								.background(Color(tokens.primaryAccent.toInt()))
								.border(1.dp, Color.White.copy(alpha = .84f), CircleShape),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun ExclusiveWallpaperSelector(
	specs: List<ReferenceRankThemeVisualSpec>,
	selectedWallpaperId: String?,
	onSelect: (ReferenceRankThemeVisualSpec) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
		ExclusiveSectionTitle(stringResource(R.string.reader_journey_customize_wallpaper))
		LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
			items(specs, key = { it.wallpaperId }) { spec ->
				val tokens = remember(spec.themeId) {
					RankThemeRegistry.resolveOrDefault(spec.themeId.stableId).tokens(RankThemeVariant.DARK)
				}
				val selected = selectedWallpaperId == spec.wallpaperId
				Box(
					modifier = Modifier
						.width(76.dp)
						.height(64.dp)
						.clip(RoundedCornerShape(12.dp))
						.border(
							if (selected) 2.dp else 1.dp,
							if (selected) Color(tokens.primaryAccent.toInt())
							else Color.White.copy(alpha = .14f),
							RoundedCornerShape(12.dp),
						)
						.clickable { onSelect(spec) },
				) {
					ReferenceRankThemeWallpaper(
						spec = spec,
						tokens = tokens,
						modifier = Modifier.fillMaxSize(),
					)
				}
			}
		}
	}
}

@Composable
private fun ExclusiveAccentSelector(
	specs: List<ReferenceRankThemeVisualSpec>,
	selectedThemeId: String?,
	onSelect: (ReferenceRankThemeVisualSpec) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
		ExclusiveSectionTitle(stringResource(R.string.reader_journey_customize_accent_glow))
		LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			items(specs, key = { it.themeId.stableId }) { spec ->
				val tokens = remember(spec.themeId) {
					RankThemeRegistry.resolveOrDefault(spec.themeId.stableId).tokens(RankThemeVariant.DARK)
				}
				val selected = selectedThemeId == spec.themeId.stableId
				Box(
					modifier = Modifier
						.size(42.dp)
						.clip(CircleShape)
						.background(
							Brush.radialGradient(
								listOf(
									Color(tokens.secondaryAccent.toInt()),
									Color(tokens.primaryAccent.toInt()),
									Color(tokens.primaryAccent.toInt()).copy(alpha = .24f),
								),
							),
						)
						.border(
							if (selected) 2.5.dp else 1.dp,
							if (selected) Color.White.copy(alpha = .92f)
							else Color.White.copy(alpha = .24f),
							CircleShape,
						)
						.clickable { onSelect(spec) },
				)
			}
		}
	}
}

@Composable
private fun ExclusiveNavigationPreview(
	spec: ReferenceRankThemeVisualSpec,
	tokens: RankThemeTokens,
) {
	Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
		ExclusiveSectionTitle(
			title = stringResource(R.string.reader_journey_customize_navigation_preview),
			subtitle = stringResource(R.string.reader_journey_customize_navigation_note),
		)
		val shape = RoundedCornerShape(22.dp)
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(76.dp)
				.shadow(10.dp, shape, clip = false)
				.clip(shape)
				.background(Color(tokens.surface.toInt()).copy(alpha = .94f))
				.border(
					BorderStroke(
						1.5.dp,
						Brush.horizontalGradient(
							listOf(
								Color(tokens.primaryAccent.toInt()),
								Color(tokens.secondaryAccent.toInt()),
								Color(tokens.primaryAccent.toInt()),
							),
						),
					),
					shape,
				)
				.padding(horizontal = 12.dp),
		) {
			Row(
				modifier = Modifier.fillMaxSize(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceAround,
			) {
				repeat(5) { index ->
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Box(
							modifier = Modifier
								.size(if (index == 0) 30.dp else 22.dp)
								.clip(CircleShape)
								.background(
									if (index == 0) Color(tokens.primaryAccent.toInt()).copy(alpha = .28f)
									else Color.Transparent,
								)
								.border(
									1.dp,
									if (index == 0) Color(tokens.primaryAccent.toInt()).copy(alpha = .86f)
									else Color.White.copy(alpha = .32f),
									CircleShape,
								),
						)
						Spacer(Modifier.height(4.dp))
						Box(
							modifier = Modifier
								.width(22.dp)
								.height(2.dp)
								.background(
									if (index == 0) Color(tokens.primaryAccent.toInt())
									else Color.White.copy(alpha = .18f),
									RoundedCornerShape(2.dp),
								),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun ExclusiveReaderCompatibilityPreview(
	spec: ReferenceRankThemeVisualSpec,
	tokens: RankThemeTokens,
) {
	Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
		ExclusiveSectionTitle(
			title = stringResource(R.string.reader_journey_customize_reader_preview),
			subtitle = stringResource(R.string.reader_journey_customize_reader_unchanged),
		)
		ReferenceRankThemeCard(
			spec = spec,
			tokens = tokens,
			modifier = Modifier.fillMaxWidth(),
		) {
			Column(
				modifier = Modifier.fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = stringResource(R.string.reader_journey_customize_reader_sample_title),
						style = MaterialTheme.typography.titleSmall,
						fontWeight = FontWeight.Bold,
						color = Color.White,
						modifier = Modifier.weight(1f),
					)
					Text(
						text = "5 / 24",
						style = MaterialTheme.typography.labelMedium,
						color = Color.White.copy(alpha = .66f),
					)
				}
				ReferenceRankThemeProgress(
					spec = spec,
					tokens = tokens,
					progress = .42f,
					modifier = Modifier
						.fillMaxWidth()
						.height(9.dp),
				)
			}
		}
	}
}

@Composable
private fun ExclusiveThemeSwitchCard(
	enabled: Boolean,
	onEnabledChange: (Boolean) -> Unit,
) {
	Surface(
		shape = RoundedCornerShape(18.dp),
		color = Color.Black.copy(alpha = .30f),
		border = BorderStroke(1.dp, Color.White.copy(alpha = .13f)),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.reader_journey_exclusive_theme_enabled),
					style = MaterialTheme.typography.bodyLarge,
					fontWeight = FontWeight.Bold,
					color = Color.White,
				)
				Text(
					text = stringResource(R.string.reader_journey_exclusive_theme_enabled_summary),
					style = MaterialTheme.typography.bodySmall,
					color = Color.White.copy(alpha = .58f),
				)
			}
			Switch(
				checked = enabled,
				onCheckedChange = onEnabledChange,
			)
		}
	}
}

@Composable
private fun ExclusiveApplyButton(
	tokens: RankThemeTokens,
	onClick: () -> Unit,
) {
	val shape = RoundedCornerShape(17.dp)
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(50.dp)
			.shadow(12.dp, shape, clip = false)
			.clip(shape)
			.background(
				Brush.horizontalGradient(
					listOf(
						Color(tokens.primaryAccent.toInt()),
						Color(tokens.secondaryAccent.toInt()),
						Color(tokens.primaryAccent.toInt()),
					),
				),
			)
			.border(1.dp, Color.White.copy(alpha = .28f), shape)
			.clickable(onClick = onClick),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = stringResource(R.string.reader_journey_customize_apply_theme),
			style = MaterialTheme.typography.labelLarge,
			fontWeight = FontWeight.Bold,
			color = Color.White,
			textAlign = TextAlign.Center,
		)
	}
}
