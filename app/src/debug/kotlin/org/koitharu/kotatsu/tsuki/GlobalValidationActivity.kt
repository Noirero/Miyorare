package org.koitharu.kotatsu.tsuki

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
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
	@Inject lateinit var localRepository: LocalMangaRepository
	@Inject lateinit var localIndex: LocalMangaIndex

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val status = TextView(this).apply { text = "Verifying bundled Miyorare validation pack…" }
		setContentView(status)
		lifecycleScope.launch {
			try {
				val proof = withContext(Dispatchers.IO) {
					if (intent.getBooleanExtra("verify_id_update", false) || isIdUpdateBuild()) return@withContext verifyIdUpdate()
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
				status.text = "Validation setup failed: ${e.message}"
			}
		}
	}

	/** True when this APK bundles the ID-update harness instead of the Global pack. */
	private fun isIdUpdateBuild(): Boolean {
		// Global builds ship global-validation/sha256; ID-update builds ship the
		// previous ID shards under global-validation/old/ instead. When the Global
		// proof file is absent, a launcher start can only be an ID-update test, so
		// route it to the same verification the ADB-driven CI run performs.
		if (hasAsset("global-validation/sha256")) return false
		val pack = MiyorareOfficialSourcePacks.find("miyorare-id") ?: return false
		val oldJar = pack.shards.firstOrNull()?.assetName ?: return false
		return hasAsset("global-validation/old/$oldJar")
	}

	private fun hasAsset(path: String): Boolean = try {
		assets.open(path).close()
		true
	} catch (e: Exception) {
		false
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
	private suspend fun verifyIdUpdate(): String {
		val provider = TsukiPluginProvider.MIYORARE
		val pack = checkNotNull(MiyorareOfficialSourcePacks.find("miyorare-id"))
		fun voratoon() = plugins.getAvailableSources().filterIsInstance<TsukiMangaSource>()
			.single { it.plugin.provider == provider && it.plugin.pluginId == pack.pluginId &&
				it.name.endsWith(":VORATOON") }
		for (shard in pack.shards) {
			val jar = File.createTempFile("legacy-id-", ".jar", cacheDir)
			try {
				assets.open("global-validation/old/${shard.assetName}").use { jar.writeBytes(it.readBytes()) }
				plugins.installLocalJar(jar, TsukiPluginManager.InstallRequest(
					pluginId = shard.pluginId, displayName = shard.displayName, provider = provider,
					origin = "debug://bundled-id/old", version = "miyorare-sources-v0.0.1",
				))
			} finally {
				jar.delete()
			}
		}
		val before = voratoon()
		val (oldManga, oldChapter) = readVoraToonFixture(before, legacy = true)
		val archive = File.createTempFile("voratoon-legacy-", ".cbz", cacheDir)
		try {
			val index = MangaIndex(null).apply {
				setMangaInfo(oldManga)
				addChapter(IndexedValue(0, oldChapter), null)
			}
			ZipOutputStream(archive.outputStream()).use { zip ->
				zip.putNextEntry(ZipEntry("index.json"))
				zip.write(index.toString().encodeToByteArray())
				zip.closeEntry()
				zip.putNextEntry(ZipEntry("001.png"))
				val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
				try {
					check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
				} finally {
					bitmap.recycle()
				}
				zip.closeEntry()
			}
			localIndex.put(LocalMangaParser(archive).getManga(withDetails = true))
			verifyOfficialUpdater(pack.pluginId)
			val after = voratoon()
			check(after.name == before.name)
			val (newManga, newChapter) = readVoraToonFixture(after, legacy = false)
			check(newManga.id == oldManga.id && newManga.url == oldManga.url)
			check(newChapter.id == oldChapter.id && newChapter.url == oldChapter.url)
			val indexed = checkNotNull(localIndex.get(newManga.id, withDetails = true))
			check(indexed.file.canonicalFile == archive.canonicalFile)
			val chapter = checkNotNull(indexed.manga.chapters).single { it.id == newChapter.id }
			val page = localRepository.getPages(chapter).single()
			check(page.url.contains("001.png"))
			ZipFile(archive).use { zip ->
				zip.getInputStream(checkNotNull(zip.getEntry("001.png"))).use { input ->
					val image = checkNotNull(BitmapFactory.decodeStream(input))
					try {
						check(image.width == 2 && image.height == 2)
					} finally {
						image.recycle()
					}
				}
			}
		} finally {
			localIndex.delete(oldManga.id)
			archive.delete()
		}
		val revision = assets.open("global-validation/revision").bufferedReader().use { it.readText().trim() }
		Log.i(TAG, "VORATOON_LEGACY_CBZ_OK revision=$revision")
		return "VORATOON_ID_UPDATE_OK revision=$revision"
	}

	private suspend fun readVoraToonFixture(source: TsukiMangaSource, legacy: Boolean): Pair<
		org.koitharu.kotatsu.parsers.model.Manga, org.koitharu.kotatsu.parsers.model.MangaChapter,
	> {
		fun flight(json: String) = "<html><script>self.__next_f.push(${JSONArray().put(1).put("0:$json\n")})</script></html>"
		val series = "/series/fixture"
		val pageUrl = "https://images.example.invalid/001.jpg"
		val client = OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			val path = request.url.encodedPath
			check(request.url.host == if (legacy) "v2.voratoon.com" else "voratoon.co")
			val body = when (path) {
				"/backend/series" -> """{"data":[{"data":{"slug":"fixture","title":"Fixture VoraToon"}}]}"""
				"/backend$series" -> """{"data":{"data":{"title":"Fixture VoraToon"}}}"""
				"/backend$series/chapters" -> """{"data":[{"data":{"index":1.5,"title":"Fractional"}}]}"""
				"/browse" -> flight("""{"series":[{"data":{"slug":"fixture","title":"Fixture VoraToon"}}],"seriesMeta":{}}""")
				series -> flight("""{"series":[{"data":{"title":"Fixture VoraToon"}}],"chapters":[{"chapterIndex":1.5,"data":{"title":"Fractional"}}]}""")
				"$series/chapter/1.5" -> """<html><img data-chapter-page="$pageUrl"></html>"""
				else -> error("Unexpected VoraToon fixture request: $path")
			}
			Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
				.message("OK").body(body.toResponseBody((if (legacy) "application/json" else "text/html").toMediaType()))
				.build()
		}.build()
		val runtime = TsukiPluginRuntime(applicationContext, plugins, client, CookieJar.NO_COOKIES, webViewExecutor)
		val handle = runtime.getHandle(source)
		val seed = handle.parser.getList(0, tsuki.model.SortOrder.POPULARITY, tsuki.model.MangaListFilter.EMPTY).single()
		val manga = handle.parser.getDetails(seed)
		val chapter = checkNotNull(manga.chapters).single()
		check(manga.url == series && chapter.url == "$series/chapter/1.5")
		if (!legacy) check(handle.parser.getPages(chapter).single().url == pageUrl)
		return manga.toMiyorare(source) to chapter.toMiyorare(source)
	}

	private suspend fun verifyOfficialUpdater(packId: String = MiyorareOfficialSourcePacks.GLOBAL_PLUGIN_ID) {
		val pack = requireNotNull(MiyorareOfficialSourcePacks.find(packId))
		val jarName = pack.shards.first().assetName
		val candidates = pack.shards.associate { shard ->
			shard.assetName to assets.open("global-validation/${shard.assetName}").use { it.readBytes() }
		}
		val candidate = candidates.getValue(jarName)
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
					val bytes = candidates[shard.assetName] ?: shard.assetName.encodeToByteArray()
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
					else -> checkNotNull(candidates[url.removePrefix(baseUrl)]) { "Unexpected updater fixture request" }
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
				releasesUrl, baseUrl + manifestName, baseUrl + lockName, baseUrl + checksumName,
			) + if (corrupt) listOf(baseUrl + jarName) else candidates.keys.map { baseUrl + it }) { "Updater did not fetch the expected release metadata and Global JAR: ${result.exceptionOrNull()}" }
			return result
		}

		val installed = checkNotNull(update("0.0.2", corrupt = false).getOrThrow())
		check(installed.version == "miyorare-sources-v0.0.2" && installed.sha256 == expected)
		if (packId == "miyorare-id") {
			for (shard in pack.shards) {
				val current = checkNotNull(plugins.findPlugin(provider, shard.pluginId))
				check(current.version == installed.version)
				check(current.sha256 == MiyorareSourcePackReleasePolicy.sha256Hex(candidates.getValue(shard.assetName)))
			}
			return
		}
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
		val pageScenario = java.util.concurrent.atomic.AtomicReference("single")
		fun indexHtml(page: Int, scenario: String): String {
			val expected = if (scenario == "single") 2 else 4
			val images = when {
				page == 0 || scenario == "repeated" -> 1..2
				scenario == "empty" -> IntRange.EMPTY
				else -> 3..4
			}
			val pager = when (scenario) {
				"single", "noPager" -> ""
				"footer" -> """<table class="ptb"><tr><td><a href="?p=1">2</a></td></tr></table>"""
				else -> """<table class="ptt"><tr><td><a href="?p=1">2</a></td></tr></table>"""
			}
			return """
				<div id="gdd"><table><tr><td>Length:</td><td>$expected pages</td></tr></table></div>
				$pager<div id="gdt">${images.joinToString("") { """<a href="/sample/image-$it"></a>""" }}</div>
			""".trimIndent()
		}
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
				""".trimIndent() + indexHtml(request.url.queryParameter("p")?.toIntOrNull() ?: 0, pageScenario.get())
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

		val chapter = checkNotNull(details.chapters).single()
		for ((scenario, expected) in listOf("single" to 2, "normal" to 4, "footer" to 4)) {
			pageScenario.set(scenario)
			val pages = repository.getFreshPages(chapter)
			check(pages.map { it.url.substringAfterLast('/') } == (1..expected).map { "image-$it" }) {
				"Expected ordered, complete pages for $scenario"
			}
		}
		for (scenario in listOf("repeated", "empty", "noPager")) {
			pageScenario.set(scenario)
			try {
				repository.getFreshPages(chapter)
				error("An incomplete $scenario gallery was accepted")
			} catch (failure: java.io.IOException) {
				check(failure.message.orEmpty().contains("Please retry")) {
					"An incomplete $scenario gallery failed without a retryable message: $failure"
				}
			}
		}
		Log.i(TAG, "GALLERY_PAGES_FIXTURE_OK")
	}

	private companion object {
		const val TAG = "GlobalValidation"
		val installLock = Any()
	}
}
