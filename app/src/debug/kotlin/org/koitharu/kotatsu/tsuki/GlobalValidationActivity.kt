package org.koitharu.kotatsu.tsuki

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.main.ui.MainActivity
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.tsuki.runtime.TsukiPluginRuntime
import org.koitharu.kotatsu.tsuki.runtime.TsukiWebViewExecutor
import org.koitharu.kotatsu.tsuki.runtime.toMiyorare
import org.koitharu.kotatsu.tsuki.runtime.toTsuki
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/** Owner validation only. No external input, network installation, or stable-app data access. */
@AndroidEntryPoint
class GlobalValidationActivity : FragmentActivity() {
	@Inject lateinit var plugins: TsukiPluginManager
	@Inject lateinit var memoryCache: MemoryContentCache
	@Inject lateinit var webViewExecutor: TsukiWebViewExecutor

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val status = TextView(this).apply { text = "Verifying bundled Miyorare-Global…" }
		setContentView(status)
		lifecycleScope.launch {
			try {
				val proof = withContext(Dispatchers.IO) {
					val identityProof = installBundledGlobal()
					if (intent.getBooleanExtra("verify_title_fixture", false)) {
						val source = plugins.getAvailableSources().single { EhentaiSourceFamily.isOfficialSource(it.name) }
						verifyTitleFixture(source)
					}
					identityProof
				}
				Log.i(TAG, proof)
				startActivity(Intent(this@GlobalValidationActivity, MainActivity::class.java))
				finish()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.e(TAG, "Global validation setup failed", e)
				status.text = "Global validation setup failed: ${e.message}"
			}
		}
	}

	private fun installBundledGlobal(): String = synchronized(installLock) {
		val directory = "global-validation"
		val revision = assets.open("$directory/revision").bufferedReader().use { it.readText().trim() }
		val expected = assets.open("$directory/sha256").bufferedReader().use { it.readText().trim() }
		require(Regex("[0-9a-f]{40}").matches(revision) && Regex("[0-9a-f]{64}").matches(expected))
		val pack = MiyorareOfficialSourcePacks.packs.single { it.displayName == "Miyorare-Global" }
		val shard = pack.shards.single()
		val jar = File.createTempFile("global-validation-", ".jar", cacheDir)
		try {
			val sha256 = MessageDigest.getInstance("SHA-256")
			assets.open("$directory/${shard.assetName}").use { input ->
				jar.outputStream().use { output ->
					val buffer = ByteArray(32 * 1024)
					while (true) {
						val read = input.read(buffer)
						if (read < 0) break
						sha256.update(buffer, 0, read)
						output.write(buffer, 0, read)
					}
				}
			}
			require(sha256.digest().joinToString("") { "%02x".format(it) } == expected) { "Global JAR hash mismatch" }
			val provider = TsukiPluginProvider.MIYORARE
			plugins.installLocalJar(jar, TsukiPluginManager.InstallRequest(
				pluginId = shard.pluginId,
				displayName = shard.displayName,
				provider = provider,
				origin = "debug://bundled-global/$revision",
			))
			check(plugins.getAvailableSources().any { EhentaiSourceFamily.isOfficialSource(it.name) }) {
				"Official ExHentai identity not available after installing bundled Global"
			}
			"GLOBAL_VALIDATION_OK revision=$revision sha256=$expected"
		} finally {
			jar.delete()
		}
	}

	/** Use the installed DEX and production loader, replacing only the HTTP response. */
	private suspend fun verifyTitleFixture(source: TsukiMangaSource) {
		val seedTitle = "[List prefix] Fixture (Ongoing) [List suffix]"
		val fullTitle = "[Detail prefix] Fixture (Ongoing) [Spanish] [Translator]"
		val alternative = "[Alt prefix] Alternative [Alt suffix]"
		val gallery = "/g/4323075/testtoken/"
		val requests = java.util.concurrent.ConcurrentLinkedQueue<String>()
		val client = OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			val path = request.url.encodedPath
			requests.add(path)
			val html = when (path) {
				"/" -> """
					<table class="itg"><tbody><tr><td></td><td>
					<a href="$gallery"><div class="glink">$seedTitle</div><div></div></a>
					</td></tr></tbody></table>
				""".trimIndent()
				gallery, gallery.trimEnd('/') -> """
					<div class="gm"><div id="gd2">
					<h1 id="gn">$fullTitle</h1><h1 id="gj">$alternative</h1>
					</div></div>
				""".trimIndent()
				else -> error("Unexpected fixture request: $path")
			}
			Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
				.message("OK").body("<html><body>$html</body></html>".toResponseBody("text/html".toMediaType()))
				.build()
		}.build()
		val runtime = TsukiPluginRuntime(applicationContext, plugins, client, CookieJar.NO_COOKIES, webViewExecutor)
		val handle = runtime.getHandle(source)
		val parser = handle.parser
		Log.i(TAG, "TITLE_TRACE parser=${parser.javaClass.name} loader=${parser.javaClass.classLoader?.javaClass?.name} jar=${handle.source.plugin.sha256}")
		val rawSeed = parser.getList(0, tsuki.model.SortOrder.NEWEST, tsuki.model.MangaListFilter.EMPTY).single()
		Log.i(TAG, "TITLE_TRACE list=${rawSeed.title}")
		val seed = rawSeed.toMiyorare(handle.source)
		val rawDetails = parser.getDetails(seed.toTsuki(handle.rawSource, handle.source))
		Log.i(TAG, "TITLE_TRACE detail=${rawDetails.title} alt=${rawDetails.altTitles}")
		val repository = TsukiMangaRepository(source, memoryCache, applicationContext, runtime)
		val details = repository.getFreshDetails(seed)
		val display = MangaDetails(details)
		val full = display.displayTitle(full = true)
		val compact = display.displayTitle(full = false)
		Log.i(TAG, "TITLE_TRACE repo=${details.title} full=$full compact=$compact paths=$requests")
		check(rawSeed.title == seedTitle) { "List parser discarded source title segments" }
		check(rawDetails.title == fullTitle && rawDetails.altTitles == setOf(alternative)) { "Detail parser discarded source title segments" }
		check(details.title == fullTitle && full == fullTitle && compact == "Fixture (Ongoing)") { "Repository or presentation changed the source title" }
		check(EhentaiSourceFamily.isOfficialSource(details.source.name))
		Log.i(TAG, "TITLE_FIXTURE_OK")
	}

	private companion object {
		const val TAG = "GlobalValidation"
		val installLock = Any()
	}
}
