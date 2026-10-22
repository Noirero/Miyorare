package org.koitharu.kotatsu.sync.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.sync.data.model.SyncContent
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent settings for Google Drive sync, kept in their own prefs file so they don't get caught
 * up in the app-settings backup/restore. No OAuth tokens are stored here — those are managed by
 * Google Play Services and fetched on demand.
 */
@Singleton
class SyncSettings @Inject constructor(@ApplicationContext context: Context) {

	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	/** Email cached for display/bookkeeping. This is metadata, not proof that Google auth is usable. */
	var accountEmail: String?
		get() = prefs.getString(KEY_ACCOUNT_EMAIL, null)
		set(value) = prefs.edit { putString(KEY_ACCOUNT_EMAIL, value) }

	var accountName: String?
		get() = prefs.getString(KEY_ACCOUNT_NAME, null)
		set(value) = prefs.edit { putString(KEY_ACCOUNT_NAME, value) }

	/** URL of the signed-in account's profile picture, or null. */
	var accountPhotoUrl: String?
		get() = prefs.getString(KEY_ACCOUNT_PHOTO, null)
		set(value) = prefs.edit { putString(KEY_ACCOUNT_PHOTO, value) }

	/** When true the account email is blurred in the UI. */
	var isEmailHidden: Boolean
		get() = prefs.getBoolean(KEY_EMAIL_HIDDEN, false)
		set(value) = prefs.edit { putBoolean(KEY_EMAIL_HIDDEN, value) }

	/** Whether account display metadata exists. Always pair with the live Google account state. */
	val hasAccountMetadata: Boolean
		get() = !accountEmail.isNullOrEmpty()

	/** Stable per-install id, used to decide whether the remote snapshot came from this device. */
	val deviceId: String
		get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
			prefs.edit { putString(KEY_DEVICE_ID, it) }
		}

	/** Periodic sync interval in minutes; 0 means manual-only. Defaults to every 6 hours. */
	var intervalMinutes: Int
		get() = prefs.getString(KEY_INTERVAL, "360")?.toIntOrNull() ?: 360
		set(value) = prefs.edit { putString(KEY_INTERVAL, value.toString()) }

	var isWifiOnly: Boolean
		get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
		set(value) = prefs.edit { putBoolean(KEY_WIFI_ONLY, value) }

	var isSyncOnStart: Boolean
		get() = prefs.getBoolean(KEY_SYNC_ON_START, true)
		set(value) = prefs.edit { putBoolean(KEY_SYNC_ON_START, value) }

	/**
	 * Keeps favorite/history deletions local instead of uploading them to other devices.
	 * Enabled by default so an accidental deletion cannot fan out through sync.
	 */
	var isDeletionSyncDisabled: Boolean
		get() = prefs.getBoolean(KEY_DISABLE_DELETION_SYNC, true)
		set(value) = prefs.edit { putBoolean(KEY_DISABLE_DELETION_SYNC, value) }

	var enabledContent: Set<String>
		get() = prefs.getStringSet(KEY_CONTENT, null) ?: SyncContent.DEFAULT
		set(value) = prefs.edit { putStringSet(KEY_CONTENT, value) }

	var lastSyncTimestamp: Long
		get() = prefs.getLong(KEY_LAST_SYNC, 0L)
		set(value) = prefs.edit { putLong(KEY_LAST_SYNC, value) }

	var lastSyncError: String?
		get() = prefs.getString(KEY_LAST_ERROR, null)
		set(value) = prefs.edit { putString(KEY_LAST_ERROR, value) }

	/** Local revision of the config bundle (settings/covers/etc.), bumped when its content changes. */
	var configRevision: Long
		get() = prefs.getLong(KEY_CONFIG_REVISION, 0L)
		set(value) = prefs.edit { putLong(KEY_CONFIG_REVISION, value) }

	/** Hash of the config bundle at the last sync, to detect local config changes. */
	var configHash: String?
		get() = prefs.getString(KEY_CONFIG_HASH, null)
		set(value) = prefs.edit { putString(KEY_CONFIG_HASH, value) }

	// Feed rows (track_logs) have no soft-delete column, so a locally-deleted feed item is
	// indistinguishable from a not-yet-seen remote one during merge. This per-device baseline records
	// the feed identities present at the last successful sync; the diff against the current local set
	// tells us what was deleted here, so deletions can be honoured instead of resurrected.
	var lastSyncedFeedIds: Set<String>
		get() = prefs.getStringSet(KEY_FEED_BASELINE, null).orEmpty()
		set(value) = prefs.edit { putStringSet(KEY_FEED_BASELINE, value) }

	fun clearAccount() = prefs.edit {
		remove(KEY_ACCOUNT_EMAIL)
		remove(KEY_ACCOUNT_NAME)
		remove(KEY_ACCOUNT_PHOTO)
		remove(KEY_LAST_SYNC)
		remove(KEY_LAST_ERROR)
		remove(KEY_CONFIG_REVISION)
		remove(KEY_CONFIG_HASH)
		remove(KEY_FEED_BASELINE)
	}

	companion object {

		private const val PREFS_NAME = "sync"
		private const val KEY_ACCOUNT_EMAIL = "account_email"
		private const val KEY_ACCOUNT_NAME = "account_name"
		private const val KEY_ACCOUNT_PHOTO = "account_photo"
		private const val KEY_EMAIL_HIDDEN = "email_hidden"
		private const val KEY_DEVICE_ID = "device_id"
		const val KEY_INTERVAL = "interval"
		const val KEY_WIFI_ONLY = "wifi_only"
		const val KEY_SYNC_ON_START = "sync_on_start"
		const val KEY_DISABLE_DELETION_SYNC = "disable_deletion_sync"
		const val KEY_CONTENT = "content"
		private const val KEY_LAST_SYNC = "last_sync"
		private const val KEY_LAST_ERROR = "last_error"
		private const val KEY_CONFIG_REVISION = "config_revision"
		private const val KEY_CONFIG_HASH = "config_hash"
		private const val KEY_FEED_BASELINE = "feed_baseline"
	}
}
