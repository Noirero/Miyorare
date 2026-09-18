package org.koitharu.kotatsu.settings.about

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.github.AppUpdateRepository
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.requireValue
import java.io.File
import java.io.IOException
import javax.inject.Inject

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

@HiltViewModel
class AppUpdateViewModel @Inject constructor(
	private val repository: AppUpdateRepository,
	@BaseHttpClient private val okHttp: OkHttpClient,
	@ApplicationContext private val context: Context,
) : BaseViewModel() {

	val nextVersion = repository.observeAvailableUpdate()
	val downloadProgress = MutableStateFlow(-1f)
	val installIntent = MutableStateFlow<Intent?>(null)
	val onDownloadDone = MutableEventFlow<Intent>()

	private var downloadJob: Job? = null

	init {
		if (nextVersion.value == null) {
			launchLoadingJob(Dispatchers.Default) {
				repository.fetchUpdate()
			}
		}
	}

	/**
	 * Download the update into app-owned storage instead of relying on DownloadManager.
	 *
	 * This keeps the update action deterministic on OEM/Android versions where a DownloadManager
	 * request can be accepted without giving this screen useful progress or completion feedback.
	 * The package installer still receives a read-only FileProvider Uri and performs the normal
	 * Android signature/package verification before installation.
	 */
	fun startDownload() {
		installIntent.value?.let {
			onDownloadDone.call(it)
			return
		}
		if (downloadJob?.isActive == true) {
			return
		}
		downloadProgress.value = 0f
		downloadJob = launchLoadingJob(Dispatchers.IO) {
			val version = nextVersion.requireValue()
			val updatesDir = File(context.cacheDir, "app-updates").apply {
				if (!exists() && !mkdirs()) {
					throw IOException("Unable to prepare update cache")
				}
			}
			updatesDir.listFiles()?.forEach { stale ->
				if (stale.isFile) {
					stale.delete()
				}
			}
			val target = File(updatesDir, "Miyorare-${version.name}.apk")
			try {
				val request = Request.Builder()
					.url(version.apkUrl)
					.get()
					.build()
				okHttp.newCall(request).await().use { response ->
					if (!response.isSuccessful) {
						throw IOException("Update download failed with HTTP ${response.code}")
					}
					val responseSize = response.body.contentLength()
					if (responseSize > 0L && version.apkSize > 0L && responseSize != version.apkSize) {
						throw IOException("Update size changed while downloading")
					}
					val expectedSize = when {
						responseSize > 0L -> responseSize
						version.apkSize > 0L -> version.apkSize
						else -> -1L
					}
					if (expectedSize <= 0L) {
						downloadProgress.value = -1f
					}
					response.body.byteStream().use { input ->
						target.outputStream().buffered().use { output ->
							val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
							var downloaded = 0L
							var lastPublishedPercent = -1
							while (true) {
								currentCoroutineContext().ensureActive()
								val count = input.read(buffer)
								if (count < 0) break
								if (count == 0) continue
								output.write(buffer, 0, count)
								downloaded += count
								if (expectedSize > 0L) {
									val progress = (downloaded.toDouble() / expectedSize.toDouble())
										.coerceIn(0.0, 1.0)
									// Animating the progress widget for every network buffer can create
									// needless main-thread churn. One update per percent stays smooth.
									val percent = (progress * 100.0).toInt().coerceAtMost(99)
									if (percent != lastPublishedPercent) {
										lastPublishedPercent = percent
										downloadProgress.value = progress.toFloat()
									}
								}
							}
						}
					}
				}
				if (!target.isFile || target.length() <= 0L) {
					throw IOException("Downloaded update is empty")
				}
				if (version.apkSize > 0L && target.length() != version.apkSize) {
					throw IOException("Downloaded update is incomplete")
				}
				downloadProgress.value = 1f
				val uri = FileProvider.getUriForFile(
					context,
					"${BuildConfig.APPLICATION_ID}.files",
					target,
				)
				val installerIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
					.setDataAndType(uri, APK_MIME_TYPE)
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
					.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
				installIntent.value = installerIntent
				onDownloadDone.call(installerIntent)
			} catch (e: Throwable) {
				target.delete()
				downloadProgress.value = -1f
				throw e
			}
		}
	}
}
