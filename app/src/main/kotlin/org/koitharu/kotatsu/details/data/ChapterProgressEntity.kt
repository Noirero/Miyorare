package org.koitharu.kotatsu.details.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import org.koitharu.kotatsu.core.db.entity.MangaEntity

/**
 * A visited chapter and its last reader position. Not part of the disposable chapter/page cache.
 * A missing row means no visit was recorded; manual read/unread remains owned by AppSettings.
 */
@Entity(
    tableName = "chapter_progress",
    primaryKeys = ["manga_id", "source", "url", "space"],
    foreignKeys = [ForeignKey(
        entity = MangaEntity::class,
        parentColumns = ["manga_id"],
        childColumns = ["manga_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ChapterProgressEntity(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "space", defaultValue = "0") val space: Int,
    @ColumnInfo(name = "page") val page: Int,
    @ColumnInfo(name = "scroll") val scroll: Int,
    @ColumnInfo(name = "total_pages") val totalPages: Int?,
) {
    fun key() = ChapterPersonalKey(source, url)
}
