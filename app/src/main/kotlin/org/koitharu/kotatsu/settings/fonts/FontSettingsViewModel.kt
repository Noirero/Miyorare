package org.koitharu.kotatsu.settings.fonts

import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.fonts.FontPack
import org.koitharu.kotatsu.core.fonts.FontPackRepository
import org.koitharu.kotatsu.core.fonts.InstalledFontPack
import javax.inject.Inject

@HiltViewModel
class FontSettingsViewModel @Inject constructor(
	private val repository: FontPackRepository,
) : ViewModel() {

	val catalog = repository.catalog
	val installed = repository.installed
	val activePack = repository.activePack

	private val _busyPackIds = MutableStateFlow<Set<String>>(emptySet())
	val busyPackIds = _busyPackIds.asStateFlow()

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

	fun install(pack: FontPack) {
		if (pack.id in _busyPackIds.value) return
		_busyPackIds.value += pack.id
		viewModelScope.launch {
			runCatching { repository.install(pack) }
				.onFailure { _errorMessage.value = it.message ?: it.javaClass.simpleName }
			_busyPackIds.value -= pack.id
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

	fun fontFamily(pack: InstalledFontPack): FontFamily {
		return repository.composeFontFamily(pack)
	}

	fun consumeError() {
		_errorMessage.value = null
	}
}
