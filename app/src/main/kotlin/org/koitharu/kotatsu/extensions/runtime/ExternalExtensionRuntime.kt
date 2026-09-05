package org.koitharu.kotatsu.extensions.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * The BCP-47 code for an extension's declared language, or null if it isn't one we can resolve.
 *
 * Mihon indexes declare codes ("en", "pt-BR"); LNReader indexes declare names, either English
 * ("English") or the autonym ("português (Brasil)"). Everything downstream — the catalog's language
 * filter above all — compares codes, so names have to be looked back up.
 */
fun getExternalExtensionLangCodeOrNull(lang: String): String? {
	val value = lang.trim().trim('‎', '‏')
	return when {
		value.isEmpty() -> null
		// A code has to look like one. Length alone isn't enough: "ไทย" is three chars and Android turns
		// any unknown 2-8 letter subtag into the label "Various languages" instead of failing.
		LANG_CODE_REGEX matches value -> value.replace('_', '-')
		else -> langCodesByName[value.lowercase(Locale.ROOT)]
	}
}

private val LANG_CODE_REGEX = Regex("[a-zA-Z]{2,3}([_-][a-zA-Z0-9]{2,8})*")

/**
 * Resolves names and legacy separators to one stable language key. Valid BCP-47 tags keep Android's
 * canonical casing ("PT_br" -> "pt-BR"); unknown future/private values are preserved as a safe,
 * lowercase key instead of being discarded.
 */
fun getExternalExtensionLangCode(lang: String): String {
	val declared = lang.trim().trim('‎', '‏')
	val resolved = getExternalExtensionLangCodeOrNull(declared) ?: declared
	if (resolved.isBlank()) return ""
	val normalized = resolved.replace('_', '-')
	val localeTag = Locale.forLanguageTag(normalized).toLanguageTag()
	return localeTag.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }
		?: normalized.lowercase(Locale.ROOT)
}

/** Display label for a language declared as either a code or a name. */
fun getExternalExtensionLanguageLabel(lang: String): String =
	getExternalExtensionLangCodeOrNull(lang)?.let(::getExternalExtensionLanguageDisplayName) ?: lang

/**
 * The exact set of names LNReader plugins can declare, from its `languagesMapping` table
 * (`src/utils/constants/languages.ts`). Most are autonyms Android's own locale data does not produce
 * verbatim — comma-separated lists ("中文, 汉语, 漢語"), or a different wording ("Bahasa Indonesia" vs
 * Android's "Indonesia") — so they are matched from this table first and the locale sweep below only
 * covers names from other index formats.
 *
 * Note LNReader keys Arabic as "ab" (Abkhazian) in that file; the name is what plugins ship, and it
 * maps to the correct code here.
 */
private val LNREADER_LANG_CODES = mapOf(
	"bahasa indonesia" to "id",
	"english" to "en",
	"español" to "es",
	"français" to "fr",
	"polski" to "pl",
	"português" to "pt",
	"tiếng việt" to "vi",
	"türkçe" to "tr",
	"русский" to "ru",
	"українська" to "uk",
	"العربية" to "ar",
	"ไทย" to "th",
	"中文, 汉语, 漢語" to "zh",
	"日本語" to "ja",
	"조선말, 한국어" to "ko",
	"multi" to "all",
)

private val langCodesByName: Map<String, String> by lazy {
	val map = HashMap<String, String>(LNREADER_LANG_CODES)
	for (locale in Locale.getAvailableLocales()) {
		if (locale.language.isEmpty()) continue
		// Language-only names first ("Portuguese"), then the country-qualified ones ("Portuguese
		// (Brazil)"), whose value has to keep the region so it matches Mihon's "pt-BR".
		map.putIfAbsent(locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT), locale.language)
		map.putIfAbsent(locale.getDisplayLanguage(locale).lowercase(Locale.ROOT), locale.language)
		map.putIfAbsent(locale.getDisplayName(Locale.ENGLISH).lowercase(Locale.ROOT), locale.toLanguageTag())
		map.putIfAbsent(locale.getDisplayName(locale).lowercase(Locale.ROOT), locale.toLanguageTag())
	}
	map
}

