package org.koitharu.kotatsu.reader.ui.epub.translation

import android.content.Context

internal data class NovelTranslationPresentationMigration(
	val bilingual: Boolean,
	val version: Int,
	val shouldPersist: Boolean,
)

/**
 * Presentation policy is deliberately independent from translation/cache identity.
 *
 * Version 0 shipped Bilingual as the implicit default and also persisted that implicit value on the
 * first translation. That means an old stored `true` cannot reliably be distinguished from an
 * explicit user choice. Version 1 therefore performs one compatibility reset to Translated Only;
 * subsequent explicit Bilingual choices are preserved normally.
 */
internal object NovelTranslationPresentationPolicy {
	const val CURRENT_VERSION = 1
	val defaultDisplayMode: NovelTranslationDisplayMode = NovelTranslationDisplayMode.TRANSLATED_ONLY
	val defaultBilingual: Boolean = false

	fun migrate(storedVersion: Int, storedBilingual: Boolean?): NovelTranslationPresentationMigration {
		if (storedVersion >= CURRENT_VERSION) {
			return NovelTranslationPresentationMigration(
				bilingual = storedBilingual ?: defaultBilingual,
				version = storedVersion,
				shouldPersist = false,
			)
		}
		return NovelTranslationPresentationMigration(
			bilingual = defaultBilingual,
			version = CURRENT_VERSION,
			shouldPersist = true,
		)
	}
}

/**
 * Lightweight Novel translation preferences.
 *
 * These preferences intentionally live outside AppSettings: translation providers are optional and
 * must not add work to application startup. Secrets are never stored here; see
 * [NovelTranslationSecrets].
 */
class NovelTranslationSettings(context: Context) {

	internal val applicationContext: Context = context.applicationContext
	private val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	init {
		migratePresentationPolicyIfNeeded()
	}

	var engine: NovelTranslationEngineKind
		get() = NovelTranslationEngineKind.fromId(prefs.getString(KEY_ENGINE, null))
		set(value) = prefs.edit().putString(KEY_ENGINE, value.id).apply()

	var provider: NovelAiProvider
		get() = NovelAiProvider.fromId(prefs.getString(KEY_PROVIDER, null))
		set(value) {
			val previous = NovelAiProvider.fromId(prefs.getString(KEY_PROVIDER, null))
			prefs.edit().apply {
				if (value == NovelAiProvider.BUILT_IN && previous != NovelAiProvider.BUILT_IN) {
					putString(KEY_LAST_REMOTE_AI_PROVIDER, previous.id)
				} else if (value != NovelAiProvider.BUILT_IN) {
					putString(KEY_LAST_REMOTE_AI_PROVIDER, value.id)
				}
				putString(KEY_PROVIDER, value.id)
			}.apply()
		}

	val lastRemoteAiProvider: NovelAiProvider
		get() = NovelAiProvider.fromId(prefs.getString(KEY_LAST_REMOTE_AI_PROVIDER, null))
			.takeUnless { it == NovelAiProvider.BUILT_IN }
			?: NovelAiProvider.GEMINI

	var sourceLanguage: String
		get() = prefs.getString(KEY_SOURCE_LANGUAGE, LANGUAGE_AUTO) ?: LANGUAGE_AUTO
		set(value) = prefs.edit().putString(KEY_SOURCE_LANGUAGE, value.ifBlank { LANGUAGE_AUTO }).apply()

	var targetLanguage: String
		get() = prefs.getString(KEY_TARGET_LANGUAGE, DEFAULT_TARGET_LANGUAGE) ?: DEFAULT_TARGET_LANGUAGE
		set(value) = prefs.edit().putString(KEY_TARGET_LANGUAGE, value.ifBlank { DEFAULT_TARGET_LANGUAGE }).apply()

	/** Presentation preference only; it deliberately does not participate in semantic cache identity. */
	var bilingual: Boolean
		get() = prefs.getBoolean(KEY_BILINGUAL, NovelTranslationPresentationPolicy.defaultBilingual)
		set(value) = prefs.edit()
			.putBoolean(KEY_BILINGUAL, value)
			.putInt(KEY_PRESENTATION_POLICY_VERSION, NovelTranslationPresentationPolicy.CURRENT_VERSION)
			.apply()

	fun isAutoTranslateEnabled(mangaId: Long): Boolean =
		mangaId > 0L && prefs.getBoolean(autoTranslateKey(mangaId), false)

	/**
	 * Auto-translate is opt-in per novel and bound to the selected provider boundary. Changing
	 * Online -> AI or switching AI providers therefore requires an explicit opt-in again.
	 */
	fun isAutoTranslateActive(mangaId: Long): Boolean =
		isAutoTranslateEnabled(mangaId) &&
			prefs.getString(autoTranslateBindingKey(mangaId), null) == currentAutoProviderBinding()

