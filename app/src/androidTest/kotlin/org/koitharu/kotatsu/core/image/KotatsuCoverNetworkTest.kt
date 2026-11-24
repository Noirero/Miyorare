package org.koitharu.kotatsu.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.ConnectivityChecker
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.kotatsu.model.KotatsuMangaSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Provider

@OptIn(ExperimentalCoilApi::class)
class KotatsuCoverNetworkTest {

	private val context: Context = ApplicationProvider.getApplicationContext()
	private val source = KotatsuMangaSource("TEST", "Test", "es", "MANGA")

	@Test
	fun sendsParserRefererToCoverHostAndPreservesExplicitHeaders() = runBlocking {
		val observed = AtomicReference<Request>()
		val client = OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			observed.set(request)
			if (request.header("Referer") == REFERER) {
				coverResponse(request, Color.RED)
			} else {
				Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
					.code(403).message("Missing referer").body(ByteArray(0).toResponseBody()).build()
			}
		}.build()
		withLoader(client) { loader ->
			val request = request().httpHeaders(NetworkHeaders.Builder().set("User-Agent", "explicit-agent").build())
			assertEquals(Color.RED, loader.execute(request.build()).coverColor())
			val sent = checkNotNull(observed.get())
			assertEquals(REFERER, sent.header("Referer"))
			assertEquals("explicit-agent", sent.header("User-Agent"))
			assertNull(sent.header("Authorization"))
		}
	}

	@Test
	fun cancellingCoverRequestCancelsTheActiveHttpCall() = runBlocking {
		val started = CompletableDeferred<Unit>()
		val cancelled = CompletableDeferred<Unit>()
		val release = CountDownLatch(1)
		val client = OkHttpClient.Builder()
			.eventListener(object : EventListener() {
				override fun canceled(call: Call) {
					cancelled.complete(Unit)
				}
			})
			.addInterceptor {
				started.complete(Unit)
				release.await(10, TimeUnit.SECONDS)
				throw IOException("Test request ended")
			}
			.build()
		withLoader(client) { loader ->
			val result = async { loader.execute(request().build()) }
			try {
				withTimeout(5_000) {
					started.await()
					result.cancel()
					cancelled.await()
				}
				assertTrue(result.isCancelled)
			} finally {
				release.countDown()
			}
		}
	}

	@Test
	fun forceRefreshBypassesTheCachedCoverAndReplacesIt() = runBlocking {
		val calls = AtomicInteger()
		val refreshPolicy = AtomicReference<String>()
		val client = OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			val color = if (calls.incrementAndGet() == 1) Color.RED else Color.BLUE
			refreshPolicy.set(request.header("Cache-Control"))
			coverResponse(request, color)
		}.build()
		withLoader(client) { loader ->
			assertEquals(Color.RED, loader.execute(request().build()).coverColor())
			assertEquals(Color.RED, loader.execute(request().build()).coverColor())
			assertEquals(1, calls.get())

			val refresh = request().diskCachePolicy(CachePolicy.WRITE_ONLY).build()
			assertEquals(Color.BLUE, loader.execute(refresh).coverColor())
			assertEquals("no-cache", refreshPolicy.get())
			assertEquals(Color.BLUE, loader.execute(request().build()).coverColor())
			assertEquals(2, calls.get())
		}
	}

	private fun request() = ImageRequest.Builder(context)
		.data("https://cover.test/image.png")
		.mangaSourceExtra(source)
		.memoryCachePolicy(CachePolicy.DISABLED)
		.allowHardware(false)

	private suspend fun withLoader(client: OkHttpClient, block: suspend (ImageLoader) -> Unit) {
		val directory = kotlin.io.path.createTempDirectory(context.cacheDir.toPath()).toFile()
		val loader = ImageLoader.Builder(context)
			.diskCache { DiskCache.Builder().directory(directory).build() }
			.components {
				add(MangaSourceHeaderInterceptor(Provider { error("Unexpected WebView") }) { resolved ->
					check(resolved == source)
					headersOf("Referer", REFERER, "User-Agent", "parser-agent", "Authorization", "test-token")
				})
				add(OkHttpNetworkFetcherFactory(callFactory = { client }, connectivityChecker = { ConnectivityChecker.ONLINE }))
			}
			.build()
		try {
			block(loader)
		} finally {
			loader.shutdown()
			directory.deleteRecursively()
		}
	}

	private fun coverResponse(request: Request, color: Int): Response {
		val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
		val bytes = try {
			bitmap.eraseColor(color)
			ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
		} finally {
			bitmap.recycle()
		}
		return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
			.code(200).message("OK").header("Content-Type", "image/png")
			.header("Cache-Control", "public, max-age=300").body(bytes.toResponseBody()).build()
	}

	private fun ImageResult.coverColor(): Int {
		check(this is SuccessResult) { (this as? ErrorResult)?.throwable?.stackTraceToString().orEmpty() }
		return image.toBitmap().getPixel(0, 0)
	}

	private companion object {
		const val REFERER = "https://source.test/"
	}
}
