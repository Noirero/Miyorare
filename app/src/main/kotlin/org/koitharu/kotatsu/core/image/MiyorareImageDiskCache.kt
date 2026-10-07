package org.koitharu.kotatsu.core.image

import android.content.Context
import coil3.disk.DiskCache
import coil3.disk.directory
import okio.FileSystem
import okio.Path
import java.io.File

/**
 * Routes stable manga-cover keys to app-specific files storage while leaving every other Coil
 * entry in Android's disposable cache. Cover entries therefore survive Android "Clear cache"
 * without turning reader pages, favicons, bookmarks, or arbitrary network images into user data.
 *
 * Existing `cover:<mangaId>` entries are lazily copied from the legacy cache on first read. The
 * mapping is safe because Coil's public DiskCache API accepts the original request key; no hashed
 * filename or journal implementation detail is guessed here.
 */
class MiyorareImageDiskCache(
	context: Context,
	private val volatileCache: DiskCache = createVolatileCache(context),
) : DiskCache {

	private val coverCache = DiskCache.Builder()
		.directory((context.getExternalFilesDir(COVER_DIR) ?: File(context.filesDir, COVER_DIR)).resolve(COIL_DIR))
		.maxSizePercent(0.02)
		.minimumMaxSizeBytes(10L * 1024L * 1024L)
		.maximumMaxSizeBytes(250L * 1024L * 1024L)
		.build()

	override val size: Long
		get() = volatileCache.size + coverCache.size

	override val maxSize: Long
		get() = volatileCache.maxSize + coverCache.maxSize

	override val directory: Path
		get() = volatileCache.directory

	override val fileSystem: FileSystem
		get() = volatileCache.fileSystem

	val coverSize: Long
		get() = coverCache.size

	override fun openSnapshot(key: String): DiskCache.Snapshot? {
		if (!isCoverKey(key)) return volatileCache.openSnapshot(key)
		coverCache.openSnapshot(key)?.let { return it }
		return migrateLegacyCover(key)
	}

	override fun openEditor(key: String): DiskCache.Editor? = cacheFor(key).openEditor(key)

	override fun remove(key: String): Boolean = cacheFor(key).remove(key)

	override fun clear() {
		volatileCache.clear()
		coverCache.clear()
	}

	override fun shutdown() {
		volatileCache.shutdown()
		coverCache.shutdown()
	}

	fun clearCovers() = coverCache.clear()

	fun clearVolatile() = volatileCache.clear()

	private fun cacheFor(key: String): DiskCache = if (isCoverKey(key)) coverCache else volatileCache

	private fun migrateLegacyCover(key: String): DiskCache.Snapshot? {
		val legacy = volatileCache.openSnapshot(key) ?: return null
		try {
			val editor = coverCache.openEditor(key) ?: return legacy
			try {
				fileSystem.copy(legacy.metadata, editor.metadata)
				fileSystem.copy(legacy.data, editor.data)
				val migrated = editor.commitAndOpenSnapshot()
				if (migrated != null) {
					legacy.close()
					volatileCache.remove(key)
					return migrated
				}
			} catch (_: Exception) {
				runCatching { editor.abort() }
			}
			return legacy
		} catch (_: Exception) {
			return legacy
		}
	}

	private fun isCoverKey(key: String): Boolean = key.startsWith(COVER_KEY_PREFIX)

	private companion object {
		const val COVER_KEY_PREFIX = "cover:"
		const val COVER_DIR = "covers"
		const val COIL_DIR = "coil"
		const val VOLATILE_DIR = "image_cache"

		fun createVolatileCache(context: Context): DiskCache = DiskCache.Builder()
			.directory((context.externalCacheDir ?: context.cacheDir).resolve(VOLATILE_DIR))
			.maxSizePercent(0.10)
			.minimumMaxSizeBytes(256L * 1024L * 1024L)
			.maximumMaxSizeBytes(2L * 1024L * 1024L * 1024L)
			.build()
	}
}
