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
 * Smart Local cover URLs are normalized here as well as at request-building call sites. The disk
 * cache is the ownership boundary for persistence, so a caller that submits
 * `smart-local://cover/<mangaId>` directly cannot accidentally create a second volatile entry.
 *
 * Existing `cover:<mangaId>` entries are lazily copied from the legacy cache on first read. The
 * mapping is safe because Coil's public DiskCache API accepts the original request key; no hashed
 * filename or journal implementation detail is guessed here.
 */
class MiyorareImageDiskCache(
	context: Context,
	private val volatileCache: DiskCache = createVolatileCache(context),
) : DiskCache {

	private val migrationPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	private val coverCache = DiskCache.Builder()
		.directory((context.getExternalFilesDir(COVER_DIR) ?: File(context.filesDir, COVER_DIR)).resolve(COIL_DIR))
		// This cache contains covers only. Keep it bounded independently instead of carrying the
		// shared volatile cache's 256 MiB-2 GiB retention policy into persistent app files.
		.maxSizePercent(0.02)
		.minimumMaxSizeBytes(10L * 1024L * 1024L)
		.maximumMaxSizeBytes(250L * 1024L * 1024L)
		.build()

	override val size: Long
		get() = volatileCache.size + coverCache.size

	override val maxSize: Long
		get() = volatileCache.maxSize + coverCache.maxSize

	// Coil uses one FileSystem for an ImageLoader. Both delegates use the default system FileSystem.
	override val directory: Path
		get() = volatileCache.directory

	override val fileSystem: FileSystem
		get() = volatileCache.fileSystem

	val coverSize: Long
		get() = coverCache.size

	override fun openSnapshot(key: String): DiskCache.Snapshot? {
		val normalizedKey = normalizeKey(key)
		if (!isCoverKey(normalizedKey)) return volatileCache.openSnapshot(normalizedKey)
		coverCache.openSnapshot(normalizedKey)?.let { return it }
		if (!migrationPreferences.getBoolean(KEY_LEGACY_MIGRATION_ENABLED, true)) return null
		return migrateLegacyCover(normalizedKey)
	}

	override fun openEditor(key: String): DiskCache.Editor? {
		val normalizedKey = normalizeKey(key)
		return cacheFor(normalizedKey).openEditor(normalizedKey)
	}

	override fun remove(key: String): Boolean {
		val normalizedKey = normalizeKey(key)
		return cacheFor(normalizedKey).remove(normalizedKey)
	}

	/** Coil-level clear means clear the complete image cache. Settings uses the scoped methods below. */
	override fun clear() {
		volatileCache.clear()
		coverCache.clear()
	}

	override fun shutdown() {
		volatileCache.shutdown()
		coverCache.shutdown()
	}

	/**
	 * Explicit user clear must also end legacy migration. Otherwise an old cover that has not yet
	 * migrated could be copied back into persistent storage the next time that manga is opened.
	 */
	fun clearCovers() {
		coverCache.clear()
		// clearCoverCache runs on Dispatchers.IO, so commit synchronously: after this method returns,
		// a process restart must not be able to resurrect an unmigrated legacy cover.
		migrationPreferences.edit().putBoolean(KEY_LEGACY_MIGRATION_ENABLED, false).commit()
	}

	fun clearVolatile() = volatileCache.clear()

	private fun cacheFor(key: String): DiskCache = if (isCoverKey(key)) coverCache else volatileCache

	private fun normalizeKey(key: String): String {
		if (!key.startsWith(SMART_LOCAL_COVER_PREFIX, ignoreCase = true)) return key
		val id = key.substring(SMART_LOCAL_COVER_PREFIX.length).substringBefore('/').substringBefore('?').substringBefore('#')
		return id.toLongOrNull()?.let { "$COVER_KEY_PREFIX$it" } ?: key
	}

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
		const val SMART_LOCAL_COVER_PREFIX = "smart-local://cover/"
		const val COVER_DIR = "covers"
		const val COIL_DIR = "coil"
		const val VOLATILE_DIR = "image_cache"
		const val PREFS_NAME = "persistent_cover_cache"
		const val KEY_LEGACY_MIGRATION_ENABLED = "legacy_migration_enabled"

		fun createVolatileCache(context: Context): DiskCache = DiskCache.Builder()
			.directory((context.externalCacheDir ?: context.cacheDir).resolve(VOLATILE_DIR))
			.maxSizePercent(0.10)
			.minimumMaxSizeBytes(256L * 1024L * 1024L)
			.maximumMaxSizeBytes(2L * 1024L * 1024L * 1024L)
			.build()
	}
}
