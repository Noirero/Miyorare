package org.koitharu.kotatsu.core.network.webview.adblock

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.WorkerThread
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@Reusable
class AdBlock @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
) {

	private var rules: RulesList? = null
	private var rulesGeneration = Long.MIN_VALUE

	val isEnabled: Boolean
		get() = settings.isAdBlockEnabled

	fun hasRuleList(): Boolean = listFile(context).let { it.isFile && it.length() > 0L }

	@WorkerThread
	fun shouldLoadUrl(
		url: String,
		baseUrl: String?,
		resourceType: ResourceType? = null,
	): Boolean {
		return shouldLoadUrl(
			url.lowercase().toHttpUrlOrNull() ?: return true,
			baseUrl?.lowercase()?.toHttpUrlOrNull(),
			resourceType,
		)
	}

	@WorkerThread
	fun shouldLoadUrl(url: HttpUrl, baseUrl: HttpUrl?, resourceType: ResourceType? = null): Boolean {
		if (!settings.isAdBlockEnabled) {
			return true
		}
		val generation = listGeneration.get()
		val currentRules = synchronized(this) {
			if (rules == null || rulesGeneration != generation) {
				rules = parseRules()
				rulesGeneration = generation
			}
			rules
		} ?: return true
		val rule = currentRules[url, baseUrl, resourceType]
		if (rule != null) {
			Log.i(TAG, "Blocked $url by $rule")
		}
		return rule == null
	}

	@WorkerThread
	private fun parseRules(): RulesList = runCatchingCancellable {
		val file = listFile(context)
		if (!file.isFile || file.length() == 0L) {
			return@runCatchingCancellable RulesList()
		}
		file.useLines { lines ->
			val rules = RulesList()
			lines.forEach(rules::add)
			rules.trimToSize()
			rules
		}
	}.onFailure { e ->
		e.printStackTraceDebug()
	}.getOrElse {
		RulesList()
	}

	class Updater @Inject constructor(
		@ApplicationContext private val context: Context,
		@BaseHttpClient private val okHttpClient: OkHttpClient,
	) {

		suspend fun updateListIfStale(maxAgeMillis: Long = LIST_REFRESH_INTERVAL): Boolean =
			updateMutex.withLock {
				if (isListFresh(maxAgeMillis)) false else updateListLocked()
			}

		suspend fun updateList(): Boolean = updateMutex.withLock {
			updateListLocked()
		}

		private fun isListFresh(maxAgeMillis: Long): Boolean {
			val file = listFile(context)
			if (file.isFile && file.length() > 0L) {
				val age = System.currentTimeMillis() - file.lastModified()
				if (age in 0 until maxAgeMillis) {
					return true
				}
			}
			return false
		}

		private suspend fun updateListLocked(): Boolean {
			val file = listFile(context)
			val dateFormat = SimpleDateFormat(CommonHeaders.DATE_FORMAT, Locale.ENGLISH).apply {
				timeZone = TimeZone.getTimeZone("GMT")
			}
			val requestBuilder = Request.Builder()
				.url(EASYLIST_URL)
				.get()
			if (file.isFile && file.length() > 0L) {
				requestBuilder.header(
					CommonHeaders.IF_MODIFIED_SINCE,
					dateFormat.format(Date(file.lastModified())),
				)
			}
			okHttpClient.newCall(requestBuilder.build()).await().use { response ->
				if (response.code == HttpURLConnection.HTTP_NOT_MODIFIED) {
					file.setLastModified(System.currentTimeMillis())
					return false
				}
				if (!response.isSuccessful) {
					throw IOException("EasyList update failed with HTTP ${response.code}")
				}

				val atomicFile = AtomicFile(file)
				val output = atomicFile.startWrite()
				try {
					val copied = response.body.byteStream().use { input ->
						input.copyTo(output)
					}
					if (copied <= 0L) {
						throw IOException("EasyList response was empty")
					}
					atomicFile.finishWrite(output)
				} catch (e: Throwable) {
					atomicFile.failWrite(output)
					throw e
				}
				file.setLastModified(System.currentTimeMillis())
				listGeneration.incrementAndGet()
				return true
			}
		}
	}

	private companion object {

		private val listGeneration = AtomicLong(0L)
		private val updateMutex = Mutex()

		fun listFile(context: Context): File {
			val root = File(context.noBackupFilesDir, LIST_DIR).apply { mkdirs() }
			val file = File(root, LIST_FILENAME)
			if (!file.exists()) {
				migrateLegacyList(context, file)
			}
			return file
		}

		private fun migrateLegacyList(context: Context, target: File) {
			val legacyRoot = File(context.externalCacheDir ?: context.cacheDir, LIST_DIR)
			val legacy = File(legacyRoot, LIST_FILENAME)
			if (!legacy.isFile || legacy.length() == 0L) return
			runCatching {
				legacy.copyTo(target, overwrite = true)
				target.setLastModified(legacy.lastModified())
			}.onFailure {
				target.delete()
			}
		}

		private const val LIST_FILENAME = "easylist.txt"
		private const val LIST_DIR = "adblock"
		private const val EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
		private const val LIST_REFRESH_INTERVAL = 24L * 60L * 60L * 1000L
		private const val TAG = "AdBlock"
	}
}
