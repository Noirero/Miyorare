package org.koitharu.kotatsu.settings.sources.catalog

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.mihon.model.MihonExtensionInfo
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.net.URI
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_PARALLEL_STORE_REFRESH = 4

enum class StoreHealth { CHECKING, AVAILABLE, UNAVAILABLE }

data class ExtensionStoreState(
	val store: ExtensionStoreRecord,
	val health: StoreHealth,
	val catalog: List<ExternalExtensionRepoEntry> = emptyList(),
	val error: Throwable? = null,
	val contentType: ExtensionStoreContentType = ExtensionStoreContentType.MANGA,
)

class ExtensionStoreContentTypeMismatchException(
	val selectedType: ExtensionStoreContentType,
	val detectedTypes: Set<ExtensionStoreContentType>,
) : IllegalArgumentException("Repository type mismatch: selected $selectedType, detected ${detectedTypes.joinToString()}")

internal fun validateExtensionStoreContentType(catalog: List<ExternalExtensionRepoEntry>, selectedType: ExtensionStoreContentType) {
	val detectedTypes = catalog.detectedExtensionStoreContentTypes()
	// Anime repositories commonly use ordinary package names. Their explicit store assignment is
	// authoritative; the package-name heuristic is advisory and must not make a valid repo unavailable.
	if (selectedType == ExtensionStoreContentType.ANIME) return
	if (detectedTypes.isNotEmpty() && selectedType !in detectedTypes) {
		throw ExtensionStoreContentTypeMismatchException(selectedType, detectedTypes)
	}
}

internal fun List<ExternalExtensionRepoEntry>.detectedExtensionStoreContentTypes(): Set<ExtensionStoreContentType> = buildSet {
	for (entry in this@detectedExtensionStoreContentTypes) {
		add(when {
			entry.packageName.contains(".animeextension.", ignoreCase = true) -> ExtensionStoreContentType.ANIME
			entry.isNovelExtension -> ExtensionStoreContentType.NOVEL
			else -> ExtensionStoreContentType.MANGA
		})
	}
}

