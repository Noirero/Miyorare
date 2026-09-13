package org.koitharu.kotatsu.backup.local.data

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koitharu.kotatsu.backup.local.data.model.LibraryGroupBackup
import org.koitharu.kotatsu.backup.local.domain.CustomCoverCodec
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNovelContentPath
import org.koitharu.kotatsu.core.model.isNovelContentSource
import org.koitharu.kotatsu.core.util.CompositeResult
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupCategoryEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupMemberEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupTrackingEntity
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject

@Reusable
class LibraryGroupBackupCodec @Inject constructor(
	private val database: MangaDatabase,
	private val coverCodec: CustomCoverCodec,
) {

	fun dump(space: FavouriteSpace = FavouriteSpace.NORMAL): Flow<LibraryGroupBackup> = flow {
		val dao = database.getLibraryGroupsDao()
		val trackingDao = database.getLibraryGroupTrackingDao()
		for (group in dao.findAllGroups(space.dbValue)) {
			val members = dao.findMembers(group.groupId)
			if (members.size < 2) continue
			val encodedCover = coverCodec.read(group.coverUrl)
			val categoryIds = dao.findCategories(group.groupId).map { it.categoryId }
			emit(
				LibraryGroupBackup(
					entity = group,
					members = members,
					coverData = encodedCover?.data,
					coverFileExtension = encodedCover?.extension,
					categoryIds = categoryIds,
					tracking = trackingDao.findAll(group.groupId),
				),
			)
		}
	}

	suspend fun restore(
		items: Sequence<LibraryGroupBackup>,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
		categoryIdMap: Map<Long, Long> = emptyMap(),
	): CompositeResult = items.fold(CompositeResult.EMPTY) { acc, backup ->
		acc + runCatchingCancellable { restoreOne(backup, space, categoryIdMap) }
	}

	private suspend fun restoreOne(
		backup: LibraryGroupBackup,
		space: FavouriteSpace,
		categoryIdMap: Map<Long, Long>,
	) {
		val orderedMembers = backup.members
			.sortedWith(compareBy({ it.position }, { it.mangaId }))
		val memberIds = orderedMembers.map { it.mangaId }
		require(memberIds.size >= 2 && memberIds.distinct().size == memberIds.size) {
			"Invalid library group membership in backup"
		}
		val title = backup.title.trim()
		require(title.isNotEmpty()) { "Library group title is empty in backup" }

		val portableCover = backup.coverUrl?.takeIf(coverCodec::isPortableCoverUrl)
		val prepared = database.withTransaction {
			val dao = database.getLibraryGroupsDao()
			for (mangaId in memberIds) {
				val categoriesCount = when (space) {
					FavouriteSpace.NORMAL -> database.getFavouritesDao().findCategoriesCount(mangaId)
					FavouriteSpace.PRIVATE -> database.getPrivateFavouritesDao().findCategoriesCount(mangaId)
				}
				require(categoriesCount > 0) {
					"Library group member $mangaId is not in the restored ${space.name.lowercase()} library"
				}
				val manga = database.getMangaDao().find(mangaId)?.manga
				requireNotNull(manga) { "Library group member $mangaId is missing from the database" }
				val source = MangaSource(manga.source)
				val isNovel = source.isNovelContentSource || (source.isLocal && manga.url.isNovelContentPath())
				require(!isNovel) {
					"Novel entries are not supported by Advanced Library Groups yet"
				}
			}

			val affiliations = dao.findMembersByMangaIds(memberIds, space.dbValue)
			val existingGroupId = if (affiliations.isEmpty()) {
				null
			} else {
				val groupIds = affiliations.mapTo(LinkedHashSet<Long>()) { it.groupId }
				require(groupIds.size == 1) { "Library group members already belong to different groups" }
				val groupId = groupIds.single()
				val existingIds = dao.findMembers(groupId).mapTo(HashSet<Long>()) { it.mangaId }
				require(existingIds == memberIds.toSet()) {
					"Library group overlaps an existing group with different members"
				}
				groupId
			}

			val oldCover = existingGroupId?.let { dao.findGroup(it)?.coverUrl }
			val provisionalCover = when {
				backup.coverData != null -> oldCover
				portableCover != null -> portableCover
				else -> oldCover
			}
			val groupId = existingGroupId ?: dao.insertGroup(
				LibraryGroupEntity(
					title = title,
					coverUrl = provisionalCover,
					createdAt = backup.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
					space = space.dbValue,
					alternativeTitle = backup.alternativeTitle.normalizeOptionalText(),
					author = backup.author.normalizeOptionalText(),
					artist = backup.artist.normalizeOptionalText(),
					description = backup.description.normalizeOptionalText(),
					metadataSource = backup.metadataSource,
					metadataTargetId = backup.metadataTargetId,
				),
			)
			if (existingGroupId == null) {
				dao.insertMembers(
					orderedMembers.mapIndexed { index, member ->
						LibraryGroupMemberEntity(
							groupId = groupId,
							mangaId = member.mangaId,
							position = index,
						)
					},
				)
			} else {
				orderedMembers.forEachIndexed { index, member ->
					dao.updateMemberPosition(groupId, member.mangaId, index)
				}
			}

			val metadataUpdated = database.getLibraryGroupMetadataDao().update(
				groupId = groupId,
				space = space.dbValue,
				title = title,
				coverUrl = provisionalCover,
				alternativeTitle = backup.alternativeTitle.normalizeOptionalText(),
				author = backup.author.normalizeOptionalText(),
				artist = backup.artist.normalizeOptionalText(),
				description = backup.description.normalizeOptionalText(),
				metadataSource = backup.metadataSource,
				metadataTargetId = backup.metadataTargetId,
			)
			require(metadataUpdated == 1) { "Unable to restore library group metadata" }

			val availableCategoryIds = database.getFavouriteCategoriesDao()
				.findAllInSpace(space.dbValue)
				.mapTo(HashSet()) { it.categoryId.toLong() }
			val restoredCategoryIds = backup.categoryIds
				.map { sourceId -> categoryIdMap[sourceId] ?: sourceId }
				.distinct()
				.filter { it in availableCategoryIds }
			dao.deleteCategories(groupId)
			if (restoredCategoryIds.isNotEmpty()) {
				dao.insertCategories(restoredCategoryIds.map { LibraryGroupCategoryEntity(groupId, it) })
			}

			val knownServices = ScrobblerService.values().mapTo(HashSet()) { it.id }
			val trackingDao = database.getLibraryGroupTrackingDao()
			trackingDao.deleteAll(groupId)
			val restoredTracking = backup.tracking
				.filter { it.service in knownServices && it.targetId > 0L && it.targetTitle.isNotBlank() }
				.distinctBy { it.service }
				.map { item ->
					LibraryGroupTrackingEntity(
						groupId = groupId,
						service = item.service,
						rateId = item.rateId,
						targetId = item.targetId,
						targetTitle = item.targetTitle.trim(),
						targetUrl = item.targetUrl.normalizeOptionalText(),
						status = item.status.normalizeOptionalText(),
						progress = item.progress.coerceAtLeast(0),
						rating = item.rating.coerceIn(0f, 1f),
						comment = item.comment.normalizeOptionalText(),
						lastSyncAt = item.lastSyncAt.coerceAtLeast(0L),
					)
				}
			if (restoredTracking.isNotEmpty()) trackingDao.upsertAll(restoredTracking)
			PreparedGroup(groupId, title, oldCover, provisionalCover)
		}

		val restoredCover = when {
			backup.coverData != null -> coverCodec.materialize(
				mangaId = groupCoverStorageId(prepared.groupId),
				coverData = backup.coverData,
				coverFileExtension = backup.coverFileExtension,
				previousUrl = prepared.oldCover,
			) ?: prepared.provisionalCover

			portableCover != null -> portableCover
			else -> prepared.provisionalCover
		}
		if (restoredCover != prepared.provisionalCover) {
			database.getLibraryGroupsDao().updateGroup(prepared.groupId, prepared.title, restoredCover)
		}
	}

	private fun groupCoverStorageId(groupId: Long): Long = Long.MIN_VALUE + groupId

	private fun String?.normalizeOptionalText(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

	private data class PreparedGroup(
		val groupId: Long,
		val title: String,
		val oldCover: String?,
		val provisionalCover: String?,
	)
}
