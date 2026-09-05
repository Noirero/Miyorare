package org.koitharu.kotatsu.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.source
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.model.MihonBackup
import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupChapter
import org.koitharu.kotatsu.backup.model.MihonBackupExtensionRepo
import org.koitharu.kotatsu.backup.model.MihonBackupFallback
import org.koitharu.kotatsu.backup.model.MihonBackupManga
import org.koitharu.kotatsu.backup.model.MihonBackupPreference
import org.koitharu.kotatsu.backup.model.MihonBackupSource
import org.koitharu.kotatsu.backup.model.MihonBackupSourcePreferences
import org.koitharu.kotatsu.backup.model.MihonBooleanPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonFloatPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonIntPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonLongPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonStringPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonStringSetPreferenceValue
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.TagEntity
import org.koitharu.kotatsu.core.exceptions.BadBackupFormatException
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.util.progress.Progress
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.mihonChapterId
import org.koitharu.kotatsu.mihon.model.mihonMangaId
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.util.longHashCode
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreRecord
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreRegistry
import org.koitharu.kotatsu.settings.sources.catalog.normalizeExtensionStoreUrl
import org.koitharu.kotatsu.settings.sources.catalog.stableExtensionStoreId
import org.koitharu.kotatsu.stats.data.StatsEntity
import org.koitharu.kotatsu.tracker.data.TrackEntity
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

internal fun decodeMihonCategorySortOrder(flags: Long): ListSortOrder? {
  val isAscending = flags and MIHON_CATEGORY_SORT_DIRECTION_MASK != 0L
  return when (flags and MIHON_CATEGORY_SORT_TYPE_MASK) {
    MIHON_SORT_ALPHABETICAL -> if (isAscending) ListSortOrder.ALPHABETIC else ListSortOrder.ALPHABETIC_REVERSE
    MIHON_SORT_LAST_READ -> if (isAscending) ListSortOrder.LONG_AGO_READ else ListSortOrder.LAST_READ
    MIHON_SORT_UNREAD_COUNT -> if (isAscending) ListSortOrder.UNREAD_COUNT_ASC else ListSortOrder.UNREAD_COUNT
    MIHON_SORT_TOTAL_CHAPTERS -> if (isAscending) ListSortOrder.TOTAL_CHAPTERS_ASC else ListSortOrder.TOTAL_CHAPTERS
    MIHON_SORT_LATEST_CHAPTER -> if (isAscending) ListSortOrder.LATEST_CHAPTER_ASC else ListSortOrder.LATEST_CHAPTER
    MIHON_SORT_DATE_ADDED -> if (isAscending) ListSortOrder.OLDEST else ListSortOrder.NEWEST
    // Current Mihon sorts with no exact DropSauce equivalent. Returning null is deliberate:
    // existing categories keep their local sort, while newly-created categories use the explicit
    // alphabetical fallback below instead of silently pretending these values mean NEWEST.
    MIHON_SORT_LAST_UPDATE,
    MIHON_SORT_CHAPTER_FETCH_DATE,
    MIHON_SORT_TRACKER_MEAN,
    MIHON_SORT_RANDOM,
    -> null
    else -> null
  }
}

private const val MIHON_CATEGORY_SORT_TYPE_MASK = 0b00111100L
private const val MIHON_CATEGORY_SORT_DIRECTION_MASK = 0b01000000L
private const val MIHON_SORT_ALPHABETICAL = 0b00000000L
private const val MIHON_SORT_LAST_READ = 0b00000100L
private const val MIHON_SORT_LAST_UPDATE = 0b00001000L
private const val MIHON_SORT_UNREAD_COUNT = 0b00001100L
private const val MIHON_SORT_TOTAL_CHAPTERS = 0b00010000L
private const val MIHON_SORT_LATEST_CHAPTER = 0b00010100L
private const val MIHON_SORT_CHAPTER_FETCH_DATE = 0b00011000L
private const val MIHON_SORT_DATE_ADDED = 0b00011100L
private const val MIHON_SORT_TRACKER_MEAN = 0b00100000L
private const val MIHON_SORT_RANDOM = 0b00111100L

