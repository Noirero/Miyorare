package org.koitharu.kotatsu.reader.ui.epub.translation

import android.app.PendingIntent
import android.content.Context
import android.icu.util.ULocale
import android.os.Build
import android.os.CancellationSignal
import android.view.translation.TranslationCapability
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationResponse
import android.view.translation.TranslationResponseValue
import android.view.translation.TranslationSpec
import android.view.translation.Translator
import androidx.annotation.RequiresApi
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import java.util.LinkedHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Miyorare's Built-in translation backend.
 *
 * Translation itself is provided by Android's system/OEM TranslationService. Miyorare does not
 * bundle a translation model or translation runtime. Source-language auto detection uses the small
 * Google Play services Language ID module and is skipped when the source language is explicit.
 */
class MiyorareBuiltInTranslationEngine(
	context: Context,
) : NovelTranslationEngine {

	private val applicationContext = context.applicationContext
	private val languageIdentifier by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
		LanguageIdentification.getClient(
			LanguageIdentificationOptions.Builder()
				.setConfidenceThreshold(LANGUAGE_CONFIDENCE_THRESHOLD)
				.build(),
		)
	}

	override suspend fun translate(request: NovelTranslationRequest): String {
		if (request.text.isBlank()) return request.text
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
			throw NovelTranslationException.Configuration(
				"Built-in translation requires Android 12 or newer",
			)
		}
		val target = normalizeLanguage(request.targetLanguage, "target")
		val source = resolveSourceLanguage(request)
		if (sameLanguage(source, target)) return request.text
		return AndroidSystemTranslationRuntime.forApplication(applicationContext)
			.translate(source, target, request.text)
	}

	private suspend fun resolveSourceLanguage(request: NovelTranslationRequest): String {
		val requested = request.sourceLanguage.trim()
		if (requested.isNotEmpty() && requested != NovelTranslationSettings.LANGUAGE_AUTO) {
			return normalizeLanguage(requested, "source")
		}
		val detected = try {
			languageIdentifier.identifyLanguage(request.text).awaitTask()
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			throw NovelTranslationException.Configuration(
				"Built-in translation could not detect the source language",
			)
		}
		if (detected == UNDETERMINED_LANGUAGE) {
			throw NovelTranslationException.Configuration(
				"Built-in translation could not detect the source language",
			)
		}
		return normalizeLanguage(detected, "detected source")
	}

	private fun normalizeLanguage(languageTag: String, role: String): String {
		val normalized = ULocale.forLanguageTag(languageTag.trim()).toLanguageTag()
		if (normalized.isBlank() || normalized == UNDETERMINED_LANGUAGE) {
			throw NovelTranslationException.Configuration("Built-in $role language is required")
		}
		return normalized
	}

	private fun sameLanguage(first: String, second: String): Boolean {
		val a = ULocale.forLanguageTag(first)
		val b = ULocale.forLanguageTag(second)
		return a.toLanguageTag().equals(b.toLanguageTag(), ignoreCase = true) ||
			(a.language.isNotBlank() && a.language.equals(b.language, ignoreCase = true))
	}

	private companion object {
		const val LANGUAGE_CONFIDENCE_THRESHOLD = 0.4f
		const val UNDETERMINED_LANGUAGE = "und"
	}
}

/** Small UI bridge for the system language-pack/settings surface. */
internal object MiyorareBuiltInTranslationSupport {
	fun settingsPendingIntent(context: Context): PendingIntent? {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
		return settingsPendingIntentApi31(context)
	}

	@RequiresApi(Build.VERSION_CODES.S)
	private fun settingsPendingIntentApi31(context: Context): PendingIntent? =
		context.getSystemService(TranslationManager::class.java)
			?.getOnDeviceTranslationSettingsActivityIntent()
}

