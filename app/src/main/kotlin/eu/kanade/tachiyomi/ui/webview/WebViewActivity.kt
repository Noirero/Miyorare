package eu.kanade.tachiyomi.ui.webview

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.koitharu.kotatsu.browser.BrowserActivity
import org.koitharu.kotatsu.core.nav.AppRouter

/**
 * Binary/runtime compatibility entry point for extensions which explicitly open Mihon's
 * WebViewActivity to solve a site challenge.
 *
 * The extension-facing extras are translated to Miyorare's existing browser activity, so this
 * restores Mihon's class/intent contract without adding or changing any normal application UI.
 */
class WebViewActivity : Activity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val url = intent?.getStringExtra(URL_KEY)
		if (!url.isNullOrBlank()) {
			startActivity(
				Intent(this, BrowserActivity::class.java)
					.setData(Uri.parse(url))
					.putExtra(AppRouter.KEY_TITLE, intent?.getStringExtra(TITLE_KEY)),
			)
		}
		finish()
	}

	private companion object {
		const val URL_KEY = "url_key"
		const val TITLE_KEY = "title_key"
	}
}
