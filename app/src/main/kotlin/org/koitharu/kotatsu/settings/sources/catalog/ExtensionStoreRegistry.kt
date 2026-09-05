package org.koitharu.kotatsu.settings.sources.catalog

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.prefs.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtensionStoreRegistry @Inject constructor(
	@ApplicationContext context: Context,
	private val settings: AppSettings,
) {

	private val lock = Any()
	private val typePrefs = context.getSharedPreferences(STORE_TYPE_PREFS, Context.MODE_PRIVATE)

	val state: ExtensionStoreRegistryState
		get() = settings.extensionStoreRegistryState

	/**
	 * Migrates old registry data and seeds the built-in Manga/Novel repositories exactly once.
	 * Deleted built-ins stay deleted because the seed version is persisted outside the store list.
	 */
	fun ensureMigrated(systemPackages: Set<String>, sandboxPackages: Set<String>): Boolean {
		synchronized(lock) {
			var changed = false
			var current = settings.extensionStoreRegistryState

			if (!settings.isExtensionStoreMigrationComplete) {
				if (current.stores.isEmpty()) {
					current = migrateLegacyExtensionStores(
						activeUrl = settings.externalExtensionsRepoUrl,
						legacyOwners = settings.getExtensionRepoUrls(),
						systemPackages = systemPackages,
						sandboxPackages = sandboxPackages,
						repoInfos = settings.externalRepoInfos,
					)
				}
				settings.isExtensionStoreMigrationComplete = true
				changed = true
			}

			// Old installs have no explicit type map. Assign known legacy novel/anime repos once; every
			// unknown legacy repo intentionally falls back to Manga rather than inspecting its contents.
			current.stores.forEach { store ->
				if (!typePrefs.contains(typeKey(store.id))) {
					setContentTypeLocked(store.id, legacyExtensionStoreContentType(store.indexUrl))
					changed = true
				}
			}

			val seedVersion = typePrefs.getInt(KEY_DEFAULT_STORE_VERSION, 0)
			if (seedVersion < DEFAULT_EXTENSION_STORES_VERSION) {
				for (builtIn in DEFAULT_EXTENSION_STORES) {
					val existing = current.stores.firstOrNull { sameRepository(it.indexUrl, builtIn.indexUrl) }
					if (existing != null) {
						setContentTypeLocked(existing.id, builtIn.contentType)
						continue
					}
					val normalizedUrl = normalizeExtensionStoreUrl(builtIn.indexUrl)
					val store = ExtensionStoreRecord(
						id = stableExtensionStoreId(normalizedUrl),
						indexUrl = normalizedUrl,
						name = builtIn.name,
						shortName = builtIn.shortName,
					)
					current = current.add(store).getOrElse { current }
					if (current.stores.any { it.id == store.id }) {
						setContentTypeLocked(store.id, builtIn.contentType)
					}
				}
				typePrefs.edit { putInt(KEY_DEFAULT_STORE_VERSION, DEFAULT_EXTENSION_STORES_VERSION) }
				changed = true
			}

			val ordered = orderStoresByContentType(current)
			if (ordered != current) changed = true
			settings.extensionStoreRegistryState = ordered
			return changed
		}
	}

	fun add(store: ExtensionStoreRecord, contentType: ExtensionStoreContentType): Result<Unit> = synchronized(lock) {
		state.add(store).map { updated ->
			setContentTypeLocked(store.id, contentType)
			settings.extensionStoreRegistryState = orderStoresByContentType(updated)
		}
	}

	/** Kept for backup imports; old backups do not contain our explicit media-family field. */
	fun importStores(stores: Iterable<ExtensionStoreRecord>) = synchronized(lock) {
		var updated = state
		for (store in stores) {
			val before = updated
			updated = updated.add(store).getOrElse { updated }
			if (updated !== before && updated.stores.any { it.id == store.id }) {
				setContentTypeLocked(store.id, legacyExtensionStoreContentType(store.indexUrl))
			}
		}
		settings.extensionStoreRegistryState = orderStoresByContentType(updated)
		settings.isExtensionStoreMigrationComplete = true
	}

	fun replace(store: ExtensionStoreRecord) = update { orderStoresByContentType(it.replace(store)) }

	fun edit(
		storeId: String,
		replacement: ExtensionStoreRecord,
		contentType: ExtensionStoreContentType,
	): Result<ExtensionStoreRecord> = synchronized(lock) {
		state.editStore(storeId, replacement).map { updated ->
			setContentTypeLocked(storeId, contentType)
			val ordered = orderStoresByContentType(updated)
			settings.extensionStoreRegistryState = ordered
			ordered.stores.first { it.id == storeId }
		}
	}

	fun removeStore(storeId: String) = synchronized(lock) {
		typePrefs.edit { remove(typeKey(storeId)) }
		settings.extensionStoreRegistryState = state.removeStore(storeId)
	}

	fun move(fromIndex: Int, toIndex: Int) = update { it.move(fromIndex, toIndex) }

	fun setOwner(mode: ExtensionInstallMode, packageName: String, storeId: String) =
		update { it.setOwner(mode, packageName, storeId) }

	fun removeOwner(mode: ExtensionInstallMode, packageName: String) =
		update { it.removeOwner(mode, packageName).cleanupDisabledStores() }

	fun reconcileOwnerships(systemPackages: Set<String>, sandboxPackages: Set<String>) =
		update { it.reconcileOwnerships(systemPackages, sandboxPackages) }

	fun findStore(storeId: String): ExtensionStoreRecord? = state.stores.firstOrNull { it.id == storeId }

	fun containsStoreUrl(indexUrl: String): Boolean = state.containsStoreUrl(indexUrl)

	fun contentType(storeId: String): ExtensionStoreContentType = synchronized(lock) {
		val raw = typePrefs.getString(typeKey(storeId), null)
		runCatching { raw?.let(ExtensionStoreContentType::valueOf) }.getOrNull()
			?: state.stores.firstOrNull { it.id == storeId }
				?.let { legacyExtensionStoreContentType(it.indexUrl) }
			?: ExtensionStoreContentType.MANGA
	}

	fun owner(
		mode: ExtensionInstallMode,
		packageName: String,
		signatures: Collection<String> = emptyList(),
	): ExtensionStoreRecord? {
		val snapshot = state
		snapshot.ownerId(mode, packageName)
			?.let { ownerId -> snapshot.stores.firstOrNull { it.id == ownerId } }
			?.let { return it }
		if (signatures.isEmpty()) return null
		val matches = snapshot.stores.filter { store ->
			store.fingerprint?.let { fingerprint ->
				signatures.any { it.equals(fingerprint, ignoreCase = true) }
			} == true
		}
		return matches.singleOrNull()?.also { setOwner(mode, packageName, it.id) }
	}

	private fun orderStoresByContentType(input: ExtensionStoreRegistryState): ExtensionStoreRegistryState {
		val originalOrder = input.stores.withIndex().associate { it.value.id to it.index }
		val ordered = input.stores.sortedWith(
			compareBy<ExtensionStoreRecord> { contentTypeUnlocked(it.id).ordinal }
				.thenBy { originalOrder[it.id] ?: Int.MAX_VALUE },
		)
		return if (ordered == input.stores) input else input.copy(stores = ordered)
	}

	private fun contentTypeUnlocked(storeId: String): ExtensionStoreContentType {
		val raw = typePrefs.getString(typeKey(storeId), null)
		return runCatching { raw?.let(ExtensionStoreContentType::valueOf) }.getOrNull()
			?: ExtensionStoreContentType.MANGA
	}

	private fun setContentTypeLocked(storeId: String, contentType: ExtensionStoreContentType) {
		typePrefs.edit { putString(typeKey(storeId), contentType.name) }
	}

	private inline fun update(transform: (ExtensionStoreRegistryState) -> ExtensionStoreRegistryState) {
		synchronized(lock) {
			settings.extensionStoreRegistryState = transform(settings.extensionStoreRegistryState)
		}
	}

	private fun sameRepository(left: String, right: String): Boolean {
		fun repositoryKey(value: String): String? {
			val normalized = normalizeExtensionStoreUrl(value)
			val match = RAW_GITHUB_REPO.find(normalized) ?: return null
			return "${match.groupValues[1].lowercase()}/${match.groupValues[2].lowercase()}"
		}
		return repositoryKey(left)?.let { it == repositoryKey(right) }
			?: normalizeExtensionStoreUrl(left).equals(normalizeExtensionStoreUrl(right), ignoreCase = true)
	}

	private companion object {
		const val STORE_TYPE_PREFS = "extension_store_content_types"
		const val KEY_DEFAULT_STORE_VERSION = "default_store_version"
		val RAW_GITHUB_REPO = Regex("(?:raw\\.githubusercontent\\.com|github\\.com)/([^/]+)/([^/]+)", RegexOption.IGNORE_CASE)
		fun typeKey(storeId: String) = "type_$storeId"
	}
}
