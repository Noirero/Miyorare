package org.koitharu.kotatsu.favourites.ui.sets

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.favourites.data.FavouriteSpace

@AndroidEntryPoint
class CategorySetsDialog : ComposeAlertDialogFragment() {
	private val viewModel by viewModels<CategorySetsViewModel>()

	override fun onStart() {
		super.onStart()
		if (viewModel.space == FavouriteSpace.PRIVATE) dialog?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onError.observeEvent(viewLifecycleOwner) { Toast.makeText(requireContext(), it.getDisplayMessage(resources), Toast.LENGTH_LONG).show() }
	}

	@Composable override fun Content() {
		val sets by viewModel.sets.collectAsState()
		val busy by viewModel.isLoading.collectAsState()
		var editing by rememberSaveable { mutableStateOf(arguments?.getBoolean("create") == true) }
		var editId by rememberSaveable { mutableStateOf<Long?>(null) }
		var name by rememberSaveable { mutableStateOf("") }
		var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
		var destructive by rememberSaveable { mutableStateOf(false) }
		var targetId by rememberSaveable { mutableStateOf<Long?>(null) }
		var impact by remember { mutableStateOf<Pair<Int, Int>?>(null) }
		LaunchedEffect(deleteId) { impact = null; deleteId?.let { impact = viewModel.impact(it) } }
		Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
			Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(stringResource(R.string.category_set_manage), style = MaterialTheme.typography.headlineSmall)
				LazyColumn(Modifier.heightIn(max = 420.dp)) {
					itemsIndexed(sets, key = { _, set -> set.setId }) { index, set ->
						Column(Modifier.testTag("set-row:${set.setId}")) {
							Text(set.title, style = MaterialTheme.typography.titleMedium)
							Row(verticalAlignment = Alignment.CenterVertically) {
								TextButton(modifier = Modifier.testTag("set-rename:${set.setId}"), enabled = !busy, onClick = { editId = set.setId; name = set.title; editing = true }) { Text(stringResource(R.string.category_set_rename)) }
								TextButton(modifier = Modifier.testTag("set-delete:${set.setId}"), enabled = !busy && sets.size > 1, onClick = { impact = null; deleteId = set.setId; targetId = sets.firstOrNull { it.setId != set.setId }?.setId; destructive = false }) { Text(stringResource(R.string.category_set_delete)) }
							}
							Row {
								TextButton(modifier = Modifier.testTag("set-up:${set.setId}"), enabled = !busy && index > 0, onClick = { viewModel.reorder(set.setId, -1) }) { Text(stringResource(R.string.category_set_up)) }
								TextButton(modifier = Modifier.testTag("set-down:${set.setId}"), enabled = !busy && index < sets.lastIndex, onClick = { viewModel.reorder(set.setId, 1) }) { Text(stringResource(R.string.category_set_down)) }
							}
						}
					}
				}
				if (sets.size == 1) Text(stringResource(R.string.category_set_last), style = MaterialTheme.typography.bodySmall)
				TextButton(enabled = !busy, onClick = { editId = null; name = ""; editing = true }) { Text(stringResource(R.string.category_set_create)) }
				TextButton(onClick = { dismiss() }, enabled = !busy) { Text(stringResource(R.string.done)) }
			}
		}
		if (editing) AlertDialog(
			onDismissRequest = { if (!busy) editing = false },
			title = { Text(stringResource(if (editId == null) R.string.category_set_create else R.string.category_set_rename)) },
			text = { OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.category_set_name)) }) },
			confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = { viewModel.save(editId, name) { editing = false } }) { Text(stringResource(R.string.save)) } },
			dismissButton = { TextButton(enabled = !busy, onClick = { editing = false }) { Text(stringResource(android.R.string.cancel)) } },
		)
		val deletedSet = sets.firstOrNull { it.setId == deleteId }
		if (deletedSet != null) AlertDialog(
			onDismissRequest = { if (!busy) deleteId = null },
			title = { Text(stringResource(R.string.category_set_delete)) },
			text = {
				Column {
					val counts = impact
					if (counts == null) CircularProgressIndicator() else Text(stringResource(if (destructive) R.string.category_set_destructive_confirm else R.string.category_set_delete_confirm, deletedSet.title, counts.first, counts.second))
					if (!destructive) {
						Text(stringResource(R.string.category_set_target))
						LazyColumn(Modifier.heightIn(max = 180.dp)) {
							itemsIndexed(sets.filterNot { it.setId == deleteId }) { _, target ->
								Row(verticalAlignment = Alignment.CenterVertically) {
									RadioButton(modifier = Modifier.semantics { contentDescription = target.title }, selected = targetId == target.setId, onClick = { targetId = target.setId }, enabled = !busy)
									Text(target.title)
								}
							}
						}
						TextButton(enabled = !busy && impact != null, onClick = { destructive = true }) { Text(stringResource(R.string.category_set_delete_categories), color = MaterialTheme.colorScheme.error) }
					}
				}
			},
			confirmButton = { TextButton(enabled = !busy && impact != null && (destructive || targetId != null), onClick = { viewModel.delete(deletedSet.setId, targetId, destructive) { deleteId = null } }) { Text(stringResource(if (destructive) R.string.category_set_delete_categories else R.string.category_set_move_delete)) } },
			dismissButton = { TextButton(enabled = !busy, onClick = { deleteId = null }) { Text(stringResource(android.R.string.cancel)) } },
		)
	}
}
