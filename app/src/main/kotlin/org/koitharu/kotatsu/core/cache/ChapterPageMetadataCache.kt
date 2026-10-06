package org.koitharu.kotatsu.core.cache

import android.app.Application
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChapterPageMetadataCache @Inject constructor(
	private val application: Application,
) {
	private val directory = File(application.cacheDir, DIRECTORY_NAME)
	private val lock = Any()

	fun sourceFingerprint(source: MangaSource): String = when (source) {
		is LnMangaSource -> "ln:${source.plugin.version}"
		is TsukiMangaSource -> "tsuki:${source.plugin.version}:${source.plugin.sha256}"
		is MihonMangaSource -> "mihon:${mihonPackageVersion(source.pkgName)}"
		else -> "native:${BuildConfig.VERSION_CODE}"
	}

	suspend fun get(
		source: MangaSource,
		chapter: MangaChapter,
		sourceVersion: String = sourceFingerprint(source),
		now: Long = System.currentTimeMillis(),
	): List<MangaPage>? = withContext(Dispatchers.IO) {
		synchronized(lock) {
			val file = entryFile(source, chapter, sourceVersion)
			if (!file.isFile) return@synchronized null
			val entry = runCatching { JSONObject(file.readText()) }.getOrElse { error ->
				file.delete()
				logMiss("Corrupt page metadata cache entry", error)
				return@synchronized null
			}
			if (entry.optInt(KEY_SCHEMA, -1) != SCHEMA_VERSION) {
				file.delete()
				return@synchronized null
			}
			val cachedAt = entry.optLong(KEY_CACHED_AT, -1L)
			if (cachedAt < 0L || now - cachedAt > TTL_MILLIS) {
				file.delete()
				return@synchronized null
			}
			val pagesJson = entry.optJSONArray(KEY_PAGES) ?: run {
				file.delete()
				return@synchronized null
			}
			val pages = runCatching {
				List(pagesJson.length()) { index ->
					val page = pagesJson.getJSONObject(index)
					MangaPage(
						id = page.getLong(KEY_ID),
						url = page.getString(KEY_URL),
						preview = if (page.isNull(KEY_PREVIEW)) null else page.getString(KEY_PREVIEW),
						source = source,
					)
				}
			}.getOrElse { error ->
				file.delete()
				logMiss("Invalid page metadata cache payload", error)
				return@synchronized null
			}
			file.setLastModified(now)
			pages
		}
	}

	/** Cache persistence is an optimization. A disk failure must never fail a successful source request. */
	suspend fun put(
		source: MangaSource,
		chapter: MangaChapter,
		pages: List<MangaPage>,
		sourceVersion: String = sourceFingerprint(source),
		now: Long = System.currentTimeMillis(),
	) = withContext(Dispatchers.IO) {
		synchronized(lock) {
			try {
				if (!directory.exists() && !directory.mkdirs()) return@synchronized
				val pageArray = JSONArray()
				pages.forEach { page ->
					pageArray.put(
						JSONObject()
							.put(KEY_ID, page.id)
							.put(KEY_URL, page.url)
							.put(KEY_PREVIEW, page.preview ?: JSONObject.NULL),
					)
				}
				val payload = JSONObject()
					.put(KEY_SCHEMA, SCHEMA_VERSION)
					.put(KEY_CACHED_AT, now)
					.put(KEY_PAGES, pageArray)
				val atomicFile = AtomicFile(entryFile(source, chapter, sourceVersion))
				val output = atomicFile.startWrite()
				try {
					output.write(payload.toString().toByteArray(Charsets.UTF_8))
					atomicFile.finishWrite(output)
				} catch (error: IOException) {
					atomicFile.failWrite(output)
					throw error
				}
				trimLocked()
			} catch (error: IOException) {
				logMiss("Unable to persist page metadata cache entry", error)
			} catch (error: SecurityException) {
				logMiss("Unable to access page metadata cache", error)
			}
		}
	}

	suspend fun invalidate(
		source: MangaSource,
		chapter: MangaChapter,
		sourceVersion: String = sourceFingerprint(source),
	) = withContext(Dispatchers.IO) {
		synchronized(lock) {
			runCatching { entryFile(source, chapter, sourceVersion).delete() }
				.onFailure { error -> logMiss("Unable to invalidate page metadata cache entry", error) }
		}
	}

	@Suppress("DEPRECATION")
	private fun mihonPackageVersion(packageName: String): String = runCatching {
		val info = application.packageManager.getPackageInfo(packageName, 0)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			"${info.versionName.orEmpty()}:${info.longVersionCode}"
		} else {
			"${info.versionName.orEmpty()}:${info.versionCode}"
		}
	}.getOrElse { "package:$packageName" }

	private fun entryFile(source: MangaSource, chapter: MangaChapter, sourceVersion: String): File {
		val identity = "${source.name}|$sourceVersion|${chapter.id}|${chapter.url}"
		val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
		return File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
	}

	private fun trimLocked() {
		val files = directory.listFiles()?.filter(File::isFile).orEmpty()
		if (files.size > MAX_ENTRIES) {
			files.sortedBy(File::lastModified)
				.take(files.size - MAX_ENTRIES)
				.forEach(File::delete)
		}
	}

	private fun logMiss(message: String, error: Throwable) {
		if (BuildConfig.DEBUG) Log.d(TAG, message, error)
	}

	private companion object {
		const val TAG = "ChapterPageMetadata"
		const val DIRECTORY_NAME = "chapter_page_metadata"
		const val SCHEMA_VERSION = 1
		const val MAX_ENTRIES = 500
		const val KEY_SCHEMA = "schema"
		const val KEY_CACHED_AT = "cachedAt"
		const val KEY_PAGES = "pages"
		const val KEY_ID = "id"
		const val KEY_URL = "url"
		const val KEY_PREVIEW = "preview"
		val TTL_MILLIS = TimeUnit.HOURS.toMillis(48)
	}
}
