package org.koitharu.kotatsu.core.diagnostics

import android.net.Uri
import coil3.EventListener
import coil3.Extras
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult

private val roleKey = Extras.Key("untagged")
private val sessionKey = Extras.Key(0L)

fun ImageRequest.Builder.diagnosticImage(mangaId: Long, role: String): ImageRequest.Builder = apply {
	if (DetailsDiagnostic.enabled) {
		extras[roleKey] = role
		extras[sessionKey] = if (role.startsWith("details")) DetailsDiagnostic.sessionFor(mangaId) else 0L
	}
}

/** One small listener per request; only bounded rows plus aggregate counters are retained. */
class DiagnosticImageListener(private val delegate: EventListener) : EventListener() {
	private var session = 0L
	private var role = "untagged"
	private var active = false
	private var fetching = false
	private var decoding = false
	private var started = 0L
	private var fetchStarted = 0L
	private var decodeStarted = 0L

	override fun onStart(request: ImageRequest) {
		delegate.onStart(request)
		session = request.extras[sessionKey] ?: 0L
		role = request.extras[roleKey] ?: "untagged"
		if (role == "untagged" && when (val data = request.data) {
			is Uri -> data.scheme == "favicon"
			is String -> data.startsWith("favicon:")
			else -> false
		}) role = "favicon"
		started = DetailsDiagnostic.now()
		active = true
		DetailsDiagnostic.imageDelta("request", 1)
		DetailsDiagnostic.imageEvent("start", session, role)
	}

	override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
		delegate.fetchStart(request, fetcher, options)
		fetchStarted = DetailsDiagnostic.now()
		fetching = true
		DetailsDiagnostic.imageDelta("fetch", 1)
	}

	override fun fetchEnd(request: ImageRequest, fetcher: Fetcher, options: Options, result: FetchResult?) {
		delegate.fetchEnd(request, fetcher, options, result)
		if (fetching) { fetching = false; DetailsDiagnostic.imageDelta("fetch", -1) }
		DetailsDiagnostic.imageEvent("fetch_end", session, role, mapOf("durationNs" to (DetailsDiagnostic.now() - fetchStarted)))
	}

	override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
		delegate.decodeStart(request, decoder, options)
		decodeStarted = DetailsDiagnostic.now()
		decoding = true
		DetailsDiagnostic.imageDelta("decode", 1)
	}

	override fun decodeEnd(request: ImageRequest, decoder: Decoder, options: Options, result: DecodeResult?) {
		delegate.decodeEnd(request, decoder, options, result)
		if (decoding) { decoding = false; DetailsDiagnostic.imageDelta("decode", -1) }
		DetailsDiagnostic.imageEvent("decode_end", session, role, mapOf("durationNs" to (DetailsDiagnostic.now() - decodeStarted),
			"imageBytes" to (result?.image?.size ?: "unknown")))
	}

	override fun onSuccess(request: ImageRequest, result: SuccessResult) {
		delegate.onSuccess(request, result)
		finish("success", mapOf("dataSource" to result.dataSource.name, "imageBytes" to result.image.size,
			"width" to result.image.width, "height" to result.image.height))
	}

	override fun onError(request: ImageRequest, result: ErrorResult) {
		delegate.onError(request, result) // Preserve captcha handling/retry behavior exactly.
		finish("error", mapOf("errorType" to result.throwable.javaClass.simpleName))
	}

	override fun onCancel(request: ImageRequest) {
		delegate.onCancel(request)
		finish("cancel", emptyMap())
	}

	private fun finish(result: String, fields: Map<String, Any?>) {
		if (fetching) { fetching = false; DetailsDiagnostic.imageDelta("fetch", -1) }
		if (decoding) { decoding = false; DetailsDiagnostic.imageDelta("decode", -1) }
		if (active) { active = false; DetailsDiagnostic.imageDelta("request", -1) }
		DetailsDiagnostic.imageEvent(result, session, role, fields + ("durationNs" to (DetailsDiagnostic.now() - started)))
	}
}
