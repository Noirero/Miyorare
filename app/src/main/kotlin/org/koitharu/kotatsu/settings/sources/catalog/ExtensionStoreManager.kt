package org.koitharu.kotatsu.settings.sources.catalog

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.mihon.model.MihonExtensionInfo
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

enum class StoreHealth {
	CHECKING,
	AVAILABLE,
	UNAVAILABLE,
}

data class ExtensionStoreState(
	val store: ExtensionStoreRecord,
	val health: StoreHealth,
	val catalog: List<ExternalExtensionRepoEntry> = emptyList(),
	val error: Throwable? = null,
	val contentType: ExtensionStoreContentType = ExtensionStoreContentType.MANGA,
)

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

	/** Manga/Novel stores consumed by the extension catalogue and updater. Anime is isolated. */
	val states: StateFlow<List<ExtensionStoreState>> = mutableCatalogStates.asStateFlow()

	/** Every configured store, including optional Anime stores, for Manage stores. */
	val allStates: StateFlow<List<ExtensionStoreState>> = mutableAllStates.asStateFlow()

	suspend fun initialize(forceRefresh: Boolean = false) = mutex.withLock {
		withContext(Dispatchers.IO) {
			val migrationPerformed = ensureMigrated()
			if (!initialized || forceRefresh || migrationPerformed) {
				refreshLocked(shouldForceStoreRefresh(forceRefresh, migrationPerformed))
				initialized = true
			}
		}
	}

	suspend fun refresh(forceRefresh: Boolean = true) = mutex.withLock {
		withContext(Dispatchers.IO) {
			val migrationPerformed = ensureMigrated()
			refreshLocked(shouldForceStoreRefresh(forceRefresh, migrationPerformed))
			initialized = true
		}
	}

	/** Legacy callers keep Manga as their old default; the Manage stores UI always passes a type. */
	suspend fun validateAndAdd(indexUrl: String): Result<ExtensionStoreRecord> =
		validateAndAdd(indexUrl, ExtensionStoreContentType.MANGA)

	suspend fun validateAndAdd(
		indexUrl: String,
		contentType: ExtensionStoreContentType,
	): Result<ExtensionStoreRecord> = mutex.withLock {
		withContext(Dispatchers.IO) {
			runCatching {
				val validated = repository.validateStore(indexUrl)
				val added = validated.store.copy(id = stableExtensionStoreId(validated.store.indexUrl))
				registry.add(added, contentType).getOrThrow()
				publishState(
					ExtensionStoreState(
						store = added,
						health = StoreHealth.AVAILABLE,
						catalog = validated.catalog.forContentType(contentType),
						contentType = contentType,
					),
				)
				added
			}
		}
	}

	/** Editing through an old caller keeps the repository's existing explicit media family. */
	suspend fun editStore(storeId: String, indexUrl: String): Result<ExtensionStoreRecord> =
		editStore(storeId, indexUrl, registry.contentType(storeId))

	suspend fun editStore(
		storeId: String,
		indexUrl: String,
		contentType: ExtensionStoreContentType,
	): Result<ExtensionStoreRecord> = mutex.withLock {
		withContext(Dispatchers.IO) {
			runCatching {
				val current = registry.findStore(storeId) ?: error("Store not found")
				val validated = repository.validateStore(indexUrl)
				val replacement = registry.edit(current.id, validated.store, contentType).getOrThrow()
				publishState(
					ExtensionStoreState(
						store = replacement,
						health = StoreHealth.AVAILABLE,
						catalog = validated.catalog.forContentType(contentType),
						contentType = contentType,
					),
				)
				replacement
			}
		}
	}

	fun removeStore(storeId: String) {
		registry.removeStore(storeId)
		syncRecords()
	}

	fun moveStore(fromIndex: Int, toIndex: Int) {
		val items = mutableAllStates.value
		val from = items.getOrNull(fromIndex) ?: return
		val to = items.getOrNull(toIndex) ?: return
		// Section boundaries are intentional. Reordering is allowed inside a section only.
		if (from.contentType != to.contentType) return
		registry.move(fromIndex, toIndex)
		syncRecords()
	}

	fun stores(): List<ExtensionStoreRecord> = registry.state.stores

	fun containsStoreUrl(indexUrl: String): Boolean = registry.containsStoreUrl(indexUrl)

	fun contentType(storeId: String): ExtensionStoreContentType = registry.contentType(storeId)

	fun state(storeId: String): ExtensionStoreState? =
		mutableAllStates.value.firstOrNull { it.store.id == storeId }

	fun owner(
		mode: ExtensionInstallMode,
		extension: MihonExtensionInfo,
	): ExtensionStoreRecord? = registry.owner(mode, extension.pkgName, extension.signatures)

	fun owner(mode: ExtensionInstallMode, packageName: String): ExtensionStoreRecord? =
		registry.owner(mode, packageName)

	fun setOwner(mode: ExtensionInstallMode, packageName: String, storeId: String) =
		registry.setOwner(mode, packageName, storeId)

	fun removeOwner(mode: ExtensionInstallMode, packageName: String) {
		registry.removeOwner(mode, packageName)
		syncRecords()
	}

	private fun ensureMigrated(): Boolean {
		val systemPackages = extensionLoader.getInstalledExtensions(context, privateMode = false)
			.mapTo(HashSet()) { it.pkgName }
		val sandboxPackages = extensionLoader.getInstalledExtensions(context, privateMode = true)
			.mapTo(HashSet()) { it.pkgName }
		val migrated = registry.ensureMigrated(systemPackages, sandboxPackages)
		registry.reconcileOwnerships(systemPackages, sandboxPackages)
		return migrated
	}

	private suspend fun refreshLocked(forceRefresh: Boolean) {
		val previousById = mutableAllStates.value.associateBy { it.store.id }
		setStates(
			registry.state.stores.map { store ->
				val contentType = registry.contentType(store.id)
				previousById[store.id]?.copy(
					store = store,
					health = StoreHealth.CHECKING,
					contentType = contentType,
					error = null,
				) ?: ExtensionStoreState(store, StoreHealth.CHECKING, contentType = contentType)
			},
		)
		val refreshed = registry.state.stores.map { store ->
			val contentType = registry.contentType(store.id)
			val previous = previousById[store.id]
			val fresh = runCatching { repository.validateStore(store.indexUrl, forceRefresh) }
			val fallbackPrevious = if (fresh.isFailure) {
				val cached = runCatching { repository.getCachedExtensions(store.indexUrl) }.getOrNull()
				if (cached != null) {
					ExtensionStoreState(
						store = store,
						health = StoreHealth.AVAILABLE,
						catalog = cached.forContentType(contentType),
						contentType = contentType,
					)
				} else {
					previous
				}
			} else {
				previous
			}
			fresh.fold(
				onSuccess = { validated ->
					// Network metadata can change, but the user's Manga/Novel/Anime assignment cannot.
					val refreshedStore = validated.store.copy(id = store.id)
					registry.replace(refreshedStore)
					ExtensionStoreState(
						store = refreshedStore,
						health = StoreHealth.AVAILABLE,
						catalog = validated.catalog.forContentType(contentType),
						contentType = contentType,
					)
				},
				onFailure = { error ->
					storeStateAfterRefresh(
						store = store,
						previous = fallbackPrevious,
						result = Result.failure(error),
						contentType = contentType,
					)
				},
			)
		}
		setStates(refreshed)
	}

	private fun publishState(state: ExtensionStoreState) {
		val byId = mutableAllStates.value.associateByTo(LinkedHashMap()) { it.store.id }
		byId[state.store.id] = state
		val order = registry.state.stores.map { it.id }
		setStates(order.mapNotNull(byId::get))
	}

	private fun syncRecords() {
		val previous = mutableAllStates.value.associateBy { it.store.id }
		setStates(
			registry.state.stores.map { record ->
				val contentType = registry.contentType(record.id)
				previous[record.id]?.copy(
					store = record,
					health = previous[record.id]?.health ?: StoreHealth.CHECKING,
					catalog = previous[record.id]?.catalog.orEmpty().forContentType(contentType),
					contentType = contentType,
				) ?: ExtensionStoreState(
					store = record,
					health = StoreHealth.CHECKING,
					contentType = contentType,
				)
			},
		)
	}

	private fun setStates(value: List<ExtensionStoreState>) {
		mutableAllStates.value = value
		mutableCatalogStates.value = value.filter { it.contentType != ExtensionStoreContentType.ANIME }
	}
}

