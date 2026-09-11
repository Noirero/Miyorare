package org.koitharu.kotatsu.favourites.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.ui.FragmentContainerActivity
import org.koitharu.kotatsu.core.ui.MiyorareHeaderShapeDrawable
import org.koitharu.kotatsu.core.ui.MiyorarePrivateFavouritesHeaderDrawable
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.core.ui.privateFavouritesVisualSpecFromPreferences
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.domain.FavouriteHeaderScrollMode
import org.koitharu.kotatsu.favourites.domain.LibraryTimeMachine
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupsRepository
import org.koitharu.kotatsu.favourites.groups.ui.LibraryGroupDetailsFragment
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSession
import org.koitharu.kotatsu.main.ui.protect.ProtectActivity
import javax.inject.Inject

@AndroidEntryPoint
class FavouritesActivity : FragmentContainerActivity(FavouritesListFragment::class.java) {

	@Inject lateinit var contentTypeStore: FavouriteContentTypeStore
	@Inject lateinit var libraryGroupsRepository: LibraryGroupsRepository
	@Inject lateinit var privateSession: PrivateFavouritesSession
	@Inject lateinit var libraryTimeMachine: LibraryTimeMachine

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
			window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		}
		super.onCreate(savedInstanceState)

		// Fragments still using the compatibility selectedType facade now transparently read/write the
		// active library space. This switches the facade only; Normal and Private persisted choices stay
		// independent in FavouriteContentTypeStore.
		contentTypeStore.activateSpace(favouriteSpace)

		if (isPrivateMode) {
			configurePrivateAppBar()
		}

		if (isPrivateMode && !privateSession.isUnlocked.value) {
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
			// The active search Flow remains compatible with the existing list ViewModels, but its value
			// is swapped with a Private-only in-memory snapshot so entering the vault never destroys or
			// exposes the Normal search query.
			previousSearchQuery = FavouritesContainerFragment.searchQuery.value
			FavouritesContainerFragment.searchQuery.value = privateSearchQuery
			val privateType = if (intent.hasExtra(EXTRA_CONTEXT_SEARCH_NOVEL)) {
				if (intent.getBooleanExtra(EXTRA_CONTEXT_SEARCH_NOVEL, false)) {
					FavouriteContentType.NOVEL
				} else {
					FavouriteContentType.MANGA
				}
			} else {
				contentTypeStore.selectedType(FavouriteSpace.PRIVATE).value
			}
			contentTypeStore.setSelectedType(privateType, FavouriteSpace.PRIVATE)
			val requestedCategoryId = intent.getLongExtra(AppRouter.KEY_ID, NO_REQUESTED_CATEGORY)
			if (requestedCategoryId != NO_REQUESTED_CATEGORY) {
				contentTypeStore.setLastCategoryId(
					privateType,
					requestedCategoryId,
					FavouriteSpace.PRIVATE,
				)
			}
			title = ""
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

	private fun configurePrivateAppBar() {
		val collapsing = findViewById<View>(R.id.collapsingToolbarLayout) ?: return
		val typedValue = TypedValue()
		if (theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, typedValue, true)) {
			val params = collapsing.layoutParams
			params.height = TypedValue.complexToDimensionPixelSize(typedValue.data, resources.displayMetrics)
			if (params is AppBarLayout.LayoutParams) {
				params.scrollFlags = 0
			}
			collapsing.layoutParams = params
		}
		appBar.setExpanded(true, false)
		appBar.elevation = 0f
		applyPrivateAppBarChrome()
	}

	private fun applyFavouritesHeaderScrollMode() {
		if (isModernLibraryGroup) return
		val mode = FavouriteHeaderScrollMode.current(this)
		val pinned = mode == FavouriteHeaderScrollMode.PINNED
		findViewById<View>(R.id.collapsingToolbarLayout)?.let { collapsing ->
			(collapsing.layoutParams as? AppBarLayout.LayoutParams)?.let { params ->
				val flags = when {
					pinned -> 0
					isPrivateMode -> AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
					else -> AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
						AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED or
						AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
				}
				if (params.scrollFlags != flags) {
					params.scrollFlags = flags
					collapsing.layoutParams = params
				}
			}
		}
		findViewById<View>(R.id.layout_category_header)?.let { header ->
			(header.layoutParams as? AppBarLayout.LayoutParams)?.let { params ->
				val flags = if (pinned) {
					0
				} else {
					AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
						AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS or
						AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
				}
				if (params.scrollFlags != flags) {
					params.scrollFlags = flags
					header.layoutParams = params
				}
			}
		}
		if (pinned) {
			appBar.setExpanded(true, false)
		}
	}

	private fun ensureTimeMachineMenu() {
		if (isPrivateMode || isModernLibraryGroup) return
		val toolbar = findViewById<MaterialToolbar>(R.id.toolbar) ?: return
		if (toolbar.menu.findItem(TIME_MACHINE_MENU_ID) != null) return
		toolbar.menu.add(
			Menu.NONE,
			TIME_MACHINE_MENU_ID,
			Menu.NONE,
			R.string.library_time_machine_undo,
		).apply {
			setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
			setOnMenuItemClickListener {
				lifecycleScope.launch {
					val restored = withContext(Dispatchers.IO) { libraryTimeMachine.undoLatest() }
					Toast.makeText(
						this@FavouritesActivity,
						if (restored) R.string.library_time_machine_restored else R.string.library_time_machine_empty,
						Toast.LENGTH_SHORT,
					).show()
				}
				true
			}
		}
	}

	internal fun applyPrivateAppBarChrome() {
		if (!isPrivateMode) return
		val palette = miyorareViewPaletteFromPreferences(privateFavourites = true) ?: return
		val privateSpec = privateFavouritesVisualSpecFromPreferences()
		appBar.background = if (privateSpec != null) {
			MiyorarePrivateFavouritesHeaderDrawable(
				palette = palette,
				variant = MiyorareHeaderShapeDrawable.Variant.FAVOURITES_TOP,
				spec = privateSpec,
				density = resources.displayMetrics.density,
			)
		} else {
			MiyorareHeaderShapeDrawable(
				palette = palette,
				variant = MiyorareHeaderShapeDrawable.Variant.FAVOURITES_TOP,
				density = resources.displayMetrics.density,
			)
		}
		appBar.elevation = 0f

		findViewById<CollapsingToolbarLayout>(R.id.collapsingToolbarLayout)?.apply {
			setBackgroundColor(Color.TRANSPARENT)
			setContentScrimColor(Color.TRANSPARENT)
			setStatusBarScrimColor(Color.TRANSPARENT)
		}
		findViewById<MaterialToolbar>(R.id.toolbar)?.apply {
			setBackgroundColor(Color.TRANSPARENT)
			setTitleTextColor(palette.onSurface)
			navigationIcon?.setTint(palette.onSurface)
			overflowIcon?.setTint(palette.onSurfaceVariant)
		}
	}

	override fun onResume() {
		super.onResume()
		appBar.post(::applyFavouritesHeaderScrollMode)
		if (!isPrivateMode && !isModernLibraryGroup) {
			// Fragments may rebuild their own menu while resuming. Install after that UI turn and guard
			// by a stable id so the item never duplicates.
			findViewById<MaterialToolbar>(R.id.toolbar)?.post(::ensureTimeMachineMenu)
		}
		if (!isPrivateMode || isFinishing) return
		applyPrivateAppBarChrome()
		if (privateSession.isUnlocked.value) {
			privateReauthShowing = false
			window.decorView.visibility = View.VISIBLE
			return
		}

		window.decorView.visibility = View.INVISIBLE
		if (privateReauthShowing) {
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
		libraryGroupsRepository.observeGroups(favouriteSpace)
			.map { groups -> groups.firstOrNull { it.id == libraryGroupId }?.containsNsfw == true }
			.distinctUntilChanged()
	} else {
		super.isNsfwContent()
	}

	override fun onDestroy() {
		if (privateScopeActive) {
			privateSearchQuery = FavouritesContainerFragment.searchQuery.value
			FavouritesContainerFragment.searchQuery.value = previousSearchQuery
			contentTypeStore.activateSpace(FavouriteSpace.NORMAL)
		} else if (contextSearchActive) {
			FavouritesContainerFragment.searchQuery.value = previousSearchQuery
			contentTypeStore.setSelectedType(previousContentType, FavouriteSpace.NORMAL)
		}
		super.onDestroy()
	}

	companion object {
		const val EXTRA_CONTEXT_SEARCH_NOVEL = "context_search_novel"
		const val EXTRA_LIBRARY_GROUP_ID = "library_group_id"
		private const val NO_REQUESTED_CATEGORY = Long.MIN_VALUE
		private const val TIME_MACHINE_MENU_ID = 0x4D59544D
		private var privateSearchQuery: String = ""
	}
}
