package org.koitharu.kotatsu.settings.storage

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.os.OpenDocumentTreeHelper
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogTextButton
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.favourites.data.FavouriteSpace

@AndroidEntryPoint
class MangaDirectorySelectDialog : ComposeAlertDialogFragment() {

	private val viewModel: MangaDirectorySelectViewModel by viewModels()
	private val pickFileTreeLauncher = OpenDocumentTreeHelper(
		activityResultCaller = this,
		flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
			or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
	) {
		if (it != null) viewModel.onCustomDirectoryPicked(it)
	}
	private val permissionRequestLauncher = registerForActivityResult(
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			RequestStorageManagerPermissionContract()
		} else {
			ActivityResultContracts.RequestPermission()
		},
	) {
		if (it) {
			viewModel.refresh()
			if (!pickFileTreeLauncher.tryLaunch(null)) {
				Toast.makeText(
					context ?: return@registerForActivityResult,
					R.string.operation_not_supported,
					Toast.LENGTH_SHORT,
				).show()
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onDismissDialog.observeEvent(viewLifecycleOwner) { dismiss() }
		viewModel.onPickDirectory.observeEvent(viewLifecycleOwner) { pickCustomDirectory() }
		viewModel.onError.observeEvent(viewLifecycleOwner) { e ->
			Toast.makeText(context ?: return@observeEvent, e.getDisplayMessage(resources), Toast.LENGTH_SHORT).show()
		}
	}

	@Composable
	override fun Content() {
		val items by viewModel.items.collectAsState()
		val selectedSpace by viewModel.selectedSpace.collectAsState()
		val privateUsesOwnRoot by viewModel.privateUsesOwnRoot.collectAsState()
		val destinationsOverlap by viewModel.destinationsOverlap.collectAsState()
		val selectedItem = items.firstOrNull { it.isChecked }
		val selectedRoot = selectedItem?.file

		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_storage),
			title = stringResource(R.string.manga_save_location),
		) {
			Text(
				text = stringResource(R.string.download_destination_root_hint),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
			)
			Text(
				text = stringResource(R.string.download_destination_space),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.primary,
				modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
			)
			DestinationSpaceRow(
				title = stringResource(R.string.download_destination_normal),
				selected = selectedSpace == FavouriteSpace.NORMAL,
				onClick = { viewModel.selectSpace(FavouriteSpace.NORMAL) },
			)
			DestinationSpaceRow(
				title = stringResource(R.string.download_destination_private),
				selected = selectedSpace == FavouriteSpace.PRIVATE,
				onClick = { viewModel.selectSpace(FavouriteSpace.PRIVATE) },
			)
			if (selectedSpace == FavouriteSpace.PRIVATE && !privateUsesOwnRoot) {
				Text(
					text = stringResource(R.string.download_destination_private_follows_normal),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
				)
			}
			if (destinationsOverlap) {
				Text(
					text = stringResource(R.string.download_destination_same_warning),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error,
					modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
				)
			}
			if (selectedRoot != null) {
				Text(
					text = stringResource(R.string.download_destination_preview, selectedRoot.path.trimEnd('/')),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
				)
			}
			if (selectedItem != null && !selectedItem.isAvailable) {
				Text(
					text = stringResource(R.string.download_destination_unavailable),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error,
					modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
				)
			}

			Column(
				modifier = Modifier
					.heightIn(max = 320.dp)
					.verticalScroll(rememberScrollState()),
			) {
				items.filter { it.file != null }.forEach { item ->
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.heightIn(min = 52.dp)
							.clip(RoundedCornerShape(16.dp))
							.clickable(enabled = item.isAvailable) { viewModel.onItemClick(item) }
							.padding(horizontal = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						RadioButton(
							selected = item.isChecked,
							onClick = { viewModel.onItemClick(item) },
							enabled = item.isAvailable,
						)
						Spacer(Modifier.size(8.dp))
						Column(modifier = Modifier.fillMaxWidth()) {
							Text(
								text = item.title ?: stringResource(item.titleRes),
								style = MaterialTheme.typography.bodyLarge,
								color = if (item.isAvailable) {
									MaterialTheme.colorScheme.onSurface
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
							)
							val path = item.file?.absolutePath
							if (path != null) {
								Text(
									text = path,
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
						}
					}
				}
			}
			if (selectedSpace == FavouriteSpace.PRIVATE && privateUsesOwnRoot) {
				ExpressiveDialogTextButton(
					text = stringResource(R.string.download_destination_private_use_normal),
				) { viewModel.useNormalDestinationForPrivate() }
			}
			items.filter { it.file == null }.forEach { item ->
				Spacer(Modifier.size(12.dp))
				ExpressivePillButton(
					text = item.title ?: stringResource(item.titleRes),
					icon = painterResource(R.drawable.ic_folder_file),
					primary = true,
				) { viewModel.onItemClick(item) }
			}
			Spacer(Modifier.size(8.dp))
			ExpressiveDialogTextButton(text = stringResource(android.R.string.cancel)) { dismiss() }
		}
	}

	private fun pickCustomDirectory() {
		if (!permissionRequestLauncher.tryLaunch(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
			Toast.makeText(context ?: return, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
		}
	}
}

@Composable
private fun DestinationSpaceRow(
	title: String,
	selected: Boolean,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = 48.dp)
			.clip(RoundedCornerShape(16.dp))
			.clickable(onClick = onClick)
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RadioButton(selected = selected, onClick = onClick)
		Spacer(Modifier.size(8.dp))
		Text(
			text = title,
			style = MaterialTheme.typography.bodyLarge,
			color = MaterialTheme.colorScheme.onSurface,
		)
	}
}
