package org.koitharu.kotatsu.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R

internal enum class DownloadStructureInfo(
	@StringRes val titleRes: Int,
	@StringRes val treeRes: Int,
	@StringRes val noteRes: Int,
) {
	DOWNLOADS(
		R.string.download_structure_title,
		R.string.download_structure_tree,
		R.string.download_structure_note,
	),
	SAVED_PAGES(
		R.string.saved_pages_structure_title,
		R.string.saved_pages_structure_tree,
		R.string.saved_pages_structure_note,
	),
	LOCAL(
		R.string.local_structure_title,
		R.string.local_structure_tree,
		R.string.local_structure_note,
	),
}

/**
 * Keeps folder-layout explanations out of the Downloads screen until the user explicitly asks for
 * them. The visible row stays compact; the information icon opens a monospace tree in a bottom sheet.
 */
@Composable
internal fun DownloadStructureInfoItem(info: DownloadStructureInfo) {
	var showSheet by remember { mutableStateOf(false) }
	val title = stringResource(info.titleRes)

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = 24.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.weight(1f),
		)
		IconButton(onClick = { showSheet = true }) {
			Icon(
				painter = painterResource(R.drawable.ic_info_outline),
				contentDescription = title,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}

	if (showSheet) {
		DownloadStructureBottomSheet(
			info = info,
			onDismiss = { showSheet = false },
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadStructureBottomSheet(
	info: DownloadStructureInfo,
	onDismiss: () -> Unit,
) {
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = sheetState,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 24.dp)
				.padding(bottom = 32.dp),
		) {
			Text(
				text = stringResource(info.titleRes),
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight.SemiBold,
			)
			Spacer(Modifier.height(16.dp))
			Surface(
				modifier = Modifier.fillMaxWidth(),
				shape = RoundedCornerShape(16.dp),
				color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
			) {
				Text(
					text = stringResource(info.treeRes),
					style = MaterialTheme.typography.bodyMedium,
					fontFamily = FontFamily.Monospace,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(16.dp),
				)
			}
			Spacer(Modifier.height(12.dp))
			Text(
				text = stringResource(info.noteRes),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}
