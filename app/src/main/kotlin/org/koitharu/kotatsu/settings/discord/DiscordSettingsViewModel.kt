package org.koitharu.kotatsu.settings.discord

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
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

	val tokenState: StateFlow<TokenState> = settings.observe(AppSettings.KEY_DISCORD_RPC, AppSettings.KEY_DISCORD_SOCIAL_SDK)
		.combine(settings.observeDiscordTokenChanges()) { _, _ -> Unit }
		.flatMapLatest { checkToken() }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, TokenState.CHECKING)

	fun saveToken(value: String?) {
		settings.discordToken = value
	}

	private fun checkToken(): Flow<TokenState> = flow {
		val token = settings.discordToken
		if (!settings.isDiscordRpcEnabled || settings.isDiscordSocialSdkEnabled) {
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
