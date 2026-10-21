package org.koitharu.kotatsu.kotatsu.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.BaseHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Fetches only Miyorare-published, verified-compatible Kotatsu Runtime Pack releases. */
@Singleton
class KotatsuRuntimeUpdater @Inject constructor(
	@ApplicationContext private val context: Context,
	@BaseHttpClient private val httpClient: OkHttpClient,
	private val runtimeManager: KotatsuRuntimeManager,
) {
	private val updateMutex = Mutex()

	suspend fun checkForUpdate(): KotatsuRuntimePackManifest? = withContext(Dispatchers.IO) {
		val request = Request.Builder().url(KotatsuRuntimePackContract.DEFAULT_MANIFEST_URL).build()
		val json = httpClient.newCall(request).await().use { response ->
			require(response.isSuccessful) { "Kotatsu runtime manifest request failed: HTTP ${response.code}" }
			response.body.string()
		}
		val remote = KotatsuRuntimePackManifest.parse(json)
		require(remote.isHostCompatible) {
			"Latest Kotatsu runtime requires host protocol ${remote.protocolVersion}"
		}
		// Compare against whichever verified slot is actually active, including the immutable bundled
		// last-known-good. A fresh install must not redownload the exact runtime already embedded in it.
		val activeSha256 = runtimeManager.activeManifestSha256()
		remote.takeUnless { activeSha256.equals(remote.sha256, ignoreCase = true) }
	}

	suspend fun updateLatest(): KotatsuRuntimePackManifest? = updateMutex.withLock {
		withContext(Dispatchers.IO) {
			// Re-check only after acquiring the single-flight lock. A concurrent manual/worker caller may
			// have activated this exact pack while this caller was waiting; installing it twice would
			// overwrite the real previous-known-good slot with a duplicate of current.
			val manifest = checkForUpdate() ?: return@withContext null
			val target = File.createTempFile("kotatsu-runtime-", ".apk", context.cacheDir)
			try {
				val request = Request.Builder().url(manifest.downloadUrl).build()
				httpClient.newCall(request).await().use { response ->
					require(response.isSuccessful) { "Kotatsu runtime download failed: HTTP ${response.code}" }
					response.body.byteStream().use { input -> target.outputStream().use(input::copyTo) }
				}
				runtimeManager.install(target, manifest)
				manifest
			} finally {
				target.delete()
			}
		}
	}
}
