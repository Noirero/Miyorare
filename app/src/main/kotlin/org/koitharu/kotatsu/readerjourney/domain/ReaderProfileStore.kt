package org.koitharu.kotatsu.readerjourney.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

/**
 * Device-local Reader Profile preferences.
 *
 * This intentionally uses its own SharedPreferences file rather than AppSettings. Display name and
 * showcase remain device-local; selected Reader Title and cosmetic snapshot may be included only in
 * the dedicated local Reader Journey backup. Cloud sync is not expanded by this store.
 */
@Singleton
class ReaderProfileStore @Inject constructor(
	@ApplicationContext context: Context,
) {

	private val context = context.applicationContext
	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val _profile = MutableStateFlow(load())
	val profile: StateFlow<ReaderProfileSettings> = _profile.asStateFlow()

	init {
		migrateLegacyCosmeticsIfNeeded()
	}

	fun update(
		displayName: String,
		selectedTitle: ReaderAchievementId?,
		showcase: List<ReaderAchievementId>,
	) {
		val safeName = displayName.trim().take(MAX_DISPLAY_NAME_LENGTH)
		val safeShowcase = showcase.distinct().take(MAX_SHOWCASE)
		val updated = ReaderProfileSettings(
			displayName = safeName,
			avatarPath = _profile.value.avatarPath,
			selectedTitle = selectedTitle,
			showcase = safeShowcase,
			cosmetics = _profile.value.cosmetics,
		)
		if (_profile.value == updated) return
		prefs.edit {
			putString(KEY_DISPLAY_NAME, updated.displayName)
			if (updated.selectedTitle == null) {
				remove(KEY_SELECTED_TITLE)
			} else {
				putString(KEY_SELECTED_TITLE, updated.selectedTitle.name)
			}
			putStringSet(KEY_SHOWCASE, updated.showcase.mapTo(LinkedHashSet()) { it.name })
		}
		_profile.value = updated
	}


	fun importAvatar(uri: Uri): Boolean {
		val resolver = context.contentResolver
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		val boundsStream = resolver.openInputStream(uri) ?: return false
		boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
		// inJustDecodeBounds deliberately returns null; dimensions carry the validation result.
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
		var sampleSize = 1
		while (bounds.outWidth / sampleSize > AVATAR_MAX_EDGE * 2 || bounds.outHeight / sampleSize > AVATAR_MAX_EDGE * 2) {
			sampleSize *= 2
		}
		val bitmap = resolver.openInputStream(uri)?.use {
			BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
		} ?: return false
		val scale = minOf(1f, AVATAR_MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
		val output = if (scale < 1f) {
			Bitmap.createScaledBitmap(
				bitmap,
				(bitmap.width * scale).toInt().coerceAtLeast(1),
				(bitmap.height * scale).toInt().coerceAtLeast(1),
				true,
			)
		} else bitmap
		return try {
			val directory = File(context.filesDir, AVATAR_DIRECTORY).apply { mkdirs() }
			val target = File(directory, AVATAR_FILE)
			val temporary = File(directory, "$AVATAR_FILE.tmp")
			temporary.outputStream().buffered().use { stream ->
				check(output.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, stream))
			}
			if (target.exists()) target.delete()
			check(temporary.renameTo(target))
			setAvatarPath(target.absolutePath)
			true
		} catch (_: Throwable) {
			false
		} finally {
			if (output !== bitmap) output.recycle()
			bitmap.recycle()
		}
	}

	fun removeAvatar() {
		_profile.value.avatarPath?.let(::File)?.delete()
		setAvatarPath(null)
	}

	private fun setAvatarPath(path: String?) {
		val current = _profile.value
		if (current.avatarPath == path) return
		prefs.edit {
			if (path == null) remove(KEY_AVATAR_PATH) else putString(KEY_AVATAR_PATH, path)
		}
		_profile.value = current.copy(avatarPath = path)
	}

	fun updateCosmetics(loadout: ReaderJourneyCosmeticLoadout) {
		val safeLoadout = ReaderJourneyCosmeticSnapshotCodec.sanitize(loadout)
		val current = _profile.value
		if (current.cosmetics == safeLoadout) return

		val committed = prefs.edit()
			.putString(KEY_COSMETIC_LOADOUT_V2, ReaderJourneyCosmeticSnapshotCodec.encode(safeLoadout))
			.commit()
		if (!committed) return

		_profile.value = current.copy(cosmetics = safeLoadout)
	}

	fun backupSelectedTitleId(): String? = _profile.value.selectedTitle?.name

	fun backupCosmeticSnapshot(): String =
		ReaderJourneyCosmeticSnapshotCodec.encode(_profile.value.cosmetics)

	/**
	 * Restores only the profile selections approved for local backup.
	 *
	 * Display name/showcase stay device-local. Ownership is reconstructed from the already-restored
	 * Reader Journey ledger and applied through [ReaderJourneyCosmeticPolicy], so a backup can never
	 * grant a locked rank cosmetic. Invalid/old cosmetic snapshots are ignored rather than replacing
	 * a valid local selection with corrupted data.
	 */
	fun restoreBackupSelection(
		selectedTitleId: String?,
		cosmeticSnapshot: String?,
		currentRank: ReaderRank,
		unlockedAchievementIds: Set<String>,
	) {
		val current = _profile.value
		val selectedTitle = selectedTitleId
			?.takeIf { it in unlockedAchievementIds }
			?.let { raw -> ReaderAchievementId.entries.firstOrNull { it.name == raw } }
		val decoded = ReaderJourneyCosmeticSnapshotCodec.decode(cosmeticSnapshot)
		val cosmeticAccessRank = ReaderJourneyRewardAccess.cosmeticAccessRank(currentRank)
		val safeCosmetics = decoded
			?.let { ReaderJourneyCosmeticPolicy.sanitizeForRank(it, cosmeticAccessRank) }
			?: current.cosmetics

		val updated = current.copy(
			selectedTitle = selectedTitle,
			cosmetics = safeCosmetics,
		)
		if (updated == current) return

		val editor = prefs.edit()
		if (selectedTitle == null) editor.remove(KEY_SELECTED_TITLE)
		else editor.putString(KEY_SELECTED_TITLE, selectedTitle.name)
		editor.putString(
			KEY_COSMETIC_LOADOUT_V2,
			ReaderJourneyCosmeticSnapshotCodec.encode(safeCosmetics),
		)
		if (!editor.commit()) return
		_profile.value = updated
	}

	private fun loadRank(key: String): ReaderRank? =
		prefs.getString(key, null)?.let { raw -> ReaderRank.entries.firstOrNull { it.name == raw } }

	private fun loadLegacyCosmetics(): ReaderJourneyCosmeticLoadout = ReaderJourneyCosmeticLoadout(
		mode = ReaderJourneyCosmeticMode.AUTO,
		frame = loadRank(KEY_COSMETIC_FRAME),
		glow = loadRank(KEY_COSMETIC_GLOW),
		background = loadRank(KEY_COSMETIC_BACKGROUND),
		progressBar = loadRank(KEY_COSMETIC_PROGRESS),
	)

	private fun loadCosmetics(): ReaderJourneyCosmeticLoadout {
		val snapshot = ReaderJourneyCosmeticSnapshotCodec.decode(
			prefs.getString(KEY_COSMETIC_LOADOUT_V2, null),
		)
		return snapshot ?: loadLegacyCosmetics()
	}

	private fun migrateLegacyCosmeticsIfNeeded() {
		val existingRaw = prefs.getString(KEY_COSMETIC_LOADOUT_V2, null)
		if (ReaderJourneyCosmeticSnapshotCodec.decode(existingRaw) != null) return

		// Missing, corrupt or old-version snapshots are repaired from the safely loaded state.
		// This never grants ownership; it only rewrites presentation selection.
		val migrated = ReaderJourneyCosmeticSnapshotCodec.sanitize(_profile.value.cosmetics)
		val committed = prefs.edit()
			.putString(KEY_COSMETIC_LOADOUT_V2, ReaderJourneyCosmeticSnapshotCodec.encode(migrated))
			.commit()
		if (!committed) return

		prefs.edit {
			remove(KEY_COSMETIC_FRAME)
			remove(KEY_COSMETIC_GLOW)
			remove(KEY_COSMETIC_BACKGROUND)
			remove(KEY_COSMETIC_PROGRESS)
		}
	}

	private fun load(): ReaderProfileSettings {
		val selectedTitle = prefs.getString(KEY_SELECTED_TITLE, null)
			?.let { raw -> ReaderAchievementId.entries.firstOrNull { it.name == raw } }
		val showcaseNames = prefs.getStringSet(KEY_SHOWCASE, emptySet()).orEmpty()
		val showcase = ReaderAchievementId.entries.filter { it.name in showcaseNames }.take(MAX_SHOWCASE)
		return ReaderProfileSettings(
			displayName = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty().trim().take(MAX_DISPLAY_NAME_LENGTH),
			avatarPath = prefs.getString(KEY_AVATAR_PATH, null)?.takeIf { File(it).isFile },
			selectedTitle = selectedTitle,
			showcase = showcase,
			cosmetics = loadCosmetics(),
		)
	}

	private companion object {
		const val PREFS_NAME = "reader_journey_profile"
		const val KEY_DISPLAY_NAME = "display_name"
		const val KEY_AVATAR_PATH = "avatar_path"
		const val KEY_SELECTED_TITLE = "selected_title"
		const val KEY_SHOWCASE = "showcase"
		const val KEY_COSMETIC_LOADOUT_V2 = "cosmetic_loadout_v2"

		const val KEY_COSMETIC_FRAME = "cosmetic_frame"
		const val KEY_COSMETIC_GLOW = "cosmetic_glow"
		const val KEY_COSMETIC_BACKGROUND = "cosmetic_background"
		const val KEY_COSMETIC_PROGRESS = "cosmetic_progress"

		const val AVATAR_DIRECTORY = "reader_journey"
		const val AVATAR_FILE = "avatar.jpg"
		const val AVATAR_MAX_EDGE = 512
		const val AVATAR_JPEG_QUALITY = 88

		const val MAX_DISPLAY_NAME_LENGTH = 40
		const val MAX_SHOWCASE = 3
	}
}
