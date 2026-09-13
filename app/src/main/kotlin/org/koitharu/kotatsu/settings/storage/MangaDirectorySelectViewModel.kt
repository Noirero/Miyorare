package org.koitharu.kotatsu.settings.storage

import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
	private var refreshJob: Job? = null

	init {
		refresh()
	}

	fun selectSpace(space: FavouriteSpace) {
		if (selectedSpace.value == space) return
		selectedSpace.value = space
		refresh()
	}

	fun onItemClick(item: DirectoryModel) {
		val file = item.file
		if (file == null) {
			onPickDirectory.call(Unit)
			return
		}
		if (!item.isAvailable) return
		// setRoot creates/reuses `<root>/downloads`; keep that filesystem I/O off the UI thread.
		launchLoadingJob(Dispatchers.IO) {
			if (!file.isWriteable()) throw AccessDeniedException(file)
			destinationStore.setRoot(selectedSpace.value, file)
			refreshFlags()
			// If both spaces now point at one root, keep the dialog visible so the warning is actually
			// seen. Otherwise preserve the old one-tap destination-selection behaviour.
			if (destinationsOverlap.value) refresh() else onDismissDialog.call(Unit)
		}
	}

	fun onCustomDirectoryPicked(uri: Uri) {
		launchLoadingJob(Dispatchers.IO) {
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
			if (destinationsOverlap.value) refresh() else onDismissDialog.call(Unit)
		}
	}

	fun useNormalDestinationForPrivate() {
		if (selectedSpace.value != FavouriteSpace.PRIVATE || !destinationStore.privateUsesOwnRoot()) return
		launchLoadingJob(Dispatchers.Default) {
			destinationStore.setRoot(FavouriteSpace.PRIVATE, null)
			refreshFlags()
			refresh()
		}
	}

	fun refresh() {
		refreshJob?.cancel()
		refreshJob = launchJob(Dispatchers.Default) {
			val space = selectedSpace.value
			val defaultValue = destinationStore.effectiveRoot(space)
			val available = storageManager.getWriteableDirs()
			items.value = buildList(available.size + 2) {
				// Keep a configured root visible even while storage is disconnected or permission was
				// revoked. It remains selected but cannot be tapped until it is writable again.
				if (defaultValue != null && defaultValue !in available) {
					val accessible = runCatching { defaultValue.isWriteable() }.getOrDefault(false)
					add(
						DirectoryModel(
							title = storageManager.getDirectoryDisplayName(defaultValue, isFullPath = false),
							titleRes = 0,
							file = defaultValue,
							isChecked = true,
							isAvailable = accessible,
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
