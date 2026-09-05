package org.koitharu.kotatsu.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.source
import org.koitharu.kotatsu.backup.model.MihonBackup
import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupFallback
import org.koitharu.kotatsu.backup.model.MihonBackupManga
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.exceptions.BadBackupFormatException
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.mihonMangaId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Final consistency pass for Tachiyomi/Mihon library restores.
 *
 * The main restore owns all metadata/progress/category work. This pass is intentionally narrow: it
 * only guarantees that entries which the backup identifies as library/favourite entries are backed
 * by an active DropSauce favourite row and that categories used for those restored rows are visible.
 * It also covers older/forked Tachiyomi backups that carry category membership even when their
 * favourite flag is absent or decoded as false.
 */
@Singleton
class MihonFavouriteRestoreRepair @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: MangaDatabase,
    private val mihonExtensionManager: MihonExtensionManager,
    private val contentTypeStore: FavouriteContentTypeStore,
) {

    private val proto = ProtoBuf

    suspend fun repair(uri: Uri): Int = withContext(Dispatchers.IO) {
        val backup = decode(uri)
        runCatching { mihonExtensionManager.ensureReady() }

        val categoryTypeUpdates = LinkedHashMap<Long, FavouriteContentType>()
        val repaired = db.withTransaction {
            repairInsideTransaction(backup, categoryTypeUpdates)
        }

        // FavouriteContentTypeStore is SharedPreferences-backed, so update it only after Room commits.
        categoryTypeUpdates.forEach { (categoryId, type) ->
            contentTypeStore.setCategoryType(categoryId, type)
        }
        repaired
    }

    private suspend fun repairInsideTransaction(
        backup: MihonBackup,
        categoryTypeUpdates: MutableMap<Long, FavouriteContentType>,
    ): Int {
        val categoryDao = db.getFavouriteCategoriesDao()
        val favouritesDao = db.getFavouritesDao()
        val existingCategories = categoryDao.findAll().toMutableList()
        val backupCategoriesByOrder = backup.backupCategories.associateBy(MihonBackupCategory::order)
        val fallbackByType = HashMap<FavouriteContentType, Long>()
        var repairedCount = 0

        for (item in backup.backupManga) {
            if (!item.isLibraryEntry()) continue

            val sourceName = resolveStoredSourceName(item.source)
            val mangaId = mihonMangaId(sourceName, item.url)
            if (db.getMangaDao().find(mangaId) == null) {
                // The main restore did not create this manga, so do not leave an orphan favourite.
                continue
            }

            val type = contentTypeForSource(item.source)
            val restoredCategoryIds = item.categories.mapNotNull { order ->
                val backupCategory = backupCategoriesByOrder[order] ?: return@mapNotNull null
                val title = backupCategory.name.trim()
                if (title.isEmpty()) return@mapNotNull null
                findOrCreateCategory(
                    title = title,
                    type = type,
                    existingCategories = existingCategories,
                    categoryTypeUpdates = categoryTypeUpdates,
                )
            }.distinct()

            val targetCategoryIds = restoredCategoryIds.ifEmpty {
                listOf(
                    fallbackByType.getOrPut(type) {
                        findOrCreateCategory(
                            title = DEFAULT_CATEGORY_TITLE,
                            type = type,
                            existingCategories = existingCategories,
                            categoryTypeUpdates = categoryTypeUpdates,
                        )
                    },
                )
            }

            // A category restored from a backup is meant to be visible on the library shelf. Reusing
            // a pre-existing hidden category used to make a successful restore look completely empty.
            targetCategoryIds.forEach { categoryId ->
                categoryDao.updateVisibility(categoryId, true)
                categoryTypeUpdates[categoryId] = type
            }

            val existingIds = favouritesDao.findCategoriesIds(mangaId).toHashSet()
            var repairedThisManga = false
            targetCategoryIds.forEachIndexed { index, categoryId ->
                if (categoryId !in existingIds) {
                    favouritesDao.upsert(
                        FavouriteEntity(
                            mangaId = mangaId,
                            categoryId = categoryId,
                            sortKey = index,
                            isPinned = false,
                            createdAt = item.dateAdded.takeIf { it > 0L } ?: System.currentTimeMillis(),
                            deletedAt = 0L,
                        ),
                    )
                    repairedThisManga = true
                }
            }
            if (repairedThisManga) repairedCount++
        }
        return repairedCount
    }

    private suspend fun findOrCreateCategory(
        title: String,
        type: FavouriteContentType,
        existingCategories: MutableList<FavouriteCategoryEntity>,
        categoryTypeUpdates: MutableMap<Long, FavouriteContentType>,
    ): Long {
        existingCategories.firstOrNull { category ->
            val id = category.categoryId.toLong()
            val pendingType = categoryTypeUpdates[id]
            category.title == title && if (pendingType != null) {
                pendingType == type
            } else {
                contentTypeStore.isCategoryForType(id, type)
            }
        }?.let { category ->
            val id = category.categoryId.toLong()
            categoryTypeUpdates[id] = type
            return id
        }

        val dao = db.getFavouriteCategoriesDao()
        val entity = FavouriteCategoryEntity(
            categoryId = 0,
            createdAt = System.currentTimeMillis(),
            sortKey = dao.getNextSortKey(),
            title = title,
            order = "NEWEST",
            track = true,
            downloadNewChapters = false,
            isVisibleInLibrary = true,
            deletedAt = 0L,
        )
        val id = dao.insert(entity)
        existingCategories += entity.copy(categoryId = id.toInt())
        categoryTypeUpdates[id] = type
        return id
    }

    private fun MihonBackupManga.isLibraryEntry(): Boolean {
        // Current Mihon explicitly writes favorite=true for library entries. Some older/forked
        // Tachiyomi backups are more reliable through their non-empty category membership, so accept
        // either signal. Read-history-only entries have neither and remain outside Disukai.
        return favorite || categories.isNotEmpty()
    }

    private fun resolveStoredSourceName(sourceId: Long): String {
        return mihonExtensionManager.getMihonMangaSourceById(sourceId)?.name
            ?: if (sourceId > 0L) "MIHON_$sourceId" else "UNKNOWN"
    }

    private fun contentTypeForSource(sourceId: Long): FavouriteContentType {
        return if (MihonExtensionManager.isNovelSourceId(sourceId)) {
            FavouriteContentType.NOVEL
        } else {
            FavouriteContentType.MANGA
        }
    }

    private fun decode(uri: Uri): MihonBackup {
        val payload = context.contentResolver.openInputStream(uri)?.use { input ->
            val source = input.source().buffer()
            val peeked = source.peek().apply { require(2) }
            val signature = peeked.readShort().toInt()
            when (signature) {
                0x1f8b -> source.gzip().buffer().readByteArray()
                else -> source.readByteArray()
            }
        } ?: throw BadBackupFormatException(null)

        return try {
            proto.decodeFromByteArray(MihonBackup.serializer(), payload)
        } catch (strictError: SerializationException) {
            runCatching {
                val fallback = proto.decodeFromByteArray(MihonBackupFallback.serializer(), payload)
                MihonBackup(
                    backupManga = fallback.backupManga,
                    backupCategories = fallback.backupCategories,
                    backupSources = fallback.backupSources,
                    backupPreferences = emptyList(),
                    backupSourcePreferences = emptyList(),
                    backupExtensionRepo = fallback.backupExtensionRepo,
                )
            }.getOrElse { fallbackError ->
                strictError.addSuppressed(fallbackError)
                throw BadBackupFormatException(strictError)
            }
        }
    }

    private companion object {
        const val DEFAULT_CATEGORY_TITLE = "Default"
    }
}
