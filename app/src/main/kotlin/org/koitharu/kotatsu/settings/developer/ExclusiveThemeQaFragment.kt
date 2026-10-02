package org.koitharu.kotatsu.settings.developer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveThemeQaComponent
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveThemeQaMotionOverride
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveThemeQaRewardState
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveThemeQaState
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveThemeQaStore
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeRegistry
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVariant
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVisualRegistry
import org.koitharu.kotatsu.readerjourney.ui.BadgeState
import org.koitharu.kotatsu.readerjourney.ui.ProfileFrameState
import org.koitharu.kotatsu.readerjourney.ui.ReferenceRankThemeBadge
import org.koitharu.kotatsu.readerjourney.ui.ReferenceRankThemeFrame
import org.koitharu.kotatsu.readerjourney.ui.ReferenceRankThemeNameplate
import org.koitharu.kotatsu.readerjourney.ui.ReferenceRankThemeWallpaper
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import javax.inject.Inject

/**
 * Interactive Beta/debug control surface for the real Exclusive Theme runtime.
 *
 * It does not call ReaderProfileStore, ReaderJourneyCosmeticPolicy mutation APIs, or the XP ledger.
 * All controls write only to ExclusiveThemeQaStore's isolated local preference file.
 */
@AndroidEntryPoint
class ExclusiveThemeQaFragment : BaseComposeSettingsFragment(R.string.developer_exclusive_theme_qa) {

	@Inject
	lateinit var qaStore: ExclusiveThemeQaStore

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				val state by qaStore.state.collectAsState()
				ExclusiveThemeQaScreen(
					state = state,
					store = qaStore,
				)
			}
		}
	}
}

@Composable
private fun ExclusiveThemeQaScreen(
	state: ExclusiveThemeQaState,
	store: ExclusiveThemeQaStore,
) {
	if (!BuildConfig.EXCLUSIVE_THEME_QA_ENABLED) {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			Text("Exclusive Theme QA is disabled for this build.")
		}
		return
	}

	val selectedTheme = state.selectedTheme
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(16.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		item {
			Surface(
				modifier = Modifier.fillMaxWidth(),
				shape = MaterialTheme.shapes.extraLarge,
				color = MaterialTheme.colorScheme.surfaceContainer,
			) {
				Column(
					modifier = Modifier.padding(16.dp),
					verticalArrangement = Arrangement.spacedBy(10.dp),
				) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Column(modifier = Modifier.weight(1f)) {
							Text("Exclusive Theme QA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
							Text(
								"Beta/debug runtime override. XP, rank, ownership and normal equipped state are not changed.",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						Switch(
							checked = state.enabled,
							onCheckedChange = store::setEnabled,
						)
					}
					Text(
						if (state.isActive) "QA Override: ON" else "QA Override: OFF",
						style = MaterialTheme.typography.labelLarge,
						color = if (state.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}

		item {
			SectionTitle("Theme")
			LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				items(RankThemeId.entries, key = { it.stableId }) { theme ->
					FilterChip(
						selected = theme == selectedTheme,
						onClick = { store.setTheme(theme) },
						label = { Text("${theme.rank.minLevel} · ${theme.displayName}") },
					)
				}
			}
		}

		item {
			SectionTitle("Apply")
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				FilterChip(
					selected = state.fullTheme,
					onClick = { store.setFullTheme(true) },
					label = { Text("Full Theme") },
				)
				FilterChip(
					selected = !state.fullTheme,
					onClick = { store.setFullTheme(false) },
					label = { Text("Components") },
				)
			}
		}

		if (!state.fullTheme) {
			item {
				SectionTitle("Component Override")
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					ExclusiveThemeQaComponent.entries.chunked(2).forEach { row ->
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.spacedBy(8.dp),
						) {
							row.forEach { component ->
								FilterChip(
									selected = component in state.components,
									onClick = {
										store.setComponent(component, component !in state.components)
									},
									label = { Text(component.label()) },
									modifier = Modifier.weight(1f),
								)
							}
							if (row.size == 1) Box(Modifier.weight(1f))
						}
					}
				}
			}
		}

		item {
			SectionTitle("Motion · Reduce Motion")
			LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				items(ExclusiveThemeQaMotionOverride.entries, key = { it.name }) { option ->
					FilterChip(
						selected = state.reduceMotion == option,
						onClick = { store.setReduceMotion(option) },
						label = { Text(option.motionLabel()) },
					)
				}
			}
		}

		item {
			SectionTitle("Motion · Battery Saver")
			LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				items(ExclusiveThemeQaMotionOverride.entries, key = { it.name }) { option ->
					FilterChip(
						selected = state.batterySaver == option,
						onClick = { store.setBatterySaver(option) },
						label = { Text(option.motionLabel()) },
					)
				}
			}
		}

		item {
			SectionTitle("State Simulation")
			LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				items(ExclusiveThemeQaRewardState.entries, key = { it.name }) { option ->
					FilterChip(
						selected = state.rewardState == option,
						onClick = { store.setRewardState(option) },
						label = { Text(option.name) },
					)
				}
			}
			Text(
				"State simulation is visual-only inside this QA surface; it never changes reward ownership.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}

		item {
			ExclusiveThemeQaPreview(state)
		}

		item {
			OutlinedButton(
				onClick = store::reset,
				modifier = Modifier.fillMaxWidth(),
			) {
				Text("Reset QA Override")
			}
		}

		item {
			Button(
				onClick = { store.setEnabled(!state.enabled) },
				modifier = Modifier.fillMaxWidth(),
			) {
				Text(if (state.enabled) "Disable QA and return to original progression" else "Enable QA Override")
			}
		}
	}
}

