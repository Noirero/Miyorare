package org.koitharu.kotatsu.favourites.ui

import android.os.Bundle
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.ui.FragmentContainerActivity
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.groups.ui.LibraryGroupDetailsFragment
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment
import javax.inject.Inject

@AndroidEntryPoint
class FavouritesActivity : FragmentContainerActivity(FavouritesListFragment::class.java) {

	@Inject lateinit var contentTypeStore: FavouriteContentTypeStore

	private var contextSearchActive = false
	private var previousSearchQuery = ""
	private var previousContentType = FavouriteContentType.MANGA

	private val libraryGroupId: Long
		get() = intent.getLongExtra(EXTRA_LIBRARY_GROUP_ID, 0L)

	override fun getFragmentClass(): Class<out Fragment> =
		if (libraryGroupId != 0L && entryPoint.settings.miyorareDesignStyle == MiyorareDesignStyle.MODERN) {
			LibraryGroupDetailsFragment::class.java
		} else {
			super.getFragmentClass()
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		if (libraryGroupId != 0L && entryPoint.settings.miyorareDesignStyle == MiyorareDesignStyle.MODERN) {
			title = getString(R.string.library_group_details)
			return
		}

		val contextQuery = intent.getStringExtra(AppRouter.KEY_QUERY)?.trim().orEmpty()
		if (contextQuery.isNotEmpty()) {
			contextSearchActive = true
			previousSearchQuery = FavouritesContainerFragment.searchQuery.value
			previousContentType = contentTypeStore.selectedType.value
			FavouritesContainerFragment.searchQuery.value = contextQuery
			contentTypeStore.setSelectedType(
				if (intent.getBooleanExtra(EXTRA_CONTEXT_SEARCH_NOVEL, false)) {
					FavouriteContentType.NOVEL
				} else {
					FavouriteContentType.MANGA
				},
			)
			title = getString(R.string.all_favourites)
			return
		}

		val categoryTitle = intent.getStringExtra(AppRouter.KEY_TITLE)
		if (categoryTitle != null) {
			title = categoryTitle
		}
	}

	override fun onDestroy() {
		if (contextSearchActive) {
			FavouritesContainerFragment.searchQuery.value = previousSearchQuery
			contentTypeStore.setSelectedType(previousContentType)
		}
		super.onDestroy()
	}

	companion object {
		const val EXTRA_CONTEXT_SEARCH_NOVEL = "context_search_novel"
		const val EXTRA_LIBRARY_GROUP_ID = "library_group_id"
	}
}
