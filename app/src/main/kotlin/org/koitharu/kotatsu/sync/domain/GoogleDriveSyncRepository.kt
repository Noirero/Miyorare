package org.koitharu.kotatsu.sync.domain

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.local.data.model.BackupPrimitive
import org.koitharu.kotatsu.backup.local.data.model.BookmarkBackup
import org.koitharu.kotatsu.backup.local.data.model.MangaBackup
import org.koitharu.kotatsu.backup.local.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backup.local.data.model.SourceSettingsBackup
import org.koitharu.kotatsu.backup.local.data.model.StatsBackup
import org.koitharu.kotatsu.backup.local.domain.CustomCoverCodec
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.data.TapGridSettings
import org.koitharu.kotatsu.sync.data.GoogleDriveApi
import org.koitharu.kotatsu.sync.data.GoogleDriveAuth
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.data.model.SyncCategory
import org.koitharu.kotatsu.sync.data.model.SyncConfig
import org.koitharu.kotatsu.sync.data.model.SyncContent
import org.koitharu.kotatsu.sync.data.model.SyncFavourite
import org.koitharu.kotatsu.sync.data.model.SyncFeedEntry
import org.koitharu.kotatsu.sync.data.model.SyncHistory
import org.koitharu.kotatsu.sync.data.model.SyncMangaPrefs
import org.koitharu.kotatsu.sync.data.model.SyncSnapshot
import org.koitharu.kotatsu.sync.data.model.SyncTrack
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncResult {
	data object Success : SyncResult
	data object SignInRequired : SyncResult

	/** [retryable] is false for errors that won't fix themselves (e.g. a newer remote format). */
	data class Error(val message: String?, val retryable: Boolean = true) : SyncResult
}

/**
 * Orchestrates a full two-way Google Drive sync: pull the remote snapshot, merge it with the local
 * database (per-record, tombstone-aware), apply the merged result locally, then push it back. Row
 * data (favourites/categories/history) propagates deletions via tombstones; the config bundle
 * (settings/reader-grid/source-settings/custom-covers/continuity) is last-writer-wins by revision.
 *
 * "What to sync" gates which sections this device reads & writes. Disabled sections are passed
 * through unchanged from the remote snapshot, so opting out on one device never erases another's data.
 */
