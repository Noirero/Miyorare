package org.koitharu.kotatsu.backup.local.data

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import androidx.preference.PreferenceManager
import org.koitharu.kotatsu.core.prefs.AppSettings

/** Keeps Android's file-based backup rules and sanitizes the legacy settings restored by Android. */
open class MiyorareAutoBackupAgent : BackupAgent() {

	override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit

	override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

	override fun onRestoreFinished() {
		super.onRestoreFinished()
		val prefs = PreferenceManager.getDefaultSharedPreferences(this)
		val tokenKey = AppSettings.KEY_DISCORD_TOKEN
		// Old Android backups contain the token in this file. Clear the restored key before
		// normal startup can mistake it for a token from an in-place app upgrade.
		if (prefs.contains(tokenKey)) {
			check(prefs.edit().remove(tokenKey).commit()) { "Could not sanitize restored Discord credentials" }
		}
	}
}
