package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.details.ui.model.ChapterListItem

@Composable
internal fun ChapterPersonalNoteViewer(item: ChapterListItem, onDismiss: () -> Unit) {
	val note = item.personalMetadata.note ?: return
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.chapter_personal_note_title)) },
		text = {
			Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(item.getTitle(LocalContext.current.resources), style = MaterialTheme.typography.bodyMedium)
				ReadOnlyNoteText(note)
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
		},
	)
}
