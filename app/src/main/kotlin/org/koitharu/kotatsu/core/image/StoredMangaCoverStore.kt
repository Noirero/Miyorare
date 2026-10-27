package org.koitharu.kotatsu.core.image

import android.content.Context
import android.graphics.Bitmap
import coil3.BitmapImage
import coil3.Extras
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.util.ext.mangaCoverDiskCacheKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Persistent representation for collection covers.
 *
 * Only compact, presentation-sized static images belong here. Search/browse images remain in Coil's
 * disposable cache, Smart Local keeps its own derived-cover authority, and animated/non-bitmap
 * covers fall back to the legacy bounded cover tier so their presentation is not flattened.
 */
@Singleton
class StoredMangaCoverStore internal constructor(
	private val directory: File,
	private val maxBytes: Long = MAX_BYTES,
	private val maxEntries: Int = MAX_ENTRIES,
) {

	@Inject constructor(@ApplicationContext context: Context) : this(
		File(context.filesDir, DIRECTORY_NAME),
	)

	private val mutex = Mutex()
	private var initialized = false
	private var lastAccess = 0L

	suspend fun find(mangaId: Long, sourceUrl: String): File? = withContext(Dispatchers.IO) {
		mutex.withLock {
			initializeLocked()
			val stem = fileStem(mangaId, sourceUrl)
			val file = extensions.asSequence()
				.map { extension -> File(directory, "$stem.$extension") }
				.firstOrNull { it.isFile }
				?: return@withLock null
			touchLocked(file)
			file
		}
	}

	suspend fun containsManga(mangaId: Long): Boolean = withContext(Dispatchers.IO) {
		mutex.withLock {
			initializeLocked()
			val prefix = mangaPrefix(mangaId)
			directory.listFiles().orEmpty().any { it.isStoredCover() && it.name.startsWith(prefix) }
		}
	}

	suspend fun put(mangaId: Long, sourceUrl: String, bitmap: Bitmap): File? {
		val encoded = withContext(Dispatchers.Default) { encode(bitmap) } ?: return null
		if (encoded.bytes.size !in 1..MAX_ENTRY_BYTES) return null
		return withContext(Dispatchers.IO) {
			mutex.withLock {
				initializeLocked()
				val stem = fileStem(mangaId, sourceUrl)
				val target = File(directory, "$stem.${encoded.extension}")
				val prefix = mangaPrefix(mangaId)
				val temporary = File.createTempFile("cover-", ".partial", directory)
				try {
					temporary.outputStream().use { output ->
						output.write(encoded.bytes)
						output.flush()
						output.fd.sync()
					}
					// One durable representation per manga. A changed source URL replaces the old one.
					directory.listFiles().orEmpty().forEach { file ->
						if (file.isStoredCover() && file.name.startsWith(prefix) && file != target) file.delete()
					}
					if (target.exists() && !target.delete()) throw IOException("Cannot replace stored cover")
					if (!temporary.renameTo(target)) throw IOException("Cannot publish stored cover")
					touchLocked(target)
					trimLocked()
					target.takeIf { it.isFile }
				} finally {
					temporary.delete()
				}
			}
		}
	}

	suspend fun remove(mangaId: Long, sourceUrl: String) = withContext(Dispatchers.IO) {
		mutex.withLock {
			initializeLocked()
			val stem = fileStem(mangaId, sourceUrl)
			extensions.forEach { extension -> File(directory, "$stem.$extension").delete() }
		}
	}

	suspend fun clear() = withContext(Dispatchers.IO) {
		mutex.withLock {
			initializeLocked()
			directory.listFiles().orEmpty().forEach { file ->
				if ((file.isStoredCover() || file.name.endsWith(".partial")) && !file.delete()) {
					throw IOException("Cannot clear stored cover")
				}
			}
		}
	}

	suspend fun size(): Long = withContext(Dispatchers.IO) {
		mutex.withLock {
			initializeLocked()
			directory.listFiles().orEmpty().filter { it.isStoredCover() }.sumOf { it.length() }
		}
	}

	private fun initializeLocked() {
		if (initialized) return
		check(directory.isDirectory || directory.mkdirs()) { "Cannot create stored cover directory" }
		directory.listFiles().orEmpty().filter { it.name.endsWith(".partial") }.forEach { it.delete() }
		lastAccess = directory.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: 0L
		trimLocked()
		initialized = true
	}

	private fun trimLocked() {
		val entries = directory.listFiles().orEmpty()
			.filter { it.isStoredCover() }
			.sortedBy { it.lastModified() }
		var bytes = entries.sumOf { it.length() }
		var count = entries.size
		for (entry in entries) {
			if (bytes <= maxBytes && count <= maxEntries) break
			val length = entry.length()
			if (entry.delete()) {
				bytes -= length
				count--
			}
		}
	}

	private fun touchLocked(file: File) {
		lastAccess = maxOf(System.currentTimeMillis(), lastAccess + 1)
		file.setLastModified(lastAccess)
	}

	private fun File.isStoredCover(): Boolean = isFile && extensions.any { name.endsWith(".$it") }

	private fun fileStem(mangaId: Long, sourceUrl: String): String =
		"${mangaPrefix(mangaId)}${digest("$RECIPE|$sourceUrl")}" 

	private fun mangaPrefix(mangaId: Long): String = "$mangaId-"

	private fun encode(bitmap: Bitmap): Encoded? {
		if (bitmap.width <= 0 || bitmap.height <= 0) return null
		val transparent = hasTransparency(bitmap)
		val output = ByteArrayOutputStream()
		val format = if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
		val quality = if (transparent) 100 else JPEG_QUALITY
		if (!bitmap.compress(format, quality, output)) return null
		return Encoded(if (transparent) "png" else "jpg", output.toByteArray())
	}

	private fun hasTransparency(bitmap: Bitmap): Boolean {
		if (!bitmap.hasAlpha()) return false
		val row = IntArray(bitmap.width)
		for (y in 0 until bitmap.height) {
			bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
			if (row.any { (it ushr 24) != 255 }) return true
		}
		return false
	}

	private data class Encoded(val extension: String, val bytes: ByteArray)

	internal companion object {
		const val MAX_EDGE = 512
		const val JPEG_QUALITY = 82
		const val MAX_BYTES = 96L * 1024 * 1024
		const val MAX_ENTRIES = 2048
		const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
		private const val DIRECTORY_NAME = "stored-covers"
		private const val RECIPE = "v1:grid512:opaque-jpeg82:alpha-png"
		private val extensions = arrayOf("jpg", "png")

		private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
			.digest(value.toByteArray())
			.take(16)
			.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
	}
}

