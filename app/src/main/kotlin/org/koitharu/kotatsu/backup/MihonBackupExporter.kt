package org.koitharu.kotatsu.backup

import android.content.Context
import android.net.Uri
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.serializer
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream
import javax.inject.Inject

/**
 * Writes favourites and reading progress into a Mihon-compatible `.tachibk` file using the same
 * gzip + protobuf container Mihon/Komikku restore.
 *
 * Export is deliberately bounded: database candidates are keyset-paged, related rows are loaded in
 * fixed batches, and each top-level protobuf manga message is written immediately. The complete
 * library and complete protobuf payload are never resident in memory at the same time.
 */
@Reusable
class MihonBackupExporter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val db: MangaDatabase,
	private val mihonExtensionManager: MihonExtensionManager,
	private val kotatsuSourceMap: KotatsuSourceMap,
) {

	data class Report(
		val exportedCount: Int,
		val skippedCount: Int,
	)

	suspend fun export(uri: Uri): Report = withContext(Dispatchers.IO) {
		runCatching { mihonExtensionManager.ensureReady() }

		val categories = db.getFavouriteCategoriesDao().findAll()
		val categoryOrderById = categories
			.mapIndexed { index, category -> category.categoryId.toLong() to index.toLong() }
			.toMap()
		val usedSources = LinkedHashMap<Long, String>()
		var exported = 0
		var skipped = 0

		val tempFile = File.createTempFile("miyorare_mihon_export_", ".tachibk", context.cacheDir)
		try {
			GZIPOutputStream(BufferedOutputStream(tempFile.outputStream(), IO_BUFFER_SIZE)).use { output ->
				val mangaDao = db.getMangaDao()
				var afterMangaId: Long? = null
				while (true) {
					val batch = afterMangaId?.let { mangaDao.findAllForMihonExport(it, EXPORT_DB_BATCH_SIZE) }
						?: mangaDao.findFirstForMihonExport(EXPORT_DB_BATCH_SIZE)
					if (batch.isEmpty()) break

					val ids = batch.map { it.manga.id }
					val favouritesByManga = db.getFavouritesDao().findByIds(ids)
						.groupBy { it.favourite.mangaId }
					val historyByManga = db.getHistoryDao().findByIds(ids).associateBy { it.mangaId }
					val chaptersByManga = db.getChaptersDao().findAll(ids).groupBy { it.mangaId }

					for (item in batch) {
						val manga = item.manga
						val favourites = favouritesByManga[manga.id].orEmpty()
						val history = historyByManga[manga.id]
						if (favourites.isEmpty() && history == null) continue

						val directSourceId = parseMihonSourceId(manga.source)
						val mappedLegacy = if (directSourceId == null) kotatsuSourceMap.resolve(manga.source) else null
						val sourceId = directSourceId ?: mappedLegacy?.sourceId
						if (sourceId == null) {
							skipped++
							continue
						}

						if (sourceId !in usedSources) {
							val liveSource = mihonExtensionManager.getMihonMangaSourceById(sourceId)
							usedSources[sourceId] = liveSource?.displayName
								?: mappedLegacy?.sourceName?.takeIf { it.isNotBlank() }
								?: manga.sourceTitle?.takeIf { it.isNotBlank() }
								?: sourceId.toString()
						}

						val categoryOrders = favourites.mapNotNullTo(LinkedHashSet()) {
							categoryOrderById[it.favourite.categoryId]
						}
						val dateAdded = favourites.asSequence()
							.map { it.favourite.createdAt }
							.filter { it > 0L }
							.minOrNull() ?: 0L
						val backupManga = toBackupManga(
							manga = manga,
							tags = item.tags,
							history = history,
							allChapters = chaptersByManga[manga.id].orEmpty(),
							sourceId = sourceId,
							normalizeLegacyUrls = mappedLegacy != null,
							categories = categoryOrders.toList(),
							isFavourite = favourites.isNotEmpty(),
							dateAdded = dateAdded,
						)
						MihonBackupWire.writeMessage(
							output,
							MihonBackupWire.FIELD_MANGA,
							serializer<MihonBackupManga>(),
							backupManga,
						)
						exported++
					}
					afterMangaId = batch.last().manga.id
				}

				if (exported == 0) {
					throw IOException("No Mihon-compatible manga entries could be exported")
				}
				categories.forEachIndexed { index, category ->
					MihonBackupWire.writeMessage(
						output,
						MihonBackupWire.FIELD_CATEGORY,
						serializer<MihonBackupCategory>(),
						MihonBackupCategory(
							name = category.title,
							order = index.toLong(),
							id = category.categoryId.toLong(),
							flags = encodeMihonCategorySortOrder(category.order),
						),
					)
				}
				usedSources.forEach { (id, name) ->
					MihonBackupWire.writeMessage(
						output,
						MihonBackupWire.FIELD_SOURCE,
						serializer<MihonBackupSource>(),
						MihonBackupSource(name = name, sourceId = id),
					)
				}
			}

			val target = context.contentResolver.openOutputStream(uri, "wt")
				?: throw IOException("Cannot open $uri")
			target.use { rawOutput ->
				BufferedOutputStream(rawOutput, IO_BUFFER_SIZE).use { output ->
					BufferedInputStream(tempFile.inputStream(), IO_BUFFER_SIZE).use { input ->
						input.copyTo(output, IO_BUFFER_SIZE)
					}
				}
			}
			Report(exportedCount = exported, skippedCount = skipped)
		} finally {
			tempFile.delete()
		}
	}

	private fun toBackupManga(
		manga: MangaEntity,
		tags: List<TagEntity>,
		history: HistoryEntity?,
		allChapters: List<ChapterEntity>,
		sourceId: Long,
		normalizeLegacyUrls: Boolean,
		categories: List<Long>,
		isFavourite: Boolean,
		dateAdded: Long,
	): MihonBackupManga {
		val currentIndex = history?.let { current ->
			allChapters.indexOfFirst { it.chapterId == current.chapterId }
		} ?: -1
		val currentChapter = allChapters.getOrNull(currentIndex)
		val lastRead = history?.updatedAt ?: 0L
		val mangaUrl = if (normalizeLegacyUrls) manga.url.toMihonUrl() else manga.url

		return MihonBackupManga(
			source = sourceId,
			url = mangaUrl,
			title = manga.title,
			author = manga.authors,
			description = manga.description,
			genre = tags.map { it.title },
			thumbnailUrl = manga.coverUrl,
			dateAdded = dateAdded,
			chapters = allChapters.mapIndexed { index, chapter ->
				chapter.toBackupChapter(
					sourceOrder = (allChapters.size - 1 - index).toLong(),
					isRead = currentIndex >= 0 && index < currentIndex,
					lastPageRead = if (index == currentIndex) history?.page?.toLong() ?: 0L else 0L,
					lastRead = if (index == currentIndex) lastRead else 0L,
					normalizeLegacyUrl = normalizeLegacyUrls,
				)
			},
			categories = categories,
			favorite = isFavourite,
			history = currentChapter?.let { chapter ->
				val historyUrl = if (normalizeLegacyUrls) chapter.url.toMihonUrl() else chapter.url
				listOf(MihonBackupHistory(url = historyUrl, lastRead = lastRead))
			}.orEmpty(),
			lastModifiedAt = lastRead,
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

	companion object {
		const val MIME_TYPE = "application/*"
		private const val MIHON_SOURCE_PREFIX = "MIHON_"
		private const val EXPORT_DB_BATCH_SIZE = 256
		private const val IO_BUFFER_SIZE = 64 * 1024

		fun generateFileName(): String = "miyorare_" +
			SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) +
			".tachibk"
	}
}

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
