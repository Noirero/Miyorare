package org.koitharu.kotatsu.browser

import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.core.network.webview.adblock.AdBlock
import org.koitharu.kotatsu.core.ui.CoroutineIntentService
import javax.inject.Inject
import javax.inject.Provider

@AndroidEntryPoint
class AdListUpdateService : CoroutineIntentService() {

	@Inject
	lateinit var updaterProvider: Provider<AdBlock.Updater>

	override suspend fun IntentJobContext.processIntent(intent: Intent) {
		updaterProvider.get().updateListIfStale()
	}

	override fun IntentJobContext.onError(error: Throwable) = Unit
}