@Singleton
class ExtensionStoreManager @Inject constructor(
	@ApplicationContext private val context: Context,
	private val registry: ExtensionStoreRegistry,
	private val repository: ExternalExtensionRepoRepository,
	private val extensionLoader: MihonExtensionLoader,
) {
	private val mutex = Mutex()
	private val mutableAllStates = MutableStateFlow<List<ExtensionStoreState>>(emptyList())
	private val mutableCatalogStates = MutableStateFlow<List<ExtensionStoreState>>(emptyList())
	private var initialized = false
	val states: StateFlow<List<ExtensionStoreState>> = mutableCatalogStates.asStateFlow()
	val allStates: StateFlow<List<ExtensionStoreState>> = mutableAllStates.asStateFlow()

	suspend fun initialize(forceRefresh: Boolean = false) = mutex.withLock { withContext(Dispatchers.IO) {
		val migrationPerformed = ensureMigrated()
		if (!initialized || forceRefresh || migrationPerformed) { refreshLocked(shouldForceStoreRefresh(forceRefresh, migrationPerformed)); initialized = true }
	} }
	suspend fun refresh(forceRefresh: Boolean = true) = mutex.withLock { withContext(Dispatchers.IO) {
		val migrationPerformed = ensureMigrated(); refreshLocked(shouldForceStoreRefresh(forceRefresh, migrationPerformed)); initialized = true
	} }
	suspend fun validateAndAdd(indexUrl: String): Result<ExtensionStoreRecord> = validateAndAdd(indexUrl, ExtensionStoreContentType.MANGA)
	suspend fun validateAndAdd(indexUrl: String, contentType: ExtensionStoreContentType): Result<ExtensionStoreRecord> = mutex.withLock { withContext(Dispatchers.IO) { runCatching {
		val validated = repository.validateStore(indexUrl); validateExtensionStoreContentType(validated.catalog, contentType)
		val added = validated.store.copy(id = stableExtensionStoreId(validated.store.indexUrl)); registry.add(added, contentType).getOrThrow()
		publishState(ExtensionStoreState(added, StoreHealth.AVAILABLE, validated.catalog.forContentType(contentType), contentType = contentType)); added
	} } }
	suspend fun editStore(storeId: String, indexUrl: String): Result<ExtensionStoreRecord> = editStore(storeId, indexUrl, registry.contentType(storeId))
	suspend fun editStore(storeId: String, indexUrl: String, contentType: ExtensionStoreContentType): Result<ExtensionStoreRecord> = mutex.withLock { withContext(Dispatchers.IO) { runCatching {
		val current = registry.findStore(storeId) ?: error("Store not found"); val validated = repository.validateStore(indexUrl); validateExtensionStoreContentType(validated.catalog, contentType)
		val replacement = registry.edit(current.id, validated.store, contentType).getOrThrow(); publishState(ExtensionStoreState(replacement, StoreHealth.AVAILABLE, validated.catalog.forContentType(contentType), contentType = contentType)); replacement
	} } }
	fun removeStore(storeId: String) { registry.removeStore(storeId); syncRecords() }
	fun moveStore(fromIndex: Int, toIndex: Int) { val items = mutableAllStates.value; val from = items.getOrNull(fromIndex) ?: return; val to = items.getOrNull(toIndex) ?: return; if (from.contentType != to.contentType) return; registry.move(fromIndex, toIndex); syncRecords() }
	fun stores() = registry.state.stores
	fun containsStoreUrl(indexUrl: String) = registry.containsStoreUrl(indexUrl)
	fun contentType(storeId: String) = registry.contentType(storeId)
	fun state(storeId: String) = mutableAllStates.value.firstOrNull { it.store.id == storeId }
	fun owner(mode: ExtensionInstallMode, extension: MihonExtensionInfo) = registry.owner(mode, extension.pkgName, extension.signatures)
	fun owner(mode: ExtensionInstallMode, packageName: String) = registry.owner(mode, packageName)
	fun setOwner(mode: ExtensionInstallMode, packageName: String, storeId: String) = registry.setOwner(mode, packageName, storeId)
	fun removeOwner(mode: ExtensionInstallMode, packageName: String) { registry.removeOwner(mode, packageName); syncRecords() }
	private fun ensureMigrated(): Boolean { val system = extensionLoader.getInstalledExtensions(context, false).mapTo(HashSet()) { it.pkgName }; val sandbox = extensionLoader.getInstalledExtensions(context, true).mapTo(HashSet()) { it.pkgName }; val migrated = registry.ensureMigrated(system, sandbox); registry.reconcileOwnerships(system, sandbox); return migrated }

	private suspend fun refreshLocked(forceRefresh: Boolean) {
		val previousById = mutableAllStates.value.associateBy { it.store.id }; val stores = registry.state.stores
		val cachedById = coroutineScope { stores.map { store -> async(Dispatchers.IO) { store.id to runCatchingCancellable { repository.getCachedExtensions(store.indexUrl) }.getOrDefault(emptyList()) } }.awaitAll().toMap() }
		setStates(stores.map { store -> val type = registry.contentType(store.id); val previous = previousById[store.id]; ExtensionStoreState(store, StoreHealth.CHECKING, previous?.catalog?.takeIf { it.isNotEmpty() } ?: cachedById[store.id].orEmpty().forContentType(type), contentType = type) })
		val dispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLEL_STORE_REFRESH)
		val results = coroutineScope { stores.map { store -> async(dispatcher) { store to runCatchingCancellable { repository.validateStore(store.indexUrl, forceRefresh) } } }.awaitAll() }
		val refreshed = results.map { (store, fresh) ->
			val type = registry.contentType(store.id); val previous = previousById[store.id]
			val fallback = if (fresh.isFailure) runCatching { repository.getCachedExtensions(store.indexUrl) }.getOrNull()?.let { ExtensionStoreState(store, StoreHealth.AVAILABLE, it.forContentType(type), contentType = type) } ?: previous else previous
			val checked = fresh.mapCatching { validated -> validateExtensionStoreContentType(validated.catalog, type); validated }
			val safePrevious = if (checked.isFailure && fresh.isSuccess) previous else fallback
			checked.fold(onSuccess = { validated -> val updated = validated.store.copy(id = store.id); registry.replace(updated); ExtensionStoreState(updated, StoreHealth.AVAILABLE, validated.catalog.forContentType(type), contentType = type) }, onFailure = { error -> storeStateAfterRefresh(store, safePrevious, Result.failure(error), type) })
		}; setStates(refreshed)
	}
	private fun publishState(state: ExtensionStoreState) { val byId = mutableAllStates.value.associateByTo(LinkedHashMap()) { it.store.id }; byId[state.store.id] = state; setStates(registry.state.stores.mapNotNull { byId[it.id] }) }
	private fun syncRecords() { val previous = mutableAllStates.value.associateBy { it.store.id }; setStates(registry.state.stores.map { record -> val type = registry.contentType(record.id); previous[record.id]?.copy(store = record, catalog = previous[record.id]?.catalog.orEmpty().forContentType(type), contentType = type) ?: ExtensionStoreState(record, StoreHealth.CHECKING, contentType = type) }) }
	private fun setStates(value: List<ExtensionStoreState>) { mutableAllStates.value = value; mutableCatalogStates.value = value.filter { it.contentType != ExtensionStoreContentType.ANIME } }
}

private fun ExternalExtensionRepoEntry.explicitContentType(): ExtensionStoreContentType? = when {
	packageName.contains(".animeextension.", ignoreCase = true) -> ExtensionStoreContentType.ANIME
	isNovelExtension -> ExtensionStoreContentType.NOVEL
	packageName.contains(".extension.", ignoreCase = true) -> ExtensionStoreContentType.MANGA
	else -> null
}
internal fun List<ExternalExtensionRepoEntry>.forContentType(contentType: ExtensionStoreContentType): List<ExternalExtensionRepoEntry> = filter { it.explicitContentType().let { explicit -> explicit == null || explicit == contentType } }
fun storeStateAfterRefresh(store: ExtensionStoreRecord, previous: ExtensionStoreState?, result: Result<List<ExternalExtensionRepoEntry>>, contentType: ExtensionStoreContentType = ExtensionStoreContentType.MANGA): ExtensionStoreState = if (result.isSuccess) ExtensionStoreState(store, StoreHealth.AVAILABLE, result.getOrThrow().forContentType(contentType), contentType = contentType) else ExtensionStoreState(store, StoreHealth.UNAVAILABLE, previous?.catalog.orEmpty().forContentType(contentType), result.exceptionOrNull(), contentType)
fun shouldForceStoreRefresh(forceRefresh: Boolean, migrationPerformed: Boolean) = forceRefresh || migrationPerformed
fun extensionStoreDisplayLabels(stores: List<ExtensionStoreRecord>): Map<String, String> { val duplicates = stores.groupingBy { it.displayName.lowercase(Locale.ROOT) }.eachCount(); return stores.associate { store -> val label = if (duplicates.getValue(store.displayName.lowercase(Locale.ROOT)) > 1) runCatching { URI(store.indexUrl).host }.getOrNull()?.let { "${store.displayName} · $it" } ?: store.displayName else store.displayName; store.id to label } }