fun getExternalExtensionLanguageDisplayName(langCode: String): String {
	val normalized = getExternalExtensionLangCode(langCode)
	return when (normalized.lowercase(Locale.ROOT)) {
		"" -> "Other"
		"all" -> "All"
		"other" -> "Other"
		else -> runCatching {
			val locale = Locale.forLanguageTag(normalized)
			val label = if (locale.country.isNotEmpty() || locale.script.isNotEmpty()) {
				locale.getDisplayName(Locale.getDefault())
			} else {
				locale.getDisplayLanguage(Locale.getDefault())
			}
			label.takeUnless { it.equals(locale.language, ignoreCase = true) }
		}
			.getOrNull()
			?.takeIf { it.isNotBlank() }
			?: normalized.uppercase(Locale.ROOT)
	}
}

/** Emoji used beside language groups in Explore. Region-qualified tags always keep their region. */
fun getExternalExtensionLanguageFlag(langCode: String): String {
	val normalized = getExternalExtensionLangCode(langCode)
	val locale = Locale.forLanguageTag(normalized)
	val language = locale.language.lowercase(Locale.ROOT)
	if (normalized.isBlank() || language == "other") return "🏳️"
	if (language == "all" || language == "mul") return "🌐"

	val explicitRegion = locale.country.uppercase(Locale.ROOT)
	val country = if (explicitRegion.isNotEmpty()) {
		// Numeric macro-regions such as es-419 have no single honest country flag. Likewise, an
		// unknown two-letter region must not be turned into a fake regional-indicator pair.
		if (explicitRegion.length != 2) return "🌐"
		REGION_ALIASES[explicitRegion] ?: explicitRegion
	} else {
		DEFAULT_SCRIPT_COUNTRIES["$language-${locale.script}"]
			?: DEFAULT_LANGUAGE_COUNTRIES[language]
			?: return "🌐"
	}
	if (country !in ISO_COUNTRIES) return "🌐"
	return country.map { letter ->
		String(Character.toChars(REGIONAL_INDICATOR_A + (letter.code - 'A'.code)))
	}.joinToString(separator = "")
}

private val REGION_ALIASES = mapOf(
	"UK" to "GB",
)

private val DEFAULT_SCRIPT_COUNTRIES = mapOf(
	"zh-Hans" to "CN",
	"zh-Hant" to "TW",
	"pa-Arab" to "PK",
	"pa-Guru" to "IN",
)

private val DEFAULT_LANGUAGE_COUNTRIES = mapOf(
	"af" to "ZA",
	"ar" to "SA",
	"az" to "AZ",
	"be" to "BY",
	"bn" to "BD",
	"bg" to "BG",
	"bs" to "BA",
	"ca" to "ES",
	"cs" to "CZ",
	"da" to "DK",
	"de" to "DE",
	"el" to "GR",
	"en" to "GB",
	"es" to "ES",
	"et" to "EE",
	"eu" to "ES",
	"fa" to "IR",
	"fi" to "FI",
	"fil" to "PH",
	"fr" to "FR",
	"gl" to "ES",
	"he" to "IL",
	"hi" to "IN",
	"hr" to "HR",
	"hu" to "HU",
	"id" to "ID",
	"it" to "IT",
	"ja" to "JP",
	"ka" to "GE",
	"kk" to "KZ",
	"km" to "KH",
	"ko" to "KR",
	"lo" to "LA",
	"lt" to "LT",
	"lv" to "LV",
	"mk" to "MK",
	"mn" to "MN",
	"ms" to "MY",
	"my" to "MM",
	"ne" to "NP",
	"nl" to "NL",
	"no" to "NO",
	"pa" to "IN",
	"pl" to "PL",
	"pt" to "PT",
	"ro" to "RO",
	"ru" to "RU",
	"sk" to "SK",
	"sl" to "SI",
	"sr" to "RS",
	"sv" to "SE",
	"ta" to "IN",
	"te" to "IN",
	"th" to "TH",
	"tl" to "PH",
	"tr" to "TR",
	"uk" to "UA",
	"ur" to "PK",
	"uz" to "UZ",
	"vi" to "VN",
	"zh" to "CN",
)

private val ISO_COUNTRIES = Locale.getISOCountries().toHashSet()
private const val REGIONAL_INDICATOR_A = 0x1F1E6

