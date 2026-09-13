package org.koitharu.kotatsu.backup.local.ui.restore

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.backup.local.data.LocalBackupRepository
import org.koitharu.kotatsu.backup.local.data.model.BackupIndex
import org.koitharu.kotatsu.backup.local.domain.BackupSection
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import java.io.BufferedInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Date
import java.util.EnumMap
import java.util.EnumSet
import java.util.zip.ZipInputStream
import javax.inject.Inject

@HiltViewModel
class RestoreViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	@ApplicationContext context: Context,
) : BaseViewModel() {

	val uri = savedStateHandle.get<String>(AppRouter.KEY_FILE)?.toUriOrNull()
	private val contentResolver = context.contentResolver

	val availableEntries = MutableStateFlow<List<BackupSectionModel>>(emptyList())
	val backupDate = MutableStateFlow<Date?>(null)
	val hasPrivateFavourites = MutableStateFlow(false)
	val restorePrivateFavourites = MutableStateFlow(false)

	init {
		launchLoadingJob(Dispatchers.Default) {
			loadBackupInfo()
		}
	}

	private suspend fun loadBackupInfo() {
		hasPrivateFavourites.value = false
		restorePrivateFavourites.value = false
		val sections = runInterruptible(Dispatchers.IO) {
			val source = uri ?: throw FileNotFoundException()
			val rawInput = contentResolver.openInputStream(source) ?: throw FileNotFoundException()
			ZipInputStream(BufferedInputStream(rawInput, IO_BUFFER_SIZE)).use { stream ->
				val result = EnumSet.noneOf(BackupSection::class.java)
				var isMiyorareBackup = false
				var entry = stream.nextEntry
				while (entry != null) {
					val section = BackupSection.of(entry)
					when {
						section != null -> {
							result.add(section)
							if (section == BackupSection.INDEX) {
								val index = stream.readIndex()
								backupDate.value = index?.createdAt?.let(::Date)
								isMiyorareBackup = index?.appId.isMiyorareApplicationId()
							}
						}

						isMiyorareBackup && entry.name.equals(
							LocalBackupRepository.MIYORARE_METADATA_ENTRY,
							ignoreCase = true,
						) -> {
							// New Miyorare backups place this one-byte marker directly after INDEX. This keeps
							// restore-dialog startup constant even for very large libraries.
							hasPrivateFavourites.value = stream.readBytes().decodeToString().trim() == "1"
							result.addAll(BackupSection.entries)
							return@use result
						}

						isMiyorareBackup && entry.name.equals(
							LocalBackupRepository.PRIVATE_FAVOURITES_ENTRY,
							ignoreCase = true,
						) -> {
							// Compatibility path for older native backups created before the lightweight marker.
							hasPrivateFavourites.value = true
						}
					}
					stream.closeEntry()
					entry = stream.nextEntry
				}
				result
			}
		}
		val map = EnumMap<BackupSection, BackupSectionModel>(BackupSection::class.java)
		for (entry in BackupSection.entries) {
			if (entry == BackupSection.INDEX || entry !in sections) continue
			map[entry] = BackupSectionModel(
				section = entry,
				isChecked = true,
				isEnabled = true,
			)
		}
		map.validate()
		availableEntries.value = map.values.sortedBy { it.section.ordinal }
	}

	fun onItemClick(item: BackupSectionModel) {
		val map = availableEntries.value.associateByTo(EnumMap(BackupSection::class.java)) { it.section }
		map[item.section] = item.copy(isChecked = !item.isChecked)
		map.validate()
		availableEntries.value = map.values.sortedBy { it.section.ordinal }
	}

	fun setRestorePrivateFavourites(restore: Boolean) {
		restorePrivateFavourites.value = restore && hasPrivateFavourites.value
	}

	fun getCheckedSections(): Set<BackupSection> = availableEntries.value
		.mapNotNullTo(EnumSet.noneOf(BackupSection::class.java)) {
			if (it.isChecked) it.section else null
		}

	fun shouldRestorePrivateFavourites(): Boolean =
		hasPrivateFavourites.value && restorePrivateFavourites.value

	/** Favorites require category records — keep the dependency consistent. */
	private fun MutableMap<BackupSection, BackupSectionModel>.validate() {
		val favorites = this[BackupSection.FAVOURITES] ?: return
		val categories = this[BackupSection.CATEGORIES]
		if (categories?.isChecked == true) {
			if (!favorites.isEnabled) {
				this[BackupSection.FAVOURITES] = favorites.copy(isEnabled = true)
			}
		} else if (favorites.isEnabled) {
			this[BackupSection.FAVOURITES] = favorites.copy(isEnabled = false, isChecked = false)
		}
	}

	private fun InputStream.readIndex(): BackupIndex? = runCatching {
		val json = Json {
			ignoreUnknownKeys = true
			coerceInputValues = true
		}
		json.decodeFromString<List<BackupIndex>>(readBytes().decodeToString()).firstOrNull()
	}.onFailure { e ->
		e.printStackTraceDebug()
	}.getOrNull()

	private fun String?.isMiyorareApplicationId(): Boolean = this in MIYORARE_APPLICATION_IDS

	private companion object {
		const val IO_BUFFER_SIZE = 64 * 1024
		val MIYORARE_APPLICATION_IDS = setOf(
			"org.noirero.miyorare",
			"org.noirero.miyorare.beta",
			"org.noirero.miyorare.debug",
		)
	}
}