@Composable
private fun ExclusiveThemeQaPreview(state: ExclusiveThemeQaState) {
	val theme = state.selectedTheme
	val spec = RankThemeVisualRegistry.resolve(theme) ?: return
	val tokens = RankThemeRegistry.resolveOrDefault(theme.stableId).tokens(RankThemeVariant.DARK)
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.extraLarge,
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Column(
			modifier = Modifier.padding(14.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text("Runtime asset preview · ${theme.displayName}", fontWeight = FontWeight.SemiBold)
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(190.dp),
				contentAlignment = Alignment.Center,
			) {
				ReferenceRankThemeWallpaper(
					spec = spec,
					tokens = tokens,
					modifier = Modifier.fillMaxSize(),
				)
				ReferenceRankThemeFrame(
					spec = spec,
					tokens = tokens,
					state = state.rewardState.frameState(),
					animate = true,
					modifier = Modifier.size(116.dp),
				) {
					ReferenceRankThemeBadge(
						spec = spec,
						tokens = tokens,
						state = state.rewardState.badgeState(),
						animate = true,
						modifier = Modifier
							.fillMaxSize()
							.padding(18.dp),
					)
				}
			}
			ReferenceRankThemeNameplate(
				spec = spec,
				tokens = tokens,
				modifier = Modifier
					.fillMaxWidth()
					.height(56.dp),
			)
		}
	}
}

@Composable
private fun SectionTitle(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.titleMedium,
		fontWeight = FontWeight.SemiBold,
		modifier = Modifier.padding(bottom = 6.dp),
	)
}

private fun ExclusiveThemeQaComponent.label(): String = when (this) {
	ExclusiveThemeQaComponent.NAVIGATION -> "Navigation"
	ExclusiveThemeQaComponent.PROFILE_FRAME -> "Profile Frame"
	ExclusiveThemeQaComponent.BADGE -> "Badge"
	ExclusiveThemeQaComponent.NAMEPLATE -> "Nameplate"
	ExclusiveThemeQaComponent.WALLPAPER -> "Wallpaper"
	ExclusiveThemeQaComponent.ACCENT_GLOW -> "Accent / Glow"
}

private fun ExclusiveThemeQaMotionOverride.motionLabel(): String = when (this) {
	ExclusiveThemeQaMotionOverride.SYSTEM -> "System / Normal"
	ExclusiveThemeQaMotionOverride.FORCE_ON -> "Force ON"
	ExclusiveThemeQaMotionOverride.FORCE_OFF -> "Force OFF"
}

private fun ExclusiveThemeQaRewardState.frameState(): ProfileFrameState = when (this) {
	ExclusiveThemeQaRewardState.LOCKED -> ProfileFrameState.LOCKED
	ExclusiveThemeQaRewardState.PREVIEWING -> ProfileFrameState.PREVIEWING
	ExclusiveThemeQaRewardState.EQUIPPED -> ProfileFrameState.EQUIPPED
	ExclusiveThemeQaRewardState.NORMAL,
	ExclusiveThemeQaRewardState.UNLOCKED -> ProfileFrameState.UNLOCKED
}

private fun ExclusiveThemeQaRewardState.badgeState(): BadgeState = when (this) {
	ExclusiveThemeQaRewardState.LOCKED -> BadgeState.LOCKED
	ExclusiveThemeQaRewardState.PREVIEWING -> BadgeState.PREVIEWING
	ExclusiveThemeQaRewardState.EQUIPPED -> BadgeState.EQUIPPED
	ExclusiveThemeQaRewardState.NORMAL,
	ExclusiveThemeQaRewardState.UNLOCKED -> BadgeState.UNLOCKED
}
