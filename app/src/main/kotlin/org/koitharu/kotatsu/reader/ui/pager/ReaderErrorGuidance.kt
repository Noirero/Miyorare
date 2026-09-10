package org.koitharu.kotatsu.reader.ui.pager

import android.content.Context
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

internal data class ReaderErrorGuidance(
	val title: String,
	val message: String,
	val shortHint: String,
	val actionLabel: String? = null,
	val actionUrl: String? = null,
)

/**
 * Lightweight, deterministic guidance for reader failures. This only runs after a page has already
 * failed; it performs no network checks and never changes request routing, DNS or plugin behavior.
 *
 * The wording is deliberately probabilistic. A client-side exception can strongly suggest where a
 * failure came from, but it cannot prove ownership of a remote outage or parser breakage.
 */
internal fun buildReaderErrorGuidance(
	context: Context,
	error: Throwable,
	source: MangaSource?,
): ReaderErrorGuidance {
	val chain = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
	val diagnosticText = chain.joinToString("\n") { throwable ->
		"${throwable.javaClass.name}: ${throwable.message.orEmpty()}"
	}.lowercase(Locale.ROOT)
	val tsukiSource = source as? TsukiMangaSource
	val pluginName = tsukiSource?.plugin?.displayName?.ifBlank { tsukiSource.plugin.provider.wireName }
	val sourceName = tsukiSource?.displayName
	val pluginOrigin = tsukiSource?.plugin?.origin?.takeIf(::isHttpUrl)

	return when {
		isDnsSinkhole(diagnosticText) -> ReaderErrorGuidance(
			title = context.getString(R.string.reader_error_guidance_dns_title),
			message = context.getString(R.string.reader_error_guidance_dns_message),
			shortHint = context.getString(R.string.reader_error_guidance_dns_hint),
		)

		chain.any { it is UnknownHostException } ||
			diagnosticText.contains("unable to resolve host") ||
			diagnosticText.contains("name or service not known") -> ReaderErrorGuidance(
			title = context.getString(R.string.reader_error_guidance_network_title),
			message = context.getString(R.string.reader_error_guidance_network_dns_message),
			shortHint = context.getString(R.string.reader_error_guidance_network_hint),
		)

		isHostCompatibilityFailure(chain, diagnosticText) -> ReaderErrorGuidance(
			title = context.getString(R.string.reader_error_guidance_app_title),
			message = context.getString(R.string.reader_error_guidance_app_message),
			shortHint = context.getString(R.string.reader_error_guidance_app_hint),
			actionLabel = context.getString(R.string.reader_error_guidance_report_miyorare),
			actionUrl = MIYORARE_ISSUES_URL,
		)

		isPluginParserFailure(chain, diagnosticText, tsukiSource != null) -> ReaderErrorGuidance(
			title = context.getString(R.string.reader_error_guidance_plugin_title),
			message = context.getString(
				R.string.reader_error_guidance_plugin_message,
				pluginName ?: context.getString(R.string.reader_error_guidance_plugin_generic),
				sourceName ?: context.getString(R.string.reader_error_guidance_source_generic),
			),
			shortHint = context.getString(R.string.reader_error_guidance_plugin_hint),
			actionLabel = pluginOrigin?.let { context.getString(R.string.reader_error_guidance_open_plugin) },
			actionUrl = pluginOrigin,
		)

		isSourceOrServerFailure(diagnosticText) -> ReaderErrorGuidance(
			title = context.getString(R.string.reader_error_guidance_source_title),
			message = context.getString(R.string.reader_error_guidance_source_message),
			shortHint = context.getString(R.string.reader_error_guidance_source_hint),
			actionLabel = pluginOrigin?.let { context.getString(R.string.reader_error_guidance_open_plugin) },
			actionUrl = pluginOrigin,
		)

		chain.any { it is SocketTimeoutException } || chain.any { it is IOException } -> ReaderErrorGuidance(
			title = context.getString(R.string.reader_error_guidance_network_title),
			message = context.getString(R.string.reader_error_guidance_network_message),
			shortHint = context.getString(R.string.reader_error_guidance_network_hint),
		)

		else -> ReaderErrorGuidance(
			title = context.getString(R.string.reader_error_guidance_unknown_title),
			message = context.getString(R.string.reader_error_guidance_unknown_message),
			shortHint = context.getString(R.string.reader_error_guidance_unknown_hint),
			actionLabel = tsukiSource?.let { pluginOrigin }?.let {
				context.getString(R.string.reader_error_guidance_open_plugin)
			},
			actionUrl = pluginOrigin,
		)
	}
}

private fun isDnsSinkhole(text: String): Boolean =
	text.contains("/0.0.0.0:") ||
		text.contains("/127.0.0.1") ||
		text.contains("localhost/127.0.0.1")

private fun isHostCompatibilityFailure(chain: List<Throwable>, text: String): Boolean {
	if (chain.any { throwable ->
		val name = throwable.javaClass.name
		name.endsWith("ClassNotFoundException") ||
			name.endsWith("NoClassDefFoundError") ||
			name.endsWith("NoSuchMethodError") ||
			name.endsWith("NoSuchFieldError") ||
			name.endsWith("IncompatibleClassChangeError")
	}) return true
	return text.contains("tsuki page url is empty") ||
		text.contains("cannot instantiate manga parser") ||
		text.contains("mapped to") && text.contains("cannot instantiate")
}

private fun isPluginParserFailure(
	chain: List<Throwable>,
	text: String,
	isTsukiSource: Boolean,
): Boolean {
	if (!isTsukiSource) return false
	val parserException = chain.any { throwable ->
		throwable.javaClass.simpleName.contains("ParseException", ignoreCase = true)
	}
	val pluginFrame = chain.any { throwable ->
		throwable.stackTrace.any { frame -> frame.className.startsWith("tsuki.") }
	}
	val parserSignal = text.contains("selector") ||
		text.contains("parseexception") ||
		text.contains("jsonexception") ||
		text.contains("required element")
	return parserException || pluginFrame && parserSignal
}

private fun isSourceOrServerFailure(text: String): Boolean =
	text.contains("connection closed") ||
		text.contains("connection reset") ||
		text.contains("reset by peer") ||
		text.contains("unexpected end of stream") ||
		text.contains("stream was reset") ||
		text.contains("http 403") ||
		text.contains("http 429") ||
		HTTP_5XX.containsMatchIn(text)

private fun isHttpUrl(value: String): Boolean =
	value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)

private const val MAX_CAUSE_DEPTH = 12
private const val MIYORARE_ISSUES_URL = "https://github.com/Noirero/Miyorare/issues"
private val HTTP_5XX = Regex("\\b(?:http\\s*)?5\\d\\d\\b")
