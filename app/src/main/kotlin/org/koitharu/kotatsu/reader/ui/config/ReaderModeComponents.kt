package org.koitharu.kotatsu.reader.ui.config

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.ui.sheet.SheetSegment
import org.koitharu.kotatsu.core.ui.sheet.SheetSegmentedSelector
import kotlin.math.roundToInt

@Composable
internal fun ReadModeSection(
    selectedMode: ReaderMode,
    onModeSelected: (ReaderMode) -> Unit,
) {
    val modes = listOf(
        ReaderMode.STANDARD to (R.string.standard to R.drawable.ic_reader_ltr),
        ReaderMode.REVERSED to (R.string.r_to_l to R.drawable.ic_reader_rtl),
        ReaderMode.VERTICAL to (R.string.vertical to R.drawable.ic_reader_vertical),
        ReaderMode.WEBTOON to (R.string.webtoon to R.drawable.ic_reader_webtoon),
    )
    val selectedIndex = modes.indexOfFirst { it.first == selectedMode }.coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SheetSegmentedSelector(
            options = modes.map { (_, pair) ->
                SheetSegment(label = stringResource(pair.first), icon = painterResource(pair.second))
            },
            selectedIndex = selectedIndex,
            onSelect = { index -> onModeSelected(modes[index].first) },
        )

        Text(
            text = stringResource(R.string.reader_mode_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
internal fun DoublePageConfigSection(
    isModeStandardOrReversed: Boolean,
    isDoubleOnLandscape: Boolean,
    onDoubleOnLandscapeChange: (Boolean) -> Unit,
    isDoubleOnFoldable: Boolean,
    onDoubleOnFoldableChange: (Boolean) -> Unit,
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
) {
    val sectionAlpha = if (isModeStandardOrReversed) 1f else 0.55f

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Toggle 1: Use two pages in landscape
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isModeStandardOrReversed) { onDoubleOnLandscapeChange(!isDoubleOnLandscape) }
                    .alpha(sectionAlpha)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_split_horizontal),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.use_two_pages_landscape),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = isDoubleOnLandscape,
                    onCheckedChange = onDoubleOnLandscapeChange,
                    enabled = isModeStandardOrReversed,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f),
            )

            // Keep dependent options visible, but readable and genuinely disabled.
            val subOptionsAlpha = if (isModeStandardOrReversed && isDoubleOnLandscape) 1f else 0.55f

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(subOptionsAlpha),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Toggle 2: Auto double on foldable
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isModeStandardOrReversed && isDoubleOnLandscape) { onDoubleOnFoldableChange(!isDoubleOnFoldable) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.width(28.dp)) // dependent control indentation
                    Text(
                        text = stringResource(R.string.auto_double_foldable),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = isDoubleOnFoldable,
                        onCheckedChange = onDoubleOnFoldableChange,
                        enabled = isModeStandardOrReversed && isDoubleOnLandscape,
                    )
                }

                // Slider: Scroll sensitivity
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(modifier = Modifier.width(28.dp))
                            Text(
                                text = stringResource(R.string.two_page_scroll_sensitivity),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            text = "${sensitivity.roundToInt()}%",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isModeStandardOrReversed && isDoubleOnLandscape) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(28.dp))
                        Slider(
                            value = sensitivity,
                            onValueChange = onSensitivityChange,
                            valueRange = 0f..100f,
                            enabled = isModeStandardOrReversed && isDoubleOnLandscape,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
