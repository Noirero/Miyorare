package org.koitharu.kotatsu.favourites.ui.categories.select

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import com.google.android.material.checkbox.MaterialCheckBox
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.joinToStringWithLimit
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.favourites.ui.categories.select.model.MangaCategoryItem
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.LoadingState

@AndroidEntryPoint
class FavoriteDialog : ComposeAlertDialogFragment() {

	private val viewModel by viewModels<FavoriteDialogViewModel>()

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onError.observeEvent(viewLifecycleOwner) { e ->
			Toast.makeText(context ?: return@observeEvent, e.getDisplayMessage(resources), Toast.LENGTH_SHORT).show()
		}
		viewModel.onSaved.observeEvent(viewLifecycleOwner) { openCategoryManagement ->
			dismiss()
			if (openCategoryManagement) router.openFavoriteCategories()
		}
	}

	@Composable
	override fun Content() {
		val context = LocalContext.current
		val content by viewModel.content.collectAsState()
		val isSaving by viewModel.isSaving.collectAsState()
		val title = remember { viewModel.manga.joinToStringWithLimit(context, 92) { it.title } }
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_heart),
			title = title,
		) {
			LazyColumn(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 280.dp),
			) {
				content.forEach { model ->
					item {
						when (model) {
							is LoadingState -> Box(
								modifier = Modifier
									.fillMaxWidth()
									.padding(24.dp),
								contentAlignment = Alignment.Center,
							) {
								CircularProgressIndicator()
							}

							is EmptyState -> Text(
								text = stringResource(model.textPrimary),
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier
									.fillMaxWidth()
									.padding(vertical = 24.dp),
							)

							is MangaCategoryItem -> CategoryRow(model, enabled = !isSaving)
						}
					}
				}
			}
			Spacer(Modifier.size(16.dp))
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				TextButton(
					onClick = { dismiss() },
					enabled = !isSaving,
					modifier = Modifier
						.weight(1f)
						.height(48.dp),
				) {
					Text(stringResource(android.R.string.cancel))
				}
				FilledTonalButton(
					onClick = {
						viewModel.prepareCategoryManagement()
						router.openFavoriteCategoryCreate()
					},
					enabled = !isSaving,
					modifier = Modifier.size(48.dp),
					contentPadding = PaddingValues(0.dp),
				) {
					Text(
						text = "+",
						style = MaterialTheme.typography.titleLarge,
					)
				}
				Button(
					onClick = { viewModel.save() },
					enabled = !isSaving,
					modifier = Modifier
						.weight(1f)
						.height(48.dp),
				) {
					Text(stringResource(android.R.string.ok))
				}
			}
		}
	}

	@Composable
	private fun CategoryRow(item: MangaCategoryItem, enabled: Boolean) {
		val toggle = {
			if (enabled) {
				viewModel.setChecked(item.category.id, item.checkedState != MaterialCheckBox.STATE_CHECKED)
			}
		}
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = 52.dp)
				.clip(RoundedCornerShape(16.dp))
				.clickable(enabled = enabled) { toggle() }
				.padding(horizontal = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			TriStateCheckbox(
				state = when (item.checkedState) {
					MaterialCheckBox.STATE_CHECKED -> ToggleableState.On
					MaterialCheckBox.STATE_INDETERMINATE -> ToggleableState.Indeterminate
					else -> ToggleableState.Off
				},
				onClick = if (enabled) ({ toggle() }) else null,
			)
			Spacer(Modifier.size(8.dp))
			Text(
				text = item.category.title,
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
			)
			if (item.isTrackerEnabled && item.category.isTrackingEnabled) {
				Icon(
					painter = painterResource(R.drawable.ic_notification),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.padding(start = 8.dp)
						.size(18.dp),
				)
			}
			if (!item.category.isVisibleInLibrary) {
				Icon(
					painter = painterResource(R.drawable.ic_eye_off),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.padding(start = 8.dp)
						.size(18.dp),
				)
			}
		}
	}
}
