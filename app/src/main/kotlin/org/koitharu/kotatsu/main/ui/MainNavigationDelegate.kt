package org.koitharu.kotatsu.main.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.core.view.iterator
import androidx.core.view.size
import androidx.core.view.updateLayoutParams
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.koitharu.kotatsu.core.diagnostics.PerformanceDiagnostics
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.ui.AllBookmarksFragment
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.core.ui.widgets.DragNavigationRailView
import org.koitharu.kotatsu.core.ui.widgets.FloatingBottomNavigationView
import org.koitharu.kotatsu.core.ui.widgets.SlidingBottomNavigationView
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.buildBundle
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.core.util.ext.setContentDescriptionAndTooltip
import org.koitharu.kotatsu.core.util.ext.smoothScrollToTop
import org.koitharu.kotatsu.databinding.NavigationRailFabBinding
import org.koitharu.kotatsu.explore.ui.ExploreFragment
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.history.ui.HistoryListFragment
import org.koitharu.kotatsu.local.ui.LocalListFragment
import org.koitharu.kotatsu.main.ui.protect.ProtectActivity
import org.koitharu.kotatsu.suggestions.ui.SuggestionsFragment
import org.koitharu.kotatsu.stats.ui.ReaderJourneyFragment
import org.koitharu.kotatsu.discovery.ui.DiscoveryHubFragment
import org.koitharu.kotatsu.tracker.ui.feed.FeedFragment
import org.koitharu.kotatsu.tracker.ui.updates.UpdatesFragment
import java.util.LinkedList
import com.google.android.material.R as materialR

