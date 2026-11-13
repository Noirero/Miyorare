package org.koitharu.kotatsu.favourites.groups.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.mangaExtra

/**
 * Pick existing manga from the active Favorites space only; never browse sources here.
 * The ViewModel provides the space-scoped candidate list and repository owns all writes.
 */
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
            query.isBlank() || candidate.manga.title.contains(query.trim(), ignoreCase = true)
        }
    }
    val colors = MaterialTheme.colorScheme
    val screenHeight = LocalConfiguration.current.screenHeightDp

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .height((screenHeight * 0.78f).coerceAtLeast(280f).dp)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.library_group_add_members),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text = stringResource(R.string.library_group_select_from_favourites),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
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
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(visible, key = { it.manga.id }) { candidate ->
                        val checked = candidate.manga.id in selected
                        val coverRequest = remember(candidate.manga) {
                            ImageRequest.Builder(context)
                                .data(candidate.manga.coverUrl)
                                .mangaExtra(candidate.manga)
                                .build()
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (checked) selected.remove(candidate.manga.id)
                                else selected.add(candidate.manga.id)
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            AsyncImage(
                                model = coverRequest,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.padding(start = 8.dp)
                                    .size(width = 48.dp, height = 68.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    text = candidate.manga.title,
                                    color = colors.onSurface,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                candidate.currentGroupName?.let { currentGroup ->
                                    Text(
                                        text = stringResource(R.string.library_group_already_in_group, currentGroup),
                                        color = colors.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.library_group_selected_count, selected.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                Button(onClick = { onAdd(selected.toList()) }, enabled = selected.isNotEmpty()) {
                    Text(stringResource(R.string.library_group_add_to))
                }
            }
        }
    }
}
