package org.koitharu.kotatsu.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupChapter
import org.koitharu.kotatsu.backup.model.MihonBackupExtensionRepo
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
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.PrivateFavouriteEntity
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
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.text.Collator
import java.util.Locale
import java.util.zip.GZIPInputStream
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
    MIHON_SORT_LAST_UPDATE,
    MIHON_SORT_CHAPTER_FETCH_DATE,
    MIHON_SORT_TRACKER_MEAN,
    MIHON_SORT_RANDOM,
    -> null
    else -> null
  }
}

internal fun favouriteSpaceForMihonRestoreTarget(target: MihonRestoreTarget): FavouriteSpace = when (target) {
  MihonRestoreTarget.NORMAL -> FavouriteSpace.NORMAL
  MihonRestoreTarget.PRIVATE -> FavouriteSpace.PRIVATE
}

private fun MihonBackupManga.isLibraryEntry(): Boolean = favorite || categories.isNotEmpty()

/**
 * Mihon's library always applies an ascending title comparator after the primary sort comparator.
 * For Date Added this means equal timestamps are ordered by a PRIMARY-strength Collator using the
 * current device locale. Store that rank in favourites.sort_key so SQLite can reproduce Mihon's
 * exact tie order for restored rows without changing the original dateAdded value.
 */
internal fun buildMihonDateAddedTieRanks(
  manga: List<MihonBackupManga>,
  locale: Locale = Locale.getDefault(),
): IntArray = buildMihonTitleTieRanks(manga.map { it.title }, locale)

