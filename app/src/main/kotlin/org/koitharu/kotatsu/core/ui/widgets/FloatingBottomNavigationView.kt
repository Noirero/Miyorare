package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.MiyorareFavouritesVisualSpec
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.main.ui.nav.FloatingNavBar
import org.koitharu.kotatsu.main.ui.nav.FloatingNavBarColors
import org.koitharu.kotatsu.main.ui.nav.FloatingNavBarItem
import org.koitharu.kotatsu.main.ui.nav.LegacyGlowNavBar
import org.koitharu.kotatsu.main.ui.protect.ProtectActivity
import com.google.android.material.R as materialR

/**
 * A SlidingBottomNavigationView whose visible face is rendered with Jetpack Compose as a
 * floating, pill-shaped toolbar (inspired by the Tomato app's HorizontalFloatingToolbar).
 *
 * It still owns the underlying NavigationBarView menu, so MainNavigationDelegate keeps
 * driving it through the standard menu / selectedItemId / listener APIs. The inherited
 * NavigationBarMenuView is hidden, and a ComposeView sibling renders the visible bar on top.
 * Legacy mode keeps the same navigation behaviour but uses its own lightweight glowing style.
 */
class FloatingBottomNavigationView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : SlidingBottomNavigationView(context, attrs) {

	private val composeItemsState = MutableStateFlow<List<FloatingNavBarItem>>(emptyList())
	private val selectedIdState = MutableStateFlow(0)
	private val labeledState = MutableStateFlow(true)
	private val navColorsState = MutableStateFlow(readNavColors())
	private val continueVisibleState = MutableStateFlow(false)
	private val legacyNavigationState = MutableStateFlow(false)
	private var continueClickListener: (() -> Unit)? = null
	private var continueLongClickListener: (() -> Unit)? = null
	private var itemLongClickListener: ((Int) -> Unit)? = null
	private val sourceItems = mutableListOf<NavItem>()
	private val hiddenIds = mutableSetOf<Int>()
	private val badgeCounts = mutableMapOf<Int, Int>()
	private var useLegacyNavigation = false
	private val privateFavouritesHost = context.findActivity()?.intent?.getIntExtra(
		EXTRA_FAVOURITE_SPACE,
		FavouriteSpace.NORMAL.dbValue,
	) == FavouriteSpace.PRIVATE.dbValue

