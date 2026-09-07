package org.koitharu.kotatsu.favourites.groups.domain

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupMemberEntity
import javax.inject.Inject

data class LibraryGroup(
	val id: Long,
	val title: String,
	val coverUrl: String?,
	val memberIds: List<Long>,
	val createdAt: Long,
)

@Reusable
class LibraryGroupsRepository @Inject constructor(
	private val db: MangaDatabase,
) {

	private val dao
		get() = db.getLibraryGroupsDao()

	fun observeGroups(): Flow<List<LibraryGroup>> = combine(
		dao.observeGroups(),
		dao.observeMembers(),
	) { groups, members ->
		val membersByGroup = members.groupBy { it.groupId }
		groups.map { group ->
			group.toDomain(
				membersByGroup[group.groupId]
					.orEmpty()
					.sortedWith(compareBy<LibraryGroupMemberEntity> { it.position }.thenBy { it.mangaId })
					.map { it.mangaId },
			)
		}
	}.distinctUntilChanged()

	suspend fun getGroup(groupId: Long): LibraryGroup? = db.withTransaction {
		val group = dao.findGroup(groupId) ?: return@withTransaction null
		val members = dao.findMembers(groupId).map { it.mangaId }
		group.toDomain(members)
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

	suspend fun repairInvalidGroups() {
		// A manga can disappear through normal database cleanup. Keep the virtual group layer healthy
		// without ever deleting the remaining manga itself.
		dao.deleteInvalidGroups()
	}

	private suspend fun normalizePositionsLocked(groupId: Long) {
		dao.findMembers(groupId).forEachIndexed { index, member ->
			if (member.position != index) {
				dao.updateMemberPosition(groupId, member.mangaId, index)
			}
		}
	}

	private fun LibraryGroupEntity.toDomain(memberIds: List<Long>) = LibraryGroup(
		id = groupId,
		title = title,
		coverUrl = coverUrl,
		memberIds = memberIds,
		createdAt = createdAt,
	)

	private fun String?.normalizeOptionalText(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
