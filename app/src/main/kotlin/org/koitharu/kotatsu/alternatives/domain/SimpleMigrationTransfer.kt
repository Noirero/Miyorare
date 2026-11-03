package org.koitharu.kotatsu.alternatives.domain

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ChapterReadOverridesMigrationStore
import org.koitharu.kotatsu.details.data.ChapterPersonalEntity
import org.koitharu.kotatsu.details.data.MangaNotesRepository
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.PrivateFavouriteEntity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.config.MangaReaderProfileStore
import javax.inject.Inject

/**
 * Transfers core library ownership independently of chapter identity.
 *
 * Untransferred optional records remain attached to the persisted source manga. In particular,
 * an unmappable group timeline retains its original member: deleting that member would cascade
 * and destroy the timeline. The old membership is not an active Library entry once favourites
 * have been moved to the destination.
 */
@Reusable
class SimpleMigrationTransfer @Inject constructor(
    private val database: MangaDatabase,
    private val settings: AppSettings,
    private val notes: MangaNotesRepository,
    private val readerProfiles: MangaReaderProfileStore,
    private val overrideDurability: ChapterReadOverridesMigrationStore,
) {
    suspend operator fun invoke(oldManga: Manga, newManga: Manga): Boolean =
        MigrateUseCase.transferMutex.withLock {
            require(oldManga.id != newManga.id) { "Replacement must use a distinct manga identity" }
            val oldId = oldManga.id
            val newId = newManga.id
            val sourceNote = notes.get(oldId)
            val destinationNote = notes.get(newId)
            if (sourceNote != null && destinationNote != null && sourceNote != destinationNote) {
                conflict("Destination manga has a different personal note", MigrationConflictReason.READER_SETTINGS)
            }
            val targets = chapterMap(oldManga, newManga)
            val oldOverrides = settings.getChapterReadOverrides(oldId)
            val originalTargetOverrides = settings.getChapterReadOverrides(newId)
            val mergedOverrides = LinkedHashMap(originalTargetOverrides)
            var preserved = oldOverrides.any { (id, value) ->
                val targetId = targets[id]?.id
                when {
                    targetId == null -> true
                    mergedOverrides[targetId] != null && mergedOverrides[targetId] != value -> true
                    else -> {
                        mergedOverrides[targetId] = value
                        false
                    }
                }
            }
            val oldProfile = readerProfiles.get(oldId)
            val targetProfile = readerProfiles.get(newId)
            if (oldProfile != null && targetProfile != null && oldProfile != targetProfile) preserved = true

            var preparedNote = false
            var preparedProfile = false
            var preparedOverrides = false
            val partial = try {
                // Commit non-Room destination copies before the Room ownership transaction.
                // The source copies are untouched until a successful commit.
                preparedNote = notes.prepareMove(oldId, newId)
                if (targetProfile == null) preparedProfile = readerProfiles.prepareMove(oldId, newId)
                if (mergedOverrides != originalTargetOverrides) {
                    settings.setChapterReadOverrides(newId, mergedOverrides)
                    preparedOverrides = true
                    overrideDurability.persistCurrent(newId)
                }
                database.withTransaction {
                    if (!database.getMangaDao().isMigrationEntryVisible(oldId) ||
                        database.getPrivateFavouritesDao().isPrivateOnly(newId)
                    ) throw MigrateUseCase.SourceChangedException()
                    val corePreserved = transferCore(oldManga, newManga, targets)
                    val optionalPreserved = transferOptional(oldManga, newManga, targets)
                    val additionalPreserved = preservesAdditionalState(oldId)
                    val needsPreservation = corePreserved || optionalPreserved || additionalPreserved || preserved
                    if (needsPreservation) {
                        database.getMangaDao().markPreservedSource(oldId, newId)
                    }
                    needsPreservation
                }
            } catch (failure: Throwable) {
                if (preparedOverrides) {
                    runCatching {
                        settings.setChapterReadOverrides(newId, originalTargetOverrides)
                        overrideDurability.persistCurrent(newId)
                    }.onFailure(failure::addSuppressed)
                }
                if (preparedProfile) runCatching { readerProfiles.rollbackPreparedMove(newId) }
                    .onFailure(failure::addSuppressed)
                if (preparedNote) runCatching { notes.rollbackPreparedMove(newId) }
                    .onFailure(failure::addSuppressed)
                throw failure
            }
            // A failed post-commit cleanup can leave an extra source copy, never missing data.
            val cleanupIncomplete = runCatching { notes.finishPreparedMove(oldId, newId) }.isFailure
            partial || cleanupIncomplete
        }

    private suspend fun transferCore(
        oldManga: Manga,
        newManga: Manga,
        targets: Map<Long, MangaChapter>,
    ): Boolean {
        val oldId = oldManga.id
        val newId = newManga.id
        val favourites = database.getFavouritesDao()
        val privateFavourites = database.getPrivateFavouritesDao()
        val sourceNormal = favourites.findAllRaw(oldId)
        val sourcePrivate = privateFavourites.findAllRaw(oldId)
        val targetNormal = favourites.findAllRaw(newId).associateBy { it.categoryId }
        val targetPrivate = privateFavourites.findAllRaw(newId).associateBy { it.categoryId }

        if (sourceNormal.isEmpty() && sourcePrivate.isEmpty()) {
            throw MigrateUseCase.SourceChangedException()
        }
        if (sourcePrivate.isNotEmpty() && sourceNormal.isEmpty() && targetNormal.isNotEmpty()) {
            conflict("Private-only source cannot replace an existing Normal library entry",
                MigrationConflictReason.LIBRARY_STATE)
        }
        if (sourceNormal.isNotEmpty() && targetPrivate.isNotEmpty() && targetNormal.isEmpty()) {
            conflict("Normal source cannot replace a Private-only library entry",
                MigrationConflictReason.LIBRARY_STATE)
        }
        for (source in sourceNormal) {
            targetNormal[source.categoryId]?.let { target ->
                if (!sameFavourite(source, target))
                    conflict("Destination has different Normal category ownership", MigrationConflictReason.LIBRARY_STATE)
            }
        }
        for (source in sourcePrivate) {
            targetPrivate[source.categoryId]?.let { target ->
                if (!samePrivateFavourite(source, target))
                    conflict("Destination has different Private category ownership", MigrationConflictReason.LIBRARY_STATE)
            }
        }

        val groups = database.getLibraryGroupsDao()
        var preservedTimeline = false
        // Check both spaces and all destination memberships before changing any ownership.
        val pending = listOf(FavouriteSpace.NORMAL, FavouriteSpace.PRIVATE).map { space ->
            val members = groups.findMembersByMangaIds(listOf(oldId), space.dbValue)
            if (members.size > 1)
                conflict("Source has multiple group memberships", MigrationConflictReason.LIBRARY_STATE)
            val source = members.singleOrNull()
            val existing = groups.findMembersByMangaIds(listOf(newId), space.dbValue)
            if (source != null && existing.any { it.groupId != source.groupId || it.position != source.position }) {
                conflict("Destination has conflicting group or position", MigrationConflictReason.LIBRARY_STATE)
            }
            source to existing
        }

        // New membership and optional timeline copies are committed atomically.
        for ((source, existing) in pending) {
            if (source == null) continue
            if (existing.isEmpty()) groups.insertMembers(listOf(source.copy(mangaId = newId)))
            val timeline = groups.findTimeline(source.groupId)
            val targetRows = timeline.filter { it.mangaId == newId }.associateBy { it.chapterId }
            var untransferred = false
            for (row in timeline.filter { it.mangaId == oldId }) {
                val newChapterId = if (row.chapterId == 0L) 0L else targets[row.chapterId]?.id
                if (newChapterId == null) {
                    untransferred = true
                    continue
                }
                val existingRow = targetRows[newChapterId]
                if (existingRow != null) {
                    if (existingRow.position != row.position) untransferred = true
                } else {
                    groups.insertTimeline(listOf(row.copy(mangaId = newId, chapterId = newChapterId)))
                }
            }
            if (untransferred) {
                // Timeline FK cascades from group member; keep source member as a hidden owner.
                preservedTimeline = true
            } else {
                groups.deleteMember(source.groupId, oldId)
            }
        }

        for (entry in sourceNormal) {
            if (targetNormal[entry.categoryId] == null) favourites.upsert(entry.copy(mangaId = newId))
        }
        for (entry in sourcePrivate) {
            if (targetPrivate[entry.categoryId] == null) privateFavourites.upsert(entry.copy(mangaId = newId))
        }
        if (sourceNormal.isNotEmpty()) favourites.delete(oldId)
        if (sourcePrivate.isNotEmpty()) privateFavourites.delete(oldId)
        return preservedTimeline
    }

    private suspend fun transferOptional(
        oldManga: Manga,
        newManga: Manga,
        targets: Map<Long, MangaChapter>,
    ): Boolean {
        val oldId = oldManga.id
        val newId = newManga.id
        var preserved = false

        val history = database.getHistoryDao()
        val sourceHistory = history.findIncludingDeleted(oldId)
        if (sourceHistory != null) {
            val targetChapter = if (sourceHistory.chapterId == 0L) null else targets[sourceHistory.chapterId]
            val existing = history.findIncludingDeleted(newId)
            if (targetChapter != null && existing == null) {
                history.insert(sourceHistory.copy(
                    mangaId = newId,
                    chapterId = targetChapter.id,
                    chaptersCount = newManga.chapters.orEmpty().count { it.branch == targetChapter.branch }
                        .takeIf { it > 0 } ?: sourceHistory.chaptersCount,
                ))
                if (sourceHistory.deletedAt == 0L) history.delete(oldId)
            } else {
                preserved = true
            }
        }

        val bookmarks = database.getBookmarksDao()
        val destinationBookmarks = bookmarks.findAll(newId).associateBy { it.pageId }
        for (source in bookmarks.findAll(oldId)) {
            val targetChapter = targets[source.chapterId]
            if (targetChapter == null) {
                preserved = true
                continue
            }
            val migrated = source.copy(mangaId = newId, chapterId = targetChapter.id)
            val existing = destinationBookmarks[migrated.pageId]
            if (existing == null) bookmarks.upsert(listOf(migrated))
            else if (existing != migrated) preserved = true
        }

        val chapterPersonal = database.getChapterPersonalDao()
        val sourceByIdentity = oldManga.chapters.orEmpty().associateBy { it.source.name to it.url }
        for (source in chapterPersonal.findAll(listOf(oldId))) {
            val oldChapter = sourceByIdentity[source.source to source.url]
            val targetChapter = oldChapter?.let { targets[it.id] }
            if (targetChapter == null) {
                preserved = true
                continue
            }
            val target = chapterPersonal.find(newId, targetChapter.source.name, targetChapter.url)
            val migrated = ChapterPersonalEntity(
                mangaId = newId, source = targetChapter.source.name, url = targetChapter.url,
                rating = source.rating, note = source.note,
            )
            if (target == null) chapterPersonal.upsert(migrated)
            else if (target != migrated) {
                // Distinct destination personal data is never overwritten.
                if ((target.rating != null && migrated.rating != null && target.rating != migrated.rating) ||
                    (!target.note.isNullOrBlank() && !migrated.note.isNullOrBlank() && target.note != migrated.note)
                ) preserved = true
                else chapterPersonal.upsert(target.copy(
                    rating = target.rating ?: migrated.rating,
                    note = target.note?.takeIf { it.isNotBlank() } ?: migrated.note,
                ))
            }
        }
        // Original bookmark/chapter-personal entries remain on the inactive source manga
        // for recovery. Only safely mapped destination copies are created.
        return preserved
    }

    private suspend fun preservesAdditionalState(oldId: Long): Boolean {
        if (database.getPreferencesDao().find(oldId) != null) return true
        if (database.getStatsDao().findAll(oldId).isNotEmpty()) return true
        if (database.getTracksDao().find(oldId) != null) return true
        if (database.getTrackLogsDao().findAllForManga(oldId).isNotEmpty()) return true
        if (database.getScrobblingDao().findAll(oldId).isNotEmpty()) return true
        if (database.getFavouriteDownloadIndexDao().findEntries(listOf(oldId)).isNotEmpty()) return true
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT EXISTS(SELECT 1 FROM reader_journey_chapters WHERE manga_id = ?)",
            arrayOf(oldId),
        )
        return cursor.use { it.moveToFirst() && it.getInt(0) != 0 }
    }

    /** Unique, injective number+volume matches only. No index, title or fuzzy fallback. */
    private fun chapterMap(oldManga: Manga, newManga: Manga): Map<Long, MangaChapter> {
        val candidates = newManga.chapters.orEmpty().filter { it.number > 0f }
            .groupBy { it.volume to it.number }
        val provisional = oldManga.chapters.orEmpty().mapNotNull { chapter ->
            if (chapter.number <= 0f) return@mapNotNull null
            val matches = candidates[chapter.volume to chapter.number].orEmpty()
            val target = if (matches.size == 1) matches.single()
                else matches.filter { it.branch == chapter.branch }.singleOrNull()
            // A chapter number alone does not verify equivalent content or page identity
            // across parsers. Copy optional state only for the exact same provider chapter.
            target?.takeIf {
                it.source.name == chapter.source.name && it.url == chapter.url &&
                    it.title.trim().equals(chapter.title.trim(), ignoreCase = true)
            }?.let { chapter.id to it }
        }.toMap()
        val counts = provisional.values.groupingBy { it.id }.eachCount()
        return provisional.filterValues { counts[it.id] == 1 }
    }

    private fun sameFavourite(a: FavouriteEntity, b: FavouriteEntity): Boolean =
        a.categoryId == b.categoryId && a.sortKey == b.sortKey && a.isPinned == b.isPinned &&
            a.createdAt == b.createdAt && a.deletedAt == b.deletedAt

    private fun samePrivateFavourite(a: PrivateFavouriteEntity, b: PrivateFavouriteEntity): Boolean =
        a.categoryId == b.categoryId && a.sortKey == b.sortKey && a.isPinned == b.isPinned &&
            a.createdAt == b.createdAt && a.deletedAt == b.deletedAt

    private fun conflict(message: String, reason: MigrationConflictReason): Nothing =
        throw LosslessMigrationConflictException(message, reason)
}