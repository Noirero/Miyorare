package org.koitharu.kotatsu.kotatsu.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
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
	private val store = KotatsuRuntimePackStore(context)

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
		val current = store.currentManifest()
		remote.takeUnless { current?.sha256.equals(remote.sha256, ignoreCase = true) }
	}

	suspend fun updateLatest(): KotatsuRuntimePackManifest? = withContext(Dispatchers.IO) {
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
