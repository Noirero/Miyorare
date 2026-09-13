package org.koitharu.kotatsu.core.nav

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter.Companion.KEY_ID
import org.koitharu.kotatsu.core.nav.AppRouter.Companion.KEY_MANGA
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.parsers.model.Manga

class MangaIntent private constructor(
	@JvmField val manga: Manga?,
	@JvmField val id: Long,
	@JvmField val uri: Uri?,
	/** Raw FavouriteSpace database value, or null for legacy/unscoped navigation. */
	@JvmField val favouriteSpace: Int?,
) {

	constructor(savedStateHandle: SavedStateHandle) : this(
		manga = savedStateHandle.get<ParcelableManga>(KEY_MANGA)?.manga,
		id = savedStateHandle[KEY_ID] ?: ID_NONE,
		uri = savedStateHandle[AppRouter.KEY_DATA],
		favouriteSpace = savedStateHandle[EXTRA_FAVOURITE_SPACE],
	)

	val mangaId: Long
		get() = if (id != ID_NONE) id else manga?.id ?: uri?.lastPathSegment?.toLongOrNull() ?: ID_NONE

	companion object {

		const val ID_NONE = 0L
	}
}
