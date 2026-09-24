package org.koitharu.kotatsu.settings.developer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.miyorareThemeColors
import org.koitharu.kotatsu.readerjourney.theme.RankThemeDefinition
import org.koitharu.kotatsu.readerjourney.theme.RankThemeRegistry
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVariant
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme

/**
 * Developer-only visual gallery for the Rank Theme contract.
 *
 * This surface is intentionally read-only. It previews semantic theme definitions and never mutates
 * Lifetime XP, Reader Rank, unlock ownership, persisted cosmetic loadout or production preferences.
 */
class RankThemeGalleryFragment : BaseComposeSettingsFragment(R.string.developer_rank_theme_gallery) {

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				RankThemeGalleryScreen()
			}
		}
	}
}

@Composable
private fun RankThemeGalleryScreen() {
	val selectedVariant = remember { mutableStateOf(RankThemeVariant.DARK) }
	val definitions = remember { RankThemeRegistry.definitions }

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		item {
			Text(
				text = stringResource(R.string.developer_rank_theme_gallery_summary),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		item {
			LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				items(RankThemeVariant.entries, key = { it.name }) { variant ->
					FilterChip(
						selected = selectedVariant.value == variant,
						onClick = { selectedVariant.value = variant },
						label = { Text(variant.name) },
					)
				}
			}
		}
		items(definitions, key = { it.id.stableId }) { definition ->
			RankThemePreviewCard(
				definition = definition,
				variant = selectedVariant.value,
			)
		}
	}
}

@Composable
private fun RankThemePreviewCard(
	definition: RankThemeDefinition,
	variant: RankThemeVariant,
) {
	val tokens = remember(definition.id, variant) { definition.tokens(variant) }
	val preview = remember(definition.id, variant) {
		miyorareThemeColors(
			preset = MiyorareThemePreset.MIYORARE,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = variant != RankThemeVariant.LIGHT,
			amoled = variant == RankThemeVariant.OLED,
			effectLevel = VisualEffectLevel.BALANCED,
			rankThemeTokens = tokens,
		)
	}
	val outerTypography = MaterialTheme.typography
	val outerShapes = MaterialTheme.shapes

	CompositionLocalProvider(LocalMiyorareVisualPalette provides preview.visualPalette) {
		MaterialTheme(
			colorScheme = preview.colorScheme,
			typography = outerTypography,
			shapes = outerShapes,
		) {
			Surface(
				modifier = Modifier.fillMaxWidth(),
				shape = RoundedCornerShape(24.dp),
				color = MaterialTheme.colorScheme.surface,
				contentColor = MaterialTheme.colorScheme.onSurface,
				border = BorderStroke(1.dp, preview.visualPalette.borderHighlight),
				shadowElevation = 2.dp,
			) {
				Column(
					modifier = Modifier.padding(16.dp),
					verticalArrangement = Arrangement.spacedBy(12.dp),
				) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically,
					) {
						Column(modifier = Modifier.weight(1f)) {
							Text(
								text = definition.id.displayName,
								style = MaterialTheme.typography.titleMedium,
								fontWeight = FontWeight.Bold,
							)
							Text(
								text = definition.id.rank.name.replace('_', ' '),
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						Surface(
							shape = CircleShape,
							color = MaterialTheme.colorScheme.primaryContainer,
							contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
						) {
							Text(
								text = "Lv " + definition.id.rank.minLevel,
								modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
								style = MaterialTheme.typography.labelMedium,
							)
						}
					}

					Text(
						text = definition.id.stableId,
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)

					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						ColorDot(MaterialTheme.colorScheme.primary)
						ColorDot(MaterialTheme.colorScheme.secondary)
						ColorDot(MaterialTheme.colorScheme.tertiary)
						ColorDot(preview.visualPalette.glow)
					}

					LinearProgressIndicator(
						progress = { 0.68f },
						modifier = Modifier
							.fillMaxWidth()
							.height(8.dp),
						color = MaterialTheme.colorScheme.primary,
						trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
					)

					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						SemanticStatusChip(
							label = stringResource(R.string.developer_theme_status_success),
							color = preview.visualPalette.success,
							modifier = Modifier.weight(1f),
						)
						SemanticStatusChip(
							label = stringResource(R.string.developer_theme_status_warning),
							color = preview.visualPalette.warning,
							modifier = Modifier.weight(1f),
						)
						SemanticStatusChip(
							label = stringResource(R.string.developer_theme_status_error),
							color = preview.visualPalette.error,
							modifier = Modifier.weight(1f),
						)
					}

					Surface(
						shape = RoundedCornerShape(16.dp),
						color = MaterialTheme.colorScheme.surfaceContainer,
						contentColor = MaterialTheme.colorScheme.onSurface,
					) {
						Column(
							modifier = Modifier.padding(12.dp),
							verticalArrangement = Arrangement.spacedBy(4.dp),
						) {
							Text(
								text = stringResource(R.string.developer_theme_sample_title),
								style = MaterialTheme.typography.bodyLarge,
								fontWeight = FontWeight.SemiBold,
							)
							Text(
								text = stringResource(R.string.developer_theme_sample_body),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun ColorDot(color: Color) {
	Box(
		modifier = Modifier.size(22.dp),
		contentAlignment = Alignment.Center,
	) {
		Surface(
			modifier = Modifier.size(18.dp),
			shape = CircleShape,
			color = color,
		) {}
	}
}

@Composable
private fun SemanticStatusChip(
	label: String,
	color: Color,
	modifier: Modifier = Modifier,
) {
	Surface(
		modifier = modifier,
		shape = RoundedCornerShape(10.dp),
		color = color.copy(alpha = 0.16f),
		contentColor = color,
	) {
		Text(
			text = label,
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
			style = MaterialTheme.typography.labelSmall,
			fontWeight = FontWeight.SemiBold,
		)
	}
}