/**
 * Returns the language's own name (autonym) — e.g. "Français", "日本語", "Español" — instead of
 * the name translated into the device language. Used wherever an extension's language is shown
 * natively (source settings language picker, browse top-bar subheading).
 */
fun getExternalExtensionLanguageAutonym(langCode: String): String {
	val normalized = getExternalExtensionLangCode(langCode)
	return when (normalized.lowercase(Locale.ROOT)) {
		"" -> "Other"
		"all" -> "All"
		"other" -> "Other"
		else -> runCatching {
			val locale = Locale.forLanguageTag(normalized)
			val label = if (locale.country.isNotEmpty() || locale.script.isNotEmpty()) {
				locale.getDisplayName(locale)
			} else {
				locale.getDisplayLanguage(locale)
			}
			label.takeUnless { it.equals(locale.language, ignoreCase = true) }
				?.replaceFirstChar { it.uppercase(locale) }
		}.getOrNull()
			?.takeIf { it.isNotBlank() }
			?: normalized.uppercase(Locale.ROOT)
	}
}

fun registerExternalExtensionPackageObserver(
	context: Context,
	scope: CoroutineScope,
	onPackageChanged: suspend () -> Unit,
): BroadcastReceiver {
	val receiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			scope.launch { onPackageChanged() }
		}
	}
	ContextCompat.registerReceiver(
		context,
		receiver,
		IntentFilter().apply {
			addAction(Intent.ACTION_PACKAGE_ADDED)
			addAction(Intent.ACTION_PACKAGE_REPLACED)
			addAction(Intent.ACTION_PACKAGE_REMOVED)
			addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
			addDataScheme("package")
		},
		ContextCompat.RECEIVER_EXPORTED,
	)
	return receiver
}

data class ProcessedExternalExtensions<SuccessT, ErrorT, SourceT, WrappedSourceT>(
	val successful: List<SuccessT>,
	val failed: List<ErrorT>,
	val sourceById: Map<Long, SourceT>,
	val wrappedSourceById: Map<Long, WrappedSourceT>,
	val untrustedPackages: List<String>,
)

fun <ResultT, SuccessT, ErrorT, SourceT, CatalogueSourceT : SourceT, WrappedSourceT> processExternalExtensionResults(
	results: List<ResultT>,
	successOf: (ResultT) -> SuccessT?,
	errorOf: (ResultT) -> ErrorT?,
	untrustedPackageNameOf: (ResultT) -> String?,
	successSources: (SuccessT) -> List<SourceT>,
	successPackageName: (SuccessT) -> String,
	successIsNsfw: (SuccessT) -> Boolean,
	sourceId: (SourceT) -> Long,
	asCatalogueSource: (SourceT) -> CatalogueSourceT?,
	catalogueSourceName: (CatalogueSourceT) -> String,
	catalogueSourceLang: (CatalogueSourceT) -> String,
	buildWrappedSource: (CatalogueSourceT, String, Boolean, Boolean) -> WrappedSourceT,
	onError: (ErrorT) -> Unit = {},
	onUntrusted: (String) -> Unit = {},
): ProcessedExternalExtensions<SuccessT, ErrorT, SourceT, WrappedSourceT> {
	val successful = mutableListOf<SuccessT>()
	val failed = mutableListOf<ErrorT>()
	val sourceById = linkedMapOf<Long, SourceT>()
	val catalogueSources = mutableListOf<Triple<CatalogueSourceT, String, Boolean>>()
	val untrustedPackages = mutableListOf<String>()

	results.forEach { result ->
		val success = successOf(result)
		val error = errorOf(result)
		val untrustedPackage = untrustedPackageNameOf(result)
		when {
			success != null -> {
				successful += success
				successSources(success).forEach { source ->
					sourceById[sourceId(source)] = source
					asCatalogueSource(source)?.let {
						catalogueSources += Triple(it, successPackageName(success), successIsNsfw(success))
					}
				}
			}
			error != null -> {
				failed += error
				onError(error)
			}
			untrustedPackage != null -> {
				untrustedPackages += untrustedPackage
				onUntrusted(untrustedPackage)
			}
		}
	}

	// A language suffix is a property of siblings shipped by the same APK. Using the source name
	// globally makes two unrelated extensions with the same display name look like language variants.
	// Count distinct normalized languages so aliases/names and legacy separators do not create a false
	// multi-language marker either.
	val languagesByPackageAndName = catalogueSources
		.groupBy { (source, pkgName) -> pkgName to catalogueSourceName(source) }
		.mapValues { (_, sources) ->
			sources.mapTo(HashSet()) {
				getExternalExtensionLangCode(catalogueSourceLang(it.first)).lowercase(Locale.ROOT)
			}
		}
	val wrappedSourceById = linkedMapOf<Long, WrappedSourceT>()
	catalogueSources.forEach { (catalogueSource, pkgName, isNsfw) ->
		wrappedSourceById[sourceId(catalogueSource)] = buildWrappedSource(
			catalogueSource,
			pkgName,
			isNsfw,
			languagesByPackageAndName[pkgName to catalogueSourceName(catalogueSource)].orEmpty().size > 1,
		)
	}

	return ProcessedExternalExtensions(successful, failed, sourceById, wrappedSourceById, untrustedPackages)
}

