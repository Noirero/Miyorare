package org.koitharu.kotatsu.favourites.groups.data

import androidx.room.Dao
import androidx.room.Query

@Dao
abstract class LibraryGroupMetadataDao {

	@Query(
		"UPDATE library_groups SET " +
			"title = :title, cover_url = :coverUrl, alternative_title = :alternativeTitle, " +
			"author = :author, artist = :artist, description = :description, " +
			"metadata_source = :metadataSource, metadata_target_id = :metadataTargetId " +
			"WHERE group_id = :groupId AND space = :space",
	)
	abstract suspend fun update(
		groupId: Long,
		space: Int,
		title: String,
		coverUrl: String?,
		alternativeTitle: String?,
		author: String?,
		artist: String?,
		description: String?,
		metadataSource: Int?,
		metadataTargetId: Long?,
	): Int
}
