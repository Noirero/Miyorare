package org.koitharu.kotatsu.scrobbling.discord.ui

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.browser.BaseBrowserActivity
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.scrobbling.discord.data.DiscordRepository
import org.koitharu.kotatsu.settings.discord.TokenState
import javax.inject.Inject

@AndroidEntryPoint
class DiscordAuthActivity : BaseBrowserActivity() {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var discordRepository: DiscordRepository

	private var tokenCheckJob: Job? = null
	private var lastTokenCandidate: String? = null

	override fun onCreate2(
		savedInstanceState: Bundle?,
		source: MangaSource,
		repository: MangaRepository?
	) {
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		viewBinding.webView.settings.userAgentString = USER_AGENT
		viewBinding.webView.webViewClient = DiscordTokenWebClient(this)
		if (savedInstanceState == null) {
			viewBinding.webView.loadUrl(BASE_URL)
		}
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			tokenCheckJob?.cancel()
			viewBinding.webView.stopLoading()
			finishAfterTransition()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	fun onTokenObtained(token: String) {
		val candidate = token.trim()
		if (candidate.isEmpty() || candidate == lastTokenCandidate || isFinishing || isDestroyed) return

		lastTokenCandidate = candidate
		tokenCheckJob?.cancel()
		tokenCheckJob = lifecycleScope.launch {
			val state = completeDiscordSignIn(candidate, discordRepository::checkToken) { verifiedToken ->
				if (!isFinishing && !isDestroyed) {
					settings.discordToken = verifiedToken
					setResult(RESULT_OK)
					finish()
				}
			}
			if (isFinishing || isDestroyed) return@launch

			val message = when (state) {
				TokenState.INVALID -> R.string.discord_token_rejected
				TokenState.RATE_LIMITED -> R.string.discord_token_rate_limited
				TokenState.UNAVAILABLE -> R.string.discord_token_verification_unavailable
				else -> return@launch
			}
			Toast.makeText(this@DiscordAuthActivity, message, Toast.LENGTH_SHORT).show()
		}
	}

	private companion object {

		const val BASE_URL = "https://discord.com/login"
		private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.363"
	}
}

internal suspend fun completeDiscordSignIn(
	token: String,
	checkToken: suspend (String) -> Unit,
	onVerified: (String) -> Unit,
): TokenState {
	val state = try {
		checkToken(token)
		TokenState.VALID
	} catch (error: CancellationException) {
		throw error
	} catch (error: Exception) {
		TokenState.fromFailure(error)
	}
	currentCoroutineContext().ensureActive()
	if (state == TokenState.VALID) onVerified(token)
	return state
}
