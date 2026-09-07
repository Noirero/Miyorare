package org.koitharu.kotatsu.favourites.groups.domain

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupMemberDisplay
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupMemberEntity
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

data class LibraryGroup(
	val id: Long,
	val title: String,
	val coverUrl: String?,
	val members: List<LibraryGroupMember>,
	val createdAt: Long,
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

	fun observeGroups(): Flow<List<LibraryGroup>> = combine(
		dao.observeGroups(),
		dao.observeMemberDisplays(),
	) { groups, members ->
		val membersByGroup = members.groupBy { it.groupId }
		groups.mapNotNull { group ->
			val groupMembers = membersByGroup[group.groupId].orEmpty()
			group.toDomain(groupMembers).takeIf { it.members.size >= 2 }
		}
	}.distinctUntilChanged()

	suspend fun getGroup(groupId: Long): LibraryGroup? = db.withTransaction {
		val group = dao.findGroup(groupId) ?: return@withTransaction null
		group.toDomain(dao.findMemberDisplays(groupId)).takeIf { it.members.size >= 2 }
	}

	suspend fun createGroup(
		title: String,
		mangaIds: Collection<Long>,
		coverUrl: String? = null,
	): Long = db.withTransaction {
		val normalizedTitle = title.trim()
		require(normalizedTitle.isNotEmpty()) { "Group title cannot be empty" }
		val uniqueIds = LinkedHashSet(mangaIds).toList()
		require(uniqueIds.size >= 2) { "A library group needs at least two manga" }

		val alreadyGrouped = dao.findMembersByMangaIds(uniqueIds)
		require(alreadyGrouped.isEmpty()) { "A manga can belong to only one library group" }
		for (mangaId in uniqueIds) {
			require(db.getFavouritesDao().findCategoriesCount(mangaId) > 0) {
				"Only manga currently in the library can be grouped"
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
		groupId
	}

	suspend fun updateGroup(groupId: Long, title: String, coverUrl: String?) {
		val normalizedTitle = title.trim()
		require(normalizedTitle.isNotEmpty()) { "Group title cannot be empty" }
		dao.updateGroup(groupId, normalizedTitle, coverUrl.normalizeOptionalText())
	}

	suspend fun removeMember(groupId: Long, mangaId: Long) = db.withTransaction {
		dao.deleteMember(groupId, mangaId)
		if (dao.countMembers(groupId) < 2) {
			dao.deleteGroup(groupId)
		} else {
			normalizePositionsLocked(groupId)
		}
	}

	suspend fun deleteGroup(groupId: Long) {
		// Deleting a group only removes the grouping rows. Manga, favourites, history, downloads and
		// metadata overrides are protected because the foreign key points from members to manga, never
		// the other way around.
		dao.deleteGroup(groupId)
	}

	suspend fun reorder(groupId: Long, orderedMangaIds: List<Long>) = db.withTransaction {
		val members = dao.findMembers(groupId)
		val currentIds = members.map { it.mangaId }
		require(orderedMangaIds.size == currentIds.size && orderedMangaIds.toSet() == currentIds.toSet()) {
			"Reorder must contain every group member exactly once"
		}
		orderedMangaIds.forEachIndexed { index, mangaId ->
			dao.updateMemberPosition(groupId, mangaId, index)
		}
	}

	suspend fun repairInvalidGroups() = db.withTransaction {
		// Favourites use soft deletion, so a foreign key alone cannot remove a member that leaves the
		// library. Prune those virtual links first, then dissolve groups with fewer than two members.
		dao.deleteMembersNotInLibrary()
		dao.deleteInvalidGroups()
	}

	private suspend fun normalizePositionsLocked(groupId: Long) {
		dao.findMembers(groupId).forEachIndexed { index, member ->
			if (member.position != index) {
				dao.updateMemberPosition(groupId, member.mangaId, index)
			}
	}

	private fun LibraryGroupEntity.toDomain(members: List<LibraryGroupMemberDisplay>) = LibraryGroup(
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
	)

	private fun String?.normalizeOptionalText(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