class ExternalExtensionManagerRuntime<ResultT, SuccessT, ErrorT, SourceT, WrappedSourceT>(
	private val context: Context,
	private val scope: CoroutineScope,
) {
	private val _installedExtensions = MutableStateFlow<List<SuccessT>>(emptyList())
	val installedExtensions: StateFlow<List<SuccessT>> = _installedExtensions.asStateFlow()

	private val _failedExtensions = MutableStateFlow<List<ErrorT>>(emptyList())
	val failedExtensions: StateFlow<List<ErrorT>> = _failedExtensions.asStateFlow()

	private val _isLoading = MutableStateFlow(false)
	val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

	private val _isReady = MutableStateFlow(false)
	val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

	// Package broadcasts refresh on an IO coroutine while repositories resolve sources from other
	// threads. Publish complete immutable snapshots atomically so a refresh never exposes Mihon
	// sources as briefly uninstalled (or races a mutable HashMap read).
	@Volatile
	private var sourceCache: Map<Long, SourceT> = emptyMap()
	@Volatile
	private var wrappedSourceCache: Map<Long, WrappedSourceT> = emptyMap()
	@Volatile
	private var isPackageObserverRegistered = false
	@Volatile
	private var isInitialized = false
	private val loadMutex = Mutex()

	@Synchronized
	fun initialize(loadAction: suspend () -> Unit) {
		if (isInitialized) return
		isInitialized = true
		registerPackageObserver(loadAction)
		scope.launch { loadAction() }
	}

	suspend fun loadExtensions(
		loadResults: suspend (Context) -> List<ResultT>,
		processResults: (List<ResultT>) -> ProcessedExternalExtensions<SuccessT, ErrorT, SourceT, WrappedSourceT>,
	) {
		// Package-added/replaced broadcasts can arrive together. StateFlow is not a lock; use an
		// atomic tryLock so two classloader scans cannot race and publish stale source instances.
		// When one is already running, wait it out rather than returning: callers treat this as
		// "sources are ready afterwards", and returning early left them reading an empty cache —
		// which is what made extension icons fall back to the generic placeholder at random.
		if (!loadMutex.tryLock()) {
			loadMutex.withLock { }
			return
		}
		_isLoading.value = true
		try {
			val processed = processResults(loadResults(context))
			val newSourceCache = LinkedHashMap<Long, SourceT>(processed.sourceById.size)
			val newWrappedSourceCache = LinkedHashMap<Long, WrappedSourceT>(processed.wrappedSourceById.size)
			newSourceCache.putAll(processed.sourceById)
			newWrappedSourceCache.putAll(processed.wrappedSourceById)
			sourceCache = newSourceCache
			wrappedSourceCache = newWrappedSourceCache
			_installedExtensions.value = processed.successful
			_failedExtensions.value = processed.failed
			_isReady.value = true
		} finally {
			_isLoading.value = false
			loadMutex.unlock()
		}
	}

	fun getSourceById(sourceId: Long): SourceT? = sourceCache[sourceId]
	fun getWrappedSourceById(sourceId: Long): WrappedSourceT? = wrappedSourceCache[sourceId]
	fun getWrappedSources(): List<WrappedSourceT> = wrappedSourceCache.values.toList()
	fun getSourceCount(): Int = sourceCache.size
	fun hasExtensions(): Boolean = installedExtensions.value.isNotEmpty()

	private fun registerPackageObserver(loadAction: suspend () -> Unit) {
		if (isPackageObserverRegistered) return
		registerExternalExtensionPackageObserver(context, scope, loadAction)
		isPackageObserverRegistered = true
	}
}

