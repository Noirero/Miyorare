package org.koitharu.kotatsu.backup

import android.content.Context
import android.net.Uri
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.sink
import org.koitharu.kotatsu.backup.model.MihonBackup
import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupChapter
import org.koitharu.kotatsu.backup.model.MihonBackupHistory
import org.koitharu.kotatsu.backup.model.MihonBackupManga
import org.koitharu.kotatsu.backup.model.MihonBackupSource
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.TagEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.kotatsumigration.data.KotatsuSourceMap
import org.koitharu.kotatsu.kotatsumigration.domain.toMihonUrl
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Writes favourites and reading progress into a Mihon-compatible `.tachibk` file using the same
 * gzip + protobuf container Mihon/Komikku restore.
 *
 * Native Mihon entries keep their persisted `MIHON_<id>` source id. Legacy Kotatsu-backed entries
 * are exported when [KotatsuSourceMap] has an equivalent Mihon source, so an older Miyorare library
 * does not silently become an empty backup. Local, novel, and genuinely unmapped sources are skipped.
 */
@Reusable
class MihonBackupExporter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val db: MangaDatabase,
	private val mihonExtensionManager: MihonExtensionManager,
	private val kotatsuSourceMap: KotatsuSourceMap,
) {

	/** Number of titles written, so the caller can tell the user whether anything was skipped. */
	data class Report(
		val exportedCount: Int,
		val skippedCount: Int,
	)

	suspend fun export(uri: Uri): Report = withContext(Dispatchers.IO) {
		// Source display names are useful metadata, but they must not be required for exporting.
		// Warm the extension registry when possible and fall back to the persisted/mapped source id.
		runCatching { mihonExtensionManager.ensureReady() }

		val (backup, skipped) = buildBackup()
		if (backup.backupManga.isEmpty()) {
			throw IOException("No Mihon-compatible manga entries could be exported")
		}
		val payload = ProtoBuf.encodeToByteArray(MihonBackup.serializer(), backup)
		if (payload.isEmpty()) throw IOException("Generated Mihon backup is empty")

		val output = context.contentResolver.openOutputStream(uri, "wt")
			?: throw IOException("Cannot open $uri")
		output.use { stream ->
			stream.sink().gzip().buffer().use { it.write(payload) }
		}
		Report(
			exportedCount = backup.backupManga.size,
			skippedCount = skipped,
		)
	}

	private suspend fun buildBackup(): Pair<MihonBackup, Int> {
		var skipped = 0
		val categories = db.getFavouriteCategoriesDao().findAll()
		// Mihon references categories by their `order`, not by id — see the restore side's
		// CategoryResolver. Emitting the list position as the order keeps both ends in step.
		val categoryOrderById: Map<Long, Long> = categories
			.mapIndexed { index, category -> category.categoryId.toLong() to index.toLong() }
			.toMap()

		val records = HashMap<Long, Record>()
		db.getFavouritesDao().dump().collect { favourite ->
			if (favourite.favourite.deletedAt != 0L) return@collect
			val record = records.getOrPut(favourite.manga.id) { Record(favourite.manga, favourite.tags) }
			record.isFavourite = true
			val createdAt = favourite.favourite.createdAt
			if (createdAt > 0 && (record.dateAdded == 0L || createdAt < record.dateAdded)) {
				record.dateAdded = createdAt
			}
			val categoryOrder = categoryOrderById[favourite.favourite.categoryId]
			if (categoryOrder != null) {
				record.categories.add(categoryOrder)
			}
		}
		db.getHistoryDao().dump().collect { entry ->
			val record = records.getOrPut(entry.history.mangaId) { Record(entry.manga, entry.tags) }
			record.history = entry.history
		}

		val usedSources = HashMap<Long, String>()
		val prepared = ArrayList<PreparedRecord>(records.size)
		for (record in records.values) {
			val directSourceId = parseMihonSourceId(record.manga.source)
			val mappedLegacy = if (directSourceId == null) {
				kotatsuSourceMap.resolve(record.manga.source)
			} else {
				null
			}
			val sourceId = directSourceId ?: mappedLegacy?.sourceId
			if (sourceId == null) {
				skipped++
				continue
			}

			if (sourceId !in usedSources) {
				val liveSource = mihonExtensionManager.getMihonMangaSourceById(sourceId)
				usedSources[sourceId] = liveSource?.displayName
					?: mappedLegacy?.sourceName?.takeIf { it.isNotBlank() }
					?: record.manga.sourceTitle?.takeIf { it.isNotBlank() }
					?: sourceId.toString()
			}
			prepared += PreparedRecord(
				record = record,
				sourceId = sourceId,
				normalizeLegacyUrls = mappedLegacy != null,
			)
		}

		val manga = ArrayList<MihonBackupManga>(prepared.size)
		for (batch in prepared.chunked(EXPORT_DB_BATCH_SIZE)) {
			val chaptersByManga = db.getChaptersDao()
				.findAll(batch.map { it.record.manga.id })
				.groupBy { it.mangaId }
			for (item in batch) {
				manga += toBackupManga(
					record = item.record,
					sourceId = item.sourceId,
					normalizeLegacyUrls = item.normalizeLegacyUrls,
					allChapters = chaptersByManga[item.record.manga.id].orEmpty(),
				)
			}
		}

		val backup = MihonBackup(
			backupManga = manga,
			backupCategories = categories.mapIndexed { index, category ->
				MihonBackupCategory(
					name = category.title,
					order = index.toLong(),
					id = category.categoryId.toLong(),
					flags = encodeMihonCategorySortOrder(category.order),
				)
			},
			backupSources = usedSources.map { (id, name) -> MihonBackupSource(name = name, sourceId = id) },
		)
		return backup to skipped
	}

	private fun toBackupManga(
		record: Record,
		sourceId: Long,
		normalizeLegacyUrls: Boolean,
		allChapters: List<ChapterEntity>,
	): MihonBackupManga {
		val manga = record.manga
		val currentIndex = record.history?.let { history ->
			allChapters.indexOfFirst { it.chapterId == history.chapterId }
		} ?: -1
		val currentChapter = allChapters.getOrNull(currentIndex)
		val lastRead = record.history?.updatedAt ?: 0L
		val mangaUrl = if (normalizeLegacyUrls) manga.url.toMihonUrl() else manga.url

		return MihonBackupManga(
			source = sourceId,
			url = mangaUrl,
			title = manga.title,
			author = manga.authors,
			description = manga.description,
			genre = record.tags.map { it.title },
			thumbnailUrl = manga.coverUrl,
			dateAdded = record.dateAdded,
			chapters = allChapters.mapIndexed { index, chapter ->
				chapter.toBackupChapter(
					// Mihon numbers chapters newest-first, Kotatsu oldest-first.
					sourceOrder = (allChapters.size - 1 - index).toLong(),
					isRead = currentIndex >= 0 && index < currentIndex,
					lastPageRead = if (index == currentIndex) record.history?.page?.toLong() ?: 0L else 0L,
					lastRead = if (index == currentIndex) lastRead else 0L,
					normalizeLegacyUrl = normalizeLegacyUrls,
				)
			},
			categories = record.categories.toList(),
			favorite = record.isFavourite,
			history = currentChapter?.let { chapter ->
				val historyUrl = if (normalizeLegacyUrls) chapter.url.toMihonUrl() else chapter.url
				listOf(MihonBackupHistory(url = historyUrl, lastRead = lastRead))
			}.orEmpty(),
			lastModifiedAt = lastRead,
			// A mapped legacy entry has not necessarily been fetched through the target extension yet.
			// Let Mihon/Komikku refresh its canonical details after restore instead of treating it as final.
			initialized = !normalizeLegacyUrls,
		)
	}

	private fun ChapterEntity.toBackupChapter(
		sourceOrder: Long,
		isRead: Boolean,
		lastPageRead: Long,
		lastRead: Long,
		normalizeLegacyUrl: Boolean,
	) = MihonBackupChapter(
		url = if (normalizeLegacyUrl) url.toMihonUrl() else url,
		name = title,
		scanlator = scanlator,
		read = isRead,
		lastPageRead = lastPageRead,
		dateUpload = uploadDate,
		chapterNumber = number,
		sourceOrder = sourceOrder,
		lastModifiedAt = lastRead,
	)

	private fun parseMihonSourceId(sourceName: String): Long? {
		if (!sourceName.startsWith(MIHON_SOURCE_PREFIX)) return null
		return sourceName.removePrefix(MIHON_SOURCE_PREFIX).substringBefore(':').toLongOrNull()
	}

	private data class PreparedRecord(
		val record: Record,
		val sourceId: Long,
		val normalizeLegacyUrls: Boolean,
	)

	private class Record(
		val manga: MangaEntity,
		val tags: List<TagEntity>,
	) {
		val categories = LinkedHashSet<Long>()
		var isFavourite = false
		var dateAdded = 0L
		var history: HistoryEntity? = null
	}

	companion object {

		// Match Mihon's own CreateDocument contract. application/octet-stream can make some Android
		// document providers replace the requested `.tachibk` extension with `.bin`.
		const val MIME_TYPE = "application/*"
		private const val MIHON_SOURCE_PREFIX = "MIHON_"
		private const val EXPORT_DB_BATCH_SIZE = 256

		fun generateFileName(): String = "miyorare_" +
			SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) +
			".tachibk"
	}
}

/** Encodes the category sort bits used by Mihon. Unsupported Miyorare-only sorts use alphabetical
 * ascending as an interoperable fallback; native Miyorare backups keep their exact Room state. */
internal fun encodeMihonCategorySortOrder(value: String): Long {
	val order = runCatching { ListSortOrder.valueOf(value) }.getOrDefault(ListSortOrder.ALPHABETIC)
	val typeBits = when (order.type) {
		ListSortOrder.Type.ALPHABETICAL -> 0b00000000L
		ListSortOrder.Type.LAST_READ -> 0b00000100L
		ListSortOrder.Type.UNREAD_COUNT -> 0b00001100L
		ListSortOrder.Type.TOTAL_CHAPTERS -> 0b00010000L
		ListSortOrder.Type.LATEST_CHAPTER -> 0b00010100L
		ListSortOrder.Type.DATE_ADDED -> 0b00011100L
		ListSortOrder.Type.PROGRESS,
		ListSortOrder.Type.NEW_CHAPTERS,
		-> 0b00000000L
	}
	return typeBits or if (order.isAscending) 0b01000000L else 0L
}
