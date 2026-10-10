package org.koitharu.kotatsu.backup.local.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.details.data.ChapterPersonalEntity
import org.koitharu.kotatsu.details.data.ChapterPersonalMetadata

@Serializable
data class ChapterPersonalBackup(
	@SerialName("source") val source: String,
	@SerialName("url") val url: String,
	@SerialName("rating") val rating: Int? = null,
	@SerialName("note") val note: String? = null,
) {
	constructor(entity: ChapterPersonalEntity) : this(entity.source, entity.url, entity.rating, entity.note)

	fun toEntity(mangaId: Long): ChapterPersonalEntity {
		require(source.isNotBlank() && url.isNotBlank())
		val metadata = ChapterPersonalMetadata.normalized(rating, note)
		require(!metadata.isEmpty) { "Empty chapter annotation in backup" }
		return ChapterPersonalEntity(mangaId, source, url, metadata.rating, metadata.note)
	}
}