class ExternalExtensionManagerFacade<ResultT, SuccessT, ErrorT, SourceT, CatalogueT : SourceT, WrappedSourceT>(
	context: Context,
	scope: CoroutineScope,
	private val loadResults: suspend (Context) -> List<ResultT>,
	private val successOf: (ResultT) -> SuccessT?,
	private val errorOf: (ResultT) -> ErrorT?,
	private val untrustedPackageNameOf: (ResultT) -> String?,
	private val successSources: (SuccessT) -> List<SourceT>,
	private val successPackageName: (SuccessT) -> String,
	private val successIsNsfw: (SuccessT) -> Boolean,
	private val successCatalogueSources: (SuccessT) -> List<CatalogueT>,
	private val sourceId: (SourceT) -> Long,
	private val asCatalogueSource: (SourceT) -> CatalogueT?,
	private val catalogueSourceName: (CatalogueT) -> String,
	private val catalogueSourceLang: (CatalogueT) -> String,
	private val buildWrappedSource: (CatalogueT, String, Boolean, Boolean) -> WrappedSourceT,
	private val sourceNamePrefix: String,
	private val errorPackageName: (ErrorT) -> String,
	private val errorMessage: (ErrorT) -> String,
	private val errorThrowable: (ErrorT) -> Throwable? = { null },
) {
	private val runtime = ExternalExtensionManagerRuntime<ResultT, SuccessT, ErrorT, SourceT, WrappedSourceT>(context, scope)

	val installedExtensions: StateFlow<List<SuccessT>> = runtime.installedExtensions
	val failedExtensions: StateFlow<List<ErrorT>> = runtime.failedExtensions
	val isLoading: StateFlow<Boolean> = runtime.isLoading
	val isReady: StateFlow<Boolean> = runtime.isReady

	fun initialize() {
		runtime.initialize(::loadExtensions)
	}

	suspend fun loadExtensions() {
		runtime.loadExtensions(loadResults) { results ->
			processExternalExtensionResults(
				results = results,
				successOf = successOf,
				errorOf = errorOf,
				untrustedPackageNameOf = untrustedPackageNameOf,
				successSources = successSources,
				successPackageName = successPackageName,
				successIsNsfw = successIsNsfw,
				sourceId = sourceId,
				asCatalogueSource = asCatalogueSource,
				catalogueSourceName = catalogueSourceName,
				catalogueSourceLang = catalogueSourceLang,
				buildWrappedSource = buildWrappedSource,
				onError = { error ->
					val throwable = errorThrowable(error)
					if (throwable == null) {
						android.util.Log.e("ExternalExtensionManager", "${errorPackageName(error)}: ${errorMessage(error)}")
					} else {
						android.util.Log.e("ExternalExtensionManager", "${errorPackageName(error)}: ${errorMessage(error)}", throwable)
					}
				},
			)
		}
	}

	fun getCatalogueSources(): List<CatalogueT> = installedExtensions.value.flatMap(successCatalogueSources)
	fun getWrappedSources(): List<WrappedSourceT> = runtime.getWrappedSources()
	fun getSourceById(sourceId: Long): SourceT? = runtime.getSourceById(sourceId)
	fun getCatalogueSourceById(sourceId: Long): CatalogueT? = runtime.getSourceById(sourceId)?.let(asCatalogueSource)
	fun getWrappedSourceById(sourceId: Long): WrappedSourceT? = runtime.getWrappedSourceById(sourceId)
	fun getWrappedSourceByName(name: String): WrappedSourceT? {
		if (!name.startsWith(sourceNamePrefix)) return null
		val sourceId = name.substringAfter(sourceNamePrefix).substringBefore(':').toLongOrNull() ?: return null
		return getWrappedSourceById(sourceId)
	}
	fun getSourcesByLanguage(): Map<String, List<CatalogueT>> =
		getCatalogueSources().groupBy { getExternalExtensionLangCode(catalogueSourceLang(it)) }
	fun getSourceCount(): Int = runtime.getSourceCount()
	fun hasExtensions(): Boolean = runtime.hasExtensions()
}
