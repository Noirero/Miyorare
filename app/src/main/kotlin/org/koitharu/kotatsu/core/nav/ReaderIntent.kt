package org.koitharu.kotatsu.core.nav

import android.content.Context
import android.content.Intent
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.reader.ui.ReaderActivity
import org.koitharu.kotatsu.reader.ui.ReaderState

@JvmInline
value class ReaderIntent private constructor(
	val intent: Intent,
) {

	class Builder(context: Context) {

		private val intent = Intent(context, ReaderActivity::class.java)
			.setAction(ACTION_MANGA_READ)

		fun manga(manga: Manga) = apply {
			intent.putExtra(AppRouter.KEY_MANGA, ParcelableManga(manga))
			intent.setData(AppRouter.shortMangaUrl(manga.id))
		}

		fun mangaId(mangaId: Long) = apply {
			intent.putExtra(AppRouter.KEY_ID, mangaId)
			intent.setData(AppRouter.shortMangaUrl(mangaId))
		}

		fun incognito() = incognito(true)

		fun incognito(value: Boolean) = apply {
			intent.putExtra(EXTRA_INCOGNITO, value)
		}

		fun peek() = apply {
			intent.putExtra(EXTRA_PEEK, true)
		}

		fun branch(branch: String?) = apply {
			intent.putExtra(EXTRA_BRANCH, branch)
		}

		fun state(state: ReaderState?) = apply {
			intent.putExtra(EXTRA_STATE, state)
		}

		/**
		 * Carries Advanced Library Group context without changing the manga/chapter identity.
		 * [space] is the FavouriteSpace database value when known; keeping it as an Int avoids a
		 * dependency from core navigation back into the favourites feature package.
		 */
		fun libraryGroup(groupId: Long, space: Int? = null) = apply {
			require(groupId != 0L) { "Library group id cannot be zero" }
			intent.putExtra(EXTRA_LIBRARY_GROUP_ID, groupId)
			if (space != null) intent.putExtra(EXTRA_LIBRARY_GROUP_SPACE, space)
		}

		fun bookmark(bookmark: Bookmark) = manga(
			bookmark.manga,
		).state(
			ReaderState(
				chapterId = bookmark.chapterId,
				page = bookmark.page,
				scroll = bookmark.scroll,
			),
		)

		fun build() = ReaderIntent(intent)
	}

	companion object {
		const val ACTION_MANGA_READ = "${BuildConfig.APPLICATION_ID}.action.READ_MANGA"
		const val EXTRA_STATE = "state"
		const val EXTRA_BRANCH = "branch"
		const val EXTRA_INCOGNITO = "incognito"
		const val EXTRA_PEEK = "peek"
		const val EXTRA_LIBRARY_GROUP_ID = "library_group_id"
		const val EXTRA_LIBRARY_GROUP_SPACE = "library_group_space"
	}
}
