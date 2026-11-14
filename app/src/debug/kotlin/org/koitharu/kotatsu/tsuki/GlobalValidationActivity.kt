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
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.BuildConfig
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
					val verifyFixture = intent.getBooleanExtra("verify_title_fixture", false)
					val identityProof = installBundledGlobal(seedUpdater = verifyFixture)
					if (verifyFixture) {
						verifyOfficialUpdater()
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

	private fun installBundledGlobal(seedUpdater: Boolean): String = synchronized(installLock) {
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
			if (seedUpdater) jar.writeBytes(distinctZipBytes(jar.readBytes()))
			val provider = TsukiPluginProvider.MIYORARE
			plugins.installLocalJar(jar, TsukiPluginManager.InstallRequest(
				pluginId = shard.pluginId,
				displayName = shard.displayName,
				provider = provider,
				origin = "debug://bundled-global/$revision",
				version = if (seedUpdater) "miyorare-sources-v0.0.1" else null,
			))
			check(plugins.getAvailableSources().any { EhentaiSourceFamily.isOfficialSource(it.name) }) {
				"Official ExHentai identity not available after installing bundled Global"
			}
			"GLOBAL_VALIDATION_OK revision=$revision sha256=$expected"
		} finally {
			jar.delete()
		}
	}


	/** A valid ZIP with distinct bytes, without changing its DEX or source contents. */
	private fun distinctZipBytes(bytes: ByteArray): ByteArray {
		check(bytes.size > 30 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte())
		return bytes.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
	}

	/** Debug-only, in-memory release transport; unmatched requests never reach the network. */
	private suspend fun verifyOfficialUpdater() {
		val packId = MiyorareOfficialSourcePacks.GLOBAL_PLUGIN_ID
		val pack = requireNotNull(MiyorareOfficialSourcePacks.find(packId))
		val jarName = pack.shards.single().assetName
		val candidate = assets.open("global-validation/$jarName").use { it.readBytes() }
		val revision = assets.open("global-validation/revision").bufferedReader().use { it.readText().trim() }
		val expected = MiyorareSourcePackReleasePolicy.sha256Hex(candidate)
		val provider = TsukiPluginProvider.MIYORARE
		val seed = checkNotNull(plugins.findPlugin(provider, packId))
		check(seed.version == "miyorare-sources-v0.0.1" && seed.sha256 != expected)

		suspend fun update(version: String, corrupt: Boolean): Result<org.koitharu.kotatsu.tsuki.model.TsukiPluginDescriptor?> {
			val tag = MiyorareOfficialSourcePacks.RELEASE_TAG_PREFIX + version
			val repository = MiyorareOfficialSourcePacks.REPOSITORY
			val baseUrl = "https://github.com/$repository/releases/download/$tag/"
			val releasesUrl = "https://api.github.com/repos/$repository/releases?per_page=30"
			val manifestName = MiyorareSourcePackReleasePolicy.RELEASE_MANIFEST_ASSET
			val lockName = MiyorareSourcePackReleasePolicy.RELEASE_LOCK_ASSET
			val checksumName = MiyorareSourcePackReleasePolicy.RELEASE_LOCK_SHA256_ASSET
			val packs = MiyorareOfficialSourcePacks.packs
			val payload = linkedMapOf<String, ByteArray>()
			// Non-Global shards are metadata-only fixtures; any attempt to fetch them fails.
			val manifestPacks = packs.map { logical ->
				val shards = logical.shards.map { shard ->
					val bytes = if (shard.assetName == jarName) candidate else shard.assetName.encodeToByteArray()
					payload[shard.assetName] = bytes
					JSONObject()
						.put("provider", if (shard.assetName.endsWith("-uma.jar")) "UMA" else "GEKKOUSHI")
						.put("pluginId", shard.pluginId).put("assetName", shard.assetName)
						.put("size", bytes.size).put("sha256", MiyorareSourcePackReleasePolicy.sha256Hex(bytes))
						.put("sourceCount", 1)
				}
				JSONObject().put("pluginId", logical.pluginId).put("language", logical.language)
					.put("sourceCount", shards.size).put("shards", JSONArray(shards))
			}
			val api = MiyorareSourcePackReleasePolicy.TSUKI_API
			val appVersion = BuildConfig.VERSION_CODE
			val manifest = JSONObject()
				.put("schema", MiyorareSourcePackReleasePolicy.RELEASE_MANIFEST_SCHEMA)
				.put("version", version).put("tag", tag)
				.put("compatibility", JSONObject().put("channel", "stable").put("tsukiApi", api)
					.put("compatibilityEpoch", MiyorareSourcePackReleasePolicy.COMPATIBILITY_EPOCH)
					.put("minMiyorareVersionCode", appVersion).put("releaseLockRequired", true)
					.put("requiredLogicalPacks", JSONArray(packs.map { it.pluginId })))
				.put("sourceRepository", "Noirero/Miyorare").put("sourceBranch", "beta").put("sourceCommit", revision)
				.put("runtimeCompatibility", JSONObject().put("repository", "Noirero/Miyorare")
					.put("branch", "main").put("commit", revision).put("versionCode", appVersion).put("tsukiApi", api))
				.put("upstreams", JSONObject().put("uma", "1".repeat(40))
					.put("gekkoushi", "2".repeat(40)).put("keiyoushi", "3".repeat(40)))
				.put("packs", JSONArray(manifestPacks)).toString().encodeToByteArray()
			payload[manifestName] = manifest
			val lockedAssets = payload.map { (name, bytes) ->
				JSONObject().put("name", name).put("size", bytes.size)
					.put("sha256", MiyorareSourcePackReleasePolicy.sha256Hex(bytes))
			}
			val lock = JSONObject().put("schemaVersion", 1).put("kind", "MIYORARE_SOURCE_PACK_RELEASE_LOCK")
				.put("immutable", true).put("tag", tag)
				.put("releaseManifestSha256", MiyorareSourcePackReleasePolicy.sha256Hex(manifest))
				.put("signatureMode", "GITHUB_ARTIFACT_ATTESTATION")
				.put("signatureIssuer", "https://token.actions.githubusercontent.com")
				.put("signatureRepository", repository)
				.put("signatureWorkflow", ".github/workflows/source-pack-release-seal.yml")
				.put("assets", JSONArray(lockedAssets)).toString().encodeToByteArray()
			payload[lockName] = lock
			val lockSha = MiyorareSourcePackReleasePolicy.sha256Hex(lock)
			payload[checksumName] = "$lockSha  $lockName\n".encodeToByteArray()
			val releaseAssets = payload.map { (name, bytes) ->
				JSONObject().put("name", name).put("size", bytes.size).put("browser_download_url", baseUrl + name)
					.put("digest", "sha256:" + MiyorareSourcePackReleasePolicy.sha256Hex(bytes))
			}
			val release = JSONArray().put(JSONObject().put("tag_name", tag).put("draft", false)
				.put("prerelease", false).put("assets", JSONArray(releaseAssets))).toString().encodeToByteArray()
			val requested = java.util.concurrent.ConcurrentLinkedQueue<String>()
			val client = OkHttpClient.Builder().addInterceptor { chain ->
				val request = chain.request()
				val url = request.url.toString()
				requested.add(url)
				val bytes = when (url) {
					releasesUrl -> release
					baseUrl + jarName -> if (corrupt) distinctZipBytes(candidate) else candidate
					baseUrl + manifestName -> manifest
					baseUrl + lockName -> lock
					baseUrl + checksumName -> checkNotNull(payload[checksumName])
					else -> error("Unexpected updater fixture request")
				}
				Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
					.message("OK").body(bytes.toResponseBody()).build()
			}.build()
			val installer = TsukiPluginInstaller(applicationContext, plugins, client)
			val result = try {
				Result.success(installer.installOrUpdateMiyorare(packId))
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Result.failure(e)
			}
			check(requested.toList() == listOf(
				releasesUrl, baseUrl + manifestName, baseUrl + lockName, baseUrl + checksumName, baseUrl + jarName,
			)) { "Updater did not fetch the expected release metadata and Global JAR: ${result.exceptionOrNull()}" }
			return result
		}

		val installed = checkNotNull(update("0.0.2", corrupt = false).getOrThrow())
		check(installed.version == "miyorare-sources-v0.0.2" && installed.sha256 == expected)
		val failure = update("0.0.3", corrupt = true).exceptionOrNull()
		check(failure is IllegalArgumentException && failure.message == "Plugin SHA-256 does not match GitHub release") {
			"Updater did not reject the changed JAR digest: $failure"
		}
		val current = checkNotNull(plugins.findPlugin(provider, packId))
		check(current.version == installed.version && current.sha256 == expected)
		val source = plugins.getAvailableSources().single { EhentaiSourceFamily.isOfficialSource(it.name) }
		check(source.plugin.sha256 == expected)
		val loadedJar = checkNotNull(plugins.pluginJar(source.plugin))
		check(TsukiPluginValidator.validate(loadedJar).getOrThrow().sha256 == expected)
		Log.i(TAG, "OFFICIAL_UPDATER_OK revision=$revision sha256=$expected corruptDigestRejected=true")
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
