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
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.PrivateFavouriteEntity
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.mihonMangaId
import javax.inject.Inject
import javax.inject.Singleton

enum class MihonRestoreTarget {
    NORMAL,
    PRIVATE,
}

/**
 * Moves only the library memberships created by a Mihon restore into the Private favourite space.
 *
 * Mihon itself has no concept of Miyorare's Normal/Private split, while [MihonBackupManager] is kept
 * backwards-compatible and restores into the Normal tables first. A snapshot taken immediately before
 * restore lets this class distinguish pre-existing Normal memberships from rows created by the import.
 * Existing Normal favourites are never removed just because the user chose Private as the restore target.
 *
 * Category sort is restored from Mihon's category flags when available. This keeps e.g. "Date added /
 * newest first" identical after Backup -> Restore. Older/forked backups without a supported sort flag
 * safely fall back to the category order produced by the normal restore path.
 */
@Singleton
class MihonRestoreTargetMigrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: MangaDatabase,
    private val extensionManager: MihonExtensionManager,
    private val contentTypeStore: FavouriteContentTypeStore,
) {

    data class NormalSnapshot(
        val memberships: Set<Pair<Long, Long>>,
        val categories: Map<Long, CategoryState>,
    )

    data class CategoryState(
        val order: String,
        val visible: Boolean,
    )

    private val proto = ProtoBuf

    suspend fun snapshotNormalState(): NormalSnapshot = withContext(Dispatchers.IO) {
        val memberships = db.getFavouritesDao().findMemberships()
            .mapTo(LinkedHashSet()) { it.mangaId to it.categoryId }
        val categories = db.getFavouriteCategoriesDao().findAll().associate { entity ->
            entity.categoryId.toLong() to CategoryState(
                order = entity.order,
                visible = entity.isVisibleInLibrary,
            )
        }
        NormalSnapshot(memberships = memberships, categories = categories)
    }

    suspend fun moveRestoredLibraryToPrivate(uri: Uri, snapshot: NormalSnapshot) = withContext(Dispatchers.IO) {
        val backup = decode(uri)
        runCatching { extensionManager.ensureReady() }

        val categoryTypesToCommit = LinkedHashMap<Long, FavouriteContentType>()
        db.withTransaction {
            moveInsideTransaction(backup, snapshot, categoryTypesToCommit)
        }
        // FavouriteContentTypeStore is SharedPreferences-backed; update only after Room commits.
        categoryTypesToCommit.forEach { (categoryId, type) ->
            contentTypeStore.setCategoryType(categoryId, type)
        }
    }

    private suspend fun moveInsideTransaction(
        backup: MihonBackup,
        snapshot: NormalSnapshot,
        categoryTypesToCommit: MutableMap<Long, FavouriteContentType>,
    ) {
        val categoryDao = db.getFavouriteCategoriesDao()
        val normalDao = db.getFavouritesDao()
        val privateDao = db.getPrivateFavouritesDao()
        val backupCategories = backup.backupCategories.associateBy(MihonBackupCategory::order)
        val normalCategories = categoryDao.findAll().toMutableList()
        val privateCategories = categoryDao.findAllInSpace(FavouriteSpace.PRIVATE.dbValue).toMutableList()
        val createdNormalCategoryIds = normalCategories
            .asSequence()
            .map { it.categoryId.toLong() }
            .filterNot { it in snapshot.categories }
            .toMutableSet()
        val privateFallbackByType = HashMap<FavouriteContentType, Long>()

        for (item in backup.backupManga) {
            if (!item.isLibraryEntry()) continue
            val sourceName = resolveStoredSourceName(item.source)
            val mangaId = mihonMangaId(sourceName, item.url)
            if (db.getMangaDao().find(mangaId) == null) continue

            val type = contentTypeForSource(item.source)
            val targets = item.categories.mapNotNull { order ->
                val source = backupCategories[order] ?: return@mapNotNull null
                val title = source.name.trim()
                if (title.isEmpty()) return@mapNotNull null
                TargetCategory(title, decodeMihonCategorySortOrder(source.flags))
            }.distinctBy { it.title }

            val effectiveTargets = targets.ifEmpty {
                listOf(TargetCategory(DEFAULT_CATEGORY_TITLE, null))
            }

            effectiveTargets.forEachIndexed { index, target ->
                val normalCategory = findCategory(normalCategories, target.title, type)
                val fallbackOrder = normalCategory?.order
                    ?.let { runCatching { ListSortOrder.valueOf(it) }.getOrNull() }
                    ?: ListSortOrder.ALPHABETIC
                val restoredOrder = target.order ?: fallbackOrder
                val privateCategoryId = if (target.title == DEFAULT_CATEGORY_TITLE && target.order == null) {
                    privateFallbackByType.getOrPut(type) {
                        findOrCreatePrivateCategory(
                            title = target.title,
                            type = type,
                            order = restoredOrder,
                            privateCategories = privateCategories,
                            categoryTypesToCommit = categoryTypesToCommit,
                        )
                    }
                } else {
                    findOrCreatePrivateCategory(
                        title = target.title,
                        type = type,
                        order = restoredOrder,
                        privateCategories = privateCategories,
                        categoryTypesToCommit = categoryTypesToCommit,
                    )
                }

                privateDao.upsert(
                    PrivateFavouriteEntity(
                        mangaId = mangaId,
                        categoryId = privateCategoryId,
                        sortKey = index,
                        isPinned = false,
                        createdAt = item.dateAdded.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        deletedAt = 0L,
                    ),
                )

                // Remove only membership rows introduced by this restore. Anything that was already
                // in Normal before the operation remains there as an intentional dual membership.
                if (normalCategory != null) {
                    val membership = mangaId to normalCategory.categoryId.toLong()
                    if (membership !in snapshot.memberships) {
                        normalDao.delete(mangaId, normalCategory.categoryId.toLong())
                    }
                }
            }
        }

        // The normal restore/repair path may update sort/visibility on reused categories. A Private
        // target must not mutate pre-existing Normal organisation, so restore that state verbatim.
        snapshot.categories.forEach { (id, state) ->
            runCatching {
                categoryDao.updateOrder(id, state.order)
                categoryDao.updateVisibility(id, state.visible)
            }
        }

        // Do not leave empty categories visible in Normal when they only existed as a staging target.
        createdNormalCategoryIds.forEach { categoryId ->
            if (normalDao.findAll(categoryId).isEmpty()) {
                categoryDao.delete(categoryId)
                contentTypeStore.removeCategories(listOf(categoryId))
            }
        }
    }

    private suspend fun findOrCreatePrivateCategory(
        title: String,
        type: FavouriteContentType,
        order: ListSortOrder,
        privateCategories: MutableList<FavouriteCategoryEntity>,
        categoryTypesToCommit: MutableMap<Long, FavouriteContentType>,
    ): Long {
        findCategory(privateCategories, title, type)?.let { existing ->
            val id = existing.categoryId.toLong()
            db.getFavouriteCategoriesDao().updateOrder(id, order.name)
            db.getFavouriteCategoriesDao().updateVisibility(id, true)
            categoryTypesToCommit[id] = type
            return id
        }

        val dao = db.getFavouriteCategoriesDao()
        val entity = FavouriteCategoryEntity(
            categoryId = 0,
            createdAt = System.currentTimeMillis(),
            sortKey = dao.getNextSortKey(FavouriteSpace.PRIVATE),
            title = title,
            order = order.name,
            track = false,
            downloadNewChapters = false,
            isVisibleInLibrary = true,
            deletedAt = 0L,
            space = FavouriteSpace.PRIVATE.dbValue,
        )
        val id = dao.insert(entity)
        privateCategories += entity.copy(categoryId = id.toInt())
        categoryTypesToCommit[id] = type
        return id
    }

    private fun findCategory(
        categories: List<FavouriteCategoryEntity>,
        title: String,
        type: FavouriteContentType,
    ): FavouriteCategoryEntity? = categories.firstOrNull { category ->
        val id = category.categoryId.toLong()
        category.title == title && contentTypeStore.isCategoryForType(id, type)
    }

    private fun MihonBackupManga.isLibraryEntry(): Boolean = favorite || categories.isNotEmpty()

    private fun resolveStoredSourceName(sourceId: Long): String =
        extensionManager.getMihonMangaSourceById(sourceId)?.name
            ?: if (sourceId > 0L) "MIHON_$sourceId" else "UNKNOWN"

    private fun contentTypeForSource(sourceId: Long): FavouriteContentType =
        if (MihonExtensionManager.isNovelSourceId(sourceId)) FavouriteContentType.NOVEL
        else FavouriteContentType.MANGA

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

    private data class TargetCategory(
        val title: String,
        val order: ListSortOrder?,
    )

    private companion object {
        const val DEFAULT_CATEGORY_TITLE = "Default"
    }
}
