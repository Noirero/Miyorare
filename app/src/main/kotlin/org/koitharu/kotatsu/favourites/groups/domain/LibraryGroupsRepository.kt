package org.koitharu.kotatsu.favourites.groups.domain

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupCategoryEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupMemberDisplay
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupMemberEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupTimelineItemEntity
import javax.inject.Inject

data class LibraryGroupMember(
	val mangaId: Long,
	val position: Int,
	val displayTitle: String,
	val displayCoverUrl: String?,
	val isNsfw: Boolean,
	val contentRating: String?,
	val source: String,
)

data class LibraryGroupTimelineItem(
	val mangaId: Long,
	val chapterId: Long,
	val position: Int,
)

data class LibraryGroup(
	val id: Long,
	val title: String,
	val coverUrl: String?,
	val members: List<LibraryGroupMember>,
	val createdAt: Long,
	val categoryIds: Set<Long> = emptySet(),
	val space: FavouriteSpace = FavouriteSpace.NORMAL,
) {
	val memberIds: List<Long>
		get() = members.map { it.mangaId }

	/** A group must never make restricted content look safer than one of its members. */
	val containsNsfw: Boolean
		get() = members.any { it.isNsfw || it.contentRating.equals("ADULT", ignoreCase = true) }
}

@Reusable
class LibraryGroupsRepository @Inject constructor(
	private val db: MangaDatabase,
) {

	private val dao
		get() = db.getLibraryGroupsDao()

	fun observeGroups(space: FavouriteSpace = FavouriteSpace.NORMAL): Flow<List<LibraryGroup>> = combine(
		dao.observeGroups(space.dbValue),
		dao.observeMemberDisplays(space.dbValue),
		dao.observeCategories(space.dbValue),
	) { groups, members, categories ->
		val membersByGroup = members.groupBy { it.groupId }
		val categoriesByGroup = categories.groupBy { it.groupId }
		groups.mapNotNull { group ->
			val groupMembers = membersByGroup[group.groupId].orEmpty()
			val categoryIds = categoriesByGroup[group.groupId]
				.orEmpty()
				.mapTo(LinkedHashSet()) { it.categoryId }
			group.toDomain(groupMembers, categoryIds).takeIf { it.members.size >= 2 }
		}
	}.distinctUntilChanged()

	fun observeTimeline(
		groupId: Long,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Flow<List<LibraryGroupTimelineItem>> = dao.observeTimeline(groupId)
		.map { items ->
			if (dao.findGroup(groupId)?.space != space.dbValue) {
				emptyList()
			} else {
				items.map { it.toDomain() }
			}
		}
		.distinctUntilChanged()

	suspend fun getGroup(
		groupId: Long,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): LibraryGroup? = db.withTransaction {
		val group = dao.findGroup(groupId)?.takeIf { it.space == space.dbValue } ?: return@withTransaction null
		val categoryIds = dao.findCategories(groupId).mapTo(LinkedHashSet()) { it.categoryId }
		group.toDomain(dao.findMemberDisplays(groupId, space.dbValue), categoryIds).takeIf { it.members.size >= 2 }
	}

	suspend fun getTimeline(
		groupId: Long,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): List<LibraryGroupTimelineItem> = db.withTransaction {
		requireGroupLocked(groupId, space)
		dao.findTimeline(groupId).map { it.toDomain() }
	}

	suspend fun createGroup(
		title: String,
		mangaIds: Collection<Long>,
		coverUrl: String? = null,
		categoryIds: Collection<Long> = emptyList(),
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Long = db.withTransaction {
		val normalizedTitle = title.trim()
		require(normalizedTitle.isNotEmpty()) { "Group title cannot be empty" }
		val uniqueIds = LinkedHashSet(mangaIds).toList()
		require(uniqueIds.size >= 2) { "A library group needs at least two manga" }
		val normalizedCategoryIds = validateCategoryIdsLocked(categoryIds, space)

		val alreadyGrouped = dao.findMembersByMangaIds(uniqueIds, space.dbValue)
		require(alreadyGrouped.isEmpty()) { "A manga can belong to only one library group in this library space" }
		for (mangaId in uniqueIds) {
			require(isFavouriteLocked(mangaId, space)) {
				"Only manga currently in this library space can be grouped"
			}
			val manga = db.getMangaDao().find(mangaId)?.manga
			requireNotNull(manga) { "Manga $mangaId is missing from the library database" }
			require(!MangaSource(manga.source).isNovelSource) {
				"Novel entries are not supported by Advanced Library Groups yet"
			}
		}

		val groupId = dao.insertGroup(
			LibraryGroupEntity(
				title = normalizedTitle,
				coverUrl = coverUrl.normalizeOptionalText(),
				createdAt = System.currentTimeMillis(),
				space = space.dbValue,
			),
		)
		dao.insertMembers(
			uniqueIds.mapIndexed { index, mangaId ->
				LibraryGroupMemberEntity(
					groupId = groupId,
					mangaId = mangaId,
					position = index,
				)
			},
		)
		if (normalizedCategoryIds.isNotEmpty()) {
			dao.insertCategories(normalizedCategoryIds.map { LibraryGroupCategoryEntity(groupId, it) })
		}
		groupId
	}

	suspend fun updateGroup(
		groupId: Long,
		title: String,
		coverUrl: String?,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	) = db.withTransaction {
		requireGroupLocked(groupId, space)
		val normalizedTitle = title.trim()
		require(normalizedTitle.isNotEmpty()) { "Group title cannot be empty" }
		dao.updateGroup(groupId, normalizedTitle, coverUrl.normalizeOptionalText())
	}

	suspend fun replaceCategories(
		groupId: Long,
		categoryIds: Collection<Long>,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	) = db.withTransaction {
		requireGroupLocked(groupId, space)
		val normalized = validateCategoryIdsLocked(categoryIds, space)
		dao.deleteCategories(groupId)
		if (normalized.isNotEmpty()) {
			dao.insertCategories(normalized.map { LibraryGroupCategoryEntity(groupId, it) })
		}
	}

	suspend fun replaceTimeline(
		groupId: Long,
		orderedItems: List<LibraryGroupTimelineItem>,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	) = db.withTransaction {
		require(groupId != 0L) { "Missing library group id" }
		requireGroupLocked(groupId, space)
		val memberIds = dao.findMembers(groupId).mapTo(HashSet<Long>()) { it.mangaId }
		val uniqueKeys = HashSet<Pair<Long, Long>>()
		orderedItems.forEach { item ->
			require(item.mangaId in memberIds) { "Timeline chapter belongs to a manga outside this group" }
			require(uniqueKeys.add(item.mangaId to item.chapterId)) { "Timeline contains a duplicate chapter" }
		}
		dao.deleteTimeline(groupId)
		if (orderedItems.isNotEmpty()) {
			dao.insertTimeline(
				orderedItems.mapIndexed { index, item ->
					LibraryGroupTimelineItemEntity(
						groupId = groupId,
						mangaId = item.mangaId,
						chapterId = item.chapterId,
						position = index,
					)
				},
			)
		}
	}

	suspend fun removeMember(
		groupId: Long,
		mangaId: Long,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	) = db.withTransaction {
		requireGroupLocked(groupId, space)
		dao.deleteMember(groupId, mangaId)
		if (dao.countMembers(groupId) < 2) {
			dao.deleteGroup(groupId)
		} else {
			normalizePositionsLocked(groupId)
		}
	}

	suspend fun deleteGroup(
		groupId: Long,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	) = db.withTransaction {
		// Deleting a group only removes grouping rows. Manga, favourites, history, downloads and
		// metadata overrides are protected because the foreign key points from members to manga, never
		// the other way around.
		requireGroupLocked(groupId, space)
		dao.deleteGroup(groupId)
	}

	suspend fun reorder(
		groupId: Long,
		orderedMangaIds: List<Long>,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	) = db.withTransaction {
		requireGroupLocked(groupId, space)
		val members = dao.findMembers(groupId)
		val currentIds = members.map { it.mangaId }
		require(orderedMangaIds.size == currentIds.size && orderedMangaIds.toSet() == currentIds.toSet()) {
			"Reorder must contain every group member exactly once"
		}
		orderedMangaIds.forEachIndexed { index, mangaId ->
			dao.updateMemberPosition(groupId, mangaId, index)
		}
	}

	suspend fun repairInvalidGroups(space: FavouriteSpace = FavouriteSpace.NORMAL) = db.withTransaction {
		// Favourites use soft deletion, so a foreign key alone cannot remove a member or category link.
		// Repairs are scoped: removing a title from Normal must never dismantle its Private group, or vice versa.
		dao.deleteMembersNotInLibrary(space.dbValue)
		dao.deleteCategoriesNotInLibrary(space.dbValue)
		dao.deleteInvalidGroups(space.dbValue)
	}

	private suspend fun validateCategoryIdsLocked(
		categoryIds: Collection<Long>,
		space: FavouriteSpace,
	): List<Long> {
		val normalized = LinkedHashSet(categoryIds.filter { it > 0L }).toList()
		if (normalized.isEmpty()) return emptyList()
		val active = db.getFavouriteCategoriesDao()
			.findAllInSpace(space.dbValue)
			.mapTo(HashSet()) { it.categoryId.toLong() }
		require(normalized.all { it in active }) { "One or more library group categories are unavailable" }
		return normalized
	}

	private suspend fun isFavouriteLocked(mangaId: Long, space: FavouriteSpace): Boolean = when (space) {
		FavouriteSpace.NORMAL -> db.getFavouritesDao().findCategoriesCount(mangaId) > 0
		FavouriteSpace.PRIVATE -> db.getPrivateFavouritesDao().findCategoriesCount(mangaId) > 0
	}

	private suspend fun requireGroupLocked(groupId: Long, space: FavouriteSpace): LibraryGroupEntity {
		return requireNotNull(dao.findGroup(groupId)?.takeIf { it.space == space.dbValue }) {
			"Library group is no longer available in this library space"
		}
	}

	private suspend fun normalizePositionsLocked(groupId: Long) {
		dao.findMembers(groupId).forEachIndexed { index, member ->
			if (member.position != index) {
				dao.updateMemberPosition(groupId, member.mangaId, index)
			}
		}
	}

	private fun LibraryGroupEntity.toDomain(
		members: List<LibraryGroupMemberDisplay>,
		categoryIds: Set<Long>,
	) = LibraryGroup(
		id = groupId,
		title = title,
		coverUrl = coverUrl,
		members = members
			.sortedWith(compareBy<LibraryGroupMemberDisplay> { it.position }.thenBy { it.mangaId })
			.map { member ->
				LibraryGroupMember(
					mangaId = member.mangaId,
					position = member.position,
					displayTitle = member.displayTitle,
					displayCoverUrl = member.displayCoverUrl,
					isNsfw = member.isNsfw,
					contentRating = member.contentRating,
					source = member.source,
				)
			},
		createdAt = createdAt,
		categoryIds = categoryIds,
		space = FavouriteSpace.fromDb(space),
	)

	private fun LibraryGroupTimelineItemEntity.toDomain() = LibraryGroupTimelineItem(
		mangaId = mangaId,
		chapterId = chapterId,
		position = position,
	)

	private fun String?.normalizeOptionalText(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
