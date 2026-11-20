package org.koitharu.kotatsu.settings.icons

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.icons.IconPack
import org.koitharu.kotatsu.core.icons.IconPackRepository
import org.koitharu.kotatsu.core.icons.InstalledIconPack
import javax.inject.Inject

@HiltViewModel
class IconSettingsViewModel @Inject constructor(
	private val repository: IconPackRepository,
) : ViewModel() {

	val catalog = repository.catalog
	val installed = repository.installed
	val activePack = repository.activePack

	private val _busyPackIds = MutableStateFlow<Set<String>>(emptySet())
	val busyPackIds = _busyPackIds.asStateFlow()

	/** Per-pack install progress: pack id to (glyphs done, glyphs total). */
	private val _installProgress = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
	val installProgress = _installProgress.asStateFlow()

	private val _catalogLoaded = MutableStateFlow(false)
	val catalogLoaded = _catalogLoaded.asStateFlow()

	private val _errorMessage = MutableStateFlow<String?>(null)
	val errorMessage = _errorMessage.asStateFlow()

	private val _catalogLoading = MutableStateFlow(false)
	val catalogLoading = _catalogLoading.asStateFlow()

	init {
		refreshCatalog()
	}

	fun refreshCatalog() {
		if (_catalogLoading.value) return
		_catalogLoading.value = true
		viewModelScope.launch {
			try {
				repository.refreshCatalog()
				_catalogLoaded.value = true
			} catch (error: Exception) {
				if (error is kotlinx.coroutines.CancellationException) throw error
				_catalogLoaded.value = false
				_errorMessage.value = error.message ?: error.javaClass.simpleName
			} finally {
				_catalogLoading.value = false
			}
		}
	}

	fun install(pack: IconPack) {
		if (pack.id in _busyPackIds.value) return
		_busyPackIds.value += pack.id
		_installProgress.value += pack.id to (0 to pack.files.size)
		viewModelScope.launch {
			runCatching {
				repository.install(pack) { done, total ->
					_installProgress.value += pack.id to (done to total)
				}
			}.onFailure { _errorMessage.value = it.message ?: it.javaClass.simpleName }
			_busyPackIds.value -= pack.id
			_installProgress.value -= pack.id
		}
	}

	fun uninstall(packId: String) {
		if (packId in _busyPackIds.value) return
		_busyPackIds.value += packId
		viewModelScope.launch {
			runCatching { repository.uninstall(packId) }
				.onFailure { _errorMessage.value = it.message ?: it.javaClass.simpleName }
			_busyPackIds.value -= packId
		}
	}

	fun activate(packId: String?) {
		repository.setActive(packId)
	}

	/** Rendered glyphs of an installed pack, for the preview strip. */
	fun glyphs(pack: InstalledIconPack): Map<String, ImageBitmap> {
		return repository.glyphs(pack)
	}

	fun consumeError() {
		_errorMessage.value = null
	}
}