	fun setAutoTranslateEnabled(mangaId: Long, enabled: Boolean) {
		if (mangaId <= 0L) return
		prefs.edit().apply {
			putBoolean(autoTranslateKey(mangaId), enabled)
			if (enabled) putString(autoTranslateBindingKey(mangaId), currentAutoProviderBinding())
			else remove(autoTranslateBindingKey(mangaId))
		}.apply()
	}

	fun currentSelection(): NovelTranslationSelection {
		val selectedEngine = engine
		val builtIn = selectedEngine == NovelTranslationEngineKind.AI && provider == NovelAiProvider.BUILT_IN
		return NovelTranslationSelection(
			engine = selectedEngine,
			sourceLanguage = sourceLanguage,
			targetLanguage = targetLanguage,
			style = if (builtIn) NovelTranslationStyle.NATURAL else style,
			contextAware = selectedEngine == NovelTranslationEngineKind.AI && !builtIn && contextAware,
			displayMode = if (bilingual) NovelTranslationDisplayMode.BILINGUAL else NovelTranslationPresentationPolicy.defaultDisplayMode,
		)
	}

	/**
	 * Models are provider-scoped so changing from one provider to another never accidentally sends a
	 * request with the previous provider's model name. Built-in exposes a runtime identity that also
	 * separates explicitly downloaded offline-model results from Android/OEM system results.
	 */
	var model: String
		get() = model(provider)
		set(value) = setModel(provider, value)

	fun model(provider: NovelAiProvider): String = if (provider == NovelAiProvider.BUILT_IN) {
		if (MiyorareOfflineTranslationSupport.shouldUseDownloadedModel(applicationContext, sourceLanguage, targetLanguage)) {
			BUILT_IN_OFFLINE_RUNTIME_ID
		} else {
			BUILT_IN_RUNTIME_ID
		}
	} else {
		prefs.getString(providerKey(KEY_MODEL_PREFIX, provider), "").orEmpty()
	}

	fun setModel(provider: NovelAiProvider, value: String) {
		if (provider == NovelAiProvider.BUILT_IN) return
		prefs.edit().putString(providerKey(KEY_MODEL_PREFIX, provider), value.trim()).apply()
	}

	/**
	 * Optional provider-scoped override for accounts/regions that use a different endpoint.
	 * Empty means [NovelAiProvider.defaultBaseUrl].
	 */
	var baseUrlOverride: String
		get() = baseUrlOverride(provider)
		set(value) = setBaseUrlOverride(provider, value)

	fun baseUrlOverride(provider: NovelAiProvider): String =
		prefs.getString(providerKey(KEY_BASE_URL_PREFIX, provider), "").orEmpty()

	fun setBaseUrlOverride(provider: NovelAiProvider, value: String) {
		if (provider == NovelAiProvider.BUILT_IN) return
		prefs.edit().putString(providerKey(KEY_BASE_URL_PREFIX, provider), value.trim()).apply()
	}

	var style: NovelTranslationStyle
		get() = NovelTranslationStyle.fromId(prefs.getString(KEY_STYLE, null))
		set(value) = prefs.edit().putString(KEY_STYLE, value.id).apply()

	var contextAware: Boolean
		get() = prefs.getBoolean(KEY_CONTEXT_AWARE, false)
		set(value) = prefs.edit().putBoolean(KEY_CONTEXT_AWARE, value).apply()

	/** DeepL free and paid accounts use different hosts. */
	var deeplFreeApi: Boolean
		get() = prefs.getBoolean(KEY_DEEPL_FREE, true)
		set(value) = prefs.edit().putBoolean(KEY_DEEPL_FREE, value).apply()

	fun resolvedBaseUrl(provider: NovelAiProvider = this.provider): String {
		val override = baseUrlOverride(provider).trim().trimEnd('/')
		return if (override.isNotEmpty()) override else provider.defaultBaseUrl.trimEnd('/')
	}

	private fun migratePresentationPolicyIfNeeded() {
		val migration = NovelTranslationPresentationPolicy.migrate(
			storedVersion = prefs.getInt(KEY_PRESENTATION_POLICY_VERSION, 0),
			storedBilingual = if (prefs.contains(KEY_BILINGUAL)) {
				prefs.getBoolean(KEY_BILINGUAL, NovelTranslationPresentationPolicy.defaultBilingual)
			} else {
				null
			},
		)
		if (!migration.shouldPersist) return
		prefs.edit()
			.putBoolean(KEY_BILINGUAL, migration.bilingual)
			.putInt(KEY_PRESENTATION_POLICY_VERSION, migration.version)
			.apply()
	}

	private fun providerKey(prefix: String, provider: NovelAiProvider) = "$prefix${provider.id}"
	private fun autoTranslateKey(mangaId: Long) = "$KEY_AUTO_TRANSLATE_PREFIX$mangaId"
	private fun autoTranslateBindingKey(mangaId: Long) = "$KEY_AUTO_TRANSLATE_BINDING_PREFIX$mangaId"
	private fun currentAutoProviderBinding() = NovelAutoTranslationPolicy.providerBinding(engine, provider)

