package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.details.data.ChapterPersonalMetadata
import org.koitharu.kotatsu.details.ui.model.ChapterListItem

@Composable
internal fun ChapterPersonalEditor(
	item: ChapterListItem,
	saving: Boolean,
	onDismiss: () -> Unit,
	onSave: (Int?, String?) -> Unit,
) {
	val key = item.personalKey
	var rating by rememberSaveable(key.source, key.url) { mutableStateOf(item.personalMetadata.rating) }
	var note by rememberSaveable(key.source, key.url) { mutableStateOf(item.personalMetadata.note.orEmpty()) }
	AlertDialog(
		onDismissRequest = { if (!saving) onDismiss() },
		title = { Text(stringResource(R.string.chapter_personal_edit)) },
		text = {
			Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(item.getTitle(LocalContext.current.resources), style = MaterialTheme.typography.bodyMedium)
				Text(stringResource(R.string.chapter_personal_local_only), style = MaterialTheme.typography.bodySmall)
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
					for (star in 1..5) {
						IconButton(onClick = { rating = star }, enabled = !saving, modifier = Modifier.weight(1f)) {
							Icon(
								painter = painterResource(R.drawable.ic_star_rate),
								contentDescription = stringResource(R.string.chapter_personal_stars, star),
								tint = if (star <= (rating ?: 0)) MaterialTheme.colorScheme.primary
									else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
								modifier = Modifier.size(24.dp),
							)
						}
					}
				}
				Text(if (rating == null) stringResource(R.string.chapter_personal_unrated) else stringResource(R.string.chapter_personal_rating_value, rating!!))
				TextButton(onClick = { rating = null }, enabled = !saving && rating != null) {
					Text(stringResource(R.string.chapter_personal_clear_rating))
				}
				OutlinedTextField(
					value = note,
					onValueChange = { if (it.length <= ChapterPersonalMetadata.MAX_NOTE_LENGTH) note = it },
					label = { Text(stringResource(R.string.chapter_personal_note)) },
					supportingText = { Text("${note.length}/${ChapterPersonalMetadata.MAX_NOTE_LENGTH}") },
					minLines = 2,
					maxLines = 4,
					enabled = !saving,
					modifier = Modifier.fillMaxWidth(),
				)
				TextButton(onClick = { note = "" }, enabled = !saving && note.isNotEmpty()) {
					Text(stringResource(R.string.chapter_personal_clear_note))
				}
			}
		},
		confirmButton = {
			TextButton(onClick = { onSave(rating, note) }, enabled = !saving) { Text(stringResource(R.string.save)) }
		},
		dismissButton = {
			TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}
