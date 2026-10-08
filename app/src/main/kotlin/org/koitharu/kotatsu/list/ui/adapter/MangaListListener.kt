package org.koitharu.kotatsu.list.ui.adapter

import android.view.View
import org.koitharu.kotatsu.core.ui.widgets.TipView
import org.koitharu.kotatsu.local.library.LocalContentType

interface MangaListListener : MangaDetailsClickListener, ListStateHolderListener, ListHeaderClickListener,
	TipView.OnButtonClickListener, QuickFilterClickListener {

	fun onFilterClick(view: View?)

	fun onSmartLocalQueryChanged(query: String) = Unit

	fun onSmartLocalFilterClick(view: View) = Unit

	fun onSmartLocalTypeChanged(type: LocalContentType?) = Unit

	fun onSmartLocalSortClick(view: View) = Unit

	fun onSmartLocalListModeChanged(mode: org.koitharu.kotatsu.core.prefs.ListMode) = Unit

	fun onSmartLocalResume(manga: org.koitharu.kotatsu.parsers.model.Manga) = Unit

	fun onSmartLocalManageFoldersClick(view: View) = Unit
}