private fun buildMihonTitleTieRanks(
  titles: List<String>,
  locale: Locale = Locale.getDefault(),
): IntArray {
  val collator = Collator.getInstance(locale).apply {
    strength = Collator.PRIMARY
  }
  val orderedIndices = titles.indices.sortedWith(Comparator { left, right ->
    val titleCompare = collator.compare(
      titles[left].lowercase(),
      titles[right].lowercase(),
    )
    if (titleCompare != 0) titleCompare else left.compareTo(right)
  })
  return IntArray(titles.size).also { ranks ->
    orderedIndices.forEachIndexed { rank, index ->
      ranks[index] = rank
    }
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

  private data class CategoryMembership(
    val type: FavouriteContentType,
    val orders: List<Long>,
  )

  private data class BackupScan(
    val categories: MutableList<MihonBackupCategory> = ArrayList(),
    val sources: MutableList<MihonBackupSource> = ArrayList(),
    val preferences: MutableList<MihonBackupPreference> = ArrayList(),
    val sourcePreferences: MutableList<MihonBackupSourcePreferences> = ArrayList(),
    val extensionRepos: MutableList<MihonBackupExtensionRepo> = ArrayList(),
    val titles: MutableList<String> = ArrayList(),
    val categoryMemberships: MutableList<CategoryMembership> = ArrayList(),
    val sourceIds: MutableSet<Long> = LinkedHashSet(),
    val trackerIds: MutableSet<Int> = LinkedHashSet(),
    var totalChapters: Int = 0,
  )

  private data class IndexedManga(
    val index: Int,
    val manga: MihonBackupManga,
  )

  private data class PendingFavourite(
    val categoryId: Long,
    val sortKey: Int,
    val createdAt: Long,
  )

  private data class PendingRestore(
    val manga: MangaEntity,
    val tags: List<TagEntity>,
    val chapters: List<ChapterEntity>,
    val favourites: List<PendingFavourite>,
    val history: HistoryEntity?,
    val stats: StatsEntity?,
    val bookmarks: List<BookmarkEntity>,
    val scrobblings: List<ScrobblingEntity>,
    val track: TrackEntity?,
    val readOverrides: Map<Long, Boolean>,
    val note: String?,
  )

  private inner class CategoryResolver(
    private val backupCategories: List<MihonBackupCategory>,
    private val accumulator: RestoreAccumulator,
    target: MihonRestoreTarget,
  ) {
    private val dao = db.getFavouriteCategoriesDao()
    private val space = favouriteSpaceForMihonRestoreTarget(target)
    private val idByOrderAndType = HashMap<Pair<Long, FavouriteContentType>, Long>()
    private val idByTitleAndType = HashMap<Pair<String, FavouriteContentType>, Long>()
    private val defaultCategoryIdByType = HashMap<FavouriteContentType, Long>()

    suspend fun prepare(memberships: List<CategoryMembership>) {
      val existingCategories = when (space) {
        FavouriteSpace.NORMAL -> dao.findAll()
        FavouriteSpace.PRIVATE -> dao.findAllInSpace(space.dbValue)
      }
      existingCategories.forEach { category ->
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
      memberships.forEach { membership ->
        if (membership.orders.isEmpty()) {
          uncategorizedTypes += membership.type
        } else {
          membership.orders.forEach { order ->
            typesByOrder.getOrPut(order) { linkedSetOf() } += membership.type
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

      memberships.forEach { membership ->
        if (membership.orders.none { idByOrderAndType.containsKey(it to membership.type) }) {
          uncategorizedTypes += membership.type
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
        dao.updateVisibility(id, true)
        accumulator.categoryTypes[id] = type
        return id
      }
      val id = dao.insert(
        FavouriteCategoryEntity(
          categoryId = 0,
          createdAt = System.currentTimeMillis(),
          sortKey = dao.getNextSortKey(space),
          title = title,
          order = (sortOrder ?: ListSortOrder.ALPHABETIC).name,
          track = space == FavouriteSpace.NORMAL,
          downloadNewChapters = false,
          isVisibleInLibrary = true,
          deletedAt = 0,
          space = space.dbValue,
        ),
      )
      idByTitleAndType[key] = id
      accumulator.categoryTypes[id] = type
      return id
    }
  }

  suspend fun analyzeBackup(uri: Uri, options: Options = Options()): RestoreReport = withContext(Dispatchers.IO) {
    val scan = scanBackup(uri, options)
    runCatching { mihonExtensionManager.ensureReady() }
    buildDiagnostics(scan, options).toReport()
  }

  suspend fun restoreBackup(
    uri: Uri,
    options: Options = Options(),
    target: MihonRestoreTarget = MihonRestoreTarget.NORMAL,
  ): RestoreReport {
    return withContext(Dispatchers.IO) {
      val scan = scanBackup(uri, options)
      runCatching { mihonExtensionManager.ensureReady() }
      val accumulator = buildDiagnostics(scan, options)

      if (options.libraryEntries) {
        val categoryResolver = CategoryResolver(scan.categories, accumulator, target)
        db.withTransaction {
          categoryResolver.prepare(scan.categoryMemberships)
        }
        restoreMangaStream(
          uri = uri,
          scan = scan,
          options = options,
          accumulator = accumulator,
          categoryResolver = categoryResolver,
          target = target,
        )
        if (target == MihonRestoreTarget.NORMAL) {
          removeEmptyReadLaterCategory()
        }
      }

      if (options.appSettings) {
        restorePreferences(scan.preferences)
      }
      if (options.sourceSettings) {
        restoreSourcePreferences(scan.sourcePreferences, accumulator)
      }
      if (options.extensionRepoSettings) {
        restoreExtensionRepo(scan.extensionRepos)
      }
      applyRestoredUiState(accumulator)
      accumulator.toReport()
    }
  }

  private suspend fun removeEmptyReadLaterCategory() {
    val readLaterTitle = context.getString(R.string.read_later)
    val dao = db.getFavouriteCategoriesDao()
    val readLater = dao.findAll().firstOrNull { it.title == readLaterTitle } ?: return
    if (db.getFavouritesDao().findAll(readLater.categoryId.toLong()).isEmpty()) {
      dao.delete(readLater.categoryId.toLong())
    }
  }

  private fun buildDiagnostics(scan: BackupScan, options: Options): RestoreAccumulator {
    val sourceTitles = scan.sources.associate { it.sourceId to it.name }
    val missingSources = if (options.libraryEntries) {
      scan.sourceIds.asSequence()
        .filter { it > 0L }
        .filter { mihonExtensionManager.getMihonMangaSourceById(it) == null }
        .map { sourceId -> sourceTitles[sourceId].orEmpty().ifBlank { sourceId.toString() } }
        .toList()
    } else {
      emptyList()
    }
    val missingTrackers = if (options.tracking) {
      scan.trackerIds.filter { mihonTrackerToScrobblerId(it) == null }
    } else {
      emptyList()
    }
    return RestoreAccumulator(
      missingSources = missingSources,
      missingTrackers = missingTrackers,
    )
  }

  private suspend fun scanBackup(uri: Uri, options: Options): BackupScan {
    val scan = BackupScan()
    try {
      openBackupPayload(uri).use { input ->
        MihonBackupWire.forEachMessage(input) { fieldNumber, payload ->
          when (fieldNumber) {
            MihonBackupWire.FIELD_MANGA -> {
              val manga = MihonBackupWire.decodeMessage(payload, serializer<MihonBackupManga>())
              if (options.libraryEntries) {
                scan.titles += manga.title
                scan.totalChapters = (scan.totalChapters.toLong() + manga.chapters.size)
                  .coerceAtMost(Int.MAX_VALUE.toLong())
                  .toInt()
                scan.sourceIds += manga.source
                if (manga.isLibraryEntry()) {
                  scan.categoryMemberships += CategoryMembership(
                    type = contentTypeForSource(manga.source),
                    orders = manga.categories.toList(),
                  )
                }
              }
              if (options.tracking) {
                manga.tracking.forEach { scan.trackerIds += it.syncId }
              }
            }
            MihonBackupWire.FIELD_CATEGORY -> scan.categories +=
              MihonBackupWire.decodeMessage(payload, serializer<MihonBackupCategory>())
            MihonBackupWire.FIELD_SOURCE -> scan.sources +=
              MihonBackupWire.decodeMessage(payload, serializer<MihonBackupSource>())
            MihonBackupWire.FIELD_PREFERENCE -> if (options.appSettings) {
              decodeOptional<MihonBackupPreference>(payload)?.let(scan.preferences::add)
            }
            MihonBackupWire.FIELD_SOURCE_PREFERENCE -> if (options.sourceSettings) {
              decodeOptional<MihonBackupSourcePreferences>(payload)?.let(scan.sourcePreferences::add)
            }
            MihonBackupWire.FIELD_EXTENSION_REPO -> if (options.extensionRepoSettings) {
              decodeOptional<MihonBackupExtensionRepo>(payload)?.let(scan.extensionRepos::add)
            }
          }
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      if (e is BadBackupFormatException) throw e
      throw BadBackupFormatException(e)
    }
    if (options.libraryEntries && scan.titles.isEmpty()) {
      throw BadBackupFormatException(IOException("Mihon backup contains no manga entries"))
    }
    return scan
  }

  private inline fun <reified T> decodeOptional(payload: ByteArray): T? {
    return try {
      MihonBackupWire.decodeMessage(payload, serializer<T>())
    } catch (_: SerializationException) {
      // Newer Mihon versions may add preference value variants unknown to this build. Preferences
      // are optional; keep restoring the portable library data just like the previous fallback model.
      null
    }
  }

  private fun openBackupPayload(uri: Uri): InputStream {
    val raw = context.contentResolver.openInputStream(uri) ?: throw BadBackupFormatException(null)
    val input = BufferedInputStream(raw, IO_BUFFER_SIZE)
    input.mark(2)
    val first = input.read()
    val second = input.read()
    input.reset()
    if (first < 0 || second < 0) {
      input.close()
      throw BadBackupFormatException(null)
    }
    return if (first == 0x1f && second == 0x8b) {
      GZIPInputStream(input, IO_BUFFER_SIZE)
    } else {
      input
    }
  }

  private suspend fun restoreMangaStream(
    uri: Uri,
    scan: BackupScan,
    options: Options,
    accumulator: RestoreAccumulator,
    categoryResolver: CategoryResolver,
    target: MihonRestoreTarget,
  ) {
    val dateAddedTieRanks = buildMihonTitleTieRanks(scan.titles)
    if (scan.totalChapters > 0) {
      BackupOperationTracker.update(
        BackupOperationTracker.Kind.MIHON_RESTORE,
        Progress(0, scan.totalChapters),
        R.string.backup_operation_restoring,
      )
    }

    val batch = ArrayList<IndexedManga>(RESTORE_MANGA_BATCH_SIZE)
    var batchChapterCount = 0
    var mangaIndex = 0
    var restoredChapterCount = 0

    suspend fun flushBatch() {
      if (batch.isEmpty()) return
      val processedChapters = restoreMangaBatch(
        batch = batch,
        scan = scan,
        options = options,
        accumulator = accumulator,
        categoryResolver = categoryResolver,
        target = target,
        dateAddedTieRanks = dateAddedTieRanks,
      )
      restoredChapterCount += processedChapters
      if (scan.totalChapters > 0 && processedChapters > 0) {
        BackupOperationTracker.update(
          BackupOperationTracker.Kind.MIHON_RESTORE,
          Progress(restoredChapterCount.coerceAtMost(scan.totalChapters), scan.totalChapters),
          R.string.backup_operation_restoring,
        )
      }
      batch.clear()
      batchChapterCount = 0
    }

    openBackupPayload(uri).use { input ->
      MihonBackupWire.forEachMessage(input) { fieldNumber, payload ->
        if (fieldNumber != MihonBackupWire.FIELD_MANGA) return@forEachMessage
        val manga = MihonBackupWire.decodeMessage(payload, serializer<MihonBackupManga>())
        batch += IndexedManga(mangaIndex++, manga)
        batchChapterCount += manga.chapters.size
        if (batch.size >= RESTORE_MANGA_BATCH_SIZE || batchChapterCount >= RESTORE_CHAPTER_BATCH_LIMIT) {
          flushBatch()
        }
      }
    }
    flushBatch()
    require(mangaIndex == scan.titles.size) {
      "Mihon backup changed between scan and restore: scanned=${scan.titles.size}, restored=$mangaIndex"
    }
  }

  private suspend fun restoreMangaBatch(
    batch: List<IndexedManga>,
    scan: BackupScan,
    options: Options,
    accumulator: RestoreAccumulator,
    categoryResolver: CategoryResolver,
    target: MihonRestoreTarget,
    dateAddedTieRanks: IntArray,
  ): Int {
    val now = System.currentTimeMillis()
    val pending = batch.map { indexed ->
      val item = indexed.manga
      val sourceName = resolveStoredSourceName(item.source, scan.sources)
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
      val categoryIds = if (item.isLibraryEntry()) {
        categoryResolver.resolve(item.categories, contentType)
      } else {
        emptyList()
      }
      val favourites = categoryIds.map { categoryId ->
        PendingFavourite(
          categoryId = categoryId,
          sortKey = dateAddedTieRanks[indexed.index],
          createdAt = item.dateAdded,
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
        val updatedAt = currentHistory?.lastRead ?: 0L
        HistoryEntity(
          mangaId = mangaId,
          createdAt = updatedAt,
          updatedAt = updatedAt,
          chapterId = currentChapter.chapterId,
          page = restoredPage,
          scroll = 0f,
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
          sourceTitle = resolveSourceTitle(item.source, scan.sources),
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

    val overridesToApply = LinkedHashMap<Long, Map<Long, Boolean>>()
    val notesToApply = LinkedHashMap<Long, String>()
    var restoredTracking = 0

    db.withTransaction {
      pending.flatMapTo(linkedSetOf()) { it.tags }
        .takeIf { it.isNotEmpty() }
        ?.let { db.getTagsDao().upsert(it.toList()) }

      val mangaDao = db.getMangaDao()
      val ids = pending.map { it.manga.id }
      val existingById = mangaDao.findByIds(ids).associateBy { it.manga.id }
      pending.forEach { item ->
        val existing = existingById[item.manga.id]
        if (existing == null) {
          mangaDao.upsert(item.manga, item.tags)
        } else {
          val mergedTags = (existing.tags + item.tags).distinctBy { it.id }
          mangaDao.upsert(existing.manga, mergedTags)
        }
      }

      val tracksDao = db.getTracksDao()
      val existingTracks = tracksDao.findByIds(ids).associateBy { it.mangaId }
      pending.forEach { item ->
        item.track?.let { restoredTrack ->
          val existingTrack = existingTracks[item.manga.id]
          if (existingTrack == null || restoredTrack.lastChapterDate > existingTrack.lastChapterDate) {
            tracksDao.upsert(restoredTrack)
          }
        }
      }

      when (target) {
        MihonRestoreTarget.NORMAL -> pending.forEach { item ->
          item.favourites.forEach { favourite ->
            db.getFavouritesDao().upsert(
              FavouriteEntity(
                mangaId = item.manga.id,
                categoryId = favourite.categoryId,
                sortKey = favourite.sortKey,
                isPinned = false,
                createdAt = favourite.createdAt,
                deletedAt = 0L,
              ),
            )
          }
        }
        MihonRestoreTarget.PRIVATE -> pending.forEach { item ->
          item.favourites.forEach { favourite ->
            db.getPrivateFavouritesDao().upsert(
              PrivateFavouriteEntity(
                mangaId = item.manga.id,
                categoryId = favourite.categoryId,
                sortKey = favourite.sortKey,
                isPinned = false,
                createdAt = favourite.createdAt,
                deletedAt = 0L,
              ),
            )
          }
        }
      }

      val chaptersDao = db.getChaptersDao()
      val existingChapters = chaptersDao.findAll(ids).groupBy { it.mangaId }
      pending.forEach { item ->
        restoreChapters(item.manga.id, item.chapters, existingChapters[item.manga.id].orEmpty())
      }

      val historyDao = db.getHistoryDao()
      val existingHistory = historyDao.findIncludingDeletedByIds(ids).associateBy { it.mangaId }
      pending.forEach { item ->
        val existing = existingHistory[item.manga.id]
        val shouldRestoreProgress = when {
          existing == null || existing.deletedAt != 0L -> true
          item.history == null -> false
          else -> item.history.updatedAt >= existing.updatedAt
        }
        if (shouldRestoreProgress) {
          item.history?.let { historyDao.upsert(it) }
          item.stats?.let { db.getStatsDao().upsert(it) }
          overridesToApply[item.manga.id] = item.readOverrides
        }
        item.note?.let { notesToApply[item.manga.id] = it }
      }

      pending.forEach { item ->
        if (item.bookmarks.isNotEmpty()) {
          db.getBookmarksDao().upsert(item.bookmarks)
        }
        item.scrobblings.forEach {
          db.getScrobblingDao().upsert(it)
          restoredTracking++
        }
      }
    }

    applyBatchUiState(overridesToApply, notesToApply)
    accumulator.restoredTrackingCount += restoredTracking
    accumulator.restoredMangaCount += pending.size
    return pending.sumOf { it.chapters.size }
  }

  private fun applyBatchUiState(
    chapterReadOverrides: Map<Long, Map<Long, Boolean>>,
    notes: Map<Long, String>,
  ) {
    if (chapterReadOverrides.isNotEmpty()) {
      PreferenceManager.getDefaultSharedPreferences(context).edit {
        chapterReadOverrides.forEach { (mangaId, overrides) ->
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
    if (notes.isNotEmpty()) {
      context.getSharedPreferences(MANGA_NOTES_PREFERENCES, Context.MODE_PRIVATE).edit {
        notes.forEach { (mangaId, note) -> putString(mangaId.toString(), note) }
      }
    }
  }

  private suspend fun restoreChapters(
    mangaId: Long,
    backupChapters: List<ChapterEntity>,
    existingChapters: List<ChapterEntity>,
  ) {
    val dao = db.getChaptersDao()
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

  private fun applyRestoredUiState(accumulator: RestoreAccumulator) {
    accumulator.categoryTypes.forEach { (categoryId, type) ->
      favouriteContentTypeStore.setCategoryType(categoryId, type)
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

  private fun mihonTrackerToScrobblerId(syncId: Int): Int? = when (syncId) {
    1 -> 3
    2 -> 2
    3 -> 4
    4 -> 1
    11 -> 5
    else -> null
  }

  private fun decodeTrackingStatus(syncId: Int, status: Int): String? = when (syncId) {
    1 -> when (status) {
      1 -> "reading"
      2 -> "completed"
      3 -> "on_hold"
      4 -> "dropped"
      6 -> "plan_to_read"
      7 -> "reading"
      else -> null
    }
    2 -> when (status) {
      1 -> "CURRENT"
      2 -> "COMPLETED"
      3 -> "PAUSED"
      4 -> "DROPPED"
      5 -> "PLANNING"
      6 -> "REPEATING"
      else -> null
    }
    3 -> when (status) {
      1 -> "current"
      2 -> "completed"
      3 -> "on_hold"
      4 -> "dropped"
      5 -> "planned"
      else -> null
    }
    4 -> when (status) {
      1 -> "watching"
      2 -> "completed"
      3 -> "on_hold"
      4 -> "dropped"
      5 -> "planned"
      6 -> "rewatching"
      else -> null
    }
    11 -> when (status) {
      1 -> "reading"
      2 -> "completed"
      3 -> "paused"
      4 -> "dropped"
      5 -> "plan_to_read"
      6 -> "rereading"
      7 -> "plan_to_read"
      else -> null
    }
    else -> null
  }

  private fun decodeTrackingRating(syncId: Int, score: Float): Float {
    val normalized = when (syncId) {
      1, 4 -> score / 10f
      2, 11 -> score / 100f
      3 -> score / 20f
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
    const val RESTORE_DB_BATCH_SIZE = 256
    const val RESTORE_MANGA_BATCH_SIZE = 64
    const val RESTORE_CHAPTER_BATCH_LIMIT = 4096
    const val IO_BUFFER_SIZE = 64 * 1024
  }
}
