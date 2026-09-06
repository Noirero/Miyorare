package org.koitharu.kotatsu.settings.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.settings.userdata.storage.StorageUsage

/**
 * Inline storage-usage display: segmented bar showing the breakdown
 * (saved manga / pages cache / other cache / available) plus a legend.
 * Modern uses the shared Miyorare card surface; Classic preserves the legacy container.
 */
@Composable
fun StorageUsageRow(
	usage: StorageUsage?,
	shape: Shape = MaterialTheme.shapes.medium,
) {
	val ctx = LocalContext.current
	val cs = MaterialTheme.colorScheme
	val palette = LocalMiyorareVisualPalette.current
	val modern = palette.isModern
	val surfaceModifier = Modifier
		.fillMaxWidth()
		.let {
			if (modern) it.miyorareSurface(palette, shape) else it
		}

	Surface(
		modifier = surfaceModifier,
		shape = shape,
		color = if (modern) Color.Transparent else cs.surfaceContainer,
	) {
		Column(
			modifier = Modifier.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(
				text = stringResource(R.string.storage_usage),
				style = MaterialTheme.typography.titleMedium,
			)
			if (usage == null) {
				Text(
					text = stringResource(R.string.computing_),
					style = MaterialTheme.typography.bodyMedium,
					color = cs.onSurfaceVariant,
				)
				return@Column
			}

			val used = usage.savedManga.bytes + usage.pagesCache.bytes + usage.otherCache.bytes
			val total = used + usage.available.bytes
			Text(
				text = ctx.getString(
					R.string.memory_usage_pattern,
					FileSize.BYTES.format(ctx, used),
					FileSize.BYTES.format(ctx, total),
				),
				style = MaterialTheme.typography.bodyMedium,
				color = cs.onSurfaceVariant,
			)

			SegmentedBar(
				segments = listOf(
					BarSegment(usage.savedManga.percent, cs.primary),
					BarSegment(usage.pagesCache.percent, cs.tertiary),
					BarSegment(usage.otherCache.percent, cs.secondary),
					// "Available" is usually most of the bar — give it visible contrast against
					// the card background.
					BarSegment(usage.available.percent, cs.outlineVariant),
				),
			)

			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				LegendItem(
					label = stringResource(R.string.saved_manga),
					value = FileSize.BYTES.format(ctx, usage.savedManga.bytes),
					color = cs.primary,
				)
				LegendItem(
					label = stringResource(R.string.pages_cache),
					value = FileSize.BYTES.format(ctx, usage.pagesCache.bytes),
					color = cs.tertiary,
				)
				LegendItem(
					label = stringResource(R.string.other_cache),
					value = FileSize.BYTES.format(ctx, usage.otherCache.bytes),
					color = cs.secondary,
				)
				LegendItem(
					label = stringResource(R.string.available),
					value = FileSize.BYTES.format(ctx, usage.available.bytes),
					color = cs.surfaceVariant,
				)
			}
		}
	}
}

private data class BarSegment(val fraction: Float, val color: Color)

@Composable
private fun SegmentedBar(segments: List<BarSegment>) {
	val total = segments.sumOf { it.fraction.toDouble() }.toFloat().coerceAtLeast(0.0001f)
	val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(12.dp)
			.clip(RoundedCornerShape(6.dp))
			.background(trackColor),
	) {
		val visible = segments.filter { it.fraction > 0f }
		visible.forEachIndexed { i, seg ->
			val weight = (seg.fraction / total).coerceAtLeast(0.001f)
			Box(
				modifier = Modifier
					.weight(weight)
					.fillMaxHeight()
					.background(seg.color),
			)
			if (i < visible.lastIndex) Spacer(Modifier.width(2.dp))
		}
	}
}

@Composable
private fun LegendItem(label: String, value: String, color: Color) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Box(
			modifier = Modifier
				.size(10.dp)
				.clip(RoundedCornerShape(3.dp))
				.background(color),
		)
		Spacer(Modifier.width(8.dp))
		Text(
			text = label,
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.weight(1f),
		)
		Text(
			text = value,
			style = MaterialTheme.typography.labelMedium,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}
