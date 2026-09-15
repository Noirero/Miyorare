package org.koitharu.kotatsu.reader.ui.epub.translation

import android.content.Context

/**
 * Lightweight Novel translation preferences.
 *
 * These preferences intentionally live outside AppSettings: translation providers are optional and
 * must not add work to application startup. Secrets are never stored here; see
 * [NovelTranslationSecrets].
 */
class NovelTranslationSettings(context: Context) {

	private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	var engine: NovelTranslationEngineKind
		get() = NovelTranslationEngineKind.fromId(prefs.getString(KEY_ENGINE, null))
		set(value) = prefs.edit().putString(KEY_ENGINE, value.id).apply()

	var provider: NovelAiProvider
		get() = NovelAiProvider.fromId(prefs.getString(KEY_PROVIDER, null))
		set(value) = prefs.edit().putString(KEY_PROVIDER, value.id).apply()

	var sourceLanguage: String
		get() = prefs.getString(KEY_SOURCE_LANGUAGE, LANGUAGE_AUTO) ?: LANGUAGE_AUTO
		set(value) = prefs.edit().putString(KEY_SOURCE_LANGUAGE, value.ifBlank { LANGUAGE_AUTO }).apply()

	var targetLanguage: String
		get() = prefs.getString(KEY_TARGET_LANGUAGE, DEFAULT_TARGET_LANGUAGE) ?: DEFAULT_TARGET_LANGUAGE
		set(value) = prefs.edit().putString(KEY_TARGET_LANGUAGE, value.ifBlank { DEFAULT_TARGET_LANGUAGE }).apply()

	var model: String
		get() = prefs.getString(KEY_MODEL, "").orEmpty()
		set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

	/**
	 * Optional override for providers whose account/region uses a different endpoint.
	 * Empty means [NovelAiProvider.defaultBaseUrl].
	 */
	var baseUrlOverride: String
		get() = prefs.getString(KEY_BASE_URL, "").orEmpty()
		set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()

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
		val override = baseUrlOverride.trim().trimEnd('/')
		return if (override.isNotEmpty()) override else provider.defaultBaseUrl.trimEnd('/')
	}

	companion object {
		const val LANGUAGE_AUTO = "auto"
		const val DEFAULT_TARGET_LANGUAGE = "id"

		private const val PREFS_NAME = "novel_translation"
		private const val KEY_ENGINE = "engine"
		private const val KEY_PROVIDER = "provider"
		private const val KEY_SOURCE_LANGUAGE = "source_language"
		private const val KEY_TARGET_LANGUAGE = "target_language"
		private const val KEY_MODEL = "model"
		private const val KEY_BASE_URL = "base_url"
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
