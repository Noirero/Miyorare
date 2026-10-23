package org.koitharu.kotatsu.settings.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAdaptivePalette
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.resolveMiyorareClassicDerivedColorScheme
import org.koitharu.kotatsu.core.ui.resolveMiyorareThemeColors
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect

/** Lightweight authored-identity previews. Artwork stays out of the picker on purpose. */
@Composable
fun MiyorareVisualThemePickerRow(
    selectedValue: String,
    onValueChange: (String) -> Unit,
    customAccent: String,
    adaptivePalette: MiyorareAdaptivePalette?,
    darkTheme: Boolean,
    amoled: Boolean,
    effectLevel: VisualEffectLevel,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    val context = LocalContext.current
    val entries = remember(customAccent, adaptivePalette, darkTheme, amoled, effectLevel) {
        MiyorareThemePreset.entries.map { preset ->
            val colors = resolveMiyorareThemeColors(
                paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
                preset = preset,
                customAccent = customAccent,
                adaptivePalette = if (preset == MiyorareThemePreset.CUSTOM) adaptivePalette else null,
                darkTheme = darkTheme,
                amoled = amoled,
                effectLevel = effectLevel,
            ).colorScheme
            ModernPreviewEntry(
                key = preset.name,
                label = context.getString(preset.titleResId),
                colors = colors.toPreviewColors(),
            )
        }
    }
    MiyorarePreviewPickerRow(
        title = stringResource(R.string.miyorare_modern_visual_theme),
        subtitle = stringResource(R.string.miyorare_modern_visual_theme_summary),
        entries = entries,
        selectedValue = selectedValue,
        onValueChange = onValueChange,
        shape = shape,
    )
}

/** Semantic-palette previews. Classic artwork/shapes never enter this path. */
@Composable
fun MiyorarePalettePickerRow(
    selectedValue: String,
    onValueChange: (String) -> Unit,
    visualPreset: MiyorareThemePreset,
    customAccent: String,
    adaptivePalette: MiyorareAdaptivePalette?,
    darkTheme: Boolean,
    amoled: Boolean,
    effectLevel: VisualEffectLevel,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    val context = LocalContext.current
    val availableSources = remember { MiyorareModernPaletteSource.getAvailableList() }
    val entries = remember(
        availableSources,
        visualPreset,
        customAccent,
        adaptivePalette,
        darkTheme,
        amoled,
        effectLevel,
    ) {
        availableSources.map { source ->
            val classicScheme = context.resolveMiyorareClassicDerivedColorScheme(
                paletteSource = source,
                darkTheme = darkTheme,
            )
            val colors = resolveMiyorareThemeColors(
                paletteSource = source,
                preset = visualPreset,
                customAccent = customAccent,
                adaptivePalette = if (visualPreset == MiyorareThemePreset.CUSTOM) adaptivePalette else null,
                darkTheme = darkTheme,
                amoled = amoled,
                effectLevel = effectLevel,
                classicDerivedColorScheme = classicScheme,
            ).colorScheme
            val label = if (source == MiyorareModernPaletteSource.MATCH_THEME) {
                context.getString(R.string.miyorare_palette_match_theme)
            } else {
                val classicTitle = source.classicScheme?.let { context.getString(it.titleResId) }.orEmpty()
                context.getString(R.string.miyorare_palette_classic_format, classicTitle)
            }
            ModernPreviewEntry(
                key = source.name,
                label = label,
                colors = colors.toPreviewColors(),
            )
        }
    }
    MiyorarePreviewPickerRow(
        title = stringResource(R.string.miyorare_colour_palette),
        subtitle = stringResource(R.string.miyorare_colour_palette_summary),
        entries = entries,
        selectedValue = MiyorareModernPaletteSource.fromStoredValue(selectedValue).name,
        onValueChange = onValueChange,
        shape = shape,
    )
}

@Composable
private fun MiyorarePreviewPickerRow(
    title: String,
    subtitle: String,
    entries: List<ModernPreviewEntry>,
    selectedValue: String,
    onValueChange: (String) -> Unit,
    shape: Shape,
) {
    val haptic = rememberHapticEffect()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(4.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.key }) { entry ->
                    MiyorarePreviewCard(
                        entry = entry,
                        selected = entry.key == selectedValue,
                        onClick = {
                            haptic(HapticEffect.CONFIRM)
                            onValueChange(entry.key)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MiyorarePreviewCard(
    entry: ModernPreviewEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cardShape = RoundedCornerShape(16.dp)
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.30f),
        label = "miyorareThemePreviewBorder",
    )
    Column(
        modifier = Modifier
            .width(104.dp)
            .semantics {
                this.selected = selected
                contentDescription = entry.label
            }
            .clickable(role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(width = 96.dp, height = 112.dp),
            shape = cardShape,
            color = entry.colors.surface,
            border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        ) {
            Box(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = stringResource(R.string.miyorare_theme_preview_sample),
                    color = entry.colors.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.align(Alignment.TopStart),
                )
                Row(
                    modifier = Modifier.align(Alignment.BottomStart),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    PreviewSwatch(entry.colors.primary)
                    PreviewSwatch(entry.colors.secondary)
                    PreviewSwatch(entry.colors.tertiary)
                }
                if (selected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = entry.colors.onSurface,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PreviewSwatch(color: Color) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(color, RoundedCornerShape(6.dp)),
    )
}

@Immutable
private data class ModernPreviewEntry(
    val key: String,
    val label: String,
    val colors: ModernPreviewColors,
)

@Immutable
private data class ModernPreviewColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val surface: Color,
    val onSurface: Color,
)

private fun androidx.compose.material3.ColorScheme.toPreviewColors() = ModernPreviewColors(
    primary = primary,
    secondary = secondary,
    tertiary = tertiary,
    surface = surfaceContainer,
    onSurface = onSurface,
)
