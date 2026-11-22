package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R

/** One lightweight, theme-aware row; no additional file lookup or ownership inference. */
@Composable
internal fun ExhentaiTitleMatchNotice() {
	var showInfo by remember { mutableStateOf(false) }
	val title = stringResource(R.string.exhentai_title_match_status)
	Surface(
		onClick = { showInfo = true },
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
			.testTag("exhentai_title_match_notice"),
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
	) {
		Row(
			modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 7.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Column(Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					text = stringResource(R.string.exhentai_title_match_subtitle),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			Icon(
				painter = painterResource(R.drawable.ic_info_outline),
				contentDescription = title,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(22.dp),
			)
		}
	}
	if (showInfo) ExhentaiTitleMatchInfoSheet(onDismiss = { showInfo = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExhentaiTitleMatchInfoSheet(onDismiss: () -> Unit) {
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
		Column(
			modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
				.padding(horizontal = 24.dp).padding(bottom = 32.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(
				text = stringResource(R.string.exhentai_title_match_info_title),
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.SemiBold,
			)
			Text(
				text = stringResource(R.string.exhentai_title_match_info_body),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Text(
				text = stringResource(R.string.exhentai_title_match_info_protection),
				style = MaterialTheme.typography.bodyMedium,
			)
			Spacer(Modifier.height(4.dp))
			Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.close))
			}
		}
	}
}