private fun List<ExternalExtensionRepoEntry>.forContentType(
	contentType: ExtensionStoreContentType,
): List<ExternalExtensionRepoEntry> = when (contentType) {
	ExtensionStoreContentType.MANGA -> filterNot { it.isNovelExtension }
	ExtensionStoreContentType.NOVEL -> filter { it.isNovelExtension }
	// Anime extensions use a Mihon/Aniyomi-like package shape and have no reliable manga/novel flag.
	// They stay visible in Manage stores but are excluded from the Manga/Novel catalogue as a whole.
	ExtensionStoreContentType.ANIME -> this
}

/** Preserves the old three-argument helper contract while allowing typed callers. */
fun storeStateAfterRefresh(
	store: ExtensionStoreRecord,
	previous: ExtensionStoreState?,
	result: Result<List<ExternalExtensionRepoEntry>>,
	contentType: ExtensionStoreContentType = ExtensionStoreContentType.MANGA,
): ExtensionStoreState = when {
	result.isSuccess -> ExtensionStoreState(
		store = store,
		health = StoreHealth.AVAILABLE,
		catalog = result.getOrThrow().forContentType(contentType),
		contentType = contentType,
	)
	else -> ExtensionStoreState(
		store = store,
		health = StoreHealth.UNAVAILABLE,
		catalog = previous?.catalog.orEmpty().forContentType(contentType),
		error = result.exceptionOrNull(),
		contentType = contentType,
	)
}

fun shouldForceStoreRefresh(forceRefresh: Boolean, migrationPerformed: Boolean): Boolean =
	forceRefresh || migrationPerformed

fun extensionStoreDisplayLabels(stores: List<ExtensionStoreRecord>): Map<String, String> {
	val duplicateNames = stores.groupingBy { it.displayName.lowercase() }.eachCount()
	return stores.associate { store ->
		val label = if (duplicateNames.getValue(store.displayName.lowercase()) > 1) {
			val host = runCatching { URI(store.indexUrl).host }.getOrNull()
			host?.let { "${store.displayName} · $it" } ?: store.displayName
		} else {
			store.displayName
		}
		store.id to label
	}
}
