package org.koitharu.kotatsu.core.fonts

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.ExperimentalTextApi
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FontPackEntryPoint {
	val fontPackRepository: FontPackRepository
}

/**
 * Owns the Font Pack catalog and the packs installed on this device.
 *
 * Packs are plain data: the catalog index lists font files hosted upstream
 * (pinned, hash-verified), [install] downloads them into
 * `files/fonts/<packId>/`, and the active pack is the one named by
 * [AppSettings.appFontPackId]. Installed state is derived from disk, so there
 * is no database to keep in sync.
 */
@Singleton
class FontPackRepository @Inject constructor(
	@BaseHttpClient private val baseHttpClient: Provider<OkHttpClient>,
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
) {

	/**
	 * The shared HTTP client, realized lazily. Creating it is prohibited on
	 * the main thread (see NetworkModule), and this repository is constructed
	 * during activity startup to resolve the active font — so the client may
	 * only be touched from the IO-dispatched functions below.
	 */
	private val okHttp: OkHttpClient get() = baseHttpClient.get()

	private val rootDir = File(context.filesDir, FONTS_DIR)

	private val previewDir = File(context.filesDir, PREVIEW_DIR)

	private val previewFamilyCache = mutableMapOf<String, FontFamily>()

	private val _catalog = MutableStateFlow<List<FontPack>>(emptyList())
	val catalog = _catalog.asStateFlow()

	private val _installed = MutableStateFlow<List<InstalledFontPack>>(emptyList())
	val installed = _installed.asStateFlow()

	private val _activePack = MutableStateFlow<InstalledFontPack?>(null)
	val activePack = _activePack.asStateFlow()

	private var composeFamilyCache: Pair<String, FontFamily>? = null

	init {
		refreshInstalled()
	}

	/** Re-scans the install directory and re-resolves the active pack. */
	fun refreshInstalled() {
		val packs = scanInstalled()
		_installed.value = packs
		val activeId = settings.appFontPackId
		val active = packs.firstOrNull { it.pack.id == activeId }
		if (activeId != null && active == null) {
			// The active pack disappeared from disk: fall back to the built-in
			// font instead of leaving a dangling preference behind.
			settings.appFontPackId = null
		}
		_activePack.value = active
		composeFamilyCache = null
		AppFontHolder.set(active?.let { baseTypeface(it) })
	}

	suspend fun refreshCatalog() {
		withContext(Dispatchers.IO) {
			val request = Request.Builder().get().url(INDEX_URL).build()
			okHttp.newCall(request).execute().use { response ->
				check(response.isSuccessful) { "Font catalog request failed: HTTP ${response.code}" }
				val body = response.body?.string() ?: error("Empty font catalog response")
				_catalog.value = FontPackIndexParser.parse(JSONObject(body))
			}
		}
	}

	/**
	 * Downloads every file of [pack], verifies size and SHA-256, checks each
	 * font actually loads, and only then moves the pack into place. Any
	 * failure leaves nothing installed.
	 */
	suspend fun install(pack: FontPack) {
		withContext(Dispatchers.IO) {
			rootDir.mkdirs()
			val tmpDir = File(rootDir, ".tmp-${pack.id}")
			tmpDir.deleteRecursively()
			check(tmpDir.mkdirs()) { "Cannot create a staging directory for ${pack.id}" }
			try {
				for (file in pack.files) {
					val target = File(tmpDir, file.file)
					download(file.url, target)
					check(target.length() == file.sizeBytes) { "Size mismatch for ${file.file}" }
					check(FontPackHashes.sha256(target) == file.sha256) { "Hash mismatch for ${file.file}" }
					checkNotNull(Typeface.createFromFile(target)) { "Font does not load: ${file.file}" }
				}
				File(tmpDir, PACK_JSON).writeText(pack.rawJson)
				val dir = File(rootDir, pack.id)
				dir.deleteRecursively()
				check(tmpDir.renameTo(dir)) { "Cannot move the font pack into place" }
			} finally {
				tmpDir.deleteRecursively()
			}
		}
		refreshInstalled()
	}

	suspend fun uninstall(packId: String) {
		withContext(Dispatchers.IO) {
			File(rootDir, packId).deleteRecursively()
		}
		if (settings.appFontPackId == packId) {
			settings.appFontPackId = null
		}
		refreshInstalled()
	}

	fun setActive(packId: String?) {
		settings.appFontPackId = packId
		refreshInstalled()
	}

	private fun scanInstalled(): List<InstalledFontPack> {
		val dirs = rootDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".tmp-") }
			?: return emptyList()
		return dirs.mapNotNull { dir ->
			val jsonFile = File(dir, PACK_JSON)
			if (!jsonFile.isFile) return@mapNotNull null
			val pack = runCatching {
				FontPackIndexParser.parsePack(JSONObject(jsonFile.readText()))
			}.getOrNull() ?: return@mapNotNull null
			if (pack.id != dir.name) return@mapNotNull null
			if (pack.files.any { !File(dir, it.file).isFile }) return@mapNotNull null
			InstalledFontPack(pack, dir)
		}.sortedBy { it.pack.name }
	}

	private fun download(url: String, target: File) {
		val request = Request.Builder().get().url(url).build()
		okHttp.newCall(request).execute().use { response ->
			check(response.isSuccessful) { "Font download failed: HTTP ${response.code}" }
			val body = response.body ?: error("Empty font download response")
			body.byteStream().use { input ->
				target.outputStream().use { output -> input.copyTo(output) }
			}
		}
	}

	/** The file that represents a pack at a glance: upright, regular weight when present. */
	private fun baseFile(pack: FontPack): FontPackFile? {
		return pack.files.firstOrNull { !it.italic && (it.variable || (it.weight ?: 400) == 400) }
			?: pack.files.firstOrNull { !it.italic }
			?: pack.files.firstOrNull()
	}

	/** The pack's upright base typeface, for the View system and the EPUB reader. */
	fun baseTypeface(pack: InstalledFontPack): Typeface? {
		val file = baseFile(pack.pack) ?: return null
		val fontFile = File(pack.dir, file.file)
		return runCatching {
			if (file.variable) {
				Typeface.Builder(fontFile)
					.setFontVariationSettings("'wght' 400")
					.build()
			} else {
				Typeface.createFromFile(fontFile)
			}
		}.getOrNull()
	}

	/**
	 * A [FontFamily] for previewing a catalog pack that is not installed.
	 * Only the pack's base file is fetched, into a separate preview cache
	 * (`files/fonts-preview/<packId>/`), verified by size and SHA-256 like
	 * an install. The pack is not installed and never becomes active
	 * through this path; the cached file is reused by later previews.
	 */
	@OptIn(ExperimentalTextApi::class)
	suspend fun previewFontFamily(pack: FontPack): FontFamily {
		previewFamilyCache[pack.id]?.let { return it }
		_installed.value.firstOrNull { it.pack.id == pack.id }?.let { return composeFontFamily(it) }
		val file = baseFile(pack) ?: error("Font pack has no previewable file: ${pack.id}")
		val target = withContext(Dispatchers.IO) {
			val dir = File(previewDir, pack.id)
			dir.mkdirs()
			val target = File(dir, file.file)
			val cached = target.isFile && target.length() == file.sizeBytes &&
				FontPackHashes.sha256(target) == file.sha256
			if (!cached) {
				download(file.url, target)
				check(target.length() == file.sizeBytes) { "Size mismatch for ${file.file}" }
				check(FontPackHashes.sha256(target) == file.sha256) { "Hash mismatch for ${file.file}" }
				checkNotNull(Typeface.createFromFile(target)) { "Font does not load: ${file.file}" }
			}
			target
		}
		val style = if (file.italic) FontStyle.Italic else FontStyle.Normal
		val family = if (file.variable) {
			FontFamily(
				Font(
					file = target,
					weight = FontWeight(400),
					style = style,
					variationSettings = FontVariation.Settings(FontVariation.weight(400)),
				),
				Font(
					file = target,
					weight = FontWeight(600),
					style = style,
					variationSettings = FontVariation.Settings(FontVariation.weight(600)),
				),
			)
		} else {
			FontFamily(Font(file = target, weight = FontWeight(file.weight ?: 400), style = style))
		}
		return family.also { previewFamilyCache[pack.id] = it }
	}

	/** A Compose [FontFamily] covering every file and weight of the pack. */
	@OptIn(ExperimentalTextApi::class)
	fun composeFontFamily(pack: InstalledFontPack): FontFamily {
		composeFamilyCache?.let { (id, family) ->
			if (id == pack.pack.id) return family
		}
		val fonts = pack.pack.files.flatMap { file ->
			val fontFile = File(pack.dir, file.file)
			val style = if (file.italic) FontStyle.Italic else FontStyle.Normal
			if (file.variable) {
				val weights = file.weights.ifEmpty { listOf(400, 700) }
				weights.map { weight ->
					Font(
						file = fontFile,
						weight = FontWeight(weight),
						style = style,
						variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
					)
				}
			} else {
				listOf(
					Font(
						file = fontFile,
						weight = FontWeight(file.weight ?: 400),
						style = style,
					),
				)
			}
		}
		return FontFamily(fonts).also { composeFamilyCache = pack.pack.id to it }
	}

	companion object {
		const val INDEX_URL =
			"https://raw.githubusercontent.com/Noirero/Miyorare-Source-Packs/main/fonts/index.json"
		private const val FONTS_DIR = "fonts"
		private const val PREVIEW_DIR = "fonts-preview"
		private const val PACK_JSON = "pack.json"
	}
}
