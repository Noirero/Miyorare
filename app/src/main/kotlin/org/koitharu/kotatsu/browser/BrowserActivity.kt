package org.koitharu.kotatsu.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.InteractiveActionRequiredException
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.network.webview.adblock.AdBlock
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import javax.inject.Inject
import javax.inject.Provider

@AndroidEntryPoint
class BrowserActivity : BaseBrowserActivity() {

	@Inject
	lateinit var adBlockUpdaterProvider: Provider<AdBlock.Updater>

	private var successCookieUrl: String? = null
	private var successCookieName: String? = null
	private var initialCookieValue: String? = null
	private var sourceHeaders: Map<String, String> = emptyMap()
	private var mihonRepository: CachingMangaRepository? = null
	private var bypassAdBlockForAuthentication = false
	private var sourceHomeWebView = false

	override fun onCreate2(savedInstanceState: Bundle?, source: MangaSource, repository: MangaRepository?) {
		successCookieUrl = intent?.getStringExtra(AppRouter.KEY_SUCCESS_COOKIE_URL)
		successCookieName = intent?.getStringExtra(AppRouter.KEY_SUCCESS_COOKIE_NAME)
		if (successCookieUrl != null && successCookieName != null) {
			initialCookieValue = getCookieValue(successCookieUrl!!, successCookieName!!)
		}

		if (source is MihonMangaSource) {
			mihonRepository = repository as? CachingMangaRepository
		}

		val httpSource = (source as? MihonMangaSource)?.catalogueSource as? HttpSource
		val allSourceHeaders = getSourceHeaders(httpSource)
		sourceHomeWebView = intent?.getBooleanExtra(EXTRA_SOURCE_HOME_WEBVIEW, false) == true
		// A source's API headers are not necessarily valid browser-navigation headers. Extensions may
		// add Accept/XHR/authorization values for catalogue calls; replaying those on the public home
		// page can make the server return an API/empty response and leave WebView looking black. Keep
		// the source User-Agent through WebSettings, but let source-home navigation otherwise behave
		// like a normal browser. Resolver/challenge WebViews retain the full source headers.
		sourceHeaders = if (sourceHomeWebView) emptyMap() else allSourceHeaders

		bypassAdBlockForAuthentication = intent?.getBooleanExtra(EXTRA_UNFILTERED_AUTH_WEBVIEW, false) == true
		val explicitUserAgent = intent?.getStringExtra(AppRouter.KEY_USER_AGENT)?.nullIfEmpty()
		val sourceUserAgent = allSourceHeaders.entries
			.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }
			?.value
			?.nullIfEmpty()
		val effectiveUserAgent = explicitUserAgent ?: sourceUserAgent
		if (effectiveUserAgent != null) {
			viewBinding.webView.settings.userAgentString = effectiveUserAgent
			if (explicitUserAgent != null && !sourceHomeWebView) {
				val headers = sourceHeaders.toMutableMap()
				val existingKey = headers.keys.firstOrNull { it.equals("user-agent", ignoreCase = true) }
				if (existingKey != null) {
					headers[existingKey] = explicitUserAgent
				} else {
					headers["User-Agent"] = explicitUserAgent
				}
				sourceHeaders = headers
			}
		}

		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		viewBinding.webView.webViewClient = BrowserClient(
			callback = this,
			adBlock = adBlock.takeUnless { bypassAdBlockForAuthentication },
			additionalHeaders = sourceHeaders,
		)

		if (adBlock.isEnabled && !bypassAdBlockForAuthentication) {
			lifecycleScope.launch(Dispatchers.IO) {
				prepareAdBlock()
			}
		}

		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				e.printStackTraceDebug()
				Snackbar.make(viewBinding.webView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
			}
			val shouldLoadInitialUrl = savedInstanceState == null || viewBinding.webView.url.isNullOrEmpty()
			if (shouldLoadInitialUrl) {
				val url = intent?.dataString
				if (url.isNullOrEmpty()) {
					finishAfterTransition()
				} else {
					onTitleChanged(
						intent?.getStringExtra(AppRouter.KEY_TITLE) ?: getString(R.string.loading_),
						url,
					)
					if (sourceHeaders.isEmpty()) {
						viewBinding.webView.loadUrl(url)
					} else {
						viewBinding.webView.loadUrl(url, sourceHeaders)
					}
				}
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		super.onCreateOptionsMenu(menu)
		menuInflater.inflate(R.menu.opt_browser, menu)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			viewBinding.webView.stopLoading()
			finishAfterTransition()
			true
		}

		R.id.action_browser -> {
			if (!router.openExternalBrowser(viewBinding.webView.url.orEmpty(), item.title)) {
				Snackbar.make(viewBinding.webView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
			}
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun onPause() {
		CookieManager.getInstance().flush()
		super.onPause()
	}

	override fun finish() {
		CookieManager.getInstance().flush()
		mihonRepository?.invalidateCache()
		if (successCookieUrl != null && successCookieName != null) {
			val currentValue = getCookieValue(successCookieUrl!!, successCookieName!!)
			setResult(if (!currentValue.isNullOrBlank()) RESULT_OK else RESULT_CANCELED)
		} else {
			setResult(RESULT_OK)
		}
		super.finish()
	}

	private suspend fun prepareAdBlock() {
		if (!adBlock.isEnabled || bypassAdBlockForAuthentication) return
		val updater = adBlockUpdaterProvider.get()
		tryUpdateAdBlock(updater, force = !adBlock.hasRuleList())
	}

	private suspend fun tryUpdateAdBlock(updater: AdBlock.Updater, force: Boolean) {
		try {
			if (force) {
				updater.updateList()
			} else {
				updater.updateListIfStale()
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			e.printStackTraceDebug()
		}
	}

	private fun getSourceHeaders(httpSource: HttpSource?): Map<String, String> {
		if (httpSource == null) return emptyMap()
		return runCatching {
			httpSource.headers
				.toMultimap()
				.mapValues { (_, values) -> values.firstOrNull().orEmpty() }
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrDefault(emptyMap())
	}

	private fun getCookieValue(url: String, cookieName: String): String? {
		val cookies = CookieManager.getInstance().getCookie(url) ?: return null
		return cookies.split(";")
			.map { it.trim() }
			.firstOrNull { it.startsWith("$cookieName=") }
			?.substringAfter("=")
	}

	class Contract : ActivityResultContract<InteractiveActionRequiredException, Boolean>() {
		override fun createIntent(
			context: Context,
			input: InteractiveActionRequiredException
		): Intent = AppRouter.browserIntent(
			context = context,
			url = input.url,
			source = input.source,
			title = null,
		).apply {
			putExtra(EXTRA_UNFILTERED_AUTH_WEBVIEW, true)
			putExtra(AppRouter.KEY_SUCCESS_COOKIE_URL, input.successCookieUrl)
			putExtra(AppRouter.KEY_SUCCESS_COOKIE_NAME, input.successCookieName)
			if (input.userAgent != null) {
				putExtra(AppRouter.KEY_USER_AGENT, input.userAgent)
			}
		}

		override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
			return resultCode == android.app.Activity.RESULT_OK
		}
	}

	companion object {

		const val TAG = "BrowserActivity"
		const val EXTRA_UNFILTERED_AUTH_WEBVIEW =
			"org.koitharu.kotatsu.browser.extra.UNFILTERED_AUTH_WEBVIEW"
		const val EXTRA_SOURCE_HOME_WEBVIEW =
			"org.koitharu.kotatsu.browser.extra.SOURCE_HOME_WEBVIEW"
	}
}
