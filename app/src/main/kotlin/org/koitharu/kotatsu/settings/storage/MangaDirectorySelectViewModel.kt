package org.koitharu.kotatsu.settings.storage

import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.isWriteable
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LocalStorageManager
import javax.inject.Inject

@HiltViewModel
class MangaDirectorySelectViewModel @Inject constructor(
	private val storageManager: LocalStorageManager,
	private val destinationStore: DownloadDestinationStore,
) : BaseViewModel() {

	val items = MutableStateFlow(emptyList<DirectoryModel>())
	val selectedSpace = MutableStateFlow(FavouriteSpace.NORMAL)
	val privateUsesOwnRoot = MutableStateFlow(destinationStore.privateUsesOwnRoot())
	val destinationsOverlap = MutableStateFlow(destinationStore.rootsOverlap())
	val onDismissDialog = MutableEventFlow<Unit>()
	val onPickDirectory = MutableEventFlow<Unit>()

	init {
		refresh()
	}

	fun selectSpace(space: FavouriteSpace) {
		if (selectedSpace.value == space) return
		selectedSpace.value = space
		refresh()
	}

	fun onItemClick(item: DirectoryModel) {
		if (item.file != null) {
			destinationStore.setRoot(selectedSpace.value, item.file)
			refreshFlags()
			onDismissDialog.call(Unit)
		} else {
			onPickDirectory.call(Unit)
		}
	}

	fun onCustomDirectoryPicked(uri: Uri) {
		launchJob(Dispatchers.Default) {
			storageManager.takePermissions(uri)
			val dir = storageManager.resolveUri(uri)
			if (!dir.isWriteable()) {
				throw AccessDeniedException(dir)
			}
			if (dir !in storageManager.getApplicationStorageDirs()) {
				storageManager.setDirIsNoMedia(dir)
			}
			destinationStore.setRoot(selectedSpace.value, dir)
			refreshFlags()
			onDismissDialog.call(Unit)
		}
	}

	fun refresh() {
		launchJob(Dispatchers.Default) {
			val space = selectedSpace.value
			val defaultValue = destinationStore.effectiveRoot(space)
			val available = storageManager.getWriteableDirs()
			items.value = buildList(available.size + 2) {
				// A previously selected root may be temporarily absent from the configured directory
				// list after an upgrade. Keep it visible instead of silently changing destinations.
				if (defaultValue != null && defaultValue !in available && defaultValue.isWriteable()) {
					add(
						DirectoryModel(
							title = storageManager.getDirectoryDisplayName(defaultValue, isFullPath = false),
							titleRes = 0,
							file = defaultValue,
							isChecked = true,
							isAvailable = true,
							isRemovable = false,
						),
					)
				}
				available.mapTo(this) { dir ->
					DirectoryModel(
						title = storageManager.getDirectoryDisplayName(dir, isFullPath = false),
						titleRes = 0,
						file = dir,
						isChecked = dir == defaultValue,
						isAvailable = true,
						isRemovable = false,
					)
				}
				this += DirectoryModel(
					title = null,
					titleRes = R.string.pick_custom_directory,
					file = null,
					isChecked = false,
					isAvailable = true,
					isRemovable = false,
				)
			}
			refreshFlags()
		}
	}

	private fun refreshFlags() {
		privateUsesOwnRoot.value = destinationStore.privateUsesOwnRoot()
		destinationsOverlap.value = destinationStore.rootsOverlap()
	}
}
