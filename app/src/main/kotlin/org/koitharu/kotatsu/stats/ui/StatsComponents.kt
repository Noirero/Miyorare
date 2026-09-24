package org.koitharu.kotatsu.stats.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.stats.domain.StatsBucket
import kotlin.math.max

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
		verticalAlignment = Alignment.CenterVertically,
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

/**
 * Kept for the per-title Stats sheet. The main dashboard uses its newer compact metric cards,
 * while this component remains the focused two-column presentation for a single title.
 */
@Composable
internal fun StatTile(
	value: String,
	label: String,
	icon: Painter,
	accent: Color,
	modifier: Modifier = Modifier,
) {
	val tileColor = MaterialTheme.colorScheme.surfaceContainerHigh.let { base ->
		if (MaterialTheme.colorScheme.background.luminance() >= 0.5f) {
			base.copy(alpha = LIGHT_STATS_CARD_ALPHA)
		} else {
			base
		}
	}
	Surface(
		shape = RoundedCornerShape(24.dp),
		color = tileColor,
		modifier = modifier,
	) {
		Column(modifier = Modifier.padding(16.dp)) {
			Box(
				modifier = Modifier
					.size(34.dp)
					.clip(CircleShape)
					.background(accent.copy(alpha = 0.18f)),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painter = icon,
					contentDescription = null,
					tint = accent,
					modifier = Modifier.size(19.dp),
				)
			}
			Spacer(Modifier.height(12.dp))
			Text(
				text = value,
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = label,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/**
 * Retained only for the per-title details sheet. The dashboard itself now uses Reading Heatmap,
 * so keeping this focused component avoids reviving the old top-level chart implementation.
 */
@Composable
internal fun ActivityBarChart(
	buckets: List<StatsBucket>,
	labels: List<String?>,
	selectedIndex: Int,
	onSelect: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
	val barColor = MaterialTheme.colorScheme.primary
	val selectedColor = MaterialTheme.colorScheme.tertiary
	val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
	val maxValue = remember(buckets) { buckets.maxOfOrNull { it.duration } ?: 0L }
	val progress by animateFloatAsState(
		targetValue = if (buckets.isEmpty()) 0f else 1f,
		animationSpec = tween(durationMillis = 520),
		label = "barChart",
	)
	val density = LocalDensity.current
	Column(modifier = modifier) {
		Canvas(
			modifier = Modifier
				.fillMaxWidth()
				.height(132.dp)
				.pointerInput(buckets.size) {
					detectTapGestures { offset ->
						if (buckets.isEmpty()) return@detectTapGestures
						val slot = size.width.toFloat() / buckets.size
						val index = (offset.x / slot).toInt().coerceIn(0, buckets.lastIndex)
						onSelect(if (index == selectedIndex) -1 else index)
					}
				},
		) {
			if (buckets.isEmpty()) return@Canvas
			val slot = size.width / buckets.size
			val gap = (slot * 0.28f).coerceAtMost(with(density) { 8.dp.toPx() })
			val barWidth = (slot - gap).coerceAtLeast(2f)
			val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
			val minBar = barWidth.coerceAtMost(size.height)
			buckets.forEachIndexed { index, bucket ->
				val left = index * slot + gap / 2f
				drawRoundRect(
					color = trackColor,
					topLeft = Offset(left, 0f),
					size = Size(barWidth, size.height),
					cornerRadius = radius,
				)
				if (bucket.duration <= 0L || maxValue <= 0L) return@forEachIndexed
				val ratio = (bucket.duration.toFloat() / maxValue).coerceIn(0f, 1f) * progress
				val barHeight = max(minBar, size.height * ratio)
				drawRoundRect(
					color = if (index == selectedIndex) selectedColor else barColor,
					topLeft = Offset(left, size.height - barHeight),
					size = Size(barWidth, barHeight),
					cornerRadius = radius,
				)
			}
		}
		Spacer(Modifier.height(8.dp))
		Row(modifier = Modifier.fillMaxWidth()) {
			labels.forEach { label ->
				Text(
					text = label.orEmpty(),
					style = MaterialTheme.typography.labelSmall,
					color = labelColor,
					textAlign = TextAlign.Center,
					maxLines = 1,
					modifier = Modifier.weight(1f),
				)
			}
		}
	}
}
