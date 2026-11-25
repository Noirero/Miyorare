package org.koitharu.kotatsu.settings.discord

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.discord.data.DiscordRepository
import javax.inject.Inject

@HiltViewModel
class DiscordSettingsViewModel @Inject constructor(
	private val settings: AppSettings,
	private val repository: DiscordRepository,
) : BaseViewModel() {

	private val tokenCheckRevision = MutableStateFlow(0L)

	val tokenState: StateFlow<TokenState> = combine(
		settings.observe(AppSettings.KEY_DISCORD_RPC),
		settings.observeDiscordTokenChanges(),
		tokenCheckRevision,
	) { _, _, _ -> Unit }
		.flatMapLatest { checkToken() }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, TokenState.CHECKING)

	fun refreshTokenState() {
		tokenCheckRevision.update { it + 1 }
	}

	fun saveToken(value: String?) {
		settings.discordToken = value
	}

	private fun checkToken(): Flow<TokenState> = flow {
		val token = settings.discordToken
		if (!settings.isDiscordRpcEnabled) {
			emit(if (token == null) TokenState.EMPTY else TokenState.SAVED)
			return@flow
		}
		if (token == null) {
			emit(TokenState.REQUIRED)
			return@flow
		}
		emit(TokenState.CHECKING)
		emit(runCatchingCancellable { repository.checkToken(token) }.fold(
			onSuccess = { TokenState.VALID },
			onFailure = { TokenState.fromFailure(it) },
		))
	}
}
