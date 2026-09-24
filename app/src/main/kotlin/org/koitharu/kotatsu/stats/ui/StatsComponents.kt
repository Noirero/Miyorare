package org.koitharu.kotatsu.stats.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal val STATS_PADDING = 20.dp
internal val STATS_CARD_CORNER = 28.dp
private const val LIGHT_STATS_CARD_ALPHA = 0.84f

/** Shared glass-like surface used by the redesigned dashboard sections. */
@Composable
internal fun StatsCard(
	modifier: Modifier = Modifier,
	color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
	content: @Composable ColumnScope.() -> Unit,
) {
	val resolvedColor = if (MaterialTheme.colorScheme.background.luminance() >= 0.5f) {
		color.copy(alpha = LIGHT_STATS_CARD_ALPHA)
	} else {
		color
	}
	Surface(
		shape = RoundedCornerShape(STATS_CARD_CORNER),
		color = resolvedColor,
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING),
	) {
		Column(modifier = Modifier.padding(20.dp), content = content)
	}
}

@Composable
internal fun StatsSectionHeader(title: String, trailing: String? = null) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING + 4.dp, vertical = 4.dp),
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.weight(1f),
		)
		if (trailing != null) {
			Text(
				text = trailing,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}
