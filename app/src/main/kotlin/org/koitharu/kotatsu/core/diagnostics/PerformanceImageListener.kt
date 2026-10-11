package org.koitharu.kotatsu.core.diagnostics

import coil3.decode.DataSource
import org.koitharu.kotatsu.core.image.CbzFetcher
import org.koitharu.kotatsu.local.library.LocalCoverFetcher
import coil3.EventListener
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler
import javax.inject.Inject

/** Keeps CaptchaHandler's retry behavior and lets Coil report its actual data source/cache result. */
class PerformanceImageListener @Inject constructor(private val captchaHandler: CaptchaHandler) : EventListener.Factory {
	override fun create(request: ImageRequest): EventListener {
		if (!PerformanceDiagnostics.enabled) return captchaHandler
		return object : EventListener() {
			private val trace = PerformanceDiagnostics.currentTrace
			private var requestTiming: PerformanceDiagnostics.Operation? = null
			private var fetch: PerformanceDiagnostics.Operation? = null
			private var decode: PerformanceDiagnostics.Operation? = null
			private var local = false
			override fun onStart(request: ImageRequest) {
				requestTiming = PerformanceDiagnostics.begin("image", "request", trace)
			}
			override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
				local = fetcher is LocalCoverFetcher || fetcher is CbzFetcher
				fetch = PerformanceDiagnostics.begin("image", "fetch", trace)
			}
			override fun fetchEnd(request: ImageRequest, fetcher: Fetcher, options: Options, result: FetchResult?) {
				PerformanceDiagnostics.end(fetch)
				fetch = null
			}
			override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
				decode = PerformanceDiagnostics.begin("image", "decode", trace)
			}
			override fun decodeEnd(request: ImageRequest, decoder: Decoder, options: Options, result: DecodeResult?) {
				PerformanceDiagnostics.end(decode)
				decode = null
			}
			override fun onSuccess(request: ImageRequest, result: SuccessResult) {
				// Route aggregates distinguish hits from fall-through without probing cache keys ourselves.
				PerformanceDiagnostics.end(requestTiming, "complete", "route=${result.dataSource.name} scope=foreground_at_start")
				val route = if (local) "LOCAL" else result.dataSource.name
				PerformanceDiagnostics.observed("image", "route_$route", trace = trace)
				if (result.request.memoryCachePolicy.readEnabled) {
					PerformanceDiagnostics.observed("image", if (result.dataSource == DataSource.MEMORY_CACHE) "memory_hit" else "memory_miss", trace = trace)
				}
				if (!local && result.request.diskCachePolicy.readEnabled && result.dataSource != DataSource.MEMORY_CACHE) {
					// NETWORK is an observed disk fall-through; other MEMORY data is not a disk lookup.
					if (result.dataSource == DataSource.DISK || result.dataSource == DataSource.NETWORK) {
						PerformanceDiagnostics.observed("image", if (result.dataSource == DataSource.DISK) "disk_hit" else "disk_miss", trace = trace)
					}
				}
			}
			override fun onCancel(request: ImageRequest) { finish("cancel") }
			override fun onError(request: ImageRequest, result: ErrorResult) {
				finish("failure")
				captchaHandler.onError(request, result)
			}
			private fun finish(outcome: String) {
				PerformanceDiagnostics.end(fetch, outcome)
				PerformanceDiagnostics.end(decode, outcome)
				PerformanceDiagnostics.end(requestTiming, outcome)
			}
		}
	}
}
