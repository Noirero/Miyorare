package org.koitharu.kotatsu.favourites.data

/**
 * Logical library space. Manga/source/chapter/download records remain shared; only favourite
 * membership and categories are separated.
 */
enum class FavouriteSpace(val dbValue: Int) {
	NORMAL(0),
	PRIVATE(1),
	;

	companion object {
		fun fromDb(value: Int): FavouriteSpace = entries.firstOrNull { it.dbValue == value } ?: NORMAL
		fun fromArgument(value: Int): FavouriteSpace = fromDb(value)
	}
}

const val EXTRA_FAVOURITE_SPACE = "favourite_space"
