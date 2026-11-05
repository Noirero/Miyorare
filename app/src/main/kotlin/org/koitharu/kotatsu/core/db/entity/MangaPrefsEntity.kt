package org.koitharu.kotatsu.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import org.koitharu.kotatsu.core.db.TABLE_PREFERENCES

@Entity(
	tableName = TABLE_PREFERENCES,
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
data class MangaPrefsEntity(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "manga_id")
	val mangaId: Long,
	@ColumnInfo(name = "mode") val mode: Int,
	@ColumnInfo(name = "cf_brightness") val cfBrightness: Float,
	@ColumnInfo(name = "cf_contrast") val cfContrast: Float,
	@ColumnInfo(name = "cf_invert") val cfInvert: Boolean,
	@ColumnInfo(name = "cf_grayscale") val cfGrayscale: Boolean,
	@ColumnInfo(name = "cf_book") val cfBookEffect: Boolean,
	@ColumnInfo(name = "title_override") val titleOverride: String?,
	@ColumnInfo(name = "cover_override") val coverUrlOverride: String?,
	@ColumnInfo(name = "content_rating_override") val contentRatingOverride: String?,
	@ColumnInfo(name = "author_override") val authorOverride: String? = null,
	@ColumnInfo(name = "artist_override") val artistOverride: String? = null,
	@ColumnInfo(name = "description_override") val descriptionOverride: String? = null,
	@ColumnInfo(name = "merge_scanlators", defaultValue = "0") val mergeScanlators: Boolean = false,
    @ColumnInfo(name = "cf_custom_enabled", defaultValue = "0") val cfCustomEnabled: Boolean = false,
    @ColumnInfo(name = "cf_custom_red", defaultValue = "70") val cfCustomRed: Int = 70,
    @ColumnInfo(name = "cf_custom_green", defaultValue = "0") val cfCustomGreen: Int = 0,
    @ColumnInfo(name = "cf_custom_blue", defaultValue = "171") val cfCustomBlue: Int = 171,
    @ColumnInfo(name = "cf_custom_alpha", defaultValue = "115") val cfCustomAlpha: Int = 115,
    @ColumnInfo(name = "cf_custom_mode", defaultValue = "'OVERLAY'") val cfCustomMode: String = "OVERLAY",
)
