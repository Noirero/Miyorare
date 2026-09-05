package org.koitharu.kotatsu.settings.storage.directories

import android.net.Uri
import android.os.StatFs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.computeSize
import org.koitharu.kotatsu.core.util.ext.isReadable
import org.koitharu.kotatsu.core.util.ext.isWriteable
import org.koitharu.kotatsu.local.data.LocalStorageManager
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MangaDirectoriesViewModel @Inject constructor(
    private val storageManager: LocalStorageManager,
    private val settings: AppSettings,
) : BaseViewModel() {

    val items = MutableStateFlow(emptyList<DirectoryConfigModel>())
    private var loadingJob: Job? = null

    init {
        loadList()
    }

    fun updateList() {
        loadList()
    }

    fun onCustomDirectoryPicked(uri: Uri) {
        launchLoadingJob(Dispatchers.Default) {
            storageManager.takePermissions(uri)
            val dir = storageManager.resolveUri(uri)
            if (!dir.canRead()) {
                throw AccessDeniedException(dir)
            }
            if (dir !in storageManager.getApplicationStorageDirs()) {
                settings.userSpecifiedMangaDirectories += dir
            }
            loadList()
        }
    }

    fun onRemoveClick(directory: File) {
        settings.userSpecifiedMangaDirectories -= directory
        if (settings.mangaStorageDir == directory) {
            settings.mangaStorageDir = null
        }
        loadList()
    }

    private fun loadList() {
        val prevJob = loadingJob
        loadingJob = launchJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()

            // Resolving folder sizes can be very expensive for a large manga library. Previously
            // the list was not published until computeSize() had recursively scanned every folder,
            // which made this screen look completely empty after choosing a directory.
            val downloadDir = runCatching { storageManager.getDefaultWriteableDir() }.getOrNull()
            val applicationDirs = runCatching { storageManager.getApplicationStorageDirs() }
                .getOrDefault(emptySet())
            val configuredCustomDirs = LinkedHashSet(settings.userSpecifiedMangaDirectories)
            settings.mangaStorageDir?.let(configuredCustomDirs::add)
            val customDirs = configuredCustomDirs - applicationDirs

            val directories = buildList<Pair<File, Boolean>>(applicationDirs.size + customDirs.size) {
                applicationDirs.forEach { add(it to true) }
                customDirs.forEach { add(it to false) }
            }

            // Publish the rows immediately. Storage size is filled in afterwards in the background.
            // This also keeps a configured custom directory visible when storage statistics cannot
            // be read on a particular device.
            items.value = directories.map { (dir, isAppPrivate) ->
                dir.toDirectoryModel(
                    isDefault = dir == downloadDir,
                    isAppPrivate = isAppPrivate,
                    calculateSize = false,
                )
            }

            directories.forEach { (dir, isAppPrivate) ->
                val isReadable = runCatching { dir.isReadable() }.getOrDefault(false)
                val isWriteable = isReadable && runCatching { dir.isWriteable() }.getOrDefault(false)
                // Do not recursively walk a disconnected SD card or a folder whose permission was
                // revoked. The row remains visible, but expensive size work is skipped until access
                // is restored.
                val size = if (isReadable) {
                    runCatching { dir.computeSize() }.getOrDefault(0L)
                } else {
                    0L
                }
                val current = items.value
                val index = current.indexOfFirst { it.path == dir }
                if (index >= 0) {
                    val old = current[index]
                    val updated = old.copy(
                        size = size,
                        available = getAvailableBytes(dir),
                        isAccessible = isReadable && isWriteable,
                        isDefault = dir == downloadDir,
                        isAppPrivate = isAppPrivate,
                    )
                    items.value = current.toMutableList().also { it[index] = updated }
                }
            }
        }
    }

    private suspend fun File.toDirectoryModel(
        isDefault: Boolean,
        isAppPrivate: Boolean,
        calculateSize: Boolean,
    ) = DirectoryConfigModel(
        title = runCatching {
            storageManager.getDirectoryDisplayName(this, isFullPath = false)
        }.getOrElse {
            name.ifBlank { absolutePath }
        },
        path = this,
        isDefault = isDefault,
        isAccessible = runCatching { isReadable() && isWriteable() }.getOrDefault(false),
        isAppPrivate = isAppPrivate,
        size = if (calculateSize) runCatching { computeSize() }.getOrDefault(0L) else 0L,
        available = getAvailableBytes(this),
    )

    private fun getAvailableBytes(directory: File): Long = runCatching {
        StatFs(directory.absolutePath).availableBytes
    }.getOrDefault(directory.freeSpace.coerceAtLeast(0L))
}
