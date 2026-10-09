package org.koitharu.kotatsu.local.ui.info

import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.computeSize
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.domain.DeleteReadChaptersUseCase
import javax.inject.Inject

@HiltViewModel
class LocalInfoViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val localMangaRepository: LocalMangaRepository,
	private val storageManager: LocalStorageManager,
	private val library: org.koitharu.kotatsu.local.library.SmartLocalLibrary,
	private val deleteReadChaptersUseCase: DeleteReadChaptersUseCase,
) : BaseViewModel() {

	private val manga = savedStateHandle.require<ParcelableManga>(AppRouter.KEY_MANGA).manga

	val isSmartLocal = manga.url.toUri().scheme == org.koitharu.kotatsu.local.library.LOCAL_LIBRARY_SCHEME
	val indexedBook = MutableStateFlow<org.koitharu.kotatsu.local.library.LocalBook?>(null)

	val isCleaningUp = MutableStateFlow(false)
	val onCleanedUp = MutableEventFlow<Pair<Int, Long>>()
	val onScanned = MutableEventFlow<Boolean>()

	val path = MutableStateFlow<String?>(null)
	val size = MutableStateFlow(-1L)
	val availableSize = MutableStateFlow(-1L)

	init {
		computeSize()
	}

	fun rescan() {
		if (!isSmartLocal || isLoading.value) return
		launchLoadingJob(Dispatchers.IO) {
			library.scan()
			computeSize().join()
			onScanned.call(library.state.value.diagnoses.isEmpty())
		}
	}

	fun cleanup() {
		if (isSmartLocal) return
		launchJob(Dispatchers.Default) {
			try {
				isCleaningUp.value = true
				val oldSize = size.value
				val chaptersCount = deleteReadChaptersUseCase.invoke(manga)
				computeSize().join()
				val newSize = size.value
				onCleanedUp.call(chaptersCount to oldSize - newSize)
			} finally {
				isCleaningUp.value = false
			}
		}
	}

	private fun computeSize() = launchLoadingJob(Dispatchers.Default) {
		if (isSmartLocal) {
			val book = requireNotNull(library.book(manga.id))
			indexedBook.value = book
			path.value = book.node.uri
			size.value = book.size
			return@launchLoadingJob
		}
		val file = manga.url.toUri().toFileOrNull() ?: localMangaRepository.findSavedManga(manga)?.file
		requireNotNull(file)
		path.value = file.path
		size.value = file.computeSize()
		availableSize.value = storageManager.computeAvailableSize()
	}
}
