package org.koitharu.kotatsu.favourites.data

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.MangaTagsEntity
import org.koitharu.kotatsu.core.db.entity.TagEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.parsers.model.Manga

class PrivateFavouriteManga(
	@Embedded val favourite: PrivateFavouriteEntity,
	@Relation(parentColumn = "manga_id", entityColumn = "manga_id")
	val manga: MangaEntity,
	@Relation(parentColumn = "category_id", entityColumn = "category_id")
	val categories: List<FavouriteCategoryEntity>,
	@Relation(
		parentColumn = "manga_id",
		entityColumn = "tag_id",
		associateBy = Junction(MangaTagsEntity::class),
	)
	val tags: List<TagEntity>,
)

fun PrivateFavouriteManga.toManga(): Manga = manga.toManga(tags.toMangaTags(), null)
fun Collection<PrivateFavouriteManga>.toPrivateMangaList(): List<Manga> = map { it.toManga() }
