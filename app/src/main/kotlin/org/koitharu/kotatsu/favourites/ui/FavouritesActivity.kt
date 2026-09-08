package org.koitharu.kotatsu.favourites.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.ui.FragmentContainerActivity
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupsRepository
import org.koitharu.kotatsu.favourites.groups.ui.LibraryGroupDetailsFragment
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSession
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment
import org.koitharu.kotatsu.main.ui.protect.ProtectActivity
import javax.inject.Inject

@AndroidEntryPoint
class FavouritesActivity : FragmentContainerActivity(FavouritesListFragment::class.java) {

	@Inject lateinit var contentTypeStore: FavouriteContentTypeStore
	@Inject lateinit var libraryGroupsRepository: LibraryGroupsRepository
	@Inject lateinit var privateSession: PrivateFavouritesSession

	private var contextSearchActive = false
	private var privateScopeActive = false
	private var privateReauthShowing = false
	private var previousSearchQuery = ""
	private var previousContentType = FavouriteContentType.MANGA

	private val libraryGroupId: Long
		get() = intent.getLongExtra(EXTRA_LIBRARY_GROUP_ID, 0L)

	private val favouriteSpace: FavouriteSpace
		get() = FavouriteSpace.fromArgument(intent.getIntExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.NORMAL.dbValue))

	private val isPrivateMode: Boolean
		get() = favouriteSpace == FavouriteSpace.PRIVATE

	private val isModernLibraryGroup: Boolean
		get() = libraryGroupId != 0L && entryPoint.settings.miyorareDesignStyle == MiyorareDesignStyle.MODERN

	override fun getFragmentClass(): Class<out Fragment> = when {
		isModernLibraryGroup -> LibraryGroupDetailsFragment::class.java
		isPrivateMode -> PrivateWorkspaceFragment::class.java
		else -> super.getFragmentClass()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		if (isPrivateMode) {
			// Never allow task previews or transient activity frames to expose the private library.
			window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		}
		super.onCreate(savedInstanceState)

		if (isPrivateMode && !privateSession.isUnlocked.value) {
			// Keep the vault visually hidden as well as FLAG_SECURE until authentication owns the screen.
			window.decorView.visibility = View.INVISIBLE
			startActivity(
				Intent(this, ProtectActivity::class.java)
					.putExtra(ProtectActivity.EXTRA_PRIVATE_FAVOURITES, true),
			)
			finish()
			return
		}

		if (isModernLibraryGroup) {
			title = getString(R.string.library_group_details)
			return
		}

		if (isPrivateMode) {
			privateScopeActive = true
			previousSearchQuery = FavouritesContainerFragment.searchQuery.value
			previousContentType = contentTypeStore.selectedType.value
			// The Private activity is exclusive while visible, so reusing the existing search flow is safe
			// as long as Normal state is restored on exit. This keeps the existing UI code unchanged while
			// preventing a Normal query from becoming visible inside Private (or vice versa).
			FavouritesContainerFragment.searchQuery.value = ""
			contentTypeStore.setSelectedType(FavouriteContentType.MANGA)
			title = getString(R.string.private_favourites)
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

	override fun onResume() {
		super.onResume()
		if (!isPrivateMode || isFinishing) return
		if (privateSession.isUnlocked.value) {
			privateReauthShowing = false
			window.decorView.visibility = View.VISIBLE
			return
		}

		// ProcessLifecycleOwner locks the vault while the app is in background. Hide the old content
		// before the next frame and authenticate on top of this instance so its Normal-state snapshot
		// can still be restored when the Private activity eventually closes.
		window.decorView.visibility = View.INVISIBLE
		if (privateReauthShowing) {
			// Returning while still locked means authentication was cancelled/failed or its activity was
			// interrupted. Never fall back to the already-inflated Private UI in that state.
			privateReauthShowing = false
			finish()
			return
		}
		privateReauthShowing = true
		startActivity(
			Intent(this, ProtectActivity::class.java)
				.putExtra(ProtectActivity.EXTRA_PRIVATE_FAVOURITES, true)
				.putExtra(ProtectActivity.EXTRA_OPEN_PRIVATE_ON_SUCCESS, false),
		)
	}

	override fun isNsfwContent(): Flow<Boolean> = if (isModernLibraryGroup) {
		libraryGroupsRepository.observeGroups()
			.map { groups -> groups.firstOrNull { it.id == libraryGroupId }?.containsNsfw == true }
			.distinctUntilChanged()
	} else {
		super.isNsfwContent()
	}

	override fun onDestroy() {
		if (contextSearchActive || privateScopeActive) {
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
