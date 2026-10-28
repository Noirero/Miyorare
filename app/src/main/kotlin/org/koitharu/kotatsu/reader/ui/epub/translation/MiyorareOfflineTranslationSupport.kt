package org.koitharu.kotatsu.reader.ui.epub.translation

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal sealed interface MiyorareOfflineModelState {
	data object Ready : MiyorareOfflineModelState
	data object SourceLanguageRequired : MiyorareOfflineModelState
	data class Missing(val languageCodes: List<String>) : MiyorareOfflineModelState
	data class Unsupported(val languageCode: String) : MiyorareOfflineModelState
}

/**
 * Explicitly managed ML Kit fallback for Built-in novel translation.
 *
 * The translation runtime may ship with Miyorare, but language models never do. Downloads happen
 * only through [downloadModels], which is called from an explicit user action in the Translation UI.
 * Translation itself never invokes an implicit model download.
 */
internal object MiyorareOfflineTranslationSupport {

	private const val PREFS_NAME = "novel_translation_offline_models"
	private const val KEY_EXPLICIT_MODELS = "explicit_models"

	@Volatile
	private var applicationContext: Context? = null

	private val modelManager: RemoteModelManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
		RemoteModelManager.getInstance()
	}

	fun attachContext(context: Context) {
		applicationContext = context.applicationContext
	}

	/**
	 * Synchronous routing hint used only to keep semantic cache/provider identity deterministic.
	 * The actual ML Kit model inventory is re-checked before translation.
	 */
	fun shouldUseDownloadedModel(context: Context, sourceLanguage: String, targetLanguage: String): Boolean {
		attachContext(context)
		val required = requiredModelLanguages(sourceLanguage, targetLanguage) ?: return false
		if (required.isEmpty()) return false
		val explicit = explicitModels(context)
		return required.all(explicit::contains)
	}

	suspend fun modelState(sourceLanguage: String, targetLanguage: String): MiyorareOfflineModelState {
		val context = requireContext()
		val plan = modelPlan(sourceLanguage, targetLanguage) ?: return MiyorareOfflineModelState.SourceLanguageRequired
		plan.unsupportedLanguage?.let { return MiyorareOfflineModelState.Unsupported(it) }
		if (plan.requiredModels.isEmpty()) return MiyorareOfflineModelState.Ready
		val downloaded = downloadedLanguages()
		pruneMissingExplicitModels(context, plan.requiredModels, downloaded)
		val explicit = explicitModels(context)
		val missing = plan.requiredModels.filterNot { language -> language in downloaded && language in explicit }
		return if (missing.isEmpty()) MiyorareOfflineModelState.Ready else MiyorareOfflineModelState.Missing(missing)
	}

	suspend fun downloadModels(sourceLanguage: String, targetLanguage: String) {
		val context = requireContext()
		val plan = requireDownloadablePlan(sourceLanguage, targetLanguage)
		val downloaded = downloadedLanguages().toMutableSet()
		val conditions = DownloadConditions.Builder().build()
		for (language in plan.requiredModels.filterNot(downloaded::contains)) {
			modelManager.download(TranslateRemoteModel.Builder(language).build(), conditions).awaitTask()
			downloaded += language
		}
		markExplicitModels(context, plan.requiredModels.filter(downloaded::contains))
	}

	suspend fun deleteModels(sourceLanguage: String, targetLanguage: String) {
		val context = requireContext()
		val plan = requireDownloadablePlan(sourceLanguage, targetLanguage)
		val downloaded = downloadedLanguages().toMutableSet()
		for (language in plan.requiredModels.filter(downloaded::contains)) {
			modelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(language).build()).awaitTask()
			downloaded -= language
		}
		removeExplicitModels(context, plan.requiredModels)
	}

	suspend fun translateInstalled(context: Context, sourceLanguage: String, targetLanguage: String, text: String): String {
		attachContext(context)
		if (text.isBlank()) return text
		val plan = modelPlan(sourceLanguage, targetLanguage)
			?: throw NovelTranslationException.Configuration("Choose a source language before using the offline translation model")
		plan.unsupportedLanguage?.let {
			throw NovelTranslationException.Configuration("Offline translation does not support language $it")
		}
		when (val state = modelState(sourceLanguage, targetLanguage)) {
			MiyorareOfflineModelState.Ready -> Unit
			MiyorareOfflineModelState.SourceLanguageRequired -> throw NovelTranslationException.Configuration(
				"Choose a source language before using the offline translation model",
			)
			is MiyorareOfflineModelState.Missing -> throw NovelTranslationException.Configuration(
				"Offline language model is not downloaded. Open Translation and download the offline language model first.",
			)
			is MiyorareOfflineModelState.Unsupported -> throw NovelTranslationException.Configuration(
				"Offline translation does not support language ${state.languageCode}",
			)
		}
		if (!shouldUseDownloadedModel(context, sourceLanguage, targetLanguage)) {
			throw NovelTranslationException.Configuration(
				"Offline language model is not enabled for Miyorare. Download it from Translation settings first.",
			)
		}
		val options = TranslatorOptions.Builder()
			.setSourceLanguage(plan.sourceLanguage)
			.setTargetLanguage(plan.targetLanguage)
			.build()
		val translator = Translation.getClient(options)
		return try {
			translator.translate(text).awaitTask()
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			throw NovelTranslationException.InvalidResponse(
				"Offline translation failed: ${error.message.orEmpty()}",
			)
		} finally {
			translator.close()
		}
	}

	internal fun requiredModelLanguages(sourceLanguage: String, targetLanguage: String): List<String>? {
		val plan = modelPlan(sourceLanguage, targetLanguage) ?: return null
		return if (plan.unsupportedLanguage == null) plan.requiredModels else emptyList()
	}

	private fun requireContext(): Context = checkNotNull(applicationContext) {
		"Offline translation support must be initialized from NovelTranslationSettings"
	}

	private suspend fun downloadedLanguages(): Set<String> =
		modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
			.awaitTask()
			.mapTo(linkedSetOf()) { it.language }

	private fun explicitModels(context: Context): Set<String> =
		context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			.getStringSet(KEY_EXPLICIT_MODELS, emptySet())
			.orEmpty()

	private fun markExplicitModels(context: Context, languages: Collection<String>) {
		if (languages.isEmpty()) return
		val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
		val next = explicitModels(context).toMutableSet().apply { addAll(languages) }
		prefs.edit().putStringSet(KEY_EXPLICIT_MODELS, next).apply()
	}

	private fun removeExplicitModels(context: Context, languages: Collection<String>) {
		if (languages.isEmpty()) return
		val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
		val next = explicitModels(context).toMutableSet().apply { removeAll(languages.toSet()) }
		prefs.edit().putStringSet(KEY_EXPLICIT_MODELS, next).apply()
	}

	private fun pruneMissingExplicitModels(context: Context, checkedLanguages: Collection<String>, downloaded: Set<String>) {
		removeExplicitModels(context, checkedLanguages.filterNot(downloaded::contains))
	}

	private fun requireDownloadablePlan(sourceLanguage: String, targetLanguage: String): OfflineModelPlan {
		val plan = modelPlan(sourceLanguage, targetLanguage)
			?: throw NovelTranslationException.Configuration("Choose a source language before downloading offline models")
		plan.unsupportedLanguage?.let {
			throw NovelTranslationException.Configuration("Offline translation does not support language $it")
		}
		return plan
	}

	private fun modelPlan(sourceLanguage: String, targetLanguage: String): OfflineModelPlan? {
		val rawSource = sourceLanguage.trim()
		if (rawSource.isEmpty() || rawSource == NovelTranslationSettings.LANGUAGE_AUTO) return null
		val rawTarget = targetLanguage.trim()
		val source = TranslateLanguage.fromLanguageTag(rawSource)
			?: return OfflineModelPlan(rawSource, rawTarget, emptyList(), rawSource)
		val target = TranslateLanguage.fromLanguageTag(rawTarget)
			?: return OfflineModelPlan(source, rawTarget, emptyList(), rawTarget)
		val required = buildList {
			if (source != TranslateLanguage.ENGLISH) add(source)
			if (target != TranslateLanguage.ENGLISH && target != source) add(target)
		}
		return OfflineModelPlan(source, target, required, null)
	}

	private data class OfflineModelPlan(
		val sourceLanguage: String,
		val targetLanguage: String,
		val requiredModels: List<String>,
		val unsupportedLanguage: String?,
	)
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