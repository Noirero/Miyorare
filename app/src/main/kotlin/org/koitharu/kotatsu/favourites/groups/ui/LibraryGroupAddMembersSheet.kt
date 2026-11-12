package org.koitharu.kotatsu.favourites.groups.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle

/** Dialog content: lazy member picker. All persisted operations live in the ViewModel/repository. */
@Composable
internal fun LibraryGroupAddMembersSheet(
    candidates: List<LibraryGroupMemberCandidate>,
    onCancel: () -> Unit,
    onAdd: (List<Long>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Long>() }
    val context = LocalContext.current
    val visible = remember(candidates, query) {
        candidates.filter { candidate ->
            query.isBlank() ||
                candidate.manga.title.contains(query.trim(), ignoreCase = true) ||
                candidate.manga.source.getTitle(context).contains(query.trim(), ignoreCase = true)
        }
    }
    val screenHeight = LocalConfiguration.current.screenHeightDp
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .height((screenHeight * 0.78f).coerceAtLeast(280f).dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.library_group_add_members),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.library_group_search_manga)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (visible.isEmpty()) {
            Text(
                text = stringResource(R.string.library_group_no_eligible),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = true).padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(visible, key = { it.manga.id }) { candidate ->
                    val checked = candidate.manga.id in selected
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (checked) selected.remove(candidate.manga.id) else selected.add(candidate.manga.id)
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(candidate.manga.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = candidate.currentGroupName
                                    ?: candidate.manga.source.getTitle(context),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.library_group_selected_count, selected.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
            Button(onClick = { onAdd(selected.toList()) }, enabled = selected.isNotEmpty()) {
                Text(stringResource(R.string.library_group_add_existing))
            }
        }
    }
}