internal data class StoredMangaCoverRequest(
	val mangaId: Long,
	val sourceUrl: String,
	val persist: Boolean,
	val forceRefresh: Boolean,
)

internal val storedMangaCoverRequestKey = Extras.Key<StoredMangaCoverRequest?>(null)

/**
 * Converts collection covers to the compact store after Coil has decoded them. Existing legacy
 * `cover:<id>` entries are still readable; after a static cover is compacted that larger entry is
 * removed. A force-refresh updates compact storage only when that manga was already stored.
 */
class StoredMangaCoverPipeline @Inject constructor(
	private val store: StoredMangaCoverStore,
	private val imageDiskCache: MiyorareImageDiskCache,
) {

	suspend fun proceed(chain: Interceptor.Chain): ImageResult {
		val request = chain.request
		val spec = request.extras[storedMangaCoverRequestKey] ?: return chain.proceed()
		val hadStoredCover = if (spec.forceRefresh) store.containsManga(spec.mangaId) else false

		if (!spec.forceRefresh) {
			store.find(spec.mangaId, spec.sourceUrl)?.let { file ->
				val cached = chain.withRequest(
					request.newBuilder()
						.data(file)
						.diskCachePolicy(CachePolicy.DISABLED)
						.networkCachePolicy(CachePolicy.DISABLED)
						.build(),
				).proceed()
				if (cached is SuccessResult) return cached.copy(request = request)
				store.remove(spec.mangaId, spec.sourceUrl)
			}
		}

		val result = chain.proceed()
		if (result !is SuccessResult) return result

		val shouldPersist = spec.persist || (spec.forceRefresh && hadStoredCover)
		if (shouldPersist && result.image is BitmapImage) {
			val compact = compactBitmap(result)
			val stored = compact?.let { store.put(spec.mangaId, spec.sourceUrl, it) }
			if (stored != null) {
				// The compact file is now authoritative for this collection cover. Remove the larger
				// legacy Coil copy so migration reduces Data usage instead of duplicating it.
				imageDiskCache.remove(mangaCoverDiskCacheKey(spec.mangaId))
			}
		} else if (spec.forceRefresh && !shouldPersist) {
			// Details refreshes must not turn a merely viewed manga into durable app data.
			imageDiskCache.remove(mangaCoverDiskCacheKey(spec.mangaId))
		}
		return result
	}

	private fun compactBitmap(result: SuccessResult): Bitmap? {
		val width = result.image.width
		val height = result.image.height
		if (width <= 0 || height <= 0) return null
		val scale = minOf(1f, StoredMangaCoverStore.MAX_EDGE.toFloat() / maxOf(width, height))
		val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
		val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
		return runCatching {
			result.image.toBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
		}.getOrNull()
	}
}