@Singleton
class MihonBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: MangaDatabase,
    private val settings: AppSettings,
    private val mihonExtensionManager: MihonExtensionManager,
    private val mihonExtensionLoader: MihonExtensionLoader,
    private val extensionStoreRegistry: ExtensionStoreRegistry,
    private val favouriteContentTypeStore: FavouriteContentTypeStore,
) {

  data class Options(
    val libraryEntries: Boolean = true,
    val appSettings: Boolean = true,
    val sourceSettings: Boolean = true,
    val extensionRepoSettings: Boolean = true,
    val tracking: Boolean = true,
  )

  data class RestoreReport(
    val restoredMangaCount: Int,
    val restoredTrackingCount: Int,
    val missingSources: List<String>,
    val missingTrackers: List<Int>,
  )

  private class RestoreAccumulator(
    missingSources: List<String>,
    missingTrackers: List<Int>,
  ) {
    val missingSources = missingSources.toMutableSet()
    val missingTrackers = missingTrackers.toMutableSet()
    val chapterReadOverrides = LinkedHashMap<Long, Map<Long, Boolean>>()
    val notes = LinkedHashMap<Long, String>()
    val categoryTypes = LinkedHashMap<Long, FavouriteContentType>()
    var restoredMangaCount = 0
    var restoredTrackingCount = 0

    fun toReport() = RestoreReport(
      restoredMangaCount = restoredMangaCount,
      restoredTrackingCount = restoredTrackingCount,
      missingSources = missingSources.sorted(),
      missingTrackers = missingTrackers.sorted(),
    )
  }

  private data class PendingRestore(
    val manga: MangaEntity,
    val tags: List<TagEntity>,
    val chapters: List<ChapterEntity>,
    val favourites: List<FavouriteEntity>,
    val history: HistoryEntity?,
    val stats: StatsEntity?,
    val bookmarks: List<BookmarkEntity>,
    val scrobblings: List<ScrobblingEntity>,
    val track: TrackEntity?,
    val readOverrides: Map<Long, Boolean>,
    val note: String?,
  )

  /**
   * Maps Mihon backup categories onto DropSauce favourite categories.
   *
   * Mihon stores the category order in every manga entry. DropSauce Noirero additionally separates
   * Manga and Novel shelves, so a mixed Mihon category needs one category id for each content type.
   */
  private inner class CategoryResolver(
    private val backupCategories: List<MihonBackupCategory>,
    private val accumulator: RestoreAccumulator,
  ) {
    private val dao = db.getFavouriteCategoriesDao()
    private val idByOrderAndType = HashMap<Pair<Long, FavouriteContentType>, Long>()
    private val idByTitleAndType = HashMap<Pair<String, FavouriteContentType>, Long>()
    private val defaultCategoryIdByType = HashMap<FavouriteContentType, Long>()

    suspend fun prepare(manga: List<MihonBackupManga>) {
      dao.findAll().forEach { category ->
        val id = category.categoryId.toLong()
        val type = if (favouriteContentTypeStore.isCategoryForType(id, FavouriteContentType.NOVEL)) {
          FavouriteContentType.NOVEL
        } else {
          FavouriteContentType.MANGA
        }
        idByTitleAndType[category.title to type] = id
      }

      val typesByOrder = HashMap<Long, MutableSet<FavouriteContentType>>()
      val uncategorizedTypes = linkedSetOf<FavouriteContentType>()
      manga.asSequence().filter { it.favorite }.forEach { item ->
        val type = contentTypeForSource(item.source)
        if (item.categories.isEmpty()) {
          uncategorizedTypes += type
        } else {
          item.categories.forEach { order ->
            typesByOrder.getOrPut(order) { linkedSetOf() } += type
          }
        }
      }

      backupCategories.sortedBy { it.order }.forEach { category ->
        val title = category.name.trim()
        if (title.isEmpty()) return@forEach
        val sortOrder = decodeMihonCategorySortOrder(category.flags)
        typesByOrder[category.order].orEmpty().forEach { type ->
          idByOrderAndType[category.order to type] = categoryIdForTitle(title, type, sortOrder)
        }
      }

      manga.asSequence().filter { it.favorite }.forEach { item ->
        val type = contentTypeForSource(item.source)
        if (item.categories.none { idByOrderAndType.containsKey(it to type) }) {
          uncategorizedTypes += type
        }
      }
      uncategorizedTypes.forEach { type ->
        defaultCategoryIdByType[type] = categoryIdForTitle(DEFAULT_CATEGORY_TITLE, type)
      }
    }

    fun resolve(orders: List<Long>, type: FavouriteContentType): List<Long> {
      val ids = orders.mapNotNull { idByOrderAndType[it to type] }.distinct()
      return ids.ifEmpty { listOfNotNull(defaultCategoryIdByType[type]) }
    }

    private suspend fun categoryIdForTitle(
      title: String,
      type: FavouriteContentType,
      sortOrder: ListSortOrder? = null,
    ): Long {
      val key = title to type
      idByTitleAndType[key]?.let { id ->
        sortOrder?.let { dao.updateOrder(id, it.name) }
        accumulator.categoryTypes[id] = type
        return id
      }
      val id = dao.insert(
        FavouriteCategoryEntity(
          categoryId = 0,
          createdAt = System.currentTimeMillis(),
          sortKey = dao.getNextSortKey(),
          title = title,
          // Unsupported Mihon sorts have no exact DropSauce equivalent. Use Mihon's default
          // alphabetical order for a new category; existing categories keep their local order above.
          order = (sortOrder ?: ListSortOrder.ALPHABETIC).name,
          track = true,
          downloadNewChapters = false,
          isVisibleInLibrary = true,
          deletedAt = 0,
        ),
      )
      idByTitleAndType[key] = id
      accumulator.categoryTypes[id] = type
      return id
    }
  }

  private val proto = ProtoBuf

  suspend fun analyzeBackup(uri: Uri, options: Options = Options()): RestoreReport = withContext(Dispatchers.IO) {
    val backup = decode(uri)
    // Source information is needed for accurate diagnostics and Manga/Novel classification, but a
    // broken third-party extension must never make an otherwise valid backup impossible to inspect.
    runCatching { mihonExtensionManager.ensureReady() }
    buildDiagnostics(backup, options).toReport()
  }

  suspend fun restoreBackup(uri: Uri, options: Options = Options()): RestoreReport {
    return withContext(Dispatchers.IO) {
      val backup = decode(uri)
      runCatching { mihonExtensionManager.ensureReady() }
      val accumulator = buildDiagnostics(backup, options)

      db.withTransaction {
        if (options.libraryEntries) {
          val categoryResolver = CategoryResolver(backup.backupCategories, accumulator)
          categoryResolver.prepare(backup.backupManga)
          restoreManga(backup, options, accumulator, categoryResolver)
          removeEmptyReadLaterCategory()
        }
      }

      // These are SharedPreferences / extension-store writes, not Room data. Keep them outside the
      // database transaction so a preference or third-party source failure cannot leave Room in an
      // unnecessarily long transaction and so UI state is only written after the DB commit succeeds.
      if (options.appSettings) {
        restorePreferences(backup.backupPreferences)
      }
      if (options.sourceSettings) {
        restoreSourcePreferences(backup.backupSourcePreferences, accumulator)
      }
      if (options.extensionRepoSettings) {
        restoreExtensionRepo(backup.backupExtensionRepo)
      }
      applyRestoredUiState(accumulator)
      accumulator.toReport()
    }
  }

  // The built-in "Read later" category is pre-populated on DB creation and a Mihon backup never
  // carries it, so it lingers empty after a restore. Drop it when empty; a populated one stays.
  private suspend fun removeEmptyReadLaterCategory() {
    val readLaterTitle = context.getString(R.string.read_later)
    val dao = db.getFavouriteCategoriesDao()
    val readLater = dao.findAll().firstOrNull { it.title == readLaterTitle } ?: return
    if (db.getFavouritesDao().findAll(readLater.categoryId.toLong()).isEmpty()) {
      dao.delete(readLater.categoryId.toLong())
    }
  }

  private fun buildDiagnostics(backup: MihonBackup, options: Options): RestoreAccumulator {
    val missingSources = if (options.libraryEntries) {
      val sourceTitles = backup.backupSources.associate { it.sourceId to it.name }
      backup.backupManga
        .asSequence()
        .map { it.source }
        .filter { it > 0L }
        .distinct()
        .filter { mihonExtensionManager.getMihonMangaSourceById(it) == null }
        .map { sourceId -> sourceTitles[sourceId].orEmpty().ifBlank { sourceId.toString() } }
        .toList()
    } else {
      emptyList()
    }
    val missingTrackers = if (options.tracking) {
      backup.backupManga
        .asSequence()
        .flatMap { it.tracking.asSequence() }
        .map { it.syncId }
        .filter { mihonTrackerToScrobblerId(it) == null }
        .distinct()
        .toList()
    } else {
      emptyList()
    }
    return RestoreAccumulator(
      missingSources = missingSources,
      missingTrackers = missingTrackers,
    )
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
        decodeFallback(payload)
      }.getOrElse { fallbackError ->
        strictError.addSuppressed(fallbackError)
        throw BadBackupFormatException(strictError)
      }
    }
  }

  private fun decodeFallback(payload: ByteArray): MihonBackup {
    val fallback = proto.decodeFromByteArray(MihonBackupFallback.serializer(), payload)
    return MihonBackup(
      backupManga = fallback.backupManga,
      backupCategories = fallback.backupCategories,
      backupSources = fallback.backupSources,
      backupPreferences = emptyList(),
      backupSourcePreferences = emptyList(),
      backupExtensionRepo = fallback.backupExtensionRepo,
    )
  }

  private suspend fun restoreManga(
    backup: MihonBackup,
    options: Options,
    accumulator: RestoreAccumulator,
    categoryResolver: CategoryResolver,
  ) {
    val now = System.currentTimeMillis()
    val totalChapters = backup.backupManga.sumOf { it.chapters.size }

    val pending = backup.backupManga.map { item ->
      val sourceName = resolveStoredSourceName(item.source, backup.backupSources)
      // Use the same identities as the live Mihon adapter. Otherwise the first network refresh
      // replaces every restored chapter ID, losing the reading branch/checkpoint.
      val mangaId = mihonMangaId(sourceName, item.url)
      val tags = item.genre.mapNotNull { title ->
        val clean = title.trim()
        if (clean.isBlank()) {
          null
        } else {
          TagEntity(
            id = "${clean.lowercase(Locale.ROOT)}:$sourceName".longHashCode(),
            title = clean,
            key = clean.lowercase(Locale.ROOT),
            source = sourceName,
            isPinned = false,
          )
        }
      }
      // Mihon assigns sourceOrder 0 to the newest chapter (sources list newest-first), whereas
      // DropSauce reads chapters in ascending `index` order (oldest first). Reverse the order so
      // chapter ordering — and therefore reading progress — comes out right.
      val orderedBackupChapters = item.chapters.sortedWith(
        compareByDescending<MihonBackupChapter> { it.sourceOrder }.thenBy { it.chapterNumber },
      )
      val chapters = orderedBackupChapters.mapIndexed { index, chapter ->
        ChapterEntity(
          chapterId = mihonChapterId(sourceName, chapter.url),
          mangaId = mangaId,
          title = chapter.name,
          number = chapter.chapterNumber,
          volume = 0,
          url = chapter.url,
          scanlator = chapter.scanlator,
          uploadDate = chapter.dateUpload,
          branch = chapter.scanlator?.trim()?.takeIf { it.isNotEmpty() },
          source = sourceName,
          index = index,
        )
      }
      val chapterByUrl = chapters.associateBy { it.url }
      val backupChapterByUrl = orderedBackupChapters.associateBy { it.url }
      val restoredReadCount = orderedBackupChapters.count { it.read }
      val contentType = contentTypeForSource(item.source)
      val categoryIds = if (item.favorite) {
        categoryResolver.resolve(item.categories, contentType)
      } else {
        emptyList()
      }
      val favourites = categoryIds.mapIndexed { sortIndex, categoryId ->
        FavouriteEntity(
          mangaId = mangaId,
          categoryId = categoryId,
          sortKey = sortIndex,
          isPinned = false,
          // Mihon sorts Date Added by the stored manga.dateAdded value, including legacy 0 values.
          // Replacing 0 with the restore time changes the order of old/migrated libraries.
          createdAt = item.dateAdded,
          deletedAt = 0,
        )
      }
      val bookmarks = orderedBackupChapters.asSequence()
        .filter { it.bookmark }
        .mapNotNull { chapter ->
          val chapterEntity = chapterByUrl[chapter.url] ?: return@mapNotNull null
          val page = chapter.lastPageRead.toInt().coerceAtLeast(0)
          BookmarkEntity(
            mangaId = mangaId,
            pageId = "$mangaId:${chapter.url}:$page".longHashCode(),
            chapterId = chapterEntity.chapterId,
            page = page,
            scroll = 0,
            imageUrl = "",
            createdAt = now,
            percent = 0f,
          )
        }
        .toList()

      // Mihon's positive history is its Continue Reading position. Chapter flags are only a
      // fallback for backups without history, so sampling a later chapter does not jump progress.
      val progressedChapter = chapters.lastOrNull { chapterEntity ->
        val backupChapter = backupChapterByUrl[chapterEntity.url]
        backupChapter?.let { it.read || it.lastPageRead > 0 } == true
      }
      val latestHistory = item.history
        .filter { it.lastRead > 0 }
        .maxByOrNull { it.lastRead }
      val currentChapter = latestHistory?.url?.let(chapterByUrl::get) ?: progressedChapter
      val currentHistory = currentChapter?.url?.let { url ->
        item.history.filter { it.url == url && it.lastRead > 0 }.maxByOrNull { it.lastRead }
      }
      val history = if (currentChapter != null) {
        val backupChapter = backupChapterByUrl[currentChapter.url]
        val restoredPage = backupChapter?.lastPageRead?.toInt()?.coerceAtLeast(0) ?: 0
        // Mihon's Last Read sort uses actual history.readAt only. A manga that is merely marked read
        // but has no history must stay at 0 instead of receiving a synthetic restore/date-added time.
        val updatedAt = currentHistory?.lastRead ?: 0L
        HistoryEntity(
          mangaId = mangaId,
          createdAt = updatedAt,
          updatedAt = updatedAt,
          chapterId = currentChapter.chapterId,
          page = restoredPage,
          scroll = 0f,
          // Mihon's unread count is totalChapters - readCount. Using the exact backup read flags here
          // makes DropSauce's percentage-backed unread sort represent the same quantity after restore.
          percent = computeReadPercent(
            readChapters = restoredReadCount,
            chaptersCount = chapters.size,
          ),
          deletedAt = 0,
          chaptersCount = chapters.size,
        )
      } else {
        null
      }

      // DropSauce normally derives read state from the current chapter. Mihon, however, stores an
      // explicit read flag per chapter and allows gaps. Persist only the flags that differ from the
      // derived contiguous state so a large library does not unnecessarily bloat preferences.
      val currentIndex = currentChapter?.index
      val readOverrides = buildMap<Long, Boolean> {
        orderedBackupChapters.forEach { backupChapter ->
          val chapter = chapterByUrl[backupChapter.url] ?: return@forEach
          val derivedRead = currentIndex != null && chapter.index <= currentIndex
          if (backupChapter.read != derivedRead) {
            put(chapter.chapterId, backupChapter.read)
          }
        }
      }

      // Seed update detection from the newest chapter in the stream the user was reading.
      // Without this, a restored manga starts with an empty track and the first refresh
      // silently treats chapters released since the backup as an already-known baseline.
      val preferredBranch = currentChapter?.branch
        ?: chapters.groupBy { it.branch }.maxByOrNull { it.value.size }?.key
      val lastTrackedChapter = chapters.lastOrNull { it.branch == preferredBranch }
      val track = lastTrackedChapter?.let { chapter ->
        TrackEntity(
          mangaId = mangaId,
          lastChapterId = chapter.chapterId,
          newChapters = 0,
          lastCheckTime = 0L,
          lastChapterDate = chapter.uploadDate,
          lastResult = TrackEntity.RESULT_NONE,
          lastError = null,
        )
      }
      val stats = if ((currentHistory?.readDuration ?: 0L) > 0L && history != null) {
        StatsEntity(
          mangaId = mangaId,
          startedAt = (history.updatedAt - currentHistory!!.readDuration).coerceAtLeast(0L),
          duration = currentHistory.readDuration,
          pages = (history.page + 1).coerceAtLeast(1),
        )
      } else {
        null
      }
      val scrobblings = if (options.tracking) {
        item.tracking.mapNotNull { tracking ->
          val scrobblerId = mihonTrackerToScrobblerId(tracking.syncId)
          if (scrobblerId == null) {
            accumulator.missingTrackers += tracking.syncId
            return@mapNotNull null
          }
          val targetId = tracking.mediaId.takeIf { it > 0 }
            ?: tracking.mediaIdInt.toLong().takeIf { it > 0 }
            ?: return@mapNotNull null
          val remoteEntryId = tracking.libraryId.toInt().takeIf { it > 0 }
            ?: targetId.toInt().takeIf { it > 0 }
            ?: return@mapNotNull null
          ScrobblingEntity(
            scrobbler = scrobblerId,
            id = remoteEntryId,
            mangaId = mangaId,
            targetId = targetId,
            status = decodeTrackingStatus(tracking.syncId, tracking.status),
            chapter = tracking.lastChapterRead.toInt().coerceAtLeast(0),
            comment = null,
            rating = decodeTrackingRating(tracking.syncId, tracking.score),
          )
        }
      } else {
        emptyList()
      }

      PendingRestore(
        manga = MangaEntity(
          id = mangaId,
          title = item.title,
          altTitles = null,
          url = item.url,
          publicUrl = item.url,
          rating = -1f,
          isNsfw = false,
          contentRating = null,
          coverUrl = item.thumbnailUrl.orEmpty(),
          largeCoverUrl = null,
          state = decodeMangaState(item.status),
          authors = buildAuthors(item),
          description = item.description,
          source = sourceName,
          sourceTitle = resolveSourceTitle(item.source, backup.backupSources),
        ),
        tags = tags,
        chapters = chapters,
        favourites = favourites,
        history = history,
        stats = stats,
        bookmarks = bookmarks,
        scrobblings = scrobblings,
        track = track,
        readOverrides = readOverrides,
        note = item.notes.trim().takeIf { it.isNotEmpty() },
      )
    }

    pending.flatMapTo(linkedSetOf()) { it.tags }
      .takeIf { it.isNotEmpty() }
      ?.let { db.getTagsDao().upsert(it.toList()) }

    // Mihon restores are merges. If a manga already exists locally, keep its live metadata and only
    // add tags from the backup instead of replacing newer source data with an older backup snapshot.
    pending.forEach { item ->
      val existing = db.getMangaDao().find(item.manga.id)
      if (existing == null) {
        db.getMangaDao().upsert(item.manga, item.tags)
      } else {
        val mergedTags = (existing.tags + item.tags).distinctBy { it.id }
        db.getMangaDao().upsert(existing.manga, mergedTags)
      }
    }

    // Do not reset update tracking when restoring an older backup over an installation that already
    // knows about newer chapters.
    pending.forEach { item ->
      item.track?.let { restoredTrack ->
        val existingTrack = db.getTracksDao().find(item.manga.id)
        if (existingTrack == null || restoredTrack.lastChapterDate > existingTrack.lastChapterDate) {
          db.getTracksDao().upsert(restoredTrack)
        }
      }
    }
    pending.forEach { item -> item.favourites.forEach { db.getFavouritesDao().upsert(it) } }

    if (totalChapters > 0) {
      // Exact chapter progress starts only when chapter persistence starts. Building the restore
      // snapshot and writing manga metadata can take a while for huge backups, so showing 0/50000
      // during that preparation makes the restore look frozen even though it is still working.
      BackupOperationTracker.update(
        BackupOperationTracker.Kind.MIHON_RESTORE,
        Progress(0, totalChapters),
        R.string.backup_operation_restoring,
      )
    }

    var restoredChapterCount = 0
    pending.forEach { item ->
      restoreChapters(item.manga.id, item.chapters)
      if (totalChapters > 0 && item.chapters.isNotEmpty()) {
        // Publish one stable cumulative value per restored manga/novel. Emitting once for every
        // chapter in a tight loop lets StateFlow/Compose conflate thousands of intermediate states
        // and wastes work; the per-title update remains visible while the next title is restored.
        restoredChapterCount += item.chapters.size
        BackupOperationTracker.update(
          BackupOperationTracker.Kind.MIHON_RESTORE,
          Progress(restoredChapterCount, totalChapters),
          R.string.backup_operation_restoring,
        )
      }
    }

    // Never move a user backwards when they restore an older backup onto an installation that has
    // since been read further. This mirrors Mihon's merge-oriented restore behavior instead of
    // blindly replacing the current checkpoint with the backup checkpoint.
    pending.forEach { item ->
      val existing = db.getHistoryDao().findIncludingDeleted(item.manga.id)
      val shouldRestoreProgress = when {
        existing == null || existing.deletedAt != 0L -> true
        item.history == null -> false
        else -> item.history.updatedAt >= existing.updatedAt
      }
      if (shouldRestoreProgress) {
        item.history?.let { db.getHistoryDao().upsert(it) }
        item.stats?.let { db.getStatsDao().upsert(it) }
        accumulator.chapterReadOverrides[item.manga.id] = item.readOverrides
      }
      item.note?.let { accumulator.notes[item.manga.id] = it }
    }

    pending.forEach { item ->
      if (item.bookmarks.isNotEmpty()) {
        db.getBookmarksDao().upsert(item.bookmarks)
      }
    }
    pending.forEach { item ->
      item.scrobblings.forEach {
        db.getScrobblingDao().upsert(it)
        accumulator.restoredTrackingCount += 1
      }
    }

    accumulator.restoredMangaCount += pending.size
  }

  /**
   * Merge backup chapters with the chapter list already stored locally.
   *
   * A restore must never behave like a source refresh replacement. The previous implementation used
   * ChaptersDao.replaceAll(), which deleted every local chapter first; restoring an older Mihon
   * backup therefore erased chapters discovered after that backup was created. Matching chapters
   * keep the local/live entity, backup-only chapters are restored, and current-only chapters are
   * appended afterwards (normally these are the newer chapters).
   */
  private suspend fun restoreChapters(mangaId: Long, backupChapters: List<ChapterEntity>) {
    val dao = db.getChaptersDao()
    val existingChapters = dao.findAll(mangaId)
    if (existingChapters.isEmpty()) {
      dao.replaceAll(mangaId, backupChapters)
      return
    }
    if (backupChapters.isEmpty()) {
      return
    }

    val existingById = existingChapters.associateBy { it.chapterId }
    val existingByUrl = existingChapters.associateBy { it.url }
    val merged = ArrayList<ChapterEntity>(existingChapters.size + backupChapters.size)
    val seenIds = HashSet<Long>()
    val seenUrls = HashSet<String>()

    backupChapters.forEach { backupChapter ->
      val chapter = existingById[backupChapter.chapterId]
        ?: existingByUrl[backupChapter.url]
        ?: backupChapter
      if (seenIds.add(chapter.chapterId) && seenUrls.add(chapter.url)) {
        merged += chapter
      }
    }
    existingChapters.forEach { chapter ->
      if (seenIds.add(chapter.chapterId) && seenUrls.add(chapter.url)) {
        merged += chapter
      }
    }

    dao.replaceAll(
      mangaId,
      merged.mapIndexed { index, chapter ->
        if (chapter.index == index) chapter else chapter.copy(index = index)
      },
    )
  }

  private fun restorePreferences(preferences: List<MihonBackupPreference>) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    prefs.edit {
      preferences.forEach { pref ->
        when (val value = pref.value) {
          is MihonBooleanPreferenceValue -> putBoolean(pref.key, value.value)
          is MihonFloatPreferenceValue -> putFloat(pref.key, value.value)
          is MihonIntPreferenceValue -> putInt(pref.key, value.value)
          is MihonLongPreferenceValue -> putLong(pref.key, value.value)
          is MihonStringPreferenceValue -> putString(pref.key, value.value)
          is MihonStringSetPreferenceValue -> putStringSet(pref.key, value.value)
        }
      }
    }
  }

  private fun restoreSourcePreferences(
    preferences: List<MihonBackupSourcePreferences>,
    @Suppress("UNUSED_PARAMETER") accumulator: RestoreAccumulator,
  ) {
    preferences.forEach { sourcePreferences ->
      val sourceId = parseSourceIdFromPreferenceKey(sourcePreferences.sourceKey)
        ?: return@forEach
      val sourceName = resolveStoredSourceName(sourceId, emptyList())
      val prefs = context.getSharedPreferences(SourceSettings.getStorageName(sourceName), Context.MODE_PRIVATE)
      prefs.edit {
        sourcePreferences.prefs.forEach { pref ->
          when (val value = pref.value) {
            is MihonBooleanPreferenceValue -> putBoolean(pref.key, value.value)
            is MihonFloatPreferenceValue -> putFloat(pref.key, value.value)
            is MihonIntPreferenceValue -> putInt(pref.key, value.value)
            is MihonLongPreferenceValue -> putLong(pref.key, value.value)
            is MihonStringPreferenceValue -> putString(pref.key, value.value)
            is MihonStringSetPreferenceValue -> putStringSet(pref.key, value.value)
          }
        }
      }
    }
  }

  private fun restoreExtensionRepo(repos: List<MihonBackupExtensionRepo>) {
    extensionStoreRegistry.ensureMigrated(
      systemPackages = mihonExtensionLoader.getInstalledExtensions(context, privateMode = false)
        .mapTo(HashSet()) { it.pkgName },
      sandboxPackages = mihonExtensionLoader.getInstalledExtensions(context, privateMode = true)
        .mapTo(HashSet()) { it.pkgName },
    )
    extensionStoreRegistry.importStores(
      repos.map { repo ->
        val url = normalizeExtensionStoreUrl(repo.indexUrl)
        ExtensionStoreRecord(
          id = stableExtensionStoreId(url),
          indexUrl = url,
          name = repo.name.ifBlank { repo.indexUrl },
          shortName = repo.badgeLabel,
          fingerprint = repo.signingKey.takeIf(String::isNotBlank),
          website = repo.contactWebsite.takeIf(String::isNotBlank),
          discord = repo.contactDiscord?.takeIf(String::isNotBlank),
        )
      },
    )
  }

  /** Apply non-Room state only after the library transaction has committed successfully. */
  private fun applyRestoredUiState(accumulator: RestoreAccumulator) {
    accumulator.categoryTypes.forEach { (categoryId, type) ->
      favouriteContentTypeStore.setCategoryType(categoryId, type)
    }

    if (accumulator.chapterReadOverrides.isNotEmpty()) {
      val prefs = PreferenceManager.getDefaultSharedPreferences(context)
      prefs.edit {
        accumulator.chapterReadOverrides.forEach { (mangaId, overrides) ->
          val key = "chapter_read_overrides_$mangaId"
          if (overrides.isEmpty()) {
            remove(key)
          } else {
            putStringSet(
              key,
              overrides.mapTo(LinkedHashSet()) { (chapterId, isRead) ->
                "$chapterId:${if (isRead) 1 else 0}"
              },
            )
          }
        }
      }
    }

    if (accumulator.notes.isNotEmpty()) {
      context.getSharedPreferences(MANGA_NOTES_PREFERENCES, Context.MODE_PRIVATE).edit {
        accumulator.notes.forEach { (mangaId, note) ->
          putString(mangaId.toString(), note)
        }
      }
    }
  }

  private fun resolveStoredSourceName(sourceId: Long, backupSources: List<MihonBackupSource>): String {
    val source = mihonExtensionManager.getMihonMangaSourceById(sourceId)
    if (source != null) {
      return source.name
    }
    return if (sourceId > 0) "MIHON_$sourceId" else "UNKNOWN"
  }

  private fun resolveSourceTitle(sourceId: Long, backupSources: List<MihonBackupSource>): String? {
    val installed = mihonExtensionManager.getMihonMangaSourceById(sourceId)
    return installed?.displayName ?: backupSources.firstOrNull { it.sourceId == sourceId }?.name
  }

  private fun contentTypeForSource(sourceId: Long): FavouriteContentType {
    return if (MihonExtensionManager.isNovelSourceId(sourceId)) {
      FavouriteContentType.NOVEL
    } else {
      FavouriteContentType.MANGA
    }
  }

  private fun buildAuthors(item: MihonBackupManga): String? {
    val author = item.author?.trim()?.takeIf { it.isNotEmpty() }
    val artist = item.artist?.trim()?.takeIf { it.isNotEmpty() && !it.equals(author, ignoreCase = true) }
    return listOfNotNull(author, artist).joinToString("\n").takeIf { it.isNotEmpty() }
  }

  private fun decodeMangaState(status: Int): String? = when (status) {
    SManga.ONGOING -> MangaState.ONGOING.name
    SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> MangaState.FINISHED.name
    SManga.CANCELLED -> MangaState.ABANDONED.name
    SManga.ON_HIATUS -> MangaState.PAUSED.name
    SManga.LICENSED -> MangaState.RESTRICTED.name
    else -> null
  }

  private fun parseSourceIdFromPreferenceKey(key: String): Long? {
    return key.substringAfterLast('_').toLongOrNull()
      ?: key.removePrefix("source_").substringBefore(':').toLongOrNull()
  }

  /**
   * Translates a Mihon tracker `syncId` (see Mihon's `TrackerManager`) into the matching DropSauce
   * scrobbler id. The two apps number their services differently, so copying the id verbatim points
   * entries at the wrong service. Returns null for trackers DropSauce doesn't support.
   */
  private fun mihonTrackerToScrobblerId(syncId: Int): Int? = when (syncId) {
    1 -> 3 // MyAnimeList -> MAL
    2 -> 2 // AniList
    3 -> 4 // Kitsu
    4 -> 1 // Shikimori
    11 -> 5 // MangaBaka
    else -> null
  }

  /** Mihon tracker status numbers are service-specific; they cannot be decoded as one shared enum. */
  private fun decodeTrackingStatus(syncId: Int, status: Int): String? = when (syncId) {
    1 -> when (status) { // MyAnimeList
      1 -> "reading"
      2 -> "completed"
      3 -> "on_hold"
      4 -> "dropped"
      6 -> "plan_to_read"
      7 -> "reading" // DropSauce MAL has no separate rereading state.
      else -> null
    }
    2 -> when (status) { // AniList
      1 -> "CURRENT"
      2 -> "COMPLETED"
      3 -> "PAUSED"
      4 -> "DROPPED"
      5 -> "PLANNING"
      6 -> "REPEATING"
      else -> null
    }
    3 -> when (status) { // Kitsu
      1 -> "current"
      2 -> "completed"
      3 -> "on_hold"
      4 -> "dropped"
      5 -> "planned"
      else -> null
    }
    4 -> when (status) { // Shikimori
      1 -> "watching"
      2 -> "completed"
      3 -> "on_hold"
      4 -> "dropped"
      5 -> "planned"
      6 -> "rewatching"
      else -> null
    }
    11 -> when (status) { // MangaBaka
      1 -> "reading"
      2 -> "completed"
      3 -> "paused"
      4 -> "dropped"
      5 -> "plan_to_read"
      6 -> "rereading"
      7 -> "plan_to_read" // "Considering" has no DropSauce equivalent.
      else -> null
    }
    else -> null
  }

  /** DropSauce stores scrobbling ratings normalized to 0..1; Mihon stores each tracker's native scale. */
  private fun decodeTrackingRating(syncId: Int, score: Float): Float {
    val normalized = when (syncId) {
      1, 4 -> score / 10f // MAL, Shikimori
      2, 11 -> score / 100f // AniList, MangaBaka
      3 -> score / 20f // Kitsu
      else -> score
    }
    return normalized.coerceIn(0f, 1f)
  }

  private fun computeReadPercent(
    readChapters: Int,
    chaptersCount: Int,
  ): Float {
    if (chaptersCount <= 0) return 0f
    return readChapters.coerceIn(0, chaptersCount) / chaptersCount.toFloat()
  }

  private companion object {
    const val DEFAULT_CATEGORY_TITLE = "Default"
    const val MANGA_NOTES_PREFERENCES = "manga_notes"
  }
}
