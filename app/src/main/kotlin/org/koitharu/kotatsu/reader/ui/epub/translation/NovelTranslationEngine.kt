package org.koitharu.kotatsu.reader.ui.epub.translation

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.koitharu.kotatsu.core.network.CurlLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A single text segment requested by the Novel reader. */
data class NovelTranslationRequest(
	val text: String,
	val sourceLanguage: String,
	val targetLanguage: String,
	val style: NovelTranslationStyle = NovelTranslationStyle.NATURAL,
	val contextAware: Boolean = false,
	val beforeContext: String = "",
	val afterContext: String = "",
)

interface NovelTranslationEngine {
	suspend fun translate(request: NovelTranslationRequest): String
	suspend fun listModels(): List<String> = emptyList()
}

/**
 * Existing Miyorare online translator, generalized so the Reader is no longer limited to a small
 * hard-coded list of language pairs. The endpoint accepts arbitrary supported language codes and
 * `auto` for source-language detection.
 */
class MiyorareOnlineTranslationEngine(
	httpClient: OkHttpClient,
) : NovelTranslationEngine {

	private val client = httpClient.translationClient()

	override suspend fun translate(request: NovelTranslationRequest): String {
		if (request.text.isBlank()) return request.text
		val source = request.sourceLanguage.ifBlank { NovelTranslationSettings.LANGUAGE_AUTO }
		val target = request.targetLanguage.trim()
		if (target.isEmpty()) throw NovelTranslationException.Configuration("Target language is required")
		if (source != NovelTranslationSettings.LANGUAGE_AUTO && source.equals(target, ignoreCase = true)) {
			return request.text
		}
		val url = ONLINE_TRANSLATE_URL.toHttpUrl().newBuilder()
			.addQueryParameter("client", "gtx")
			.addQueryParameter("sl", source)
			.addQueryParameter("tl", target)
			.addQueryParameter("dt", "t")
			.addQueryParameter("q", request.text)
			.build()
		val response = client.newCall(
			Request.Builder()
				.url(url)
				.header("User-Agent", USER_AGENT)
				.get()
				.build(),
		).await()
		response.use {
			it.requireSuccessful()
			val root = parseJson(it.body.string()).jsonArray
			val segments = root.getOrNull(0)?.jsonArray
				?: throw NovelTranslationException.InvalidResponse("Online translator returned an invalid response")
			val translated = segments.joinToString("") { segment ->
				segment.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
			}
			if (translated.isBlank() && request.text.isNotBlank()) {
				throw NovelTranslationException.InvalidResponse("Online translator returned an empty result")
			}
			return translated
		}
	}

	companion object {
		private const val ONLINE_TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single"
	}
}

/**
 * Optional BYOK engine. No provider SDK or on-device model is bundled: all providers reuse OkHttp
 * and kotlinx.serialization already present in Miyorare.
 */
