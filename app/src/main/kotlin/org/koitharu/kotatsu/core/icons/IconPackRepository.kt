package org.koitharu.kotatsu.core.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
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
import org.koitharu.kotatsu.core.fonts.FontPackHashes
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface IconPackEntryPoint {
	val iconPackRepository: IconPackRepository
}

/**
 * Owns the Icon Pack catalog and the packs installed on this device.
 *
 * Mirrors the Font Pack flow: the catalog index lists SVG glyphs hosted
 * upstream (hash-verified), [install] downloads them into
 * `files/icons/<packId>/`, renders each one to a bitmap up front — so a
 * glyph that cannot render rejects the whole install instead of failing
 * later on a settings screen — and the active pack is the one named by
 * [AppSettings.appIconPackId]. Installed state is derived from disk, so
 * there is no database to keep in sync.
 */
@Singleton
class IconPackRepository @Inject constructor(
	@BaseHttpClient private val baseHttpClient: Provider<OkHttpClient>,
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
) {

	/**
	 * The shared HTTP client, realized lazily. Creating it is prohibited on
	 * the main thread (see NetworkModule), and this repository is constructed
	 * during activity startup to resolve the active pack — so the client may
	 * only be touched from the IO-dispatched functions below.
	 */
	private val okHttp: OkHttpClient get() = baseHttpClient.get()

	private val rootDir = File(context.filesDir, ICONS_DIR)

	private val _catalog = MutableStateFlow<List<IconPack>>(emptyList())
	val catalog = _catalog.asStateFlow()

	private val _installed = MutableStateFlow<List<InstalledIconPack>>(emptyList())
	val installed = _installed.asStateFlow()

	private val _activePack = MutableStateFlow<InstalledIconPack?>(null)
	val activePack = _activePack.asStateFlow()

	init {
		refreshInstalled()
	}

	/** Re-scans the install directory and re-resolves the active pack. */
	fun refreshInstalled() {
		val packs = scanInstalled()
		_installed.value = packs
		val activeId = settings.appIconPackId
		val active = packs.firstOrNull { it.pack.id == activeId }
		if (activeId != null && active == null) {
			// The active pack disappeared from disk: fall back to the built-in
			// icons instead of leaving a dangling preference behind.
			settings.appIconPackId = null
		}
		_activePack.value = active
		IconPackHolder.set(active?.let { glyphs(it) } ?: emptyMap())
	}

	suspend fun refreshCatalog() {
		withContext(Dispatchers.IO) {
			val request = Request.Builder().get().url(INDEX_URL).build()
			okHttp.newCall(request).execute().use { response ->
				check(response.isSuccessful) { "Icon catalog request failed: HTTP ${response.code}" }
				val body = response.body?.string() ?: error("Empty icon catalog response")
				_catalog.value = IconPackIndexParser.parse(JSONObject(body))
			}
		}
	}

	/**
	 * Downloads every glyph of [pack], verifies size and SHA-256, checks the
	 * SVG is a safe static glyph, renders it to a bitmap, and only then moves
	 * the pack into place. Any failure leaves nothing installed.
	 */
	suspend fun install(pack: IconPack) {
		withContext(Dispatchers.IO) {
			rootDir.mkdirs()
			val tmpDir = File(rootDir, ".tmp-${pack.id}")
			tmpDir.deleteRecursively()
			check(tmpDir.mkdirs()) { "Cannot create a staging directory for ${pack.id}" }
			try {
				for (file in pack.files) {
					val svgFile = File(tmpDir, file.svgFileName)
					download(file.url, svgFile)
					check(svgFile.length() == file.sizeBytes) { "Size mismatch for ${file.key}" }
					check(FontPackHashes.sha256(svgFile) == file.sha256) { "Hash mismatch for ${file.key}" }
					val svgText = svgFile.readText()
					check(IconPackSvg.isSafeGlyph(svgText)) { "Unsafe or malformed SVG: ${file.key}" }
					renderGlyph(svgFile, File(tmpDir, file.pngFileName))
				}
				File(tmpDir, PACK_JSON).writeText(pack.rawJson)
				val dir = File(rootDir, pack.id)
				dir.deleteRecursively()
				check(tmpDir.renameTo(dir)) { "Cannot move the icon pack into place" }
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
		if (settings.appIconPackId == packId) {
			settings.appIconPackId = null
		}
		refreshInstalled()
	}

	fun setActive(packId: String?) {
		settings.appIconPackId = packId
		refreshInstalled()
	}

	/** The pack's glyphs rendered as bitmaps, keyed by drawable name. */
	fun glyphs(pack: InstalledIconPack): Map<String, ImageBitmap> {
		return pack.pack.files.mapNotNull { file ->
			val png = File(pack.dir, file.pngFileName)
			val bitmap = runCatching { BitmapFactory.decodeFile(png.absolutePath) }.getOrNull()
				?: return@mapNotNull null
			file.key to bitmap.asImageBitmap()
		}.toMap()
	}

	private fun scanInstalled(): List<InstalledIconPack> {
		val dirs = rootDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".tmp-") }
			?: return emptyList()
		return dirs.mapNotNull { dir ->
			val jsonFile = File(dir, PACK_JSON)
			if (!jsonFile.isFile) return@mapNotNull null
			val pack = runCatching {
				IconPackIndexParser.parsePack(JSONObject(jsonFile.readText()))
			}.getOrNull() ?: return@mapNotNull null
			if (pack.id != dir.name) return@mapNotNull null
			if (pack.files.any { !File(dir, it.svgFileName).isFile || !File(dir, it.pngFileName).isFile }) {
				return@mapNotNull null
			}
			InstalledIconPack(pack, dir)
		}.sortedBy { it.pack.name }
	}

	private fun download(url: String, target: File) {
		val request = Request.Builder().get().url(url).build()
		okHttp.newCall(request).execute().use { response ->
			check(response.isSuccessful) { "Icon download failed: HTTP ${response.code}" }
			val body = response.body ?: error("Empty icon download response")
			body.byteStream().use { input ->
				target.outputStream().use { output -> input.copyTo(output) }
			}
		}
	}

	/**
	 * Renders one glyph SVG to a [RENDER_SIZE_PX] bitmap using the app's
	 * image loader (which carries the SVG decoder). Runs at install time so
	 * a glyph that cannot be decoded rejects the install while the built-in
	 * icons are still untouched.
	 */
	private suspend fun renderGlyph(svgFile: File, pngFile: File) {
		val loader = SingletonImageLoader.get(context)
		val request = ImageRequest.Builder(context)
			.data(svgFile)
			.size(RENDER_SIZE_PX, RENDER_SIZE_PX)
			.build()
		val result = loader.execute(request)
		val image = (result as? SuccessResult)?.image
			?: error("SVG does not render: ${svgFile.name}")
		val bitmap = image.toBitmap(RENDER_SIZE_PX, RENDER_SIZE_PX)
		pngFile.outputStream().use { output ->
			check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
				"Cannot write the rendered glyph: ${svgFile.name}"
			}
		}
	}

	companion object {
		const val INDEX_URL =
			"https://raw.githubusercontent.com/Noirero/Miyorare-Source-Packs/main/icons/index.json"
		private const val ICONS_DIR = "icons"
		private const val PACK_JSON = "pack.json"
		private const val RENDER_SIZE_PX = 96
	}
}
