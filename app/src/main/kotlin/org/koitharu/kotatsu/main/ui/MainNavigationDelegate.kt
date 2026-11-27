package org.koitharu.kotatsu.main.ui

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
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
import com.google.android.material.appbar.AppBarLayout
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
import org.koitharu.kotatsu.core.prefs.NavigationTransitionPreferences
import org.koitharu.kotatsu.core.prefs.ScreenTransition
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.core.ui.widgets.DragNavigationRailView
import org.koitharu.kotatsu.core.ui.widgets.DirectionalSlide
import org.koitharu.kotatsu.core.ui.widgets.DirectionalSlidePresentation
import org.koitharu.kotatsu.core.ui.widgets.InteractiveTabSlidePresentation
import org.koitharu.kotatsu.core.ui.widgets.TabSlideFrame
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
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
	private val interactiveSlide = InteractiveTabSlidePresentation()
	private var previewFavourites: FavouritesContainerFragment? = null
	private var previewHeader: PreviewHeader? = null
	private var committingTabDrag = false
	private var dragPreviewMatched = false
	private val transitionPreferences = NavigationTransitionPreferences(navBar.context)
	private val slidePresentation = DirectionalSlidePresentation()
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
		val fromId = primaryFragment?.let(::getItemId)
		val userNavbar = committingTabDrag || when (navBar) {
			is FloatingBottomNavigationView -> navBar.isUserSelection
			is DragNavigationRailView -> !navBar.isProgrammaticSelection && navBar.isLaidOut
			else -> false
		}
		return if (onNavigationItemSelected(item.itemId, userNavbar)) {
			item.isChecked = true
			if (userNavbar && !committingTabDrag && fromId != null && fromId != item.itemId) {
				(navBar as? DragNavigationRailView)?.animateTabSelection(fromId, item.itemId)
			}
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
			floating.setTabDragListeners(::previewTab, ::commitTabDrag, ::seekTabSlide)
			floating.setComposeItems(settings.mainNavItems)
			floating.setComposeLabeled(settings.isNavLabelsVisible)
			floating.setUseLegacyNavigation(settings.isLegacyNavigationBar)
			floating.setOnItemLongClickListener { itemId ->
				if (itemId == R.id.nav_favorites) openPrivateFavourites()
			}
		}
		(navBar as? DragNavigationRailView)?.setTabDragListeners(::previewTab, ::commitTabDrag, ::seekTabSlide)
		installNativePrivateLongClick()
		applyMotionPreference()
		settings.observe(NavigationTransitionPreferences.KEY_MOTION, NavigationTransitionPreferences.KEY_TRANSITION)
			.onEach { cancelTabDrag(); applyMotionPreference() }.launchIn(lifecycleOwner.lifecycleScope)
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

	private fun onNavigationItemSelected(@IdRes itemId: Int, userNavbar: Boolean = false): Boolean {
		val newFragment = fragmentClassForItem(itemId) ?: return false
		if (userNavbar && fragmentManager.isStateSaved) return false
		if (!setPrimaryFragment(newFragment, userNavbar)) {
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

	private fun applyMotionPreference() {
		val motion = transitionPreferences.motion
		(navBar as? FloatingBottomNavigationView)?.setNavigationMotion(motion)
		(navBar as? DragNavigationRailView)?.setNavigationMotion(motion)
	}

	private fun slideDirection(fromId: Int, toId: Int): Int {
		val visibleIds = (0 until navBar.menu.size).map { navBar.menu.getItem(it) }
			.filter { it.isVisible && it.isEnabled }.map { it.itemId }
		return DirectionalSlide.direction(visibleIds.indexOf(fromId), visibleIds.indexOf(toId),
			navBar !is DragNavigationRailView && navBar.layoutDirection == View.LAYOUT_DIRECTION_RTL)
	}

	private fun clearTabPreview(keepFavouritesWork: Boolean = false) {
		slidePresentation.finish()
		interactiveSlide.finish()
		if (!keepFavouritesWork) previewFavourites?.setTabPreviewVisible(false)
		previewFavourites = null
		restorePreviewHeader()
	}

	private fun restorePreviewHeader() {
		val preview = previewHeader ?: return
		previewHeader = null
		preview.restore()
	}

	/** Move the existing page-specific header into its own page for the duration of a preview.
	 * The shared AppBar must measure only shared chrome, while Favorites keeps its list anchor.
	 */
	private fun movePreviewHeader(currentView: View) {
		if (previewHeader != null) return
		val content = currentView as? LinearLayout ?: return
		val appBar = (navBar.context.findActivity() as? AppBarOwner)?.appBar ?: return
		val header = appBar.findViewById<View>(R.id.layout_category_header) ?: return
		if (header.parent !== appBar) return
		val parent = appBar.parent as? CoordinatorLayout ?: return
		val behavior = (appBar.layoutParams as? CoordinatorLayout.LayoutParams)?.behavior
			as? AppBarLayout.Behavior ?: return
		val params = header.layoutParams as AppBarLayout.LayoutParams
		val index = appBar.indexOfChild(header)
		val scrollState = behavior.onSaveInstanceState(parent, appBar)
		val hiddenHeight = (-appBar.top - header.top).coerceIn(0, header.height)
		// Keep a zero-height child at the original index: Material AppBar restores its
		// saved scroll state by child index during layout. The slot contributes no height.
		val slot = View(appBar.context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
		val slotParams = AppBarLayout.LayoutParams(params.width, 0).apply { scrollFlags = params.scrollFlags }
		appBar.removeView(header)
		appBar.addView(slot, index, slotParams)
		content.addView(header, 0, LinearLayout.LayoutParams(params.width, params.height).apply {
			topMargin = params.topMargin - hiddenHeight
			bottomMargin = params.bottomMargin
			marginStart = params.marginStart
			marginEnd = params.marginEnd
		})
		previewHeader = PreviewHeader(appBar, parent, behavior, scrollState, slot, content, header, params)
	}

	private class PreviewHeader(
		private val appBar: AppBarLayout,
		private val parent: CoordinatorLayout,
		private val behavior: AppBarLayout.Behavior,
		private val scrollState: Parcelable,
		private val slot: View,
		private val content: LinearLayout,
		private val header: View,
		private val params: AppBarLayout.LayoutParams,
	) {
		fun restore() {
			val index = appBar.indexOfChild(slot)
			if (index < 0) return
			appBar.removeView(slot)
			// Do not steal a header that has already been moved by its lifecycle owner.
			if (header.parent !== content) return
			content.removeView(header)
			appBar.addView(header, index.coerceAtMost(appBar.childCount), params)
			behavior.onRestoreInstanceState(parent, appBar, scrollState)
			appBar.requestLayout()
		}
	}

	private fun previewTab(@IdRes itemId: Int?): Boolean {
		// A final null after release must not interrupt the accepted settlement.
		if (itemId == null) {
			if (interactiveSlide.isActive) clearTabPreview()
			return true
		}
		return !fragmentManager.isStateSaved && navBar.menu.findItem(itemId)?.let {
			it.isVisible && it.isEnabled
		} == true && fragmentClassForItem(itemId) != null
	}

	private fun seekTabSlide(frame: TabSlideFrame) {
		if (fragmentManager.isStateSaved) { clearTabPreview(); return }
		val current = primaryFragment ?: return
		val currentView = current.view ?: return
		val currentId = getItemId(current)
		if (!interactiveSlide.isActive) slidePresentation.finish()
		// Keep the existing FragmentManager as selection/lifecycle owner. Only the two
		// visible pages of the continuous frame are resolved, at most once per gesture.
		val rendered = interactiveSlide.seek(frame, currentId, currentView) { id ->
			if (id == currentId) currentView else previewFragment(id)?.view
		}
		if (!rendered) { clearTabPreview(); return }

		val favourites = if (currentId != R.id.nav_favorites && frame.contains(R.id.nav_favorites)) {
			fragmentManager.findFragmentByTag(FavouritesContainerFragment::class.java.name) as? FavouritesContainerFragment
		} else null
		if (previewFavourites !== favourites) {
			previewFavourites?.setTabPreviewVisible(false)
			previewFavourites = favourites
			favourites?.setTabPreviewVisible(true)
		}
		// Only transfer at the gesture boundary, not on every pointer frame.
		if (current is FavouritesContainerFragment && frame.hasOtherThan(currentId)) {
			movePreviewHeader(currentView)
		} else restorePreviewHeader()
	}

	private fun previewFragment(@IdRes id: Int): Fragment? {
		if (!previewTab(id)) return null
		val type = fragmentClassForItem(id) ?: return null
		val target = fragmentManager.findFragmentByTag(type.name) ?: newBottomTab(type).also { fragment ->
			fragmentManager.beginTransaction()
				.setReorderingAllowed(true)
				.add(R.id.container, fragment, type.name)
				.hide(fragment)
				.setMaxLifecycle(fragment, Lifecycle.State.STARTED)
				.commitNow()
		}
		return target.takeIf { it.isHidden }
	}

	private fun commitTabDrag(@IdRes itemId: Int) {
		if (!previewTab(itemId)) { clearTabPreview(); return }
		val alreadySelected = primaryFragment?.let(::getItemId) == itemId
		val matched = interactiveSlide.contains(itemId)
		val handoff = interactiveSlide.captureHandoff(itemId)
		// Keep Favorites list work continuous across preview -> real commit.
		val favourites = previewFavourites.takeIf { matched && itemId == R.id.nav_favorites && !alreadySelected }
		clearTabPreview(keepFavouritesWork = favourites != null)
		if (alreadySelected) {
			// A short/reversed drag settles back without triggering a reselection or lifecycle change.
			handoff?.let(slidePresentation::resume)
			return
		}
		dragPreviewMatched = matched
		committingTabDrag = true
		try {
			navBar.selectedItemId = itemId
			if (handoff != null && primaryFragment?.view === handoff.incoming) {
				slidePresentation.resume(handoff)
			}
		} finally {
			committingTabDrag = false
			dragPreviewMatched = false
			favourites?.setTabPreviewVisible(false)
		}
	}

	private fun newBottomTab(type: Class<out Fragment>): Fragment = instantiateFragment(type).apply {
		arguments = buildBundle(1) { putBoolean(AppRouter.KEY_IS_BOTTOMTAB, true) }
	}

	private fun setPrimaryFragment(fragmentClass: Class<out Fragment>, userNavbar: Boolean = false): Boolean {
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
		val direction = if (current != null && userNavbar && transitionPreferences.transition == ScreenTransition.DIRECTIONAL_SLIDE) {
			val targetId = (0 until navBar.menu.size).map { navBar.menu.getItem(it).itemId }
				.firstOrNull { fragmentClassForItem(it) == fragmentClass }
			if (targetId != null) slideDirection(getItemId(current), targetId) else 0
		} else 0
		if (userNavbar && direction != 0 && !dragPreviewMatched && navBar.context.isAnimationsEnabled) {
			val animations = DirectionalSlide.animations(direction)
			transaction.setCustomAnimations(animations.first, animations.second)
		} else if (!userNavbar && !involvesFavourites && !committingTabDrag) {
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
		// A direct user selection is accepted synchronously so a later gesture cannot read a stale primary tab.
		// Programmatic, restore and back paths retain their existing transaction policy.
		if (committingTabDrag || (userNavbar && current != null)) transaction.commitNow() else transaction.commit()
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
		(navBar as? DragNavigationRailView)?.cancelTabDrag()
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