	companion object {
		const val LANGUAGE_AUTO = "auto"
		const val DEFAULT_TARGET_LANGUAGE = "id"
		internal const val BUILT_IN_RUNTIME_ID = "android-system-translation-api31-v1"
		internal const val BUILT_IN_OFFLINE_RUNTIME_ID = "google-mlkit-translate-17.0.3-explicit-v1"

		private const val PREFS_NAME = "novel_translation"
		private const val KEY_ENGINE = "engine"
		private const val KEY_PROVIDER = "provider"
		private const val KEY_LAST_REMOTE_AI_PROVIDER = "last_remote_ai_provider"
		private const val KEY_SOURCE_LANGUAGE = "source_language"
		private const val KEY_TARGET_LANGUAGE = "target_language"
		private const val KEY_BILINGUAL = "bilingual"
		private const val KEY_PRESENTATION_POLICY_VERSION = "presentation_policy_version"
		private const val KEY_AUTO_TRANSLATE_PREFIX = "auto_translate_"
		private const val KEY_AUTO_TRANSLATE_BINDING_PREFIX = "auto_translate_binding_"
		private const val KEY_MODEL_PREFIX = "model_"
		private const val KEY_BASE_URL_PREFIX = "base_url_"
		private const val KEY_STYLE = "style"
		private const val KEY_CONTEXT_AWARE = "context_aware"
		private const val KEY_DEEPL_FREE = "deepl_free_api"
	}
}

enum class NovelTranslationEngineKind(val id: String) {
	ONLINE("online"),
	AI("ai");

	companion object {
		fun fromId(value: String?): NovelTranslationEngineKind = entries.firstOrNull { it.id == value } ?: ONLINE
	}
}

enum class NovelTranslationStyle(val id: String) {
	NATURAL("natural"),
	LITERAL("literal"),
	NOVEL("novel");

	companion object {
		fun fromId(value: String?): NovelTranslationStyle = entries.firstOrNull { it.id == value } ?: NATURAL
	}
}

enum class NovelAiProtocol {
	ON_DEVICE,
	OPENAI_COMPATIBLE,
	ANTHROPIC,
	DEEPL,
}

/**
 * Provider registry. Model names are intentionally not hard-coded here because providers rotate
 * models frequently. The UI should discover models when possible and always allow manual entry.
 */
enum class NovelAiProvider(
	val id: String,
	val displayName: String,
	val protocol: NovelAiProtocol,
	val defaultBaseUrl: String,
	val supportsModelDiscovery: Boolean,
) {
	BUILT_IN(
		id = "built_in",
		displayName = "Built-in",
		protocol = NovelAiProtocol.ON_DEVICE,
		defaultBaseUrl = "on-device:android-system-translation",
		supportsModelDiscovery = false,
	),
	OPENAI(
		id = "openai",
		displayName = "OpenAI",
		protocol = NovelAiProtocol.OPENAI_COMPATIBLE,
		defaultBaseUrl = "https://api.openai.com/v1",
		supportsModelDiscovery = true,
	),
	GEMINI(
		id = "gemini",
		displayName = "Gemini",
		protocol = NovelAiProtocol.OPENAI_COMPATIBLE,
		defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
		supportsModelDiscovery = true,
	),
	CLAUDE(
		id = "claude",
		displayName = "Claude",
		protocol = NovelAiProtocol.ANTHROPIC,
		defaultBaseUrl = "https://api.anthropic.com/v1",
		supportsModelDiscovery = false,
	),
	DEEPL(
		id = "deepl",
		displayName = "DeepL",
		protocol = NovelAiProtocol.DEEPL,
		defaultBaseUrl = "https://api-free.deepl.com/v2",
		supportsModelDiscovery = false,
	),
	XAI(
		id = "xai",
		displayName = "Grok / xAI",
		protocol = NovelAiProtocol.OPENAI_COMPATIBLE,
		defaultBaseUrl = "https://api.x.ai/v1",
		supportsModelDiscovery = true,
	),
	QWEN(
		id = "qwen",
		displayName = "Qwen",
		protocol = NovelAiProtocol.OPENAI_COMPATIBLE,
		// International shared endpoint. Workspace-specific/other-region accounts can override it.
		defaultBaseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
		supportsModelDiscovery = true,
	),
	GLM(
		id = "glm",
		displayName = "GLM",
		protocol = NovelAiProtocol.OPENAI_COMPATIBLE,
		defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
		supportsModelDiscovery = true,
	),
	KIMI(
		id = "kimi",
		displayName = "Kimi",
		protocol = NovelAiProtocol.OPENAI_COMPATIBLE,
		defaultBaseUrl = "https://api.moonshot.cn/v1",
		supportsModelDiscovery = true,
	),
	CUSTOM(
		id = "custom",
		displayName = "Custom / OpenAI-compatible",
		protocol = NovelAiProtocol.OPENAI_COMPATIBLE,
		defaultBaseUrl = "",
		supportsModelDiscovery = true,
	);

	companion object {
		fun fromId(value: String?): NovelAiProvider = entries.firstOrNull { it.id == value } ?: GEMINI
	}
}