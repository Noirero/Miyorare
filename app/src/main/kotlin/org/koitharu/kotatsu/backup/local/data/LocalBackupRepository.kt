package org.koitharu.kotatsu.backup.local.data

import android.content.Context
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.local.data.model.BackupIndex
import org.koitharu.kotatsu.backup.local.data.model.BackupPrimitive
import org.koitharu.kotatsu.backup.local.data.model.BookmarkBackup
import org.koitharu.kotatsu.backup.local.data.model.CategoryBackup
import org.koitharu.kotatsu.backup.local.data.model.ChapterBackup
import org.koitharu.kotatsu.backup.local.data.model.FavouriteBackup
import org.koitharu.kotatsu.backup.local.data.model.FeedBackup
import org.koitharu.kotatsu.backup.local.data.model.HistoryBackup
import org.koitharu.kotatsu.backup.local.data.model.LibraryGroupBackup
import org.koitharu.kotatsu.backup.local.data.model.MangaBackup
import org.koitharu.kotatsu.backup.local.data.model.MangaPrefsBackup
import org.koitharu.kotatsu.backup.local.data.model.MangaWithChaptersBackup
import org.koitharu.kotatsu.backup.local.data.model.PrivateCategoryBackup
import org.koitharu.kotatsu.backup.local.data.model.PrivateFavouriteItemBackup
import org.koitharu.kotatsu.backup.local.data.model.PrivateFavouritesBackup
import org.koitharu.kotatsu.backup.local.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backup.local.data.model.SourceBackup
import org.koitharu.kotatsu.backup.local.data.model.SourceSettingsBackup
import org.koitharu.kotatsu.backup.local.data.model.StatsBackup
import org.koitharu.kotatsu.backup.local.domain.BackupSection
import org.koitharu.kotatsu.backup.local.domain.CustomCoverCodec
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.util.CompositeResult
import org.koitharu.kotatsu.core.util.progress.Progress
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSecurityStore
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.data.TapGridSettings
import org.koitharu.kotatsu.sync.data.model.SyncFeedEntry
import org.koitharu.kotatsu.sync.data.model.SyncMangaPrefs
import org.koitharu.kotatsu.sync.data.model.SyncTrack
import org.koitharu.kotatsu.sync.domain.SyncMerger
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@Reusable
class LocalBackupRepository @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: MangaDatabase,
	private val settings: AppSettings,
	private val tapGridSettings: TapGridSettings,
	private val coverCodec: CustomCoverCodec,
	private val libraryGroupBackupCodec: LibraryGroupBackupCodec,
	private val privateFavouritesSecurity: PrivateFavouritesSecurityStore,
	private val favouriteContentTypeStore: FavouriteContentTypeStore,
) {

	private val json = Json {
		allowSpecialFloatingPointValues = true
		coerceInputValues = true
		encodeDefaults = true
		ignoreUnknownKeys = true
		useAlternativeNames = false
		classDiscriminator = "_t"
	}

	suspend fun createBackup(
		output: ZipOutputStream,
		progress: FlowCollector<Progress>?,
	) {
		val sections = BackupSection.entries
		// Snapshot this privacy choice once. A switch change while a backup is running must not make
		// the ZIP metadata disagree with the payload that is actually written.
		val includePrivateFavourites = privateFavouritesSecurity.includePrivateInBackup
		progress?.emit(Progress.INDETERMINATE)
		var commonProgress = Progress(0, sections.size + if (includePrivateFavourites) 1 else 0)
		for (section in sections) {
			when (section) {
				BackupSection.INDEX -> {
					output.writeJsonArray(
						section = BackupSection.INDEX,
						data = flowOf(BackupIndex()),
						serializer = serializer(),
					)
					// A tiny Miyorare-only metadata entry immediately after INDEX lets the restore dialog
					// discover Private availability without walking/decompressing a very large library ZIP.
					// Older backups do not have this marker and are still supported by a full entry scan.
					output.writeMiyorareMetadata(includePrivateFavourites)
				}

				BackupSection.HISTORY -> output.writeJsonArray(
					section = BackupSection.HISTORY,
					data = database.getHistoryDao().dump().map(::HistoryBackup),
					serializer = serializer(),
				)

				BackupSection.CATEGORIES -> output.writeJsonArray(
					section = BackupSection.CATEGORIES,
					data = database.getFavouriteCategoriesDao().findAll().asFlow().map { category ->
						CategoryBackup(category, categoryContentType(category.categoryId.toLong()))
					},
					serializer = serializer(),
				)

				BackupSection.FAVOURITES -> output.writeJsonArray(
					section = BackupSection.FAVOURITES,
					data = database.getFavouritesDao().dump().map(::FavouriteBackup),
					serializer = serializer(),
				)

				BackupSection.LIBRARY_GROUPS -> output.writeJsonArray(
					section = BackupSection.LIBRARY_GROUPS,
					data = libraryGroupBackupCodec.dump(),
					serializer = serializer(),
				)

				BackupSection.BOOKMARKS -> output.writeJsonArray(
					section = BackupSection.BOOKMARKS,
					data = database.getBookmarksDao().dump().map { (manga, bookmarks) ->
						BookmarkBackup(manga, bookmarks)
					},
					serializer = serializer(),
				)

				BackupSection.SETTINGS -> output.writeJsonObject(
					section = BackupSection.SETTINGS,
					data = dumpAppSettings(),
					serializer = serializer(),
				)

				BackupSection.SETTINGS_READER_GRID -> output.writeJsonObject(
					section = BackupSection.SETTINGS_READER_GRID,
					data = dumpReaderGridSettings(),
					serializer = serializer(),
				)

				BackupSection.SOURCES -> output.writeJsonArray(
					section = BackupSection.SOURCES,
					data = database.getSourcesDao().dumpEnabled().map(::SourceBackup),
					serializer = serializer(),
				)

				BackupSection.SOURCE_SETTINGS -> output.writeJsonArray(
					section = BackupSection.SOURCE_SETTINGS,
					data = dumpSourceSettings().asFlow(),
					serializer = serializer(),
				)

				BackupSection.SCROBBLING -> output.writeJsonArray(
					section = BackupSection.SCROBBLING,
					data = database.getScrobblingDao().dumpEnabled().map(::ScrobblingBackup),
					serializer = serializer(),
				)

				BackupSection.STATS -> output.writeJsonArray(
					section = BackupSection.STATS,
					data = database.getStatsDao().dumpEnabled().map(::StatsBackup),
					serializer = serializer(),
				)

				BackupSection.CHAPTERS -> output.writeJsonArray(
					section = BackupSection.CHAPTERS,
					data = dumpMangaChapters(),
					serializer = serializer(),
				)

				BackupSection.FEED -> output.writeJsonObject(
					section = BackupSection.FEED,
					data = dumpFeed(),
					serializer = serializer(),
				)

				BackupSection.MANGA_PREFS -> output.writeJsonArray(
					section = BackupSection.MANGA_PREFS,
					data = dumpMangaPrefs(),
					serializer = serializer(),
				)
			}
			progress?.emit(commonProgress)
			commonProgress++
		}
		// Private metadata is never mixed into the legacy sections. When opt-in is off this ZIP has
		// no private entry at all, so even category names cannot leak into a routine local backup.
		if (includePrivateFavourites) {
			output.writePrivateFavourites(dumpPrivateFavourites())
			commonProgress++
		}
		progress?.emit(commonProgress)
	}

	suspend fun restoreBackup(
		input: ZipInputStream,
		sections: Set<BackupSection>,
		progress: FlowCollector<Progress>?,
		restorePrivateFavourites: Boolean = false,
		itemProgress: (suspend (BackupSection, Int) -> Unit)? = null,
	): CompositeResult {
		progress?.emit(Progress.INDETERMINATE)
		var commonProgress = Progress(0, sections.size + if (restorePrivateFavourites) 1 else 0)
		var entry = input.nextEntry
		var result = CompositeResult.EMPTY
		val restoredMangaIds = HashSet<Long>()
		val normalCategoryIdMap = HashMap<Long, Long>()
		while (entry != null) {
			if (entry.name.equals(MIYORARE_METADATA_ENTRY, ignoreCase = true)) {
				input.closeEntry()
				entry = input.nextEntry
				continue
			}
			if (entry.name.equals(PRIVATE_FAVOURITES_ENTRY, ignoreCase = true)) {
				// Restore-time inclusion is deliberately independent from the persistent backup switch.
				// The caller must opt in for this specific restore operation.
				if (restorePrivateFavourites) {
					result += restorePrivateFavourites(input)
					commonProgress++
					progress?.emit(commonProgress)
				}
				input.closeEntry()
				entry = input.nextEntry
				continue
			}

			val section = BackupSection.of(entry)
			if (section != null && section in sections) {
				var sectionProcessed = 0
				itemProgress?.invoke(section, sectionProcessed)
				suspend fun reportProcessed(count: Int) {
					sectionProcessed += count
					itemProgress?.invoke(section, sectionProcessed)
				}
				result += when (section) {
					BackupSection.INDEX -> CompositeResult.EMPTY

					BackupSection.HISTORY -> input.readJsonArray<HistoryBackup>(serializer()).restoreMangaToDb(
						restoredMangaIds = restoredMangaIds,
						onBatchProcessed = { count -> reportProcessed(count) },
						mangaOf = { it.manga },
					) { getHistoryDao().upsert(it.toEntity()) }

					BackupSection.CATEGORIES -> restoreCategories(
						items = input.readJsonArray<CategoryBackup>(serializer()),
						idMap = normalCategoryIdMap,
						onItemProcessed = { count -> reportProcessed(count) },
					)

					BackupSection.FAVOURITES -> input.readJsonArray<FavouriteBackup>(serializer()).restoreMangaToDb(
						restoredMangaIds = restoredMangaIds,
						onBatchProcessed = { count -> reportProcessed(count) },
						mangaOf = { it.manga },
					) { item ->
						normalCategoryIdMap[item.categoryId]?.let { categoryId ->
							getFavouritesDao().upsert(item.toEntity().copy(categoryId = categoryId))
						}
					}

					BackupSection.LIBRARY_GROUPS -> libraryGroupBackupCodec.restore(
						input.readJsonArray<LibraryGroupBackup>(serializer()),
					)

					BackupSection.BOOKMARKS -> input.readJsonArray<BookmarkBackup>(serializer()).restoreMangaToDb(
						restoredMangaIds = restoredMangaIds,
						onBatchProcessed = { count -> reportProcessed(count) },
						mangaOf = { it.manga },
					) {
						if (it.bookmarks.isNotEmpty()) getBookmarksDao().upsert(it.bookmarks.map { b -> b.toEntity() })
					}

					BackupSection.SETTINGS -> restoreAppSettings(input)
					BackupSection.SETTINGS_READER_GRID -> restoreReaderGridSettings(input)

					BackupSection.SOURCES -> input.readJsonArray<SourceBackup>(serializer()).restoreToDb(onBatchProcessed = { count -> reportProcessed(count) }) {
						getSourcesDao().upsert(it.toEntity())
					}

					BackupSection.SOURCE_SETTINGS -> restoreSourceSettings(
						input = input,
						onItemProcessed = { count -> reportProcessed(count) },
					)

					BackupSection.SCROBBLING -> input.readJsonArray<ScrobblingBackup>(serializer()).restoreToDb(onBatchProcessed = { count -> reportProcessed(count) }) {
						getScrobblingDao().upsert(it.toEntity())
					}

					BackupSection.STATS -> input.readJsonArray<StatsBackup>(serializer()).restoreToDb(onBatchProcessed = { count -> reportProcessed(count) }) {
						getStatsDao().upsert(it.toEntity())
					}

					BackupSection.CHAPTERS -> input.readJsonArray<MangaWithChaptersBackup>(serializer())
						.restoreMangaToDb(
							restoredMangaIds = restoredMangaIds,
							onBatchProcessed = { count -> reportProcessed(count) },
							mangaOf = { it.manga },
						) { getChaptersDao().replaceAll(it.manga.id, it.chapters.map { c -> c.toEntity() }) }

					BackupSection.FEED -> restoreFeed(
						input = input,
						restoredMangaIds = restoredMangaIds,
						onBatchProcessed = { count -> reportProcessed(count) },
					)

					BackupSection.MANGA_PREFS -> restoreMangaPrefs(
						input,
						restoredMangaIds,
						onBatchProcessed = { count -> reportProcessed(count) },
					)

					null -> CompositeResult.EMPTY
				}
				commonProgress++
				progress?.emit(commonProgress)
			}
			input.closeEntry()
			entry = input.nextEntry
		}
		if (BackupSection.CATEGORIES in sections) {
			removeEmptyReadLaterCategory()
		}
		progress?.emit(commonProgress)
		return result
	}

	private suspend fun <T> ZipOutputStream.writeJsonArray(
		section: BackupSection,
		data: Flow<T>,
		serializer: SerializationStrategy<T>,
	) {
		data.onStart {
			putNextEntry(ZipEntry(section.entryName))
			write("[")
		}.onCompletion { error ->
			if (error == null) {
				write("]")
			}
			closeEntry()
			flush()
		}.collectIndexed { index, value ->
			if (index > 0) {
				write(",")
			}
			json.encodeToStream(serializer, value, this)
		}
	}

	private fun <T> ZipOutputStream.writeJsonObject(
		section: BackupSection,
		data: T,
		serializer: SerializationStrategy<T>,
	) {
		putNextEntry(ZipEntry(section.entryName))
		try {
			json.encodeToStream(serializer, data, this)
		} finally {
			closeEntry()
			flush()
		}
	}

	private fun ZipOutputStream.writeMiyorareMetadata(includePrivateFavourites: Boolean) {
		putNextEntry(ZipEntry(MIYORARE_METADATA_ENTRY))
		try {
			write(if (includePrivateFavourites) "1" else "0")
		} finally {
			closeEntry()
			flush()
		}
	}

	private fun ZipOutputStream.writePrivateFavourites(data: PrivateFavouritesBackup) {
		putNextEntry(ZipEntry(PRIVATE_FAVOURITES_ENTRY))
		try {
			json.encodeToStream(serializer(), data, this)
		} finally {
			closeEntry()
			flush()
		}
	}

	private fun <T> InputStream.readJsonArray(
		serializer: DeserializationStrategy<T>,
	): Sequence<T> = json.decodeToSequence(this, serializer, DecodeSequenceMode.ARRAY_WRAPPED)

	private fun OutputStream.write(str: String) = write(str.toByteArray())

	private suspend fun dumpPrivateFavourites(): PrivateFavouritesBackup {
		val categories = database.getFavouriteCategoriesDao()
			.findAllInSpace(FavouriteSpace.PRIVATE.dbValue)
			.map { category ->
				PrivateCategoryBackup(category, categoryContentType(category.categoryId.toLong()))
			}
		val favourites = database.getPrivateFavouritesDao().dump()
			.map(::PrivateFavouriteItemBackup)
			.toList()
		return PrivateFavouritesBackup(categories = categories, favourites = favourites)
	}

	private suspend fun restorePrivateFavourites(input: InputStream): CompositeResult {
		return runCatchingCancellable {
			val backup = json.decodeFromString<PrivateFavouritesBackup>(input.readBytes().decodeToString())
			val restoredTypes = LinkedHashMap<Long, FavouriteContentType>()
			database.withTransaction {
				val categoriesDao = database.getFavouriteCategoriesDao()
				val privateById = categoriesDao.findAllInSpace(FavouriteSpace.PRIVATE.dbValue)
					.associateBy { it.categoryId }
				val idMap = HashMap<Long, Long>(backup.categories.size)
				for (category in backup.categories) {
					val oldId = category.categoryId.toLong()
					val type = parseContentType(category.contentType) ?: FavouriteContentType.MANGA
					val sameIdentity = privateById[category.categoryId]?.takeIf { existing ->
						existing.title == category.title && favouriteContentTypeStore.isCategoryForType(oldId, type)
					}
					val mappedId = if (sameIdentity != null) {
						categoriesDao.upsert(category.toEntity())
						oldId
					} else {
						categoriesDao.insert(
							category.toEntity().copy(
								categoryId = 0,
								sortKey = categoriesDao.getNextSortKey(FavouriteSpace.PRIVATE),
							),
						)
					}
					idMap[oldId] = mappedId
					restoredTypes[mappedId] = type
				}
				for (item in backup.favourites) {
					val categoryId = idMap[item.categoryId] ?: continue
					database.upsertMangaBackup(item.manga)
					database.getPrivateFavouritesDao().upsert(item.toEntity().copy(categoryId = categoryId))
				}
			}
			for ((categoryId, type) in restoredTypes) {
				favouriteContentTypeStore.setCategoryType(categoryId, type)
			}
		}.let { CompositeResult.EMPTY + it }
	}

	private suspend fun restoreCategories(
		items: Sequence<CategoryBackup>,
		idMap: MutableMap<Long, Long>,
		onItemProcessed: suspend (Int) -> Unit,
	): CompositeResult {
		var result = CompositeResult.EMPTY
		val categoriesDao = database.getFavouriteCategoriesDao()
		val normalById = categoriesDao.findAll().associateBy { it.categoryId }
		for (item in items) {
			result += runCatchingCancellable {
				val oldId = item.categoryId.toLong()
				val type = parseContentType(item.contentType) ?: FavouriteContentType.MANGA
				val sameIdentity = normalById[item.categoryId]?.takeIf { existing ->
					existing.title == item.title && favouriteContentTypeStore.isCategoryForType(oldId, type)
				}
				val mappedId = database.withTransaction {
					if (sameIdentity != null) {
						categoriesDao.upsert(item.toEntity().copy(space = FavouriteSpace.NORMAL.dbValue))
						oldId
					} else {
						categoriesDao.insert(
							item.toEntity().copy(
								categoryId = 0,
								sortKey = categoriesDao.getNextSortKey(FavouriteSpace.NORMAL),
								space = FavouriteSpace.NORMAL.dbValue,
							),
						)
					}
				}
				idMap[oldId] = mappedId
				favouriteContentTypeStore.setCategoryType(mappedId, type)
			}
			onItemProcessed(1)
		}
		return result
	}

	private fun categoryContentType(categoryId: Long): String =
		if (favouriteContentTypeStore.isCategoryForType(categoryId, FavouriteContentType.NOVEL)) {
			FavouriteContentType.NOVEL.name
		} else {
			FavouriteContentType.MANGA.name
		}

	private fun parseContentType(value: String?): FavouriteContentType? =
		value?.let { runCatching { FavouriteContentType.valueOf(it) }.getOrNull() }

	private fun dumpAppSettings(): Map<String, BackupPrimitive> {
		val map = settings.getAllValues().toMutableMap()
		AppSettings.SENSITIVE_BACKUP_KEYS.forEach { map.remove(it) }
		return map.mapNotNullToBackupValues()
	}

	private fun dumpReaderGridSettings(): Map<String, BackupPrimitive> {
		return tapGridSettings.getAllValues().mapNotNullToBackupValues()
	}

	private suspend fun dumpSourceSettings(): List<SourceSettingsBackup> {
		val sources = database.getSourcesDao().findAll()
		val result = ArrayList<SourceSettingsBackup>(sources.size)
		for (source in sources) {
			val prefsName = SourceSettings.getStorageName(source.source)
			val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
			val map = prefs.all.mapNotNullToBackupValues()
			if (map.isNotEmpty()) {
				result += SourceSettingsBackup(source = source.source, values = map)
			}
		}
		return result
	}

	private suspend fun dumpMangaChapters(): Flow<MangaWithChaptersBackup> {
		val dao = database.getMangaDao()
		val chaptersDao = database.getChaptersDao()
		val sources = database.getSourcesDao().findAll().map { it.source }
		val seen = HashSet<Long>()
		return kotlinx.coroutines.flow.flow {
			for (source in sources) {
				var offset = 0
				while (true) {
					val items = dao.findAllBySourceForBackup(source, offset, BACKUP_DB_BATCH_SIZE)
					if (items.isEmpty()) break
					offset += items.size
					val uniqueItems = items.filter { seen.add(it.manga.id) }
					if (uniqueItems.isNotEmpty()) {
						val ids = uniqueItems.map { it.manga.id }
						val chaptersByManga = chaptersDao.findAll(ids).groupBy { it.mangaId }
						for (item in uniqueItems) {
							val chapters = chaptersByManga[item.manga.id].orEmpty()
							if (chapters.isEmpty()) continue
							emit(MangaWithChaptersBackup(MangaBackup(item), chapters.map(::ChapterBackup)))
						}
					}
					if (items.size < BACKUP_DB_BATCH_SIZE) break
				}
			}
		}
	}

	private suspend fun dumpFeed(): FeedBackup {
		val mangaDao = database.getMangaDao()
		val mangaCache = HashMap<Long, MangaBackup?>()
		suspend fun mangaOf(id: Long): MangaBackup? =
			mangaCache.getOrPut(id) { mangaDao.find(id)?.let(::MangaBackup) }
		val tracks = database.getTracksDao().findAllForSync().mapNotNull { entity ->
			mangaOf(entity.mangaId)?.let { SyncTrack(entity, it) }
		}
		val logs = database.getTrackLogsDao().findAllForSync().mapNotNull { entity ->
			mangaOf(entity.mangaId)?.let { SyncFeedEntry(entity, it) }
		}
		return FeedBackup(tracks = tracks, logs = logs)
	}

	private fun dumpMangaPrefs(): Flow<MangaPrefsBackup> = flow {
		val mangaDao = database.getMangaDao()
		for (entity in database.getPreferencesDao().getOverrides()) {
			val manga = mangaDao.find(entity.mangaId) ?: continue
			val cover = coverCodec.read(entity.coverUrlOverride)
			emit(
				MangaPrefsBackup(
					manga = MangaBackup(manga),
					prefs = SyncMangaPrefs(
						entity = entity,
						coverData = cover?.data,
						coverFileExtension = cover?.extension,
					),
				),
			)
		}
	}

	private suspend fun restoreFeed(
		input: InputStream,
		restoredMangaIds: MutableSet<Long>,
		onBatchProcessed: suspend (Int) -> Unit,
	): CompositeResult {
		val decoded = runCatchingCancellable { json.decodeFromStream<FeedBackup>(input) }
		if (decoded.isFailure) return CompositeResult.failure(checkNotNull(decoded.exceptionOrNull()))
		val backup = decoded.getOrThrow()
		var result = backup.tracks.asSequence().restoreMangaToDb(
			restoredMangaIds = restoredMangaIds,
			onBatchProcessed = onBatchProcessed,
			mangaOf = { it.manga },
		) { getTracksDao().upsert(it.toEntity()) }
		val logsDao = database.getTrackLogsDao()
		val existing = logsDao.findAllForSync().mapTo(HashSet()) { SyncMerger.feedIdentity(it.mangaId, it.chapters) }
		val uniqueLogs = backup.logs.asSequence().filter { existing.add(SyncMerger.feedIdentity(it)) }
		result += uniqueLogs.restoreMangaToDb(
			restoredMangaIds = restoredMangaIds,
			onBatchProcessed = onBatchProcessed,
			mangaOf = { it.manga },
		) { logsDao.insert(it.toEntity()) }
		return result
	}

	private suspend fun restoreMangaPrefs(
		input: InputStream,
		restoredMangaIds: MutableSet<Long>,
		onBatchProcessed: suspend (Int) -> Unit,
	): CompositeResult {
		var result = CompositeResult.EMPTY
		for (batch in input.readJsonArray<MangaPrefsBackup>(serializer()).chunked(RESTORE_DB_BATCH_SIZE)) {
			val prepared = ArrayList<Pair<MangaPrefsBackup, String?>>(batch.size)
			for (item in batch) {
				val preparation = runCatchingCancellable {
					val prefs = item.prefs
					val currentCover = database.getPreferencesDao().find(prefs.mangaId)?.coverUrlOverride
					val resolvedCover = when {
						prefs.coverData != null -> coverCodec.materialize(
							mangaId = prefs.mangaId,
							coverData = prefs.coverData,
							coverFileExtension = prefs.coverFileExtension,
							previousUrl = currentCover,
						) ?: currentCover
						coverCodec.isPortableCoverUrl(prefs.coverUrlOverride) -> prefs.coverUrlOverride
						else -> currentCover
					}
					item to resolvedCover
				}
				if (preparation.isSuccess) prepared += preparation.getOrThrow() else result += preparation
			}
			if (prepared.isNotEmpty()) {
				val pendingMangaIds = HashSet<Long>()
				val batchRestore = runCatchingCancellable {
					database.withTransaction {
						for ((item, resolvedCover) in prepared) {
							val id = item.manga.id
							if (id !in restoredMangaIds && pendingMangaIds.add(id)) database.upsertMangaBackup(item.manga)
							database.getPreferencesDao().upsert(item.prefs.toEntity(resolvedCover))
						}
					}
				}
				if (batchRestore.isSuccess) {
					restoredMangaIds.addAll(pendingMangaIds)
					result += CompositeResult.success(prepared.size)
				} else {
					for ((item, resolvedCover) in prepared) {
						val single = runCatchingCancellable {
							database.withTransaction {
								if (item.manga.id !in restoredMangaIds) database.upsertMangaBackup(item.manga)
								database.getPreferencesDao().upsert(item.prefs.toEntity(resolvedCover))
							}
						}
						if (single.isSuccess) restoredMangaIds.add(item.manga.id)
						result += single
					}
				}
			}
			onBatchProcessed(batch.size)
		}
		return result
	}

	private suspend fun removeEmptyReadLaterCategory() {
		runCatchingCancellable {
			val readLaterTitle = context.getString(R.string.read_later)
			val dao = database.getFavouriteCategoriesDao()
			val readLater = dao.findAll().firstOrNull { it.title == readLaterTitle } ?: return
			if (database.getFavouritesDao().findAll(readLater.categoryId.toLong()).isEmpty()) {
				dao.delete(readLater.categoryId.toLong())
			}
	}

	private suspend fun MangaDatabase.upsertMangaBackup(manga: MangaBackup) {
		val tags = manga.tags.map { it.toEntity() }
		if (tags.isNotEmpty()) getTagsDao().upsert(tags)
		getMangaDao().upsert(manga.toEntity(), tags)
	}

	private suspend fun <T> Sequence<T>.restoreMangaToDb(
		restoredMangaIds: MutableSet<Long>,
		onBatchProcessed: suspend (Int) -> Unit,
		mangaOf: (T) -> MangaBackup,
		block: suspend MangaDatabase.(T) -> Unit,
	): CompositeResult {
		var result = CompositeResult.EMPTY
		for (batch in chunked(RESTORE_DB_BATCH_SIZE)) {
			val pendingMangaIds = HashSet<Long>()
			val batchRestore = runCatchingCancellable {
				database.withTransaction {
					for (item in batch) {
						val manga = mangaOf(item)
						if (manga.id !in restoredMangaIds && pendingMangaIds.add(manga.id)) database.upsertMangaBackup(manga)
						database.block(item)
					}
				}
			}
			if (batchRestore.isSuccess) {
				restoredMangaIds.addAll(pendingMangaIds)
				result += CompositeResult.success(batch.size)
			} else {
				for (item in batch) {
					val manga = mangaOf(item)
					val single = runCatchingCancellable {
						database.withTransaction {
							if (manga.id !in restoredMangaIds) database.upsertMangaBackup(manga)
							database.block(item)
						}
					}
					if (single.isSuccess) restoredMangaIds.add(manga.id)
					result += single
				}
			}
			onBatchProcessed(batch.size)
		}
		return result
	}

	private suspend fun <T> Sequence<T>.restoreToDb(
		onBatchProcessed: suspend (Int) -> Unit = {},
		block: suspend MangaDatabase.(T) -> Unit,
	): CompositeResult {
		var result = CompositeResult.EMPTY
		for (batch in chunked(RESTORE_DB_BATCH_SIZE)) {
			val batchRestore = runCatchingCancellable {
				database.withTransaction {
					for (item in batch) {
						database.block(item)
					}
				}
			}
			if (batchRestore.isSuccess) {
				result += CompositeResult.success(batch.size)
			} else {
				for (item in batch) {
					result += runCatchingCancellable {
						database.withTransaction { database.block(item) }
					}
				}
			}
			onBatchProcessed(batch.size)
		}
		return result
	}

	private fun restoreAppSettings(input: InputStream): CompositeResult {
		return runCatchingCancellable {
			val map = json.decodeFromString<Map<String, BackupPrimitive>>(input.readBytes().decodeToString())
				.toMutableMap()
			AppSettings.SENSITIVE_BACKUP_KEYS.forEach { map.remove(it) }
			settings.upsertAll(map.toRawMap())
		}.let { CompositeResult.EMPTY + it }
	}

	private fun restoreReaderGridSettings(input: InputStream): CompositeResult {
		return runCatchingCancellable {
			val map = json.decodeFromString<Map<String, BackupPrimitive>>(input.readBytes().decodeToString())
			tapGridSettings.upsertAll(map.toRawMap())
		}.let { CompositeResult.EMPTY + it }
	}

	private suspend fun restoreSourceSettings(
		input: InputStream,
		onItemProcessed: suspend (Int) -> Unit,
	): CompositeResult {
		var result = CompositeResult.EMPTY
		for (entry in input.readJsonArray<SourceSettingsBackup>(serializer())) {
			result += runCatchingCancellable {
				val prefsName = SourceSettings.getStorageName(entry.source)
				val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
				prefs.edit {
					entry.values.forEach { (key, primitive) ->
						when (primitive) {
							is BackupPrimitive.StringValue -> putString(key, primitive.value)
							is BackupPrimitive.BoolValue -> putBoolean(key, primitive.value)
							is BackupPrimitive.IntValue -> putInt(key, primitive.value)
							is BackupPrimitive.LongValue -> putLong(key, primitive.value)
							is BackupPrimitive.FloatValue -> putFloat(key, primitive.value)
							is BackupPrimitive.StringSetValue -> putStringSet(key, primitive.value)
						}
					}
				}
			}
			onItemProcessed(1)
		}
		return result
	}

	private fun Map<String, *>.mapNotNullToBackupValues(): Map<String, BackupPrimitive> {
		val out = LinkedHashMap<String, BackupPrimitive>(size)
		for ((key, value) in this) {
			BackupPrimitive.of(value)?.let { out[key] = it }
		}
		return out
	}

	private fun Map<String, BackupPrimitive>.toRawMap(): Map<String, Any?> {
		val out = LinkedHashMap<String, Any?>(size)
		for ((key, value) in this) out[key] = value.rawValue()
		return out
	}

	companion object {
		internal const val MIYORARE_METADATA_ENTRY = "miyorare_metadata"
		internal const val PRIVATE_FAVOURITES_ENTRY = "private_favourites"
		private const val BACKUP_DB_BATCH_SIZE = 128
		private const val RESTORE_DB_BATCH_SIZE = 128
	}
}
