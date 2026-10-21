package org.koitharu.kotatsu.explore.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLangCode
import org.koitharu.kotatsu.kotatsu.model.KotatsuMangaSource
import org.koitharu.kotatsu.kotatsu.runtime.KotatsuRuntimeManager
import org.koitharu.kotatsu.lnreader.LnPluginManager
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.tsuki.TsukiPluginManager
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedSource(val source: MangaSource, val languageSubtitle: String?)

data class MihonSourceFilterEntry(
	val source: MihonMangaSource,
	val isSourceEnabled: Boolean,
	val isLanguageEnabled: Boolean,
) {
	val isEnabled: Boolean get() = isSourceEnabled && isLanguageEnabled
}

internal fun storedMihonSourceId(source: MangaSource): Long? = when (source) {
	is MangaSourceInfo -> storedMihonSourceId(source.mangaSource)
	is MihonMangaSource -> source.sourceId
	is MissingMangaSource -> source.name.takeIf { it.startsWith("MIHON_") }?.removePrefix("MIHON_")?.substringBefore(':')?.toLongOrNull()
	else -> null
}

@Singleton
class MangaSourcesRepository @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val settings: AppSettings,
	private val mihonExtensionManager: MihonExtensionManager? = null,
	private val lnPluginManager: LnPluginManager? = null,
	private val tsukiPluginManager: TsukiPluginManager? = null,
	private val kotatsuRuntimeManager: KotatsuRuntimeManager? = null,
) {
	private val usageRefresh = MutableStateFlow(0)
	private val pinnedRefresh = MutableStateFlow(0)

	fun getEnabledSources(): List<MangaSource> = buildSortedSourceInfoList(getAllEnabledSources()).map { it.mangaSource }

	fun getPinnedSources(): Set<MangaSource> {
		val sourcesByKey = getAllEnabledSources().associateBy(::sourceKeyOf)
		return getPinnedSourceKeys().mapNotNull { sourcesByKey[it] }.toSet()
	}

	fun getTopSources(limit: Int): List<MangaSource> = getEnabledSources().take(limit)

	fun observeEnabledSources(): Flow<List<MangaSourceInfo>> {
		val scriptPlugins = combine(observeLnSources(), observeTsukiSources()) { ln, tsuki -> ln + tsuki }
		val optionalPlugins = combine(scriptPlugins, observeKotatsuSources()) { plugins, kotatsu -> plugins + kotatsu }
		return combine(
			observeMihonSources(),
			optionalPlugins,
			settings.observeAsFlow(AppSettings.KEY_SOURCES_ORDER) { sourcesSortOrder },
			usageRefresh,
			pinnedRefresh,
		) { mihon, optional, _, _, _ -> buildSortedSourceInfoList(mihon + optional) }.distinctUntilChanged()
	}

	fun observeAll(): Flow<List<Pair<MangaSource, Boolean>>> {
		val legacy = combine(observeMihonSources(), observeLnSources(), observeTsukiSources()) { mihon, ln, tsuki -> mihon + ln + tsuki }
		return combine(legacy, observeKotatsuSources()) { sources, kotatsu -> (sources + kotatsu).map { it to true } }
	}

	fun setIsPinned(sources: Collection<MangaSource>, isPinned: Boolean): ReversibleHandle {
		val before = getPinnedSourceKeys()
		val updated = before.toMutableList()
		for (source in sources) {
			val key = sourceKeyOf(source)
			if (isPinned) { updated.remove(key); updated.add(0, key) } else updated.remove(key)
		}
		setPinnedSourceKeys(updated)
		pinnedRefresh.value++
		return ReversibleHandle { setPinnedSourceKeys(before); pinnedRefresh.value++ }
	}

	fun setSourcesHidden(sources: Collection<MangaSource>, hidden: Boolean): ReversibleHandle {
		val packages = sources.mapNotNullTo(HashSet()) { it.unwrapMihon()?.pkgName }
		val plugins = sources.mapNotNullTo(HashSet()) { it.unwrapLn()?.pluginId }
		val before = settings.mihonHiddenPackages
		val beforeLn = settings.lnHiddenPlugins
		settings.mihonHiddenPackages = if (hidden) before + packages else before - packages
		settings.lnHiddenPlugins = if (hidden) beforeLn + plugins else beforeLn - plugins
		return ReversibleHandle { settings.mihonHiddenPackages = before; settings.lnHiddenPlugins = beforeLn }
	}

	private val usagePrefs: SharedPreferences by lazy { context.getSharedPreferences("source_usage", Context.MODE_PRIVATE) }
	fun trackUsage(source: MangaSource) {
		usagePrefs.edit().putLong(sourceKeyOf(source), System.currentTimeMillis()).apply()
		usageRefresh.value++
	}
	private fun getLastUsedTimestamp(source: MangaSource): Long = usagePrefs.getLong(sourceKeyOf(source), 0L)
	private val sourceStatePrefs: SharedPreferences by lazy { context.getSharedPreferences("source_state", Context.MODE_PRIVATE) }

	private fun sourceKeyOf(source: MangaSource): String = when (source) {
		is MangaSourceInfo -> sourceKeyOf(source.mangaSource)
		is MihonMangaSource -> "mihon:${source.sourceId}"
		is LnMangaSource -> "ln:${source.pluginId}"
		is TsukiMangaSource -> "tsuki:${source.name}"
		is KotatsuMangaSource -> "kotatsu:${source.name}"
		else -> getMihonSources().firstOrNull { it.name == source.name }?.let(::sourceKeyOf) ?: source.name
	}

	private fun getPinnedSourceKeys(): List<String> = sourceStatePrefs.getString(KEY_PINNED_ORDER, null).orEmpty()
		.takeIf { it.isNotEmpty() }?.split(PIN_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
	private fun setPinnedSourceKeys(keys: List<String>) { sourceStatePrefs.edit().putString(KEY_PINNED_ORDER, keys.joinToString(PIN_SEPARATOR)).apply() }

	private fun buildSortedSourceInfoList(sources: List<MangaSource>): List<MangaSourceInfo> {
		if (sources.isEmpty()) return emptyList()
		val pinnedOrder = normalizeLegacyPinnedSourceKeys(getPinnedSourceKeys(), sources)
		val pinnedIndex = HashMap<String, Int>(pinnedOrder.size)
		for ((index, key) in pinnedOrder.withIndex()) pinnedIndex[key] = index
		val pinned = ArrayList<MangaSourceInfo>()
		val unpinned = ArrayList<MangaSourceInfo>()
		for (source in sources) {
			val isPinned = sourceKeyOf(source) in pinnedIndex
			val item = MangaSourceInfo(source, isEnabled = true, isPinned = isPinned)
			if (isPinned) pinned += item else unpinned += item
		}
		pinned.sortBy { pinnedIndex[sourceKeyOf(it.mangaSource)] ?: Int.MAX_VALUE }
		when (settings.sourcesSortOrder) {
			SourcesSortOrder.ALPHABETIC -> unpinned.sortWith(compareBy { it.getTitle(context) })
			SourcesSortOrder.LAST_USED -> unpinned.sortWith(compareByDescending { getLastUsedTimestamp(it.mangaSource) })
			SourcesSortOrder.MANUAL -> Unit
		}
		return pinned + unpinned
	}

	private fun getMihonSources(): List<MihonMangaSource> {
		val manager = mihonExtensionManager ?: return emptyList()
		manager.initialize()
		val hideNsfw = settings.isNsfwContentDisabled
		val hiddenPackages = settings.mihonHiddenPackages
		val disabledSourceIds = settings.mihonDisabledSourceIds
		return manager.getMihonMangaSources()
			.filterNot { hideNsfw && it.isNsfw }
			.filterNot { it.pkgName in hiddenPackages }
			.filterNot { it.sourceId.toString() in disabledSourceIds }
			.filterNot { isLanguageDisabled(it.language, disabledSourceIds) }
	}

	fun resolveActiveSource(source: MangaSource): ResolvedSource {
		val sourceId = storedMihonSourceId(source) ?: return ResolvedSource(source, null)
		val fallback = source.unwrapMihon()
		val manager = mihonExtensionManager ?: return ResolvedSource(fallback ?: source, fallback?.languageDisplayName)
		manager.initialize()
		val exact = manager.getMihonMangaSourceById(sourceId) ?: fallback
		return if (exact != null) ResolvedSource(exact, exact.languageDisplayName) else ResolvedSource(source, null)
	}

	fun setMihonSourcesEnabled(sourceIds: Collection<Long>, enabled: Boolean) { setMihonSourceStates(sourceIds.associateWith { enabled }) }
	fun setMihonLanguageEnabled(language: String, enabled: Boolean) { setMihonLanguageStates(mapOf(language to enabled)) }
	fun setMihonSourceStates(states: Map<Long, Boolean>) { setMihonFilterStates(states, emptyMap()) }
	fun setMihonLanguageStates(states: Map<String, Boolean>) { setMihonFilterStates(emptyMap(), states) }

	@Synchronized
	fun setMihonFilterStates(sourceStates: Map<Long, Boolean>, languageStates: Map<String, Boolean>) {
		if (sourceStates.isEmpty() && languageStates.isEmpty()) return
		val before = settings.mihonDisabledSourceIds
		val updated = before.toMutableSet()
		for ((sourceId, enabled) in sourceStates) {
			val key = sourceId.toString(); if (enabled) updated.remove(key) else updated.add(key)
		}
		for ((language, enabled) in languageStates) {
			val normalized = normalizedLanguage(language)
			updated.removeAll { it.startsWith(DISABLED_LANGUAGE_PREFIX) && normalizedLanguage(it.removePrefix(DISABLED_LANGUAGE_PREFIX)) == normalized }
			if (!enabled) updated.add(DISABLED_LANGUAGE_PREFIX + normalized)
		}
		if (updated != before) settings.mihonDisabledSourceIds = updated
	}

	fun getLnSources(): List<LnMangaSource> {
		val manager = lnPluginManager ?: return emptyList()
		manager.initialize()
		val hidden = settings.lnHiddenPlugins
		return manager.getAll().filterNot { it.pluginId in hidden }
	}

	fun observeLnSources(): Flow<List<LnMangaSource>> {
		val manager = lnPluginManager ?: return kotlinx.coroutines.flow.flowOf(emptyList())
		return flow {
			manager.initialize()
			emitAll(combine(manager.sources, settings.observeAsFlow(AppSettings.KEY_LN_HIDDEN_PLUGINS) { lnHiddenPlugins }) { _: Any?, _: Any? -> getLnSources() }.distinctUntilChanged())
		}
	}

	fun getTsukiSources(): List<TsukiMangaSource> {
		val manager = tsukiPluginManager ?: return emptyList()
		manager.initialize()
		val hideNsfw = settings.isNsfwContentDisabled
		return manager.getEnabledSources().filterNot { hideNsfw && it.descriptor.contentType.equals("HENTAI", ignoreCase = true) }
	}

	fun observeTsukiSources(): Flow<List<TsukiMangaSource>> {
		val manager = tsukiPluginManager ?: return kotlinx.coroutines.flow.flowOf(emptyList())
		return flow {
			manager.initialize()
			emitAll(combine(manager.plugins, settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled }) { _: Any?, _: Any? -> getTsukiSources() }.distinctUntilChanged())
		}
	}

	fun getKotatsuSources(): List<KotatsuMangaSource> {
		val manager = kotatsuRuntimeManager ?: return emptyList()
		manager.initialize()
		val hideNsfw = settings.isNsfwContentDisabled
		return manager.getEnabledSources().filterNot { hideNsfw && it.contentType.equals("HENTAI", ignoreCase = true) }
	}

	fun observeKotatsuSources(): Flow<List<KotatsuMangaSource>> {
		val manager = kotatsuRuntimeManager ?: return kotlinx.coroutines.flow.flowOf(emptyList())
		return combine(manager.sources, settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled }) { _: Any?, _: Any? -> getKotatsuSources() }.distinctUntilChanged()
	}

	private fun getAllEnabledSources(): List<MangaSource> = getMihonSources() + getLnSources() + getTsukiSources() + getKotatsuSources()

	private fun MangaSource.unwrapLn(): LnMangaSource? = when (this) {
		is LnMangaSource -> this
		is MangaSourceInfo -> mangaSource as? LnMangaSource
		else -> null
	}
	private fun MangaSource.unwrapMihon(): MihonMangaSource? = when (this) {
		is MihonMangaSource -> this
		is MangaSourceInfo -> mangaSource as? MihonMangaSource
		else -> null
	}

	private fun hasMultiLanguageSources(): Boolean {
		val manager = mihonExtensionManager ?: return false
		return manager.getMihonMangaSources().groupBy { it.pkgName to it.catalogueSource.name }
			.any { (_, group) -> group.mapTo(HashSet()) { normalizedLanguage(it.language) }.size > 1 }
	}

	private fun getAllMihonSources(): List<MihonMangaSource> {
		val manager = mihonExtensionManager ?: return emptyList()
		manager.initialize()
		val sources = manager.getMihonMangaSources()
		val hideNsfw = settings.isNsfwContentDisabled
		return sources.filter { !hideNsfw || !it.isNsfw }
	}

	fun observeMihonSources(): Flow<List<MihonMangaSource>> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(emptyList())
		return flow {
			manager.initialize()
			emitAll(combine(
				manager.installedExtensions,
				manager.isLoading,
				settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
				settings.observeAsFlow(AppSettings.KEY_MIHON_HIDDEN_PACKAGES) { mihonHiddenPackages },
				settings.observeAsFlow(AppSettings.KEY_MIHON_DISABLED_SOURCE_IDS) { mihonDisabledSourceIds },
			) { _: Any?, _: Any?, _: Any?, _: Any?, _: Any? -> getMihonSources() }.distinctUntilChanged())
		}
	}

	fun observeMihonSourceFilters(): Flow<List<MihonSourceFilterEntry>> = combine(
		observeAllMihonSources(), settings.observeAsFlow(AppSettings.KEY_MIHON_DISABLED_SOURCE_IDS) { mihonDisabledSourceIds },
	) { sources, disabled ->
		sources.map { source -> MihonSourceFilterEntry(source, source.sourceId.toString() !in disabled, !isLanguageDisabled(source.language, disabled)) }
	}.distinctUntilChanged()

	fun observeHasMultiLanguageSources(): Flow<Boolean> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(false)
		return flow {
			manager.initialize()
			emitAll(combine(manager.installedExtensions, manager.isLoading) { _: Any?, _: Any? -> hasMultiLanguageSources() }.distinctUntilChanged())
		}
	}

	fun observeMihonLoadingState(): Flow<Boolean> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(false)
		return manager.isLoading
	}

	fun observeAllMihonSources(): Flow<List<MihonMangaSource>> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(emptyList())
		return flow {
			manager.initialize()
			emitAll(combine(manager.installedExtensions, manager.isLoading, settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled }) { _: Any?, _: Any?, _: Any? -> getAllMihonSources() }.distinctUntilChanged())
		}
	}

	suspend fun reloadMihonSources() { mihonExtensionManager?.loadExtensions() }

	suspend fun ensureExternalSourcesReady() {
		mihonExtensionManager?.ensureReady()
		lnPluginManager?.initialize()
		tsukiPluginManager?.initialize()
		kotatsuRuntimeManager?.initialize()
	}

	private fun normalizeLegacyPinnedSourceKeys(keys: List<String>, sources: List<MangaSource>): List<String> {
		var changed = false
		val normalized = keys.mapNotNull { key ->
			if (!key.startsWith("mihon:") || key.removePrefix("mihon:").toLongOrNull() != null) return@mapNotNull key
			val legacy = key.removePrefix("mihon:")
			val matches = sources.filterIsInstance<MihonMangaSource>().filter { legacy == "${it.pkgName}:${it.catalogueSource.name}" }
			val activeLanguage = matches.firstNotNullOfOrNull { settings.getMihonActiveLang(it.pkgName, it.catalogueSource.name) }
			val match = activeLanguage?.let { active -> matches.firstOrNull { normalizedLanguage(active) == normalizedLanguage(it.language) } } ?: matches.firstOrNull()
			changed = true
			match?.let(::sourceKeyOf)
		}.distinct()
		if (changed) setPinnedSourceKeys(normalized)
		return normalized
	}

	private fun normalizedLanguage(language: String): String = getExternalExtensionLangCode(language).ifBlank { "other" }.lowercase(Locale.ROOT)
	private fun isLanguageDisabled(language: String, disabled: Set<String>): Boolean {
		val normalized = normalizedLanguage(language)
		return disabled.any { it.startsWith(DISABLED_LANGUAGE_PREFIX) && normalizedLanguage(it.removePrefix(DISABLED_LANGUAGE_PREFIX)) == normalized }
	}

	private companion object {
		private const val KEY_PINNED_ORDER = "pinned_order"
		private const val PIN_SEPARATOR = "\n"
		private const val DISABLED_LANGUAGE_PREFIX = "lang:"
	}
}