	private val composeView: ComposeView = ComposeView(context).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			// MiyorareTheme bakes in both the gflex variable font typography and the
			// activity's color scheme — gives both nav styles the same active theme colours.
			org.koitharu.kotatsu.settings.compose.MiyorareTheme {
				val items by composeItemsState.collectAsState()
				val selectedId by selectedIdState.collectAsState()
				val labeled by labeledState.collectAsState()
				val navColors by navColorsState.collectAsState()
				val showContinue by continueVisibleState.collectAsState()
				val useLegacy by legacyNavigationState.collectAsState()
				val visualPalette = LocalMiyorareVisualPalette.current
				val hasExclusiveNavigation =
					visualPalette.isModern && visualPalette.exclusiveTheme?.navigation != null
				// Normal navigation keeps the approved Favourites glass/geometry on every destination.
				// selectedId still moves the active indicator; only the container style remains stable.
				val emphasizeFavourites = !privateFavouritesHost
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(
							horizontal = if (emphasizeFavourites) {
								MiyorareFavouritesVisualSpec.BOTTOM_NAV_HORIZONTAL_MARGIN_DP.dp
							} else {
								12.dp
							},
							vertical = if (emphasizeFavourites) {
								MiyorareFavouritesVisualSpec.BOTTOM_NAV_VERTICAL_MARGIN_DP.dp
							} else {
								8.dp
							},
						),
					contentAlignment = Alignment.Center,
				) {
					// Exclusive navigation has one renderer regardless of the legacy-navigation
					// preference. Falling into LegacyGlowNavBar here would preserve only palette
					// colours and discard the authored silhouette/active-state/ornament identity.
					if (useLegacy && !hasExclusiveNavigation) {
						LegacyGlowNavBar(
							items = items,
							selectedId = selectedId,
							showLabels = labeled,
							colors = navColors,
							onItemSelected = { id -> this@FloatingBottomNavigationView.selectedItemId = id },
							onItemReselected = { id ->
								menu.findItem(id)?.let { reselectedListener?.invoke(it) }
							},
							onItemLongClick = ::dispatchItemLongClick,
							modifier = Modifier.fillMaxWidth(),
							emphasizeFavourites = emphasizeFavourites,
						)
					} else {
						FloatingNavBar(
							items = items,
							selectedId = selectedId,
							showLabels = labeled,
							colors = navColors,
							onItemSelected = { id -> this@FloatingBottomNavigationView.selectedItemId = id },
							onItemReselected = { id ->
								menu.findItem(id)?.let { reselectedListener?.invoke(it) }
							},
							onItemLongClick = ::dispatchItemLongClick,
							modifier = Modifier.wrapContentWidth(),
							showContinue = showContinue,
							emphasizeFavourites = emphasizeFavourites,
							onContinueClick = { continueClickListener?.invoke() },
							onContinueLongClick = { continueLongClickListener?.invoke() },
						)
					}
				}
			}
		}
	}

	private var reselectedListener: ((android.view.MenuItem) -> Unit)? = null

	init {
		// The parent NavigationBarView paints a solid surface — clear it so Compose can render the
		// floating face in both modes without a second opaque layer underneath it.
		background = null
		elevation = 0f
		// Hide everything the parent NavigationBarView added (the native menu view) BEFORE
		// our composeView gets added — we identify it by exclusion because the concrete class is
		// restricted to the Material library group.
		hideNativeChildren()
		addView(
			composeView,
			ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			),
		)
	}

	override val maxItemCountOverride: Int
		get() = MAX_RENDERED_ITEMS

	override fun setSelectedItemId(@IdRes itemId: Int) {
		super.setSelectedItemId(itemId)
		selectedIdState.value = selectedItemId
	}

	override fun setOnItemReselectedListener(listener: OnItemReselectedListener?) {
		super.setOnItemReselectedListener(listener)
		reselectedListener = listener?.let { l -> { item -> l.onNavigationItemReselected(item) } }
	}

	/**
	 * Maximum number of items the floating bar will render. Four slots remain configurable and the
	 * fifth slot is reserved for Reader Journey.
	 */
	val maxRenderedItems: Int = MAX_RENDERED_ITEMS

	/**
	 * Set the list of nav items currently configured in settings. The bar renders up to
	 * [maxRenderedItems] of them, skipping any temporarily hidden via [setComposeItemVisibility].
	 */
	fun setComposeItems(items: List<NavItem>) {
		sourceItems.clear()
		sourceItems.addAll(items)
		rebuildComposeItems()
	}

	fun setComposeLabeled(value: Boolean) {
		labeledState.value = value
	}

	/** Toggle the standalone circular "continue reading" button rendered next to the Classic bar. */
	fun setContinueVisible(value: Boolean) {
		continueVisibleState.value = value
	}

	fun setOnContinueClickListener(listener: (() -> Unit)?) {
		continueClickListener = listener
	}

	fun setOnContinueLongClickListener(listener: (() -> Unit)?) {
		continueLongClickListener = listener
	}

	/** Long-press hook for individual nav items. Normal taps still flow through NavigationBarView. */
	fun setOnItemLongClickListener(listener: ((Int) -> Unit)?) {
		itemLongClickListener = listener
	}

	fun setUseLegacyNavigation(value: Boolean) {
		if (useLegacyNavigation == value) return
		useLegacyNavigation = value
		legacyNavigationState.value = value
		updateNavigationMode()
	}

	fun setComposeBadge(@IdRes itemId: Int, count: Int) {
		if (count == 0) badgeCounts.remove(itemId) else badgeCounts[itemId] = count
		rebuildComposeItems()
	}

	fun setComposeItemVisibility(@IdRes itemId: Int, isVisible: Boolean) {
		if (isVisible) hiddenIds.remove(itemId) else hiddenIds.add(itemId)
		rebuildComposeItems()
	}

	private fun dispatchItemLongClick(@IdRes itemId: Int) {
		itemLongClickListener?.let {
			it(itemId)
			return
		}
		if (itemId == R.id.nav_favorites) {
			context.startActivity(
				Intent(context, ProtectActivity::class.java)
					.putExtra(ProtectActivity.EXTRA_PRIVATE_FAVOURITES, true),
			)
		}
	}

	private fun rebuildComposeItems() {
		val out = ArrayList<FloatingNavBarItem>(sourceItems.size.coerceAtMost(maxRenderedItems))
		for (item in sourceItems) {
			if (item.id in hiddenIds) continue
			out += FloatingNavBarItem(
				id = item.id,
				titleRes = item.navTitle,
				icon = item.icon,
				badgeCount = badgeCounts[item.id] ?: 0,
			)
			if (out.size >= maxRenderedItems) break
		}
		composeItemsState.value = out
		selectedIdState.value = selectedItemId
	}

	private fun hideNativeChildren() {
		for (i in 0 until childCount) {
			val child = getChildAt(i)
			if (child !== composeView) {
				child.visibility = View.GONE
				child.translationY = 0f
			}
		}
	}

	private fun updateNavigationMode() {
		navColorsState.value = readNavColors()
		composeView.visibility = View.VISIBLE
		hideNativeChildren()
		background = null
		elevation = 0f
	}

	private fun readNavColors(): FloatingNavBarColors {
		val unselectedState = intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_checked)
		val fallbackContainer = context.getThemeColor(materialR.attr.colorSurfaceContainer)
		val fallbackUnselectedContent = context.getThemeColor(materialR.attr.colorOnSurfaceVariant)
		// The theme's own active indicator (colorSecondaryContainer) is nearly the same tone as the
		// bar itself, so the selected item barely reads as selected. The primary container pair is
		// the strongest on-theme option and carries its own guaranteed-contrast content colour.
		return FloatingNavBarColors(
			container = backgroundTintList?.defaultColor ?: fallbackContainer,
			selectedContainer = context.getThemeColor(materialR.attr.colorPrimaryContainer),
			selectedContent = context.getThemeColor(materialR.attr.colorOnPrimaryContainer),
			unselectedContent = itemIconTintList?.getColorForState(unselectedState, fallbackUnselectedContent)
				?: itemTextColor?.getColorForState(unselectedState, fallbackUnselectedContent)
				?: fallbackUnselectedContent,
		)
	}

	companion object {
		const val MAX_RENDERED_ITEMS = 5
	}
}
