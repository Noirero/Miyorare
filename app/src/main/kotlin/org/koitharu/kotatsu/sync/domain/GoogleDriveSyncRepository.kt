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
import org.koitharu.kotatsu.backup.local.data.model.ReaderAchievementBackup
import org.koitharu.kotatsu.backup.local.data.model.ReaderJourneyBackup
import org.koitharu.kotatsu.backup.local.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backup.local.data.model.SourceSettingsBackup
import org.koitharu.kotatsu.backup.local.data.model.StatsBackup
import org.koitharu.kotatsu.backup.local.domain.CustomCoverCodec
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sync.data.model.SyncTrack
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
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
 * (settings/reader-grid/source-settings/custom-covers) is last-writer-wins by revision.
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
				// A cached token can be stale → 401. Invalidate once, then let the active auth provider
				// either mint a fresh GMS token or require foreground browser sign-in again.
				if (e.code == 401) {
					Log.w(TAG, "token rejected (401), invalidating and retrying authorization once")
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
			// Persist the error like any other failure — otherwise a revoked/expired grant kills
			// background sync forever while settings still claim everything is fine.
			syncSettings.lastSyncError = context.getString(R.string.sync_sign_in_required)
			return SyncResult.SignInRequired
		} catch (e: SyncSchemaException) {
			// Remote was written by a newer app version — never overwrite it. Don't retry either.
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

		// Retry loop for optimistic concurrency: if another device writes the canonical file between
		// our read and our write, we re-read and re-merge so its changes are never lost. Bounded; the
		// last attempt writes best-effort (the per-record merge converges on the next sync regardless).
		var attempt = 0
		while (true) {
			var files = api.findSyncFiles(token)
			val migratingStoredSnapshot = files.isEmpty()
			if (migratingStoredSnapshot) {
				files = api.findMigrationCandidates(token)
			}
			// Migration candidates are never overwritten in place. Valid data is merged first,
			// then written into a newly-created canonical Miyorare sync file.
			val canonical = if (migratingStoredSnapshot) null else files.firstOrNull()
			val baseVersion = canonical?.version

			// Download + decode every file. A download failure THROWS out of here — we must never let a
			// transient network/auth error look like "no remote" and overwrite good cloud data locally.
			val remotes = ArrayList<SyncSnapshot>(files.size)
			val decodedIds = HashSet<String>(files.size)
			for (file in files) {
				val bytes = api.download(token, file.id)
				val snapshot = decodeSnapshot(bytes) // null == same-schema corruption → ignored
				if (snapshot != null) {
					remotes += snapshot
					decodedIds += file.id
				}
			}
			// Old/staging builds may already have uploaded metadata for a title that is now Private-only.
			// Scrub those rows BEFORE every merge/apply. Privacy deliberately overrides per-section sync
			// toggles and deletion-sync preferences: a disabled section may be preserved in the cloud, but
			// it must never preserve a title the current device has explicitly isolated in Private.
			val combinedRemote = SyncMerger.combine(remotes)
			val privateOnlyIds = privateOnlyMangaIds()
			val scrubbedRemote = combinedRemote?.scrubPrivateOnly(privateOnlyIds)
			val privacyScrubbed = combinedRemote != null && scrubbedRemote !== combinedRemote

			// Remap BEFORE the merge and the unchanged-check: remote category ids come from another
			// device's autoincrement sequence, so a raw id match means nothing. Doing it here also
			// keeps an id-space difference alone from triggering an upload every sync.
			val remote = scrubbedRemote?.let { snapshot ->
				if (SyncContent.FAVOURITES in enabled) remapRemoteCategories(snapshot) else snapshot
			}

			val local = buildLocalSnapshot(now)
			val merged = SyncMerger.merge(local, remote, enabled, syncSettings.isDeletionSyncDisabled)
			applyMergedSnapshot(merged, enabled)

			val outgoing = merged.scrubPrivateOnly(privateOnlyMangaIds())
			val outgoingBytes = json.encodeToString(SyncSnapshot.serializer(), outgoing).encodeToByteArray()
			val remoteBytes = remote?.let { json.encodeToString(SyncSnapshot.serializer(), it).encodeToByteArray() }
			if (!privacyScrubbed && remoteBytes != null && remoteBytes.contentEquals(outgoingBytes)) {
				Log.i(TAG, "sync unchanged; no upload needed")
				if (migratingStoredSnapshot) {
					// A migration candidate existed but the merged value was equal. Still create the canonical
					// file so subsequent runs do not repeatedly scan legacy filenames.
					api.upload(token, outgoingBytes, null)
					deleteMigrationCandidates(token, files, decodedIds)
				}
				return
			}

			if (canonical != null && attempt < MAX_CONFLICT_RETRIES) {
				val currentVersion = api.getFileVersion(token, canonical.id)
				if (currentVersion != baseVersion) {
					attempt++
					Log.i(TAG, "remote changed during sync; re-reading (attempt=$attempt)")
					continue
				}
			}

			api.upload(token, outgoingBytes, canonical?.id)
			if (migratingStoredSnapshot) deleteMigrationCandidates(token, files, decodedIds)
			return
		}
	}

	private suspend fun deleteMigrationCandidates(
		token: String,
		files: List<GoogleDriveApi.DriveFile>,
		decodedIds: Set<String>,
	) {
		for (file in files) {
			if (file.id in decodedIds) runCatchingCancellable { api.delete(token, file.id) }
		}
	}

	private suspend fun buildLocalSnapshot(now: Long): SyncSnapshot {
		val enabled = SyncContent.fromKeys(syncSettings.enabledContent)
		val prefs = database.getMangaPrefsDao().observeAll().toList().firstOrNull().orEmpty()
		val manga = database.getMangaDao().findAll().associateBy { it.id }
		val privateIds = privateOnlyMangaIds()
		val favourites = if (SyncContent.FAVOURITES in enabled) {
			database.getFavouritesDao().observeAll().toList().firstOrNull().orEmpty()
				.filterNot { it.mangaId in privateIds }
				.map { item ->
					SyncFavourite(
						mangaId = item.mangaId,
						categoryId = item.categoryId,
						createdAt = item.createdAt,
						updatedAt = item.updatedAt,
						deletedAt = item.deletedAt,
					)
				}
		} else emptyList()
		val categories = if (SyncContent.FAVOURITES in enabled) {
			database.getCategoriesDao().observeAll().toList().firstOrNull().orEmpty().map { category ->
				SyncCategory(
					id = category.id,
					name = category.name,
					order = category.order,
					isTrackingEnabled = category.isTrackingEnabled,
					showInLibrary = category.showInLibrary,
					createdAt = category.createdAt,
					updatedAt = category.updatedAt,
					deletedAt = category.deletedAt,
				)
			}
		} else emptyList()
		val history = if (SyncContent.HISTORY in enabled) {
			database.getHistoryDao().observeAll().toList().firstOrNull().orEmpty()
				.filterNot { it.mangaId in privateIds }
				.map { item -> SyncHistory.fromEntity(item) }
		} else emptyList()
		val feed = if (SyncContent.FEED in enabled) {
			database.getFeedDao().observeAll().toList().firstOrNull().orEmpty()
				.filterNot { it.mangaId in privateIds }
				.map { item -> SyncFeedEntry.fromEntity(item) }
		} else emptyList()
		val track = if (SyncContent.TRACK in enabled) {
			database.getTrackDao().observeAll().toList().firstOrNull().orEmpty()
				.filterNot { it.mangaId in privateIds }
				.map { item -> SyncTrack.fromEntity(item) }
		} else emptyList()
		val mangaPrefs = if (SyncContent.MANGA_PREFS in enabled) prefs
			.filterNot { it.mangaId in privateIds }
			.map { SyncMangaPrefs.fromEntity(it) } else emptyList()
		val config = if (SyncContent.SETTINGS in enabled) buildConfig(now) else null
		return SyncSnapshot(
			schemaVersion = SyncSnapshot.CURRENT_SCHEMA,
			deviceId = syncSettings.deviceId,
			updatedAt = now,
			favourites = favourites,
			categories = categories,
			history = history,
			feed = feed,
			track = track,
			mangaPrefs = mangaPrefs,
			config = config,
		)
	}

	private suspend fun privateOnlyMangaIds(): Set<Long> {
		val favourites = database.getFavouritesDao().observeAll().toList().firstOrNull().orEmpty()
		return favourites.groupBy { it.mangaId }
			.filterValues { entries -> entries.any { it.categoryId == PRIVATE_CATEGORY_ID } }
			.keys
	}

	private suspend fun buildConfig(now: Long): SyncConfig {
		val prefs = context.getSharedPreferences("sync-config", Context.MODE_PRIVATE)
		val revision = prefs.getLong(KEY_CONFIG_REVISION, 0L).takeIf { it > 0 } ?: now.also {
			prefs.edit { putLong(KEY_CONFIG_REVISION, it) }
		}
		return SyncConfig(
			revision = revision,
			appSettings = BackupPrimitive.dumpPrefs(appSettings.prefs),
			readerTapGrid = TapGridSettingsBackup.from(tapGridSettings),
			sourceSettings = SourceSettingsBackup.from(SourceSettings(context)),
			customCovers = coverCodec.exportAll(),
			stats = StatsBackup.from(database),
			readerJourney = ReaderJourneyBackup.from(database),
			readerAchievements = ReaderAchievementBackup.from(database),
			bookmarks = BookmarkBackup.from(database),
			manga = MangaBackup.from(database),
			scrobbling = ScrobblingBackup.from(database),
		)
	}

	private suspend fun applyMergedSnapshot(snapshot: SyncSnapshot, enabled: Set<SyncContent>) {
		database.withTransaction {
			if (SyncContent.FAVOURITES in enabled) applyFavourites(snapshot.favourites, snapshot.categories)
			if (SyncContent.HISTORY in enabled) applyHistory(snapshot.history)
			if (SyncContent.FEED in enabled) applyFeed(snapshot.feed)
			if (SyncContent.TRACK in enabled) applyTrack(snapshot.track)
			if (SyncContent.MANGA_PREFS in enabled) applyMangaPrefs(snapshot.mangaPrefs)
		}
		if (SyncContent.SETTINGS in enabled) snapshot.config?.let { applyConfig(it) }
	}

	private suspend fun applyFavourites(items: List<SyncFavourite>, categories: List<SyncCategory>) {
		val categoryDao = database.getCategoriesDao()
		val favouriteDao = database.getFavouritesDao()
		for (category in categories) {
			if (category.deletedAt != null) {
				categoryDao.deleteById(category.id)
			} else {
				categoryDao.upsert(category.toEntity())
			}
		}
		for (item in items) {
			if (item.deletedAt != null) {
				favouriteDao.delete(item.mangaId, item.categoryId)
			} else {
				favouriteDao.upsert(item.toEntity())
			}
		}
	}

	private suspend fun applyHistory(items: List<SyncHistory>) {
		val dao = database.getHistoryDao()
		for (item in items) {
			if (item.deletedAt != null) dao.deleteById(item.mangaId) else dao.upsert(item.toEntity())
		}
	}

	private suspend fun applyFeed(items: List<SyncFeedEntry>) {
		val dao = database.getFeedDao()
		for (item in items) {
			if (item.deletedAt != null) dao.deleteById(item.mangaId) else dao.upsert(item.toEntity())
		}
	}

	private suspend fun applyTrack(items: List<SyncTrack>) {
		val dao = database.getTrackDao()
		for (item in items) {
			if (item.deletedAt != null) dao.deleteById(item.mangaId) else dao.upsert(item.toEntity())
		}
	}

	private suspend fun applyMangaPrefs(items: List<SyncMangaPrefs>) {
		val dao = database.getMangaPrefsDao()
		for (item in items) {
			if (item.deletedAt != null) dao.deleteByMangaId(item.mangaId) else dao.upsert(item.toEntity())
		}
	}

	private suspend fun applyConfig(config: SyncConfig) {
		val prefs = context.getSharedPreferences("sync-config", Context.MODE_PRIVATE)
		val localRevision = prefs.getLong(KEY_CONFIG_REVISION, 0L)
		if (config.revision < localRevision) return
		BackupPrimitive.restorePrefs(appSettings.prefs, config.appSettings)
		TapGridSettingsBackup.restore(tapGridSettings, config.readerTapGrid)
		SourceSettingsBackup.restore(SourceSettings(context), config.sourceSettings)
		coverCodec.restoreAll(config.customCovers)
		StatsBackup.restore(database, config.stats)
		ReaderJourneyBackup.restore(database, config.readerJourney)
		ReaderAchievementBackup.restore(database, config.readerAchievements)
		BookmarkBackup.restore(database, config.bookmarks)
		MangaBackup.restore(database, config.manga)
		ScrobblingBackup.restore(database, config.scrobbling)
		prefs.edit { putLong(KEY_CONFIG_REVISION, config.revision) }
	}

	private suspend fun remapRemoteCategories(snapshot: SyncSnapshot): SyncSnapshot {
		val localCategories = database.getCategoriesDao().observeAll().toList().firstOrNull().orEmpty()
		if (snapshot.categories.isEmpty()) return snapshot
		val localByName = localCategories.associateBy { it.name.trim().lowercase() }
		val remap = HashMap<Long, Long>()
		val remappedCategories = snapshot.categories.map { remote ->
			val local = localByName[remote.name.trim().lowercase()]
			if (local != null && local.id != remote.id) {
				remap[remote.id] = local.id
				remote.copy(id = local.id)
			} else remote
		}
		if (remap.isEmpty()) return snapshot
		return snapshot.copy(
			categories = remappedCategories,
			favourites = snapshot.favourites.map { item ->
				item.copy(categoryId = remap[item.categoryId] ?: item.categoryId)
			},
		)
	}

	private fun SyncSnapshot.scrubPrivateOnly(privateIds: Set<Long>): SyncSnapshot {
		if (privateIds.isEmpty()) return this
		val favourites = favourites.filterNot { it.mangaId in privateIds }
		val history = history.filterNot { it.mangaId in privateIds }
		val feed = feed.filterNot { it.mangaId in privateIds }
		val track = track.filterNot { it.mangaId in privateIds }
		val mangaPrefs = mangaPrefs.filterNot { it.mangaId in privateIds }
		if (
			favourites.size == this.favourites.size &&
			history.size == this.history.size &&
			feed.size == this.feed.size &&
			track.size == this.track.size &&
			mangaPrefs.size == this.mangaPrefs.size
		) return this
		return copy(
			favourites = favourites,
			history = history,
			feed = feed,
			track = track,
			mangaPrefs = mangaPrefs,
		)
	}

	private companion object {
		const val TAG = "DriveSync"
		const val PRIVATE_CATEGORY_ID = -1000L
		const val MAX_CONFLICT_RETRIES = 2
		const val KEY_CONFIG_REVISION = "config_revision"
	}
}

private object TapGridSettingsBackup {
	fun from(settings: TapGridSettings): Map<String, String> = settings.export()
	fun restore(settings: TapGridSettings, values: Map<String, String>) = settings.import(values)
}
