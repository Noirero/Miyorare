package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.details.ui.model.ChapterRange

@Composable
internal fun InlineChapterRangeHeader(range: ChapterRange, accent: Color, onClick: () -> Unit) {
	val palette = LocalMiyorareVisualPalette.current
	val read = range.readCount
	val state = stringResource(if (range.expanded) R.string.collapse else R.string.expand)
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(MiyorareVisualTokens.RADIUS_CONTROL_DP.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (palette.isModern) 0.82f else 1f),
		border = BorderStroke(0.75.dp, if (palette.isModern) palette.borderHighlight.copy(alpha = 0.28f)
			else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
		modifier = Modifier.fillMaxWidth().padding(horizontal = SCREEN_PADDING, vertical = 3.dp)
			.semantics { stateDescription = state },
	) {
		Row(
			modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Icon(painterResource(R.drawable.ic_chevron_right), null,
				modifier = Modifier.size(18.dp).rotate(if (range.expanded) 90f else 0f))
			if (read == range.totalCount) {
				Icon(painterResource(R.drawable.ic_check), null, tint = accent, modifier = Modifier.size(18.dp))
			}
			Text(range.numberLabel ?: stringResource(R.string.chapter_grouping_special),
				style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f))
			Column(modifier = Modifier.width(96.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(stringResource(R.string.chapter_range_read_progress, read, range.totalCount),
					style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				LinearProgressIndicator(progress = { read.toFloat() / range.totalCount }, color = accent,
					modifier = Modifier.fillMaxWidth().height(3.dp))
			}
		}
	}
}