class NovelAiTranslationEngine(
	httpClient: OkHttpClient,
	private val settings: NovelTranslationSettings,
	private val secrets: NovelTranslationSecrets,
) : NovelTranslationEngine {

	private val client = httpClient.translationClient()

	override suspend fun translate(request: NovelTranslationRequest): String {
		if (request.text.isBlank()) return request.text
		val provider = settings.provider
		val apiKey = secrets.get(provider)
			?: throw NovelTranslationException.Configuration("API key is not configured for ${provider.displayName}")
		return when (provider.protocol) {
			NovelAiProtocol.OPENAI_COMPATIBLE -> translateOpenAiCompatible(provider, apiKey, request)
			NovelAiProtocol.ANTHROPIC -> translateAnthropic(provider, apiKey, request)
			NovelAiProtocol.DEEPL -> translateDeepL(provider, apiKey, request)
		}
	}

	override suspend fun listModels(): List<String> {
		val provider = settings.provider
		if (!provider.supportsModelDiscovery || provider.protocol != NovelAiProtocol.OPENAI_COMPATIBLE) return emptyList()
		val apiKey = secrets.get(provider) ?: return emptyList()
		val baseUrl = resolvedBaseUrl(provider)
		if (baseUrl.isBlank()) return emptyList()
		val response = client.newCall(
			Request.Builder()
				.url(baseUrl.toHttpUrl().newBuilder().addPathSegment("models").build())
				.header("Authorization", "Bearer $apiKey")
				.header("User-Agent", USER_AGENT)
				.get()
				.build(),
		).await()
		response.use {
			it.requireSuccessful()
			val root = parseJson(it.body.string()).jsonObject
			return root["data"]?.jsonArray.orEmpty()
				.mapNotNull { item -> item.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
				.distinct()
				.sorted()
		}
	}

	private suspend fun translateOpenAiCompatible(
		provider: NovelAiProvider,
		apiKey: String,
		request: NovelTranslationRequest,
	): String {
		val baseUrl = resolvedBaseUrl(provider)
		if (baseUrl.isBlank()) throw NovelTranslationException.Configuration("Base URL is required")
		val model = settings.model.trim()
		if (model.isEmpty()) throw NovelTranslationException.Configuration("Select or enter a model")
		val body = buildJsonObject {
			put("model", model)
			put("messages", buildJsonArray {
				add(buildJsonObject {
					put("role", "system")
					put("content", systemPrompt(request))
				})
				add(buildJsonObject {
					put("role", "user")
					put("content", userPrompt(request))
				})
			})
		}
		val response = client.newCall(
			Request.Builder()
				.url(baseUrl.toHttpUrl().newBuilder().addPathSegments("chat/completions").build())
				.header("Authorization", "Bearer $apiKey")
				.header("User-Agent", USER_AGENT)
				.post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
				.build(),
		).await()
		response.use {
			it.requireSuccessful()
			val root = parseJson(it.body.string()).jsonObject
			val content = root["choices"]?.jsonArray
				?.firstOrNull()?.jsonObject
				?.get("message")?.jsonObject
				?.get("content")
				?.let(::extractTextContent)
				.orEmpty()
			if (content.isBlank()) throw NovelTranslationException.InvalidResponse("${provider.displayName} returned an empty result")
			return content.trim()
		}
	}

	private suspend fun translateAnthropic(
		provider: NovelAiProvider,
		apiKey: String,
		request: NovelTranslationRequest,
	): String {
		val model = settings.model.trim()
		if (model.isEmpty()) throw NovelTranslationException.Configuration("Select or enter a Claude model")
		val baseUrl = resolvedBaseUrl(provider)
		val body = buildJsonObject {
			put("model", model)
			put("max_tokens", 4096)
			put("system", systemPrompt(request))
			put("messages", buildJsonArray {
				add(buildJsonObject {
					put("role", "user")
					put("content", userPrompt(request))
				})
			})
		}
		val response = client.newCall(
			Request.Builder()
				.url(baseUrl.toHttpUrl().newBuilder().addPathSegment("messages").build())
				.header("x-api-key", apiKey)
				.header("anthropic-version", ANTHROPIC_VERSION)
				.header("User-Agent", USER_AGENT)
				.post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
				.build(),
		).await()
		response.use {
			it.requireSuccessful()
			val root = parseJson(it.body.string()).jsonObject
			val content = root["content"]?.jsonArray.orEmpty()
				.mapNotNull { part -> part.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
				.joinToString("")
			if (content.isBlank()) throw NovelTranslationException.InvalidResponse("Claude returned an empty result")
			return content.trim()
		}
	}

	private suspend fun translateDeepL(
		provider: NovelAiProvider,
		apiKey: String,
		request: NovelTranslationRequest,
	): String {
		val target = request.targetLanguage.trim()
		if (target.isEmpty()) throw NovelTranslationException.Configuration("Target language is required")
		val configured = settings.baseUrlOverride.trim()
		val baseUrl = when {
			configured.isNotEmpty() -> configured.trimEnd('/')
			settings.deeplFreeApi -> provider.defaultBaseUrl
			else -> DEEPL_PAID_BASE_URL
		}
		val form = FormBody.Builder()
			.add("text", request.text)
			.add("target_lang", target.uppercase())
			.apply {
				request.sourceLanguage.takeUnless { it.isBlank() || it == NovelTranslationSettings.LANGUAGE_AUTO }
					?.let { add("source_lang", it.uppercase()) }
			}
			.build()
		val response = client.newCall(
			Request.Builder()
				.url(baseUrl.toHttpUrl().newBuilder().addPathSegment("translate").build())
				.header("Authorization", "DeepL-Auth-Key $apiKey")
				.header("User-Agent", USER_AGENT)
				.post(form)
				.build(),
		).await()
		response.use {
			it.requireSuccessful()
			val root = parseJson(it.body.string()).jsonObject
			val translated = root["translations"]?.jsonArray
				?.firstOrNull()?.jsonObject
				?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
			if (translated.isBlank()) throw NovelTranslationException.InvalidResponse("DeepL returned an empty result")
			return translated
		}
	}

	private fun resolvedBaseUrl(provider: NovelAiProvider): String = settings.resolvedBaseUrl(provider)

	private fun systemPrompt(request: NovelTranslationRequest): String {
		val style = when (request.style) {
			NovelTranslationStyle.NATURAL -> "natural and fluent while preserving meaning, names, tone, and formatting"
			NovelTranslationStyle.LITERAL -> "literal and close to the source wording while remaining understandable"
			NovelTranslationStyle.NOVEL -> "polished for novel prose and dialogue while preserving meaning, characterization, names, honorifics, and paragraph breaks"
		}
		val source = request.sourceLanguage.takeUnless { it.isBlank() || it == NovelTranslationSettings.LANGUAGE_AUTO }
			?: "automatically detected source language"
		return "You are Miyorare's translation engine. Translate from $source to ${request.targetLanguage}. " +
			"Use a $style translation. Return only the translated text for <translate>, with no notes, headings, quotes, explanations, or markdown. " +
			"Do not translate text inside <context_before> or <context_after>; those blocks exist only to resolve references and tone."
	}

	private fun userPrompt(request: NovelTranslationRequest): String = buildString {
		if (request.contextAware && request.beforeContext.isNotBlank()) {
			append("<context_before>\n")
			append(request.beforeContext.take(MAX_CONTEXT_CHARS))
			append("\n</context_before>\n")
		}
		append("<translate>\n")
		append(request.text)
		append("\n</translate>")
		if (request.contextAware && request.afterContext.isNotBlank()) {
			append("\n<context_after>\n")
			append(request.afterContext.take(MAX_CONTEXT_CHARS))
			append("\n</context_after>")
		}
	}

	private fun extractTextContent(element: kotlinx.serialization.json.JsonElement): String = when (element) {
		is JsonPrimitive -> element.contentOrNull.orEmpty()
		is JsonArray -> element.mapNotNull { item ->
			(item as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
		}.joinToString("")
		else -> ""
	}

	companion object {
		private const val ANTHROPIC_VERSION = "2023-06-01"
		private const val DEEPL_PAID_BASE_URL = "https://api.deepl.com/v2"
		private const val MAX_CONTEXT_CHARS = 4000
	}
}

sealed class NovelTranslationException(message: String) : IOException(message) {
	class Configuration(message: String) : NovelTranslationException(message)
	class Authentication : NovelTranslationException("The translation API key was rejected")
	class RateLimited(val retryAfterSeconds: Long?) : NovelTranslationException(
		retryAfterSeconds?.let { "Translation provider rate limit reached. Retry after ${it}s" }
			?: "Translation provider rate limit reached",
	)
	class ProviderUnavailable(val statusCode: Int) : NovelTranslationException("Translation provider is unavailable (HTTP $statusCode)")
	class RequestRejected(val statusCode: Int) : NovelTranslationException("Translation request was rejected (HTTP $statusCode)")
	class InvalidResponse(message: String) : NovelTranslationException(message)
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val USER_AGENT = "Mozilla/5.0 (Android) Miyorare"
private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseJson(body: String) = try {
	JSON.parseToJsonElement(body)
} catch (error: Exception) {
	throw NovelTranslationException.InvalidResponse("Translation provider returned invalid JSON")
}

private fun OkHttpClient.translationClient(): OkHttpClient = newBuilder().apply {
	// The debug base client has a cURL interceptor that logs headers and request bodies. Translation
	// requests can contain BYOK credentials and private reading text, so never inherit that logger.
	interceptors().removeAll { it is CurlLoggingInterceptor }
	connectTimeout(20, TimeUnit.SECONDS)
	readTimeout(60, TimeUnit.SECONDS)
	writeTimeout(30, TimeUnit.SECONDS)
	callTimeout(75, TimeUnit.SECONDS)
}.build()

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
	continuation.invokeOnCancellation { cancel() }
	enqueue(object : Callback {
		override fun onFailure(call: Call, e: IOException) {
			if (continuation.isActive) continuation.resumeWithException(e)
		}

		override fun onResponse(call: Call, response: Response) {
			if (continuation.isActive) continuation.resume(response) else response.close()
		}
	})
}

private fun Response.requireSuccessful() {
	if (isSuccessful) return
	when (code) {
		401, 403 -> throw NovelTranslationException.Authentication()
		429 -> throw NovelTranslationException.RateLimited(headers["Retry-After"]?.toLongOrNull())
		in 500..599 -> throw NovelTranslationException.ProviderUnavailable(code)
		else -> throw NovelTranslationException.RequestRejected(code)
	}
}