class MainNavigationDelegate(
	private val navBar: NavigationBarView,
	private val fragmentManager: FragmentManager,
	private val settings: AppSettings,
) : OnBackPressedCallback(false),
	NavigationBarView.OnItemSelectedListener,
	NavigationBarView.OnItemReselectedListener, View.OnClickListener {

	private val listeners = LinkedList<OnFragmentChangedListener>()
	private var tabPreview: TabPreview? = null
	private var committingTabDrag = false
	val navRailHeader = (navBar as? NavigationRailView)?.headerView?.let {
		NavigationRailFabBinding.bind(it)
	}

	var onExploreReselected: (() -> Unit)? = null

	val primaryFragment: Fragment?
		get() = fragmentManager.fragments.lastOrNull {
			it.isAdded && !it.isHidden && getItemId(it) != 0
		}

	init {
		navBar.setOnItemSelectedListener(this)
		navBar.setOnItemReselectedListener(this)
		navRailHeader?.run {
			root.updateLayoutParams<FrameLayout.LayoutParams> {
				gravity = Gravity.TOP or Gravity.CENTER
			}
			val horizontalPadding = (navBar as NavigationRailView).itemActiveIndicatorMarginHorizontal
			root.setPadding(horizontalPadding, 0, horizontalPadding, 0)
			buttonExpand.setOnClickListener(this@MainNavigationDelegate)
			buttonExpand.setContentDescriptionAndTooltip(R.string.expand)
			railFab.isExtended = false
			railFab.isAnimationEnabled = false
		}
	}

	override fun onNavigationItemSelected(item: MenuItem): Boolean {
		return if (onNavigationItemSelected(item.itemId)) {
			item.isChecked = true
			navBar.hapticFeedback(HapticEffect.CONFIRM)
			true
		} else {
			false
		}
	}

	override fun onNavigationItemReselected(item: MenuItem) {
		if (item.itemId == R.id.nav_explore) {
			onExploreReselected?.invoke()
		}
		onNavigationItemReselected()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_expand -> {
				if (navBar is NavigationRailView) {
					setNavbarIsExpanded(!navBar.isExpanded)
				}
			}
		}
	}

	override fun handleOnBackPressed() {
		navBar.selectedItemId = firstItem()?.itemId ?: return
	}

	fun onCreate(lifecycleOwner: LifecycleOwner, savedInstanceState: Bundle?) {
		if (navBar.menu.isEmpty()) {
			createMenu(settings.mainNavItems, navBar.menu)
		}
		(navBar as? FloatingBottomNavigationView)?.let { floating ->
			floating.setTabDragListeners(::previewTab, ::commitTabDrag)
			floating.setComposeItems(settings.mainNavItems)
			floating.setComposeLabeled(settings.isNavLabelsVisible)
			floating.setUseLegacyNavigation(settings.isLegacyNavigationBar)
			floating.setOnItemLongClickListener { itemId ->
				if (itemId == R.id.nav_favorites) openPrivateFavourites()
			}
		}
		(navBar as? DragNavigationRailView)?.setTabDragListeners(::previewTab, ::commitTabDrag)
		installNativePrivateLongClick()
		observeSettings(lifecycleOwner)
		val fragment = primaryFragment
		if (fragment != null && navBar.menu.findItem(getItemId(fragment))?.isVisible == true) {
			normalizeFragmentLifecycles(fragment)
			onFragmentChanged(fragment, fromUser = false)
			val itemId = getItemId(fragment)
			if (navBar.selectedItemId != itemId) {
				navBar.selectedItemId = itemId
			}
		} else {
			val restoredId = if (savedInstanceState == null) {
				firstItem()?.itemId ?: navBar.selectedItemId
			} else {
				navBar.selectedItemId
			}
			val itemId = restoredId.takeIf { navBar.menu.findItem(it)?.isVisible == true } ?: firstItem()?.itemId ?: return
			onNavigationItemSelected(itemId)
		}
	}

	fun observeTitle() = callbackFlow {
		val listener = OnFragmentChangedListener { f, _ ->
			trySendBlocking(getItemId(f))
		}
		addOnFragmentChangedListener(listener)
		awaitClose { removeOnFragmentChangedListener(listener) }
	}.map {
		navBar.menu.findItem(it)?.title
	}

	fun setCounter(item: NavItem, counter: Int) {
		setCounter(item.id, counter)
	}

	/** Open Normal Favourites even when the owner has hidden its bottom-nav shortcut. */
	fun showNormalFavourites() {
		val item = navBar.menu.findItem(R.id.nav_favorites)
		if (item?.isVisible == true) {
			navBar.selectedItemId = R.id.nav_favorites
		} else {
			setPrimaryFragment(FavouritesContainerFragment::class.java)
		}
	}

	fun syncSelectedItem() {
		val fragment = primaryFragment ?: return
		onFragmentChanged(fragment, fromUser = false)
		val itemId = getItemId(fragment)
		if (navBar.selectedItemId != itemId) {
			navBar.selectedItemId = itemId
		}
	}

	private fun setCounter(@IdRes id: Int, counter: Int) {
		if (counter == 0) {
			navBar.getBadge(id)?.isVisible = false
		} else {
			val badge = navBar.getOrCreateBadge(id)
			if (counter < 0) {
				badge.clearNumber()
			} else {
				badge.number = counter
			}
			badge.isVisible = true
		}
		(navBar as? FloatingBottomNavigationView)?.setComposeBadge(id, counter)
	}

	fun setItemVisibility(@IdRes itemId: Int, isVisible: Boolean) {
		val item = navBar.menu.findItem(itemId) ?: return
		if (item.isVisible != isVisible) (navBar as? DragNavigationRailView)?.cancelTabDrag()
		item.isVisible = isVisible
		(navBar as? FloatingBottomNavigationView)?.setComposeItemVisibility(itemId, isVisible)
		if (item.isChecked && !isVisible) {
			navBar.selectedItemId = firstItem()?.itemId ?: return
		}
	}

	fun addOnFragmentChangedListener(listener: OnFragmentChangedListener) {
		listeners.add(listener)
	}

	fun removeOnFragmentChangedListener(listener: OnFragmentChangedListener) {
		listeners.remove(listener)
	}

	private fun openPrivateFavourites() {
		navBar.hapticFeedback(HapticEffect.CONFIRM)
		navBar.context.startActivity(
			Intent(navBar.context, ProtectActivity::class.java)
				.putExtra(ProtectActivity.EXTRA_PRIVATE_FAVOURITES, true),
		)
	}

	private fun installNativePrivateLongClick() {
		navBar.post {
			navBar.findViewById<View>(R.id.nav_favorites)?.setOnLongClickListener {
				openPrivateFavourites()
				true
			}
		}
	}

	private fun onNavigationItemSelected(@IdRes itemId: Int): Boolean {
		val newFragment = fragmentClassForItem(itemId) ?: return false
		if (!setPrimaryFragment(newFragment)) {
			onNavigationItemReselected()
		}
		return true
	}

	private fun fragmentClassForItem(@IdRes itemId: Int): Class<out Fragment>? = when (itemId) {
		R.id.nav_history -> HistoryListFragment::class.java
		R.id.nav_favorites -> FavouritesContainerFragment::class.java
		R.id.nav_explore -> ExploreFragment::class.java
		R.id.nav_feed -> FeedFragment::class.java
		R.id.nav_local -> LocalListFragment::class.java
		R.id.nav_suggestions -> SuggestionsFragment::class.java
		R.id.nav_bookmarks -> AllBookmarksFragment::class.java
		R.id.nav_updated -> UpdatesFragment::class.java
		R.id.nav_reader_journey -> ReaderJourneyFragment::class.java
		R.id.nav_discovery_hub -> DiscoveryHubFragment::class.java
		else -> null
	}

	private fun getItemId(fragment: Fragment) = when (fragment) {
		is HistoryListFragment -> R.id.nav_history
		is FavouritesContainerFragment -> R.id.nav_favorites
		is ExploreFragment -> R.id.nav_explore
		is FeedFragment -> R.id.nav_feed
		is LocalListFragment -> R.id.nav_local
		is SuggestionsFragment -> R.id.nav_suggestions
		is AllBookmarksFragment -> R.id.nav_bookmarks
		is UpdatesFragment -> R.id.nav_updated
		is ReaderJourneyFragment -> R.id.nav_reader_journey
		is DiscoveryHubFragment -> R.id.nav_discovery_hub
		else -> 0
	}

	internal fun cancelTabDrag() {
		(navBar as? FloatingBottomNavigationView)?.cancelTabDrag()
		(navBar as? DragNavigationRailView)?.cancelTabDrag()
		clearTabPreview()
	}

	private fun clearTabPreview(keepFavouritesWork: Boolean = false) {
		val preview = tabPreview ?: return
		tabPreview = null
		if (!keepFavouritesWork) preview.favourites?.setTabPreviewVisible(false)
		preview.target.visibility = preview.targetVisibility
		preview.current.visibility = preview.currentVisibility
		preview.externalHeader?.let { (view, visibility) -> view.visibility = visibility }
	}

	private fun previewTab(@IdRes itemId: Int?): Boolean {
		clearTabPreview()
		if (itemId == null) return true
		if (fragmentManager.isStateSaved || navBar.menu.findItem(itemId)?.isVisible != true) return false
		val current = primaryFragment ?: return false
		if (getItemId(current) == itemId) return true
		val currentView = current.view ?: return false
		val type = fragmentClassForItem(itemId) ?: return false
		val target = fragmentManager.findFragmentByTag(type.name) ?: newBottomTab(type).also { fragment ->
			// First visit can load content, but doesn't resume the tab or hand off shared chrome.
			fragmentManager.beginTransaction()
				.setReorderingAllowed(true)
				.add(R.id.container, fragment, type.name)
				.hide(fragment)
				.setMaxLifecycle(fragment, Lifecycle.State.STARTED)
				.commitNow()
		}
		if (!target.isHidden) return false
		val targetView = target.view ?: return false
		// Fragment.show/hide dispatches search/app bar/account refresh hooks. Change
		// only root view visibility during preview; preserve the committed lifecycle.
		// Favorites reparents its category header to the activity app bar. Hide it
		// for another tab's preview without changing app-bar ownership or layout.
		val externalHeader = if (current is FavouritesContainerFragment) {
			(navBar.context.findActivity() as? AppBarOwner)?.appBar
				?.findViewById<View>(R.id.layout_category_header)
		} else null
		tabPreview = TabPreview(
			currentView, currentView.visibility, targetView, targetView.visibility,
			externalHeader?.let { it to it.visibility },
			target as? FavouritesContainerFragment,
		)
		externalHeader?.visibility = View.INVISIBLE
		currentView.visibility = View.INVISIBLE
		targetView.visibility = View.VISIBLE
		(target as? FavouritesContainerFragment)?.setTabPreviewVisible(true)
		return true
	}

	private fun commitTabDrag(@IdRes itemId: Int) {
		if (fragmentManager.isStateSaved || primaryFragment?.let(::getItemId) == itemId ||
			navBar.menu.findItem(itemId)?.isVisible != true
		) return
		// Transfer the already-visible Favorites producer to RESUMED without
		// cancelling it in between. A different release target gets no handoff.
		val favourites = tabPreview?.favourites.takeIf { itemId == R.id.nav_favorites }
		clearTabPreview(keepFavouritesWork = favourites != null)
		committingTabDrag = true
		try {
			navBar.selectedItemId = itemId
		} finally {
			committingTabDrag = false
			favourites?.setTabPreviewVisible(false)
		}
	}

	private data class TabPreview(
		val current: View,
		val currentVisibility: Int,
		val target: View,
		val targetVisibility: Int,
		val externalHeader: Pair<View, Int>?,
		val favourites: FavouritesContainerFragment?,
	)

	private fun newBottomTab(type: Class<out Fragment>): Fragment = instantiateFragment(type).apply {
		arguments = buildBundle(1) { putBoolean(AppRouter.KEY_IS_BOTTOMTAB, true) }
	}

	private fun setPrimaryFragment(fragmentClass: Class<out Fragment>): Boolean {
		cancelTabDrag()
		if (fragmentManager.isStateSaved || fragmentClass.isInstance(primaryFragment)) {
			return false
		}
		val tag = fragmentClass.name
		val current = primaryFragment
		val trace = PerformanceDiagnostics.navigation(current?.javaClass?.simpleName ?: "none", fragmentClass.simpleName)
		val transaction = fragmentManager.beginTransaction()
			.setReorderingAllowed(true)
		val involvesFavourites = fragmentClass == FavouritesContainerFragment::class.java ||
			current is FavouritesContainerFragment
		if (!involvesFavourites && !committingTabDrag) {
			transaction.setCustomAnimations(
				R.anim.m3_fade_through_enter,
				R.anim.m3_fade_through_exit,
				R.anim.m3_fade_through_pop_enter,
				R.anim.m3_fade_through_pop_exit,
			)
		}
		if (current != null) {
			transaction.hide(current)
			transaction.setMaxLifecycle(current, Lifecycle.State.STARTED)
		}
		val existing = fragmentManager.findFragmentByTag(tag)
		val shownFragment: Fragment
		if (existing != null) {
			shownFragment = existing
			transaction.show(existing)
		} else {
			shownFragment = newBottomTab(fragmentClass)
			transaction.add(R.id.container, shownFragment, tag)
		}
		PerformanceDiagnostics.bind(shownFragment, trace)
		if (trace != null) navBar.context.findActivity()?.let { PerformanceDiagnostics.bind(it, trace) }
		transaction.setMaxLifecycle(shownFragment, Lifecycle.State.RESUMED)
		transaction.runOnCommit {
			PerformanceDiagnostics.activate(trace)
			PerformanceDiagnostics.milestone(trace, "transaction_committed")
			shownFragment.view?.let { PerformanceDiagnostics.drawn(shownFragment, it) }
			val shown = primaryFragment ?: shownFragment
			onFragmentChanged(shown, fromUser = true)
		}
		if (committingTabDrag) transaction.commitNow() else transaction.commit()
		return true
	}

	private fun normalizeFragmentLifecycles(active: Fragment) {
		if (fragmentManager.isStateSaved) {
			return
		}
		val transaction = fragmentManager.beginTransaction().setReorderingAllowed(true)
		var hasChanges = false
		for (f in fragmentManager.fragments) {
			if (getItemId(f) == 0) {
				continue
			}
			transaction.setMaxLifecycle(
				f,
				if (f === active) Lifecycle.State.RESUMED else Lifecycle.State.STARTED,
			)
			hasChanges = true
		}
		if (hasChanges) {
			transaction.commitNow()
		}
	}

	private fun onNavigationItemReselected() {
		val fragment = primaryFragment
		if (fragment is DiscoveryHubFragment) {
			fragment.scrollToTop()
			return
		}
		val recyclerView = (fragment as? RecyclerViewOwner)?.recyclerView ?: run {
			(fragment?.view as? NestedScrollView)?.smoothScrollTo(0, 0)
			return
		}
		recyclerView.smoothScrollToTop()
	}

	private fun onFragmentChanged(fragment: Fragment, fromUser: Boolean) {
		isEnabled = getItemId(fragment) != firstItem()?.itemId
		listeners.forEach { it.onFragmentChanged(fragment, fromUser) }
	}

	private fun createMenu(items: List<NavItem>, menu: Menu) {
		for (item in items) {
			menu.add(Menu.NONE, item.id, Menu.NONE, item.title)
				.setIcon(item.icon)
			if (menu.size >= navBar.maxItemCount) {
				break
			}
		}
	}

	private fun instantiateFragment(fragmentClass: Class<out Fragment>): Fragment {
		val classLoader = navBar.context.classLoader
		return fragmentManager.fragmentFactory.instantiate(classLoader, fragmentClass.name)
	}

	private fun observeSettings(lifecycleOwner: LifecycleOwner) {
		settings.observe(
			AppSettings.KEY_TRACKER_ENABLED,
			AppSettings.KEY_SUGGESTIONS,
			AppSettings.KEY_NAV_LABELS,
			AppSettings.KEY_NAV_LEGACY,
		)
			.onEach {
				val items = settings.mainNavItems
				if ((0 until navBar.menu.size).map { navBar.menu.getItem(it).itemId } != items.map { item -> item.id }) {
					val selected = getItemId(primaryFragment ?: return@onEach)
					cancelTabDrag()
					navBar.menu.clear()
					createMenu(items, navBar.menu)
					(navBar as? FloatingBottomNavigationView)?.setComposeItems(items)
					installNativePrivateLongClick()
					if (items.any { item -> item.id == selected }) {
						// Restore the checked item without treating a settings update as a reselection.
						navBar.setOnItemSelectedListener(null)
						navBar.setOnItemReselectedListener(null)
						navBar.selectedItemId = selected
						navBar.setOnItemSelectedListener(this@MainNavigationDelegate)
						navBar.setOnItemReselectedListener(this@MainNavigationDelegate)
					} else navBar.selectedItemId = items.first().id
				}
				setNavbarIsLabeled(settings.isNavLabelsVisible)
				(navBar as? FloatingBottomNavigationView)?.setUseLegacyNavigation(settings.isLegacyNavigationBar)
			}.launchIn(lifecycleOwner.lifecycleScope)
	}

	private fun firstItem(): MenuItem? {
		val menu = navBar.menu
		for (item in menu) {
			if (item.isVisible) return item
		}
		return null
	}

	private fun setNavbarIsLabeled(value: Boolean) {
		if (navBar is SlidingBottomNavigationView) {
			navBar.minimumHeight = navBar.resources.getDimensionPixelSize(
				if (value) {
					materialR.dimen.m3_bottom_nav_min_height
				} else {
					R.dimen.nav_bar_height_compact
				},
			)
		}
		(navBar as? FloatingBottomNavigationView)?.setComposeLabeled(value)
		navRailHeader?.buttonExpand?.isVisible = value
		if (!value) {
			setNavbarIsExpanded(false)
		}
		navBar.labelVisibilityMode = if (value) {
			NavigationBarView.LABEL_VISIBILITY_LABELED
		} else {
			NavigationBarView.LABEL_VISIBILITY_UNLABELED
		}
	}

	private fun setNavbarIsExpanded(value: Boolean) {
		if (navBar !is NavigationRailView) {
			return
		}
		(navBar as? DragNavigationRailView)?.cancelTabDrag()
		if (value) {
			navBar.expand()
			navRailHeader?.run {
				root.updateLayoutParams<FrameLayout.LayoutParams> {
					gravity = Gravity.TOP or Gravity.START
				}
				railFab.extend()
				buttonExpand.setImageResource(R.drawable.ic_drawer_menu_open)
				buttonExpand.setContentDescriptionAndTooltip(R.string.collapse)
				val horizontalPadding = navBar.itemActiveIndicatorExpandedMarginHorizontal
				root.setPadding(horizontalPadding, 0, horizontalPadding, 0)
			}
		} else {
			navBar.collapse()
			navRailHeader?.run {
				root.updateLayoutParams<FrameLayout.LayoutParams> {
					gravity = Gravity.TOP or Gravity.CENTER
				}
				railFab.shrink()
				buttonExpand.setImageResource(R.drawable.ic_drawer_menu)
				buttonExpand.setContentDescriptionAndTooltip(R.string.expand)
				val horizontalPadding = navBar.itemActiveIndicatorMarginHorizontal
				root.setPadding(horizontalPadding, 0, horizontalPadding, 0)
			}
		}
	}

	fun interface OnFragmentChangedListener {
		fun onFragmentChanged(fragment: Fragment, fromUser: Boolean)
	}

	companion object {
		const val MAX_ITEM_COUNT = 5
	}
}