@Singleton
class GoogleDriveSyncRepository @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: MangaDatabase,
	private val appSettings: AppSettings,
	private val tapGridSettings: TapGridSettings,
	private val syncSettings: SyncSettings,
	private val auth: GoogleDriveAuth,
	private val api: GoogleDriveApi,
	private val coverCodec: CustomCoverCodec,
	private val crossDeviceContinuity: CrossDeviceContinuity,
) {

	private val json = Json {
		encodeDefaults = true
		ignoreUnknownKeys = true
		allowSpecialFloatingPointValues = true
		coerceInputValues = true
	}

	/** Whether a sync is currently running (so the UI can show progress and avoid overlap). */
	val isSyncing = MutableStateFlow(false)
	private val syncMutex = Mutex()

	/** Records the signed-in account. Email/name/photo come straight from GoogleSignIn — no network call. */
	fun onSignedIn(email: String?, displayName: String?, photoUrl: String?) {
		syncSettings.accountEmail = email?.ifBlank { null } ?: "Google Drive"
		syncSettings.accountName = displayName
		syncSettings.accountPhotoUrl = photoUrl
		Log.i(TAG, "signed in as ${syncSettings.accountEmail}")
	}

	suspend fun sync(): SyncResult {
		if (!syncSettings.isSignedIn) return SyncResult.SignInRequired
		if (!syncMutex.tryLock()) return SyncResult.Success
		isSyncing.value = true
		try {
			var token = auth.requireAccessToken()
			try {
				performSync(token)
			} catch (e: SyncApiException) {
				// A cached token can be stale → 401. Refresh once and retry.
				if (e.code == 401) {
					Log.w(TAG, "token rejected (401), refreshing and retrying")
					auth.invalidateToken(token)
					token = auth.requireAccessToken()
					performSync(token)
				} else {
					throw e
				}
			}
			return SyncResult.Success
		} catch (e: SyncSignInRequiredException) {
			Log.w(TAG, "sign-in required", e)
			syncSettings.lastSyncError = context.getString(R.string.sync_sign_in_required)
			return SyncResult.SignInRequired
		} catch (e: SyncSchemaException) {
			Log.e(TAG, "remote schema too new", e)
			syncSettings.lastSyncError = e.message
			return SyncResult.Error(e.message, retryable = false)
		} catch (e: Exception) {
			Log.e(TAG, "sync failed", e)
			syncSettings.lastSyncError = e.message ?: e.javaClass.simpleName
			return SyncResult.Error(e.message ?: e.javaClass.simpleName)
		} finally {
			isSyncing.value = false
			syncMutex.unlock()
		}
	}

	private suspend fun performSync(token: String) {
		val enabled = SyncContent.fromKeys(syncSettings.enabledContent)
		val now = System.currentTimeMillis()
		Log.i(TAG, "sync start: enabled=$enabled")

		var attempt = 0
		while (true) {
			val files = api.findSyncFiles(token)
			val canonical = files.firstOrNull()
			val baseVersion = canonical?.version

			val remotes = ArrayList<SyncSnapshot>(files.size)
			val decodedIds = HashSet<String>(files.size)
			for (file in files) {
				val bytes = api.download(token, file.id)
				val snapshot = decodeSnapshot(bytes)
				if (snapshot != null) {
					remotes += snapshot
					decodedIds += file.id
				}
			}

			val combinedRemote = SyncMerger.combine(remotes)
			val privateOnlyIds = privateOnlyMangaIds(protectedOnly = true)
			val continuityPrivateOnlyIds = privateOnlyMangaIds(protectedOnly = false)
			val scrubbedRemote = combinedRemote?.scrubPrivateOnly(privateOnlyIds, continuityPrivateOnlyIds)
			val privacyScrubbed = combinedRemote != null && scrubbedRemote !== combinedRemote

			val remote = scrubbedRemote?.let { snapshot ->
				if (SyncContent.FAVOURITES in enabled) remapRemoteCategories(snapshot) else snapshot
			}
			Log.i(
				TAG,
				"remote: files=${files.size} readable=${remotes.size} fav=${remote?.favourites?.size} " +
					"hist=${remote?.history?.size} cat=${remote?.categories?.size} privacyScrubbed=$privacyScrubbed",
			)

			val configResult = buildMergedConfig(remote?.config, enabled, now)
			val merged = buildMergedSnapshot(remote, configResult.config, now)
			Log.i(
				TAG,
				"merged: fav=${merged.favourites.size} hist=${merged.history.size} cat=${merged.categories.size} " +
					"feed=${merged.feed.size} cfgRev=${merged.config?.revision} remoteCfgWon=${configResult.remoteWon}",
			)
			applyToDatabase(merged, configResult.remoteWon, enabled)

			val upload = pruneTombstones(merged, now)
			val unchanged = !privacyScrubbed && files.size == 1 && remote != null &&
				normalizedJson(upload) == normalizedJson(remote)
			if (unchanged) {
				Log.i(TAG, "no changes to push; skipping upload")
			} else {
				if (canonical != null && baseVersion != null && attempt < MAX_CONFLICT_RETRIES) {
					val current = api.getFileVersion(token, canonical.id)
					if (current != null && current != baseVersion) {
						Log.w(TAG, "remote changed during sync (v$baseVersion → v$current); retrying merge")
						attempt++
						continue
					}
				}
				val payload = json.encodeToString(SyncSnapshot.serializer(), upload).encodeToByteArray()
				val fileId = api.upload(token, payload, canonical?.id)
				Log.i(TAG, "uploaded ${payload.size} bytes to $fileId")
				for (file in files) {
					if (file.id != fileId && file.id in decodedIds) {
						runCatchingCancellable { api.delete(token, file.id) }
							.onSuccess { Log.i(TAG, "removed duplicate sync file ${file.id}") }
							.onFailure { Log.w(TAG, "failed to remove duplicate ${file.id}", it) }
					}
				}
			}

			gcOldTombstones(now)
			syncSettings.lastSyncTimestamp = now
			syncSettings.lastSyncError = null
			syncSettings.configRevision = configResult.config.revision
			syncSettings.configHash = configContentHash(dumpLocalConfig(enabled))
			syncSettings.lastSyncedFeedIds = merged.feed.mapTo(HashSet(merged.feed.size)) { SyncMerger.feedIdentity(it) }
			return
		}
	}

	suspend fun deleteRemoteData(): SyncResult = try {
		val token = auth.requireAccessToken()
		for (file in api.findSyncFiles(token)) {
			runCatchingCancellable { api.delete(token, file.id) }
		}
		syncSettings.lastSyncTimestamp = 0L
		syncSettings.configRevision = 0L
		syncSettings.configHash = null
		SyncResult.Success
	} catch (e: SyncSignInRequiredException) {
		SyncResult.SignInRequired
	} catch (e: Exception) {
		SyncResult.Error(e.message)
	}

	private fun decodeSnapshot(bytes: ByteArray): SyncSnapshot? {
		val text = bytes.decodeToString()
		if (text.isBlank()) return null
		val version = runCatching {
			json.decodeFromString(SchemaProbe.serializer(), text).schemaVersion
		}.getOrNull()
		if (version != null && version > SyncSnapshot.SCHEMA_VERSION) {
			throw SyncSchemaException(version)
		}
		return try {
			json.decodeFromString(SyncSnapshot.serializer(), text)
		} catch (e: Exception) {
			Log.w(TAG, "remote file unreadable (${e.message}); will overwrite with local data")
			null
		}
	}

	private fun normalizedJson(snapshot: SyncSnapshot): String = json.encodeToString(
		SyncSnapshot.serializer(),
		SyncSnapshot(
			schemaVersion = snapshot.schemaVersion,
			deviceId = "",
			syncedAt = 0L,
			categories = snapshot.categories,
			favourites = snapshot.favourites,
			history = snapshot.history,
			bookmarks = snapshot.bookmarks,
			scrobblings = snapshot.scrobblings,
			tracks = snapshot.tracks,
			feed = snapshot.feed,
			stats = snapshot.stats,
			config = snapshot.config,
		),
	)

	private fun pruneTombstones(snapshot: SyncSnapshot, now: Long): SyncSnapshot {
		val cutoff = now - TOMBSTONE_TTL_MS
		val categories = snapshot.categories.filter { it.deletedAt == 0L || it.deletedAt >= cutoff }
		val favourites = snapshot.favourites.filter { it.deletedAt == 0L || it.deletedAt >= cutoff }
		val history = snapshot.history.filter { it.deletedAt == 0L || it.deletedAt >= cutoff }
		if (categories.size == snapshot.categories.size &&
			favourites.size == snapshot.favourites.size &&
			history.size == snapshot.history.size
		) {
			return snapshot
		}
		Log.i(TAG, "pruned tombstones older than ${TOMBSTONE_TTL_MS}ms from upload")
		return SyncSnapshot(
			schemaVersion = snapshot.schemaVersion,
			deviceId = snapshot.deviceId,
			syncedAt = snapshot.syncedAt,
			categories = categories,
			favourites = favourites,
			history = history,
			bookmarks = snapshot.bookmarks,
			scrobblings = snapshot.scrobblings,
			tracks = snapshot.tracks,
			feed = snapshot.feed,
			stats = snapshot.stats,
			config = snapshot.config,
		)
	}

	private suspend fun gcOldTombstones(now: Long) {
		if (syncSettings.isDeletionSyncDisabled) return
		val cutoff = now - TOMBSTONE_TTL_MS
		runCatchingCancellable {
			database.getFavouritesDao().gc(cutoff)
			database.getFavouriteCategoriesDao().gc(cutoff)
			database.getHistoryDao().gc(cutoff)
		}.onFailure { Log.w(TAG, "local tombstone gc failed", it) }
	}

	suspend fun signOut() {
		auth.signOut()
		syncSettings.clearAccount()
	}

	private suspend fun remapRemoteCategories(remote: SyncSnapshot): SyncSnapshot {
		val (categories, favourites) = SyncMerger.remapRemoteCategories(
			remoteCategories = remote.categories,
			remoteFavourites = remote.favourites,
			localCategories = localCategories(),
		)
		return if (categories === remote.categories && favourites === remote.favourites) {
			remote
		} else {
			remote.copy(categories = categories, favourites = favourites)
		}
	}

	/**
	 * protectedOnly=true keeps the existing app-wide Private isolation semantics for established sync
	 * sections. protectedOnly=false is stricter and is used for Continuity: a title that exists only in
	 * Private never exports Notes/Profile, even if the user temporarily disabled Private UI isolation.
	 */
	private suspend fun privateOnlyMangaIds(protectedOnly: Boolean): Set<Long> {
		val privateIds = if (protectedOnly) {
			database.getPrivateFavouritesDao().findActiveMangaIds().toMutableSet()
		} else {
			database.getPrivateFavouritesDao().findAllActiveMangaIds().toMutableSet()
		}
		if (privateIds.isEmpty()) return emptySet()
		val normalIds = privateIds.chunked(DB_QUERY_BATCH_SIZE)
			.flatMap { database.getFavouritesDao().findMemberships(it) }
			.mapTo(HashSet()) { it.mangaId }
		privateIds.removeAll(normalIds)
		return privateIds
	}

	private fun SyncSnapshot.scrubPrivateOnly(
		privateOnlyIds: Set<Long>,
		continuityPrivateOnlyIds: Set<Long>,
	): SyncSnapshot {
		if (privateOnlyIds.isEmpty() && continuityPrivateOnlyIds.isEmpty()) return this
		val favourites = favourites.filterNot { it.mangaId in privateOnlyIds }
		val history = history.filterNot { it.mangaId in privateOnlyIds }
		val bookmarks = bookmarks.filterNot { it.manga.id in privateOnlyIds }
		val scrobblings = scrobblings.filterNot { it.mangaId in privateOnlyIds }
		val tracks = tracks.filterNot { it.mangaId in privateOnlyIds }
		val feed = feed.filterNot { it.mangaId in privateOnlyIds }
		val stats = stats.filterNot { it.mangaId in privateOnlyIds }
		val oldConfig = config
		val mangaPrefs = oldConfig?.mangaPrefs?.filterNot { it.mangaId in privateOnlyIds }
		val scrubbedContinuity = crossDeviceContinuity.scrubPayload(
			oldConfig?.continuityPayload,
			continuityPrivateOnlyIds,
		)
		val cleanedSettings = oldConfig?.settings?.filterKeys { it != CrossDeviceContinuity.LEGACY_SETTINGS_KEY }
		val prefsChanged = oldConfig != null && mangaPrefs != null && mangaPrefs.size != oldConfig.mangaPrefs.size
		val continuityChanged = oldConfig?.continuityPayload != scrubbedContinuity
		val settingsChanged = oldConfig != null && cleanedSettings != oldConfig.settings
		val configChanged = oldConfig != null && (prefsChanged || continuityChanged || settingsChanged)
		val newConfig = if (configChanged) {
			SyncConfig(
				revision = oldConfig!!.revision,
				settings = checkNotNull(cleanedSettings),
				readerGrid = oldConfig.readerGrid,
				sourceSettings = oldConfig.sourceSettings,
				mangaPrefs = checkNotNull(mangaPrefs),
				continuityPayload = scrubbedContinuity,
			)
		} else {
			oldConfig
		}
		val changed = favourites.size != this.favourites.size ||
			history.size != this.history.size ||
			bookmarks.size != this.bookmarks.size ||
			scrobblings.size != this.scrobblings.size ||
			tracks.size != this.tracks.size ||
			feed.size != this.feed.size ||
			stats.size != this.stats.size ||
			configChanged
		if (!changed) return this
		Log.i(
			TAG,
			"privacy scrub: hidden=${privateOnlyIds.size} continuityHidden=${continuityPrivateOnlyIds.size} " +
				"fav=${this.favourites.size - favourites.size} hist=${this.history.size - history.size} " +
				"bookmarks=${this.bookmarks.size - bookmarks.size} tracking=${this.scrobblings.size - scrobblings.size} " +
				"tracks=${this.tracks.size - tracks.size} feed=${this.feed.size - feed.size} " +
				"stats=${this.stats.size - stats.size} prefs=${if (prefsChanged) oldConfig!!.mangaPrefs.size - mangaPrefs!!.size else 0}",
		)
		return copy(
			favourites = favourites,
			history = history,
			bookmarks = bookmarks,
			scrobblings = scrobblings,
			tracks = tracks,
			feed = feed,
			stats = stats,
			config = newConfig,
		)
	}

	private suspend fun buildMergedSnapshot(
		remote: SyncSnapshot?,
		config: SyncConfig,
		now: Long,
	): SyncSnapshot {
		val enabled = SyncContent.fromKeys(syncSettings.enabledContent)
		val favEnabled = SyncContent.FAVOURITES in enabled
		val histEnabled = SyncContent.HISTORY in enabled
		val propagateDeletions = !syncSettings.isDeletionSyncDisabled

		val categories = if (favEnabled) {
			SyncMerger.mergeCategories(localCategories(), remote?.categories.orEmpty(), propagateDeletions)
		} else remote?.categories.orEmpty()
		val favourites = if (favEnabled) {
			SyncMerger.mergeFavourites(localFavourites(), remote?.favourites.orEmpty(), propagateDeletions)
		} else remote?.favourites.orEmpty()
		val history = if (histEnabled) {
			SyncMerger.mergeHistory(localHistory(), remote?.history.orEmpty(), propagateDeletions)
		} else remote?.history.orEmpty()
		val bookmarks = if (SyncContent.BOOKMARKS in enabled) {
			SyncMerger.mergeBookmarks(localBookmarks(), remote?.bookmarks.orEmpty())
		} else remote?.bookmarks.orEmpty()
		val scrobblings = if (SyncContent.TRACKING in enabled) {
			SyncMerger.mergeScrobblings(localScrobblings(), remote?.scrobblings.orEmpty())
		} else remote?.scrobblings.orEmpty()
		val tracks = if (SyncContent.FEED in enabled) {
			SyncMerger.mergeTracks(localTracks(), remote?.tracks.orEmpty())
		} else remote?.tracks.orEmpty()
		val feed = if (SyncContent.FEED in enabled) {
			val localFeedList = localFeed()
			val localFeedIds = localFeedList.mapTo(HashSet(localFeedList.size)) { SyncMerger.feedIdentity(it) }
			val deletedHere = syncSettings.lastSyncedFeedIds - localFeedIds
			SyncMerger.mergeFeed(localFeedList, remote?.feed.orEmpty(), deletedHere, propagateDeletions)
		} else remote?.feed.orEmpty()
		val stats = if (SyncContent.STATS in enabled) {
			SyncMerger.mergeStats(localStats(), remote?.stats.orEmpty())
		} else remote?.stats.orEmpty()
		return SyncSnapshot(
			deviceId = syncSettings.deviceId,
			syncedAt = now,
			categories = categories,
			favourites = favourites,
			history = history,
			bookmarks = bookmarks,
			scrobblings = scrobblings,
			tracks = tracks,
			feed = feed,
			stats = stats,
			config = config,
		)
	}

	private class ConfigMergeResult(val config: SyncConfig, val remoteWon: Boolean)

	private suspend fun buildMergedConfig(
		remote: SyncConfig?,
		enabled: Set<SyncContent>,
		now: Long,
	): ConfigMergeResult {
		val settingsEnabled = SyncContent.SETTINGS in enabled
		val coversEnabled = SyncContent.CUSTOM_COVERS in enabled
		val local = dumpLocalConfig(enabled)
		val currentHash = configContentHash(local)
		val hasBaseline = syncSettings.configHash != null
		val localChanged = hasBaseline && (settingsEnabled || coversEnabled) && currentHash != syncSettings.configHash
		val localRevision = if (localChanged) now else syncSettings.configRevision
		val remoteRevision = remote?.revision ?: -1L
		val remoteWon = remote != null && (!localChanged || remoteRevision > localRevision)

		val merged = SyncConfig(
			revision = maxOf(localRevision, remoteRevision, 0L),
			settings = mergeConfigMap(local.settings, remote?.settings, remoteWon)
				.filterKeys { it !in EXCLUDED_SETTINGS_KEYS },
			readerGrid = mergeReaderGrid(local.readerGrid, remote?.readerGrid, remoteWon),
			sourceSettings = mergeConfigList(local.sourceSettings, remote?.sourceSettings, remoteWon) { it.source },
			mangaPrefs = mergeConfigList(local.mangaPrefs, remote?.mangaPrefs, remoteWon) { it.mangaId },
			continuityPayload = mergeWhole(local.continuityPayload, remote?.continuityPayload, remoteWon),
		)
		return ConfigMergeResult(merged, remoteWon)
	}

	private suspend fun dumpLocalConfig(enabled: Set<SyncContent>): SyncConfig {
		val settingsEnabled = SyncContent.SETTINGS in enabled
		val coversEnabled = SyncContent.CUSTOM_COVERS in enabled
		return SyncConfig(
			revision = 0L,
			settings = if (settingsEnabled) dumpAppSettings() else emptyMap(),
			readerGrid = if (settingsEnabled) dumpReaderGrid() else emptyMap(),
			sourceSettings = if (settingsEnabled) dumpSourceSettings() else emptyList(),
			mangaPrefs = if (coversEnabled) dumpMangaPrefs() else emptyList(),
			continuityPayload = if (settingsEnabled) crossDeviceContinuity.exportPayload() else null,
		)
	}

	private fun <V> mergeConfigMap(local: Map<String, V>, remote: Map<String, V>?, remoteWon: Boolean): Map<String, V> {
		if (remote == null) return local
		val out = LinkedHashMap<String, V>(local.size + remote.size)
		if (remoteWon) {
			out.putAll(local)
			out.putAll(remote)
		} else {
			out.putAll(remote)
			out.putAll(local)
		}
		return out
	}

	private fun mergeReaderGrid(
		local: Map<String, BackupPrimitive>,
		remote: Map<String, BackupPrimitive>?,
		remoteWon: Boolean,
	): Map<String, BackupPrimitive> = when {
		remote.isNullOrEmpty() -> local
		local.isEmpty() -> remote
		remoteWon -> remote
		else -> local
	}

	private fun mergeWhole(local: String?, remote: String?, remoteWon: Boolean): String? = when {
		remote == null -> local
		local == null -> remote
		remoteWon -> remote
		else -> local
	}

	private inline fun <T, K> mergeConfigList(
		local: List<T>,
		remote: List<T>?,
		remoteWon: Boolean,
		key: (T) -> K,
	): List<T> {
		if (remote == null) return local
		val out = LinkedHashMap<K, T>(local.size + remote.size)
		val first = if (remoteWon) local else remote
		val second = if (remoteWon) remote else local
		for (item in first) out[key(item)] = item
		for (item in second) out[key(item)] = item
		return out.values.toList()
	}

	private suspend fun applyToDatabase(
		merged: SyncSnapshot,
		remoteConfigWon: Boolean,
		enabled: Set<SyncContent>,
	) {
		if (SyncContent.FAVOURITES in enabled) {
			val locallyDeletedCategories = if (syncSettings.isDeletionSyncDisabled) {
				database.getFavouriteCategoriesDao().findAllForSync()
					.filterTo(HashSet()) { it.deletedAt != 0L }
					.mapTo(HashSet()) { it.categoryId }
			} else emptySet()
			val locallyDeletedFavourites = if (syncSettings.isDeletionSyncDisabled) {
				database.getFavouritesDao().findAllForSync()
					.filter { it.deletedAt != 0L }
					.mapTo(HashSet()) { it.mangaId to it.categoryId }
			} else emptySet()
			database.withTransaction {
				for (category in merged.categories) {
					if (category.categoryId !in locallyDeletedCategories) database.getFavouriteCategoriesDao().upsert(category.toEntity())
				}
				for (favourite in merged.favourites) {
					if ((favourite.mangaId to favourite.categoryId) !in locallyDeletedFavourites) {
						upsertManga(favourite.manga)
						database.getFavouritesDao().upsert(favourite.toEntity())
					}
				}
			}
		}
		if (SyncContent.HISTORY in enabled) {
			val locallyDeletedHistory = if (syncSettings.isDeletionSyncDisabled) {
				database.getHistoryDao().findAllForSync()
					.filterTo(HashSet()) { it.deletedAt != 0L }
					.mapTo(HashSet()) { it.mangaId }
			} else emptySet()
			database.withTransaction {
				for (entry in merged.history) {
					if (entry.mangaId !in locallyDeletedHistory) {
						upsertManga(entry.manga)
						database.getHistoryDao().upsertForSync(entry.toEntity())
					}
				}
			}
		}
		if (SyncContent.BOOKMARKS in enabled) {
			for (group in merged.bookmarks) {
				runCatchingCancellable {
					database.withTransaction {
						upsertManga(group.manga)
						if (group.bookmarks.isNotEmpty()) database.getBookmarksDao().upsert(group.bookmarks.map { it.toEntity() })
					}
				}
			}
		}
		if (SyncContent.FEED in enabled) {
			for (track in merged.tracks) {
				runCatchingCancellable {
					database.withTransaction {
						upsertManga(track.manga)
						database.getTracksDao().upsert(track.toEntity())
					}
				}
			}
			applyFeed(merged.feed)
		}
		if (SyncContent.TRACKING in enabled) {
			for (entry in merged.scrobblings) runCatchingCancellable { database.getScrobblingDao().upsert(entry.toEntity()) }
		}
		if (SyncContent.STATS in enabled) {
			for (entry in merged.stats) runCatchingCancellable { database.getStatsDao().upsert(entry.toEntity()) }
		}
		if (remoteConfigWon) merged.config?.let { applyConfig(it, enabled) }
	}

	private suspend fun applyConfig(config: SyncConfig, enabled: Set<SyncContent>) {
		if (SyncContent.SETTINGS in enabled) {
			val settings = config.settings.toMutableMap()
			EXCLUDED_SETTINGS_KEYS.forEach { settings.remove(it) }
			appSettings.upsertAll(settings.mapValues { it.value.rawValue() })
			tapGridSettings.upsertAll(config.readerGrid.mapValues { it.value.rawValue() })
			applySourceSettings(config.sourceSettings)
			config.continuityPayload?.let { crossDeviceContinuity.applyPayload(it) }
		}
		if (SyncContent.CUSTOM_COVERS in enabled) {
			for (pref in config.mangaPrefs) {
				val currentCover = database.getPreferencesDao().find(pref.mangaId)?.coverUrlOverride
				val resolvedCover = when {
					pref.coverData != null -> coverCodec.materialize(
						mangaId = pref.mangaId,
						coverData = pref.coverData,
						coverFileExtension = pref.coverFileExtension,
						previousUrl = currentCover,
					) ?: currentCover
					coverCodec.isPortableCoverUrl(pref.coverUrlOverride) -> pref.coverUrlOverride
					else -> currentCover
				}
				database.getPreferencesDao().upsert(pref.toEntity(resolvedCover))
			}
		}
	}

	private suspend fun applyFeed(feed: List<SyncFeedEntry>) {
		val dao = database.getTrackLogsDao()
		val localByIdentity = dao.findAllForSync().groupBy { entity ->
			SyncMerger.feedIdentity(entity.mangaId, entity.chapters)
		}.toMutableMap()
		for (entry in feed) {
			runCatchingCancellable {
				database.withTransaction {
					upsertManga(entry.manga)
					val identity = SyncMerger.feedIdentity(entry)
					val matches = localByIdentity.remove(identity).orEmpty()
					val keepId = matches.minOfOrNull { it.id } ?: 0L
					dao.insert(entry.toEntity(keepId))
					for (duplicate in matches) if (duplicate.id != keepId) dao.delete(duplicate.id)
				}
			}
		}
	}

	private suspend fun upsertManga(manga: MangaBackup) {
		val tags = manga.tags.map { it.toEntity() }
		if (tags.isNotEmpty()) database.getTagsDao().upsert(tags)
		database.getMangaDao().upsert(manga.toEntity(), tags)
	}

	private suspend fun applySourceSettings(list: List<SourceSettingsBackup>) {
		val knownSources = database.getSourcesDao().findAll().mapTo(HashSet()) { it.source }
		for (entry in list) {
			if (entry.source !in knownSources) {
				Log.d(TAG, "sync: skipping source settings for '${entry.source}' — source not installed")
				continue
			}
			val prefsName = SourceSettings.getStorageName(entry.source)
			val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
			prefs.edit {
				entry.values.forEach { (key, primitive) ->
					when (primitive) {
						is BackupPrimitive.StringValue -> putString(key, primitive.value)
						is BackupPrimitive.BoolValue -> putBoolean(key, primitive.value)
						is BackupPrimitive.IntValue -> putInt(key, primitive.value)
						is BackupPrimitive.LongValue -> putLong(key, primitive.value)
						is BackupPrimitive.FloatValue -> putFloat(key, primitive.value)
						is BackupPrimitive.StringSetValue -> putStringSet(key, primitive.value)
					}
				}
			}
		}
	}

	private suspend fun localCategories(): List<SyncCategory> =
		database.getFavouriteCategoriesDao().findAllForSync().map(::SyncCategory)

	private suspend fun localFavourites(): List<SyncFavourite> {
		val mangaCache = HashMap<Long, MangaBackup>()
		return database.getFavouritesDao().findAllForSync().mapNotNull { entity ->
			val manga = mangaCache.getOrPut(entity.mangaId) {
				database.getMangaDao().find(entity.mangaId)?.toBackup() ?: run {
					Log.w(TAG, "sync: skipping favourite(mangaId=${entity.mangaId}) — manga row missing")
					return@mapNotNull null
				}
			}
			SyncFavourite(entity, manga)
		}
	}

	private suspend fun localHistory(): List<SyncHistory> {
		val mangaCache = HashMap<Long, MangaBackup>()
		return database.getHistoryDao().findAllForSync().mapNotNull { entity ->
			val manga = mangaCache.getOrPut(entity.mangaId) {
				database.getMangaDao().find(entity.mangaId)?.toBackup() ?: run {
					Log.w(TAG, "sync: skipping history(mangaId=${entity.mangaId}) — manga row missing")
					return@mapNotNull null
				}
			}
			SyncHistory(entity, manga)
		}
	}

	private suspend fun localBookmarks(): List<BookmarkBackup> =
		database.getBookmarksDao().dump().toList().map { (manga, items) -> BookmarkBackup(manga, items) }

	private suspend fun localScrobblings(): List<ScrobblingBackup> =
		database.getScrobblingDao().dumpEnabled().toList().map(::ScrobblingBackup)

	private suspend fun localStats(): List<StatsBackup> =
		database.getStatsDao().dumpEnabled().toList().map(::StatsBackup)

	private suspend fun localTracks(): List<SyncTrack> {
		val mangaCache = HashMap<Long, MangaBackup>()
		return database.getTracksDao().findAllForSync().mapNotNull { entity ->
			val manga = mangaCache.getOrPut(entity.mangaId) {
				database.getMangaDao().find(entity.mangaId)?.toBackup() ?: run {
					Log.w(TAG, "sync: skipping track(mangaId=${entity.mangaId}) — manga row missing")
					return@mapNotNull null
				}
			}
			SyncTrack(entity, manga)
		}
	}

	private suspend fun localFeed(): List<SyncFeedEntry> {
		val mangaCache = HashMap<Long, MangaBackup>()
		return database.getTrackLogsDao().findAllForSync().mapNotNull { entity ->
			val manga = mangaCache.getOrPut(entity.mangaId) {
				database.getMangaDao().find(entity.mangaId)?.toBackup() ?: run {
					Log.w(TAG, "sync: skipping feed entry(id=${entity.id}) — manga row missing")
					return@mapNotNull null
				}
			}
			SyncFeedEntry(entity, manga)
		}
	}

	private fun MangaWithTags.toBackup(): MangaBackup = MangaBackup(this)

	private fun dumpAppSettings(): Map<String, BackupPrimitive> {
		val map = appSettings.getAllValues().toMutableMap()
		EXCLUDED_SETTINGS_KEYS.forEach { map.remove(it) }
		return map.toSortedMap().mapNotNullValuesToBackup()
	}

	private fun dumpReaderGrid(): Map<String, BackupPrimitive> =
		tapGridSettings.getAllValues().toSortedMap().mapNotNullValuesToBackup()

	private suspend fun dumpSourceSettings(): List<SourceSettingsBackup> {
		val sources = database.getSourcesDao().findAll()
		val result = ArrayList<SourceSettingsBackup>(sources.size)
		for (source in sources.sortedBy { it.source }) {
			val prefsName = SourceSettings.getStorageName(source.source)
			val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
			val values = prefs.all.toSortedMap().mapNotNullValuesToBackup()
			if (values.isNotEmpty()) result += SourceSettingsBackup(source = source.source, values = values)
		}
		return result
	}

	private suspend fun dumpMangaPrefs(): List<SyncMangaPrefs> =
		database.getPreferencesDao().getOverrides().sortedBy { it.mangaId }.map { entity ->
			val cover = coverCodec.read(entity.coverUrlOverride)
			SyncMangaPrefs(
				entity = entity,
				coverData = cover?.data,
				coverFileExtension = cover?.extension,
			)
		}

	private fun Map<String, *>.mapNotNullValuesToBackup(): Map<String, BackupPrimitive> {
		val out = LinkedHashMap<String, BackupPrimitive>(size)
		for ((key, value) in this) BackupPrimitive.of(value)?.let { out[key] = it }
		return out
	}

	private fun configContentHash(config: SyncConfig): String {
		val normalized = SyncConfig(
			revision = 0L,
			settings = config.settings.toSortedMap(),
			readerGrid = config.readerGrid.toSortedMap(),
			sourceSettings = config.sourceSettings.sortedBy { it.source },
			mangaPrefs = config.mangaPrefs.sortedBy { it.mangaId },
			continuityPayload = config.continuityPayload,
		)
		return json.encodeToString(SyncConfig.serializer(), normalized).hashCode().toString()
	}

	@Serializable
	private class SchemaProbe(@SerialName("schema") val schemaVersion: Int = 0)

	private companion object {
		const val TAG = "GDriveSync"
		const val TOMBSTONE_TTL_MS = 60L * 24 * 60 * 60 * 1000
		const val MAX_CONFLICT_RETRIES = 3
		const val DB_QUERY_BATCH_SIZE = 500
		val EXCLUDED_SETTINGS_KEYS = AppSettings.SENSITIVE_BACKUP_KEYS + CrossDeviceContinuity.LEGACY_SETTINGS_KEY
	}
}
