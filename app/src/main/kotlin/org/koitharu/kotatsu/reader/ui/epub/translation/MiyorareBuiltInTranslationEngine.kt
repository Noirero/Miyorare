package org.koitharu.kotatsu.reader.ui.epub.translation

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.IOException
import java.util.LinkedHashMap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Miyorare's on-device translation backend.
 *
 * Language packs are downloaded by ML Kit on first use and translation runs locally afterwards.
 * The runtime keeps a small LRU of source/target translators so repeated reading does not recreate
 * model sessions for every visible segment.
 */
class MiyorareBuiltInTranslationEngine(
	context: Context,
) : NovelTranslationEngine {

	private val runtime = BuiltInTranslationRuntime.forApplication(context.applicationContext)

	override suspend fun translate(request: NovelTranslationRequest): String = runtime.translate(request)
}

private class BuiltInTranslationRuntime private constructor(
	private val applicationContext: Context,
) {

	private data class Session(
		val translator: Translator,
		val mutex: Mutex = Mutex(),
	)

	private val sessionsMutex = Mutex()
	private val sessions = LinkedHashMap<String, Session>(MAX_CACHED_TRANSLATORS + 1, 0.75f, true)
	private val languageIdentifier by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
		LanguageIdentification.getClient(
			LanguageIdentificationOptions.Builder()
				.setConfidenceThreshold(LANGUAGE_CONFIDENCE_THRESHOLD)
				.build(),
		)
	}
	private val downloadConditions = DownloadConditions.Builder().build()

	suspend fun translate(request: NovelTranslationRequest): String {
		if (request.text.isBlank()) return request.text
		val target = supportedLanguage(request.targetLanguage, "target")
		val source = resolveSourceLanguage(request)
		if (source.equals(target, ignoreCase = true)) return request.text

		val session = session(source, target)
		return session.mutex.withLock {
			try {
				session.translator.downloadModelIfNeeded(downloadConditions).awaitTask()
				val translated = session.translator.translate(request.text).awaitTask()
				if (translated.isBlank() && request.text.isNotBlank()) {
					throw NovelTranslationException.InvalidResponse("Built-in translator returned an empty result")
				}
				translated
			} catch (error: NovelTranslationException) {
				throw error
			} catch (error: Exception) {
				throw IOException("Built-in translation failed", error)
			}
		}
	}

	private suspend fun resolveSourceLanguage(request: NovelTranslationRequest): String {
		val requested = request.sourceLanguage.trim()
		if (requested.isNotEmpty() && requested != NovelTranslationSettings.LANGUAGE_AUTO) {
			return supportedLanguage(requested, "source")
		}
		val detected = try {
			languageIdentifier.identifyLanguage(request.text).awaitTask()
		} catch (error: Exception) {
			throw IOException("Built-in language detection failed", error)
		}
		if (detected == UNDETERMINED_LANGUAGE) {
			throw NovelTranslationException.Configuration("Built-in translator could not detect the source language")
		}
		return supportedLanguage(detected, "detected source")
	}

	private fun supportedLanguage(languageTag: String, role: String): String {
		val normalized = languageTag.trim()
		if (normalized.isEmpty()) {
			throw NovelTranslationException.Configuration("Built-in $role language is required")
		}
		return TranslateLanguage.fromLanguageTag(normalized)
			?: throw NovelTranslationException.Configuration("Built-in translation does not support $role language: $languageTag")
	}

	private suspend fun session(sourceLanguage: String, targetLanguage: String): Session = sessionsMutex.withLock {
		val key = "$sourceLanguage>$targetLanguage"
		sessions[key]?.let { return@withLock it }
		val translator = Translation.getClient(
			TranslatorOptions.Builder()
				.setSourceLanguage(sourceLanguage)
				.setTargetLanguage(targetLanguage)
				.build(),
		)
		val created = Session(translator)
		sessions[key] = created
		while (sessions.size > MAX_CACHED_TRANSLATORS) {
			val eldest = sessions.entries.iterator().next()
			sessions.remove(eldest.key)
			eldest.value.translator.close()
		}
		created
	}

	companion object {
		private const val LANGUAGE_CONFIDENCE_THRESHOLD = 0.4f
		private const val UNDETERMINED_LANGUAGE = "und"
		private const val MAX_CACHED_TRANSLATORS = 3
		@Volatile
		private var instance: BuiltInTranslationRuntime? = null

		fun forApplication(context: Context): BuiltInTranslationRuntime = instance ?: synchronized(this) {
			instance ?: BuiltInTranslationRuntime(context).also { instance = it }
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