@RequiresApi(Build.VERSION_CODES.S)
private class AndroidSystemTranslationRuntime private constructor(
	context: Context,
) {

	private data class Session(
		val translator: Translator,
		val mutex: Mutex = Mutex(),
		var users: Int = 0,
	)

	private val manager: TranslationManager? = context.getSystemService(TranslationManager::class.java)
	private val callbackExecutor: Executor = Executors.newCachedThreadPool { runnable ->
		Thread(runnable, "Miyorare-SystemTranslate").apply { isDaemon = true }
	}
	private val sessionsMutex = Mutex()
	private val sessions = LinkedHashMap<String, Session>(MAX_CACHED_TRANSLATORS + 1, 0.75f, true)

	suspend fun translate(sourceLanguage: String, targetLanguage: String, text: String): String {
		val translationManager = manager ?: throw unavailable()
		val capability = findCapability(translationManager, sourceLanguage, targetLanguage)
		when (capability.state) {
			TranslationCapability.STATE_ON_DEVICE -> Unit
			TranslationCapability.STATE_AVAILABLE_TO_DOWNLOAD -> throw NovelTranslationException.Configuration(
				"Built-in language pack for $sourceLanguage → $targetLanguage is not installed. " +
					"Install it in Android system translation settings, then try again.",
			)
			TranslationCapability.STATE_DOWNLOADING -> throw NovelTranslationException.Configuration(
				"Built-in language pack for $sourceLanguage → $targetLanguage is still downloading. " +
					"Try again after the system download finishes.",
			)
			else -> throw NovelTranslationException.Configuration(
				"Built-in translation does not support $sourceLanguage → $targetLanguage on this device",
			)
		}

		val session = acquireSession(translationManager, capability)
		return try {
			session.mutex.withLock {
				translateWithSession(session.translator, text)
			}
		} finally {
			releaseSession(session)
		}
	}

	private suspend fun findCapability(
		translationManager: TranslationManager,
		sourceLanguage: String,
		targetLanguage: String,
	): TranslationCapability = withContext(Dispatchers.Default) {
		val capabilities = try {
			translationManager.getOnDeviceTranslationCapabilities(
				TranslationSpec.DATA_FORMAT_TEXT,
				TranslationSpec.DATA_FORMAT_TEXT,
			)
		} catch (_: RuntimeException) {
			throw unavailable()
		}
		capabilities
			.filter { capability ->
				matchesLanguage(capability.sourceSpec.locale, sourceLanguage) &&
					matchesLanguage(capability.targetSpec.locale, targetLanguage)
			}
			.maxByOrNull(::capabilityPreference)
			?: throw NovelTranslationException.Configuration(
				"Built-in translation does not support $sourceLanguage → $targetLanguage on this device",
			)
	}

	private suspend fun acquireSession(
		translationManager: TranslationManager,
		capability: TranslationCapability,
	): Session {
		val key = sessionKey(capability)
		sessionsMutex.withLock {
			sessions[key]?.takeUnless { it.translator.isDestroyed }?.let { session ->
				session.users += 1
				return session
			}
		}

		val translator = createTranslator(translationManager, capability)
		return sessionsMutex.withLock {
			val existing = sessions[key]?.takeUnless { it.translator.isDestroyed }
			if (existing != null) {
				translator.destroy()
				existing.users += 1
				existing
			} else {
				Session(translator = translator, users = 1).also { session ->
					sessions[key] = session
					trimIdleSessions()
				}
			}
		}
	}

	private suspend fun createTranslator(
		translationManager: TranslationManager,
		capability: TranslationCapability,
	): Translator {
		val supported = capability.supportedTranslationFlags
		val flags = if (supported and TranslationContext.FLAG_LOW_LATENCY != 0) {
			TranslationContext.FLAG_LOW_LATENCY
		} else {
			0
		}
		val translationContext = TranslationContext.Builder(
			capability.sourceSpec,
			capability.targetSpec,
		).setTranslationFlags(flags).build()

		return suspendCancellableCoroutine { continuation ->
			try {
				translationManager.createOnDeviceTranslator(
					translationContext,
					callbackExecutor,
				) { translator ->
					if (!continuation.isActive) {
						translator?.destroy()
						return@createOnDeviceTranslator
					}
					if (translator == null) {
						continuation.resumeWithException(unavailable())
					} else {
						continuation.resume(translator)
					}
				}
			} catch (error: RuntimeException) {
				if (continuation.isActive) continuation.resumeWithException(unavailable(error))
			}
		}
	}

	private suspend fun translateWithSession(translator: Translator, text: String): String {
		if (translator.isDestroyed) throw unavailable()
		val request = TranslationRequest.Builder()
			.setTranslationRequestValues(mutableListOf(TranslationRequestValue.forText(text)))
			.build()
		val cancellationSignal = CancellationSignal()

		return suspendCancellableCoroutine { continuation ->
			continuation.invokeOnCancellation { cancellationSignal.cancel() }
			try {
				translator.translate(request, cancellationSignal, callbackExecutor) callback@{ response ->
					if (!response.isFinalResponse || !continuation.isActive) return@callback
					when (response.translationStatus) {
						TranslationResponse.TRANSLATION_STATUS_SUCCESS -> {
							val value = response.translationResponseValues[0]
							if (value == null || value.statusCode != TranslationResponseValue.STATUS_SUCCESS) {
								continuation.resumeWithException(
									NovelTranslationException.InvalidResponse(
										"Android system translation returned no translated text",
									),
								)
								return@callback
							}
							val translated = value.text?.toString().orEmpty()
							if (translated.isBlank() && text.isNotBlank()) {
								continuation.resumeWithException(
									NovelTranslationException.InvalidResponse(
										"Android system translation returned an empty result",
									),
								)
							} else {
								continuation.resume(translated)
							}
						}
						TranslationResponse.TRANSLATION_STATUS_CONTEXT_UNSUPPORTED -> continuation.resumeWithException(
							NovelTranslationException.Configuration(
								"Android system translation no longer supports this language pair",
							),
						)
						else -> continuation.resumeWithException(
							NovelTranslationException.InvalidResponse(
								"Android system translation failed",
							),
						)
					}
				}
			} catch (error: RuntimeException) {
				if (continuation.isActive) {
					continuation.resumeWithException(
						NovelTranslationException.InvalidResponse(
							"Android system translation failed: ${error.message.orEmpty()}",
						),
					)
				}
			}
		}
	}

	private suspend fun releaseSession(session: Session) = sessionsMutex.withLock {
		session.users = (session.users - 1).coerceAtLeast(0)
		trimIdleSessions()
	}

	private fun trimIdleSessions() {
		while (sessions.size > MAX_CACHED_TRANSLATORS) {
			val idle = sessions.entries.firstOrNull { it.value.users == 0 } ?: return
			sessions.remove(idle.key)
			idle.value.translator.destroy()
		}
	}

	private fun sessionKey(capability: TranslationCapability): String =
		"${capability.sourceSpec.locale.toLanguageTag()}>${capability.targetSpec.locale.toLanguageTag()}"

	private fun capabilityPreference(capability: TranslationCapability): Int = when (capability.state) {
		TranslationCapability.STATE_ON_DEVICE -> 4
		TranslationCapability.STATE_DOWNLOADING -> 3
		TranslationCapability.STATE_AVAILABLE_TO_DOWNLOAD -> 2
		else -> 1
	}

	private fun matchesLanguage(locale: ULocale, languageTag: String): Boolean {
		val requested = ULocale.forLanguageTag(languageTag)
		if (locale.toLanguageTag().equals(requested.toLanguageTag(), ignoreCase = true)) return true
		if (!locale.language.equals(requested.language, ignoreCase = true)) return false
		return requested.script.isBlank() || locale.script.equals(requested.script, ignoreCase = true)
	}

	private fun unavailable(cause: Throwable? = null): NovelTranslationException.Configuration {
		val suffix = cause?.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
		return NovelTranslationException.Configuration(
			"Built-in translation is unavailable on this device$suffix",
		)
	}

	companion object {
		private const val MAX_CACHED_TRANSLATORS = 3
		@Volatile
		private var instance: AndroidSystemTranslationRuntime? = null

		fun forApplication(context: Context): AndroidSystemTranslationRuntime = instance ?: synchronized(this) {
			instance ?: AndroidSystemTranslationRuntime(context.applicationContext).also { instance = it }
		}
	}
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
	addOnSuccessListener { result ->
		if (continuation.isActive) continuation.resume(result)
	}
	addOnFailureListener { error ->
		if (continuation.isActive) continuation.resumeWithException(error)
	}
	addOnCanceledListener {
		if (continuation.isActive) continuation.cancel()
	}
}
