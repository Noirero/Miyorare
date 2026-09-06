package org.koitharu.kotatsu.details.ui

import android.content.Context
import android.content.Intent
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.dialog.DialogAction
import org.koitharu.kotatsu.core.ui.dialog.showActionChoiceDialog
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.favourites.ui.FavouritesActivity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.search.domain.SearchKind

internal enum class DetailsTextActionKind {
	TITLE,
	AUTHOR,
}

internal fun showDetailsTextActions(
	context: Context,
	router: AppRouter,
	settings: AppSettings,
	manga: Manga,
	text: String,
	kind: DetailsTextActionKind,
) {
	val query = text.trim()
	if (query.isEmpty()) return

	val source = manga.source
	val isNovel = manga.isNovelContent()
	val actions = buildList {
		add(
			DialogAction(
				context.getString(R.string.search_on_s, context.getString(R.string.all_favourites)),
			) {
				context.startActivity(
					Intent(context, FavouritesActivity::class.java)
						.putExtra(AppRouter.KEY_QUERY, query)
						.putExtra(FavouritesActivity.EXTRA_CONTEXT_SEARCH_NOVEL, isNovel),
				)
			},
		)

		// Local entries do not have a meaningful remote source search. Keep the other actions available.
		if (!source.isLocal) {
			add(
				DialogAction(context.getString(R.string.search_on_s, source.getTitle(context))) {
					when (kind) {
						DetailsTextActionKind.TITLE -> router.openSearch(source, query)
						DetailsTextActionKind.AUTHOR -> router.openList(
							source,
							MangaListFilter(author = query),
							null,
						)
					}
				},
			)
		}

		add(
			DialogAction(context.getString(R.string.search_everywhere)) {
				settings.isGlobalSearchNovelScope = isNovel
				router.openSearch(
					query,
					if (kind == DetailsTextActionKind.AUTHOR) SearchKind.AUTHOR else SearchKind.SIMPLE,
				)
			},
		)
		add(
			DialogAction(context.getString(androidx.preference.R.string.copy)) {
				context.copyToClipboard(
					context.getString(if (isNovel) R.string.content_type_novel else R.string.content_type_manga),
					query,
				)
			},
		)
	}

	showActionChoiceDialog(
		context = context,
		icon = if (kind == DetailsTextActionKind.AUTHOR) R.drawable.ic_user else R.drawable.ic_search,
		title = query,
		actions = actions,
		dismissLabel = context.getString(R.string.close),
	)
}

private fun Manga.isNovelContent(): Boolean {
	if (source.isNovelSource) return true
	if (!source.isLocal) return false
	val normalizedUrl = url.replace('\\', '/')
	return normalizedUrl.contains("/00.Novel/", ignoreCase = true) ||
		normalizedUrl.substringBefore('#').substringBefore('?').endsWith(".epub", ignoreCase = true)
}
