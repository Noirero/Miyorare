package org.koitharu.kotatsu.favourites.ui.container

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.search.SearchBar
import com.google.android.material.search.SearchView
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.util.ActionModeListener
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.centerContentOnDisplay
import org.koitharu.kotatsu.core.util.ext.findCurrentPagerFragment
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.recyclerView
import org.koitharu.kotatsu.core.util.ext.setTabsEnabled
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.databinding.FragmentFavouritesContainerBinding
import org.koitharu.kotatsu.databinding.ItemEmptyStateBinding
import org.koitharu.kotatsu.favourites.domain.FavouriteCategoryNavigationMode
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.domain.FavouriteDisplayPreferences
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import javax.inject.Inject
import kotlin.math.roundToInt
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

@AndroidEntryPoint
class FavouritesContainerFragment : BaseFragment<FragmentFavouritesContainerBinding>(),
	ActionModeListener,
	RecyclerViewOwner,
	ViewStub.OnInflateListener,
	View.OnClickListener {

	@Inject lateinit var contentTypeStore: FavouriteContentTypeStore
	@Inject lateinit var displayPreferences: FavouriteDisplayPreferences
	@Inject lateinit var visualEffectPreferences: VisualEffectPreferences
	@Inject lateinit var settings: AppSettings

	private val viewModel: FavouritesContainerViewModel by viewModels()
	private var inlineSearchEdit: AppCompatEditText? = null
	private var inlineSearchActive = false
	private var searchBackCallback: OnBackPressedCallback? = null
	private var pagerAdapter: FavouritesContainerAdapter? = null
	private var categories: List<FavouriteTabModel> = emptyList()
	private var isEmptyState = false
	private var isActionModeActive = false
	private var displayedContentType: FavouriteContentType? = null
	private var pendingCategoryRestore: FavouriteContentType? = null

	private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
		override fun onPageSelected(position: Int) {
			if (pendingCategoryRestore == null) {
				rememberCurrentCategory()
			}
			updateCategoryPickerLabel()
			activity?.invalidateOptionsMenu()
		}
	}

	override val recyclerView: RecyclerView?
		get() = (findCurrentFragment() as? RecyclerViewOwner)?.recyclerView

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentFavouritesContainerBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentFavouritesContainerBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val shouldRestoreInlineSearch = savedInstanceState?.getBoolean(STATE_INLINE_SEARCH_ACTIVE)
			?: searchSessionActive.value
		savedInstanceState?.getString(STATE_SEARCH_QUERY)?.let { searchQuery.value = it }
		searchScopeActive.value = !isHidden
		val adapter = FavouritesContainerAdapter(
			fragment = this,
			showCategoryCounts = {
				displayPreferences.current(contentTypeStore.selectedType.value).showCategoryCounts
			},
			onListCommitted = ::onCategoriesCommitted,
		)
		pagerAdapter = adapter
		binding.pager.adapter = adapter
		binding.pager.offscreenPageLimit = 1
		binding.pager.recyclerView?.apply {
			isNestedScrollingEnabled = false
			itemAnimator = null
		}
		binding.pager.registerOnPageChangeCallback(pageChangeCallback)
		TabLayoutMediator(
			binding.tabs,
			binding.pager,
			FavouritesTabConfigurationStrategy(
				adapter = adapter,
				viewModel = viewModel,
				router = router,
				modern = settings.miyorareDesignStyle == MiyorareDesignStyle.MODERN,
			),
		).attach()
		binding.buttonCategoryPicker.setOnClickListener { showCategoryPicker() }
		binding.stubEmpty.setOnInflateListener(this)
		binding.toggleContentType.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (!isChecked) return@addOnButtonCheckedListener
			val type = when (checkedId) {
				R.id.button_content_novel -> FavouriteContentType.NOVEL
				else -> FavouriteContentType.MANGA
			}
			if (contentTypeStore.selectedType.value != type) {
				rememberCurrentCategory()
				contentTypeStore.setSelectedType(type)
			}
		}
		onContentTypeChanged(contentTypeStore.selectedType.value)
		if (!isHidden) {
			attachTabsToAppBar()
			installFavouriteSearchHandler()
		}
		actionModeDelegate.addListener(this)
		viewModel.categories.observe(viewLifecycleOwner, adapter)
		viewModel.isEmpty.observe(viewLifecycleOwner, ::onEmptyStateChanged)
		contentTypeStore.selectedType.observe(viewLifecycleOwner, ::onContentTypeChanged)
		displayPreferences.state.observe(viewLifecycleOwner) {
			applyCategoryNavigation(displayPreferences.current(contentTypeStore.selectedType.value))
		}
		displayPreferences.categoryNavigationMode.observe(viewLifecycleOwner) {
			applyCategoryNavigation(displayPreferences.current(contentTypeStore.selectedType.value))
		}
		visualEffectPreferences.level.observe(viewLifecycleOwner, ::applyVisualFoundation)
		addMenuProvider(
			FavouritesContainerMenuProvider(
				router = router,
				isAllFavouritesSelected = { currentCategory()?.id == FavouritesListFragment.NO_ID },
				totalTitle = {
					getString(R.string.favourites_total_format, currentCategory()?.count ?: 0)
				},
				onGoToTop = { currentFavouritesList()?.scrollToTop() },
				onGoToBottom = { currentFavouritesList()?.scrollToBottom() },
			),
		)
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.pager))

		searchBackCallback = object : OnBackPressedCallback(false) {
			override fun handleOnBackPressed() = exitInlineSearch()
		}.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }

		if (shouldRestoreInlineSearch && !isHidden) {
			enterInlineSearch()
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		outState.putBoolean(STATE_INLINE_SEARCH_ACTIVE, searchSessionActive.value)
		outState.putString(STATE_SEARCH_QUERY, searchQuery.value)
		super.onSaveInstanceState(outState)
	}

	override fun onDestroyView() {
		rememberCurrentCategory()
		viewBinding?.pager?.unregisterOnPageChangeCallback(pageChangeCallback)
		exitInlineSearch(clearQuery = false, endSession = false)
		restoreGlobalSearchHandler()
		inlineSearchEdit?.let { edit -> (edit.parent as? ViewGroup)?.removeView(edit) }
		inlineSearchEdit = null
		searchBackCallback = null
		pagerAdapter = null
		categories = emptyList()
		isActionModeActive = false
		searchScopeActive.value = false
		detachTabsFromAppBar()
		actionModeDelegate.removeListener(this)
		super.onDestroyView()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	override fun onResume() {
		super.onResume()
		if (isHidden) return
		searchScopeActive.value = true
		installFavouriteSearchHandler()
		onContentTypeChanged(contentTypeStore.selectedType.value)
		if (searchSessionActive.value) {
			enterInlineSearch()
		}
	}

	override fun onHiddenChanged(hidden: Boolean) {
		super.onHiddenChanged(hidden)
		searchScopeActive.value = !hidden
		if (hidden) {
			exitInlineSearch(clearQuery = false, endSession = false)
			restoreGlobalSearchHandler()
		} else {
			installFavouriteSearchHandler()
			onContentTypeChanged(contentTypeStore.selectedType.value)
			if (searchSessionActive.value) {
				enterInlineSearch()
			}
			for (page in childFragmentManager.fragments) {
				val recyclerView = (page as? RecyclerViewOwner)?.recyclerView ?: continue
				when (val lm = recyclerView.layoutManager) {
					is LinearLayoutManager -> lm.scrollToPositionWithOffset(0, 0)
					else -> recyclerView.scrollToPosition(0)
				}
			}
		}
	}

	override fun onActionModeStarted(mode: ActionMode) {
		isActionModeActive = true
		applyCategoryInteraction()
		viewBinding?.run {
			buttonContentManga.isEnabled = false
			buttonContentNovel.isEnabled = false
		}
	}

	override fun onActionModeFinished(mode: ActionMode) {
		isActionModeActive = false
		applyCategoryInteraction()
		viewBinding?.run {
			buttonContentManga.isEnabled = true
			buttonContentNovel.isEnabled = true
		}
	}

	override fun onInflate(stub: ViewStub?, inflated: View) {
		val stubBinding = ItemEmptyStateBinding.bind(inflated)
		inflated.centerContentOnDisplay()
		stubBinding.icon.setImageAsync(R.drawable.ic_empty_favourites)
		stubBinding.textPrimary.setText(R.string.text_empty_holder_primary)
		stubBinding.textSecondary.setTextAndVisible(R.string.empty_favourite_categories)
		stubBinding.buttonRetry.setTextAndVisible(R.string.manage)
		stubBinding.buttonRetry.setOnClickListener(this)
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_retry -> router.openFavoriteCategories()
		}
	}

	private fun onContentTypeChanged(type: FavouriteContentType) {
		if (displayedContentType != type) {
			displayedContentType = type
			pendingCategoryRestore = type
		}
		val checkedId = if (type == FavouriteContentType.NOVEL) {
			R.id.button_content_novel
		} else {
			R.id.button_content_manga
		}
		viewBinding?.toggleContentType?.let { toggle ->
			if (toggle.checkedButtonId != checkedId) toggle.check(checkedId)
		}
		val hint = if (type == FavouriteContentType.NOVEL) {
			getString(R.string.search_novel)
		} else {
			getString(R.string.search_manga)
		}
		inlineSearchEdit?.hint = hint
		if (!isHidden) {
			activity?.findViewById<SearchBar>(R.id.search_bar)?.hint = hint
		}
		applyCategoryNavigation(displayPreferences.current(type))
	}

	private fun onCategoriesCommitted(value: List<FavouriteTabModel>) {
		categories = value
		activity?.invalidateOptionsMenu()
		val binding = viewBinding ?: return
		val restoreType = pendingCategoryRestore
		if (restoreType != null && isCategoryListForType(value, restoreType)) {
			val categoryId = contentTypeStore.getLastCategoryId(restoreType)
			val target = value.indexOfFirst { it.id == categoryId }.takeIf { it >= 0 } ?: 0
			if (value.isNotEmpty()) {
				binding.pager.setCurrentItem(target, false)
				contentTypeStore.setLastCategoryId(restoreType, value[target].id)
			}
			pendingCategoryRestore = null
		} else if (restoreType == null && value.isNotEmpty() && binding.pager.currentItem >= value.size) {
			binding.pager.setCurrentItem(0, false)
		}
		applyCategoryNavigation(displayPreferences.current(contentTypeStore.selectedType.value))
	}

	private fun isCategoryListForType(
		items: List<FavouriteTabModel>,
		type: FavouriteContentType,
	): Boolean = items.any { it.id == LOCAL_FAVOURITES_CATEGORY_ID } == (type == FavouriteContentType.MANGA)

	private fun onEmptyStateChanged(isEmpty: Boolean) {
		isEmptyState = isEmpty
		viewBinding?.run {
			pager.isGone = isEmpty
			stubEmpty.isVisible = isEmpty
			toggleContentType.isVisible = true
		}
		applyCategoryNavigation(displayPreferences.current(contentTypeStore.selectedType.value))
	}

	private fun applyCategoryNavigation(options: FavouriteDisplayPreferences.Options) {
		val binding = viewBinding ?: return
		val hasCategories = categories.isNotEmpty()
		val hasMultipleCategories = categories.size > 1
		binding.tabs.isVisible = !isEmptyState && hasMultipleCategories && options.showCategoryTabs
		binding.buttonCategoryPicker.isVisible = !isEmptyState && hasCategories && !options.showCategoryTabs
		for (index in 0 until binding.tabs.tabCount) {
			val item = categories.getOrNull(index) ?: continue
			val tab = binding.tabs.getTabAt(index) ?: continue
			updateFavouriteTabBadge(
				tab = tab,
				count = item.count,
				isVisible = options.showCategoryCounts && item.count > 0,
			)
		}
		applyCategoryInteraction()
		updateCategoryPickerLabel(options)
	}

	private fun applyCategoryInteraction() {
		val binding = viewBinding ?: return
		val navigationMode = displayPreferences.categoryNavigationMode.value
		val canTap = !isActionModeActive && navigationMode.allowsTap
		val canSwipe = !isActionModeActive && navigationMode.allowsSwipe

		binding.pager.isUserInputEnabled = canSwipe
		binding.tabs.setTabsEnabled(!isActionModeActive)
		for (index in 0 until binding.tabs.tabCount) {
			binding.tabs.getTabAt(index)?.view?.apply {
				isClickable = canTap
				isFocusable = canTap
			}
		}
		binding.buttonCategoryPicker.isEnabled = !isActionModeActive
		binding.buttonCategoryPicker.isClickable = canTap
		binding.buttonCategoryPicker.isFocusable = canTap
	}

	private fun applyVisualFoundation(level: VisualEffectLevel) {
		if (settings.miyorareDesignStyle == MiyorareDesignStyle.MODERN) {
			applyModernVisualFoundation(level)
		} else {
			applyClassicVisualFoundation(level)
		}
	}

	/** Preserve the exact pre-pass appearance for Classic while Modern evolves independently. */
	private fun applyClassicVisualFoundation(level: VisualEffectLevel) {
		val binding = viewBinding ?: return
		val context = binding.root.context
		val density = resources.displayMetrics.density
		val surface = context.getThemeColor(materialR.attr.colorSurface, Color.TRANSPARENT)
		val primary = context.getThemeColor(appcompatR.attr.colorPrimary, surface)
		val headerColor = ColorUtils.blendARGB(surface, primary, level.surfaceTintFraction)
		val outlineColor = ColorUtils.blendARGB(surface, primary, 0.55f)
		val surfaceRadius = MiyorareVisualTokens.RADIUS_SURFACE_DP * density
		val headerBackground = GradientDrawable().apply {
			setColor(headerColor)
			cornerRadii = floatArrayOf(
				0f, 0f,
				0f, 0f,
				surfaceRadius, surfaceRadius,
				surfaceRadius, surfaceRadius,
			)
			if (level.outlineAlpha > 0) {
				setStroke(
					(1f * density).roundToInt().coerceAtLeast(1),
					ColorUtils.setAlphaComponent(outlineColor, level.outlineAlpha),
				)
			}
		}
		binding.layoutCategoryHeader.background = headerBackground
		binding.layoutCategoryHeader.elevation = level.headerElevationDp * density
		binding.tabs.setSelectedTabIndicatorColor(Color.TRANSPARENT)

		val controlRadius = (MiyorareVisualTokens.RADIUS_CONTROL_DP * density).roundToInt()
		binding.buttonCategoryPicker.cornerRadius = controlRadius
		binding.buttonCategoryPicker.strokeWidth = (1f * density).roundToInt().coerceAtLeast(1)
		binding.buttonCategoryPicker.strokeColor = ColorStateList.valueOf(
			ColorUtils.setAlphaComponent(outlineColor, level.outlineAlpha.coerceAtLeast(48)),
		)
		binding.buttonCategoryPicker.iconTint = ColorStateList.valueOf(primary)
	}

	private fun applyModernVisualFoundation(level: VisualEffectLevel) {
		val binding = viewBinding ?: return
		val context = binding.root.context
		val density = resources.displayMetrics.density
		fun dp(value: Float) = (value * density).roundToInt()

		val surface = context.getThemeColor(materialR.attr.colorSurface, Color.TRANSPARENT)
		val surfaceContainer = context.getThemeColor(materialR.attr.colorSurfaceContainer, surface)
		val primary = context.getThemeColor(appcompatR.attr.colorPrimary, surface)
		val primaryContainer = context.getThemeColor(materialR.attr.colorPrimaryContainer, primary)
		val onPrimaryContainer = context.getThemeColor(materialR.attr.colorOnPrimaryContainer, Color.WHITE)
		val onSurfaceVariant = context.getThemeColor(materialR.attr.colorOnSurfaceVariant, Color.LTGRAY)
		val tertiary = context.getThemeColor(materialR.attr.colorTertiary, primary)
		val strength = when (level) {
			VisualEffectLevel.LIGHT -> MiyorareVisualTokens.GRADIENT_STRENGTH_LIGHT
			VisualEffectLevel.BALANCED -> MiyorareVisualTokens.GRADIENT_STRENGTH_BALANCED * 0.68f
			VisualEffectLevel.FULL -> MiyorareVisualTokens.GRADIENT_STRENGTH_FULL * 0.68f
		}
		val surfaceRadius = MiyorareVisualTokens.RADIUS_SURFACE_DP * density
		val outlineColor = ColorUtils.blendARGB(surface, primary, 0.52f)
		val headerBackground = GradientDrawable(
			GradientDrawable.Orientation.TL_BR,
			intArrayOf(
				ColorUtils.blendARGB(surface, primary, strength * 0.94f),
				ColorUtils.blendARGB(surfaceContainer, tertiary, strength * 0.50f),
				ColorUtils.blendARGB(surface, primary, strength * 0.16f),
			),
		).apply {
			cornerRadii = floatArrayOf(
				0f, 0f,
				0f, 0f,
				surfaceRadius, surfaceRadius,
				surfaceRadius, surfaceRadius,
			)
			setStroke(
				dp(0.75f).coerceAtLeast(1),
				ColorUtils.setAlphaComponent(outlineColor, (level.outlineAlpha * 0.50f).roundToInt()),
			)
		}
		binding.layoutCategoryHeader.background = headerBackground
		binding.layoutCategoryHeader.elevation = when (level) {
			VisualEffectLevel.LIGHT -> 0f
			VisualEffectLevel.BALANCED -> 1f * density
			VisualEffectLevel.FULL -> 1.5f * density
		}
		binding.layoutCategoryHeader.setPadding(0, dp(2f), 0, dp(2f))
		binding.tabs.setSelectedTabIndicatorColor(Color.TRANSPARENT)
		binding.tabs.setTabTextColors(onSurfaceVariant, primary)
		binding.tabs.setTabRippleColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 24)))

		binding.toggleContentType.setPadding(dp(1f), dp(1f), dp(1f), dp(1f))
		(binding.toggleContentType.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
			params.marginStart = dp(MiyorareVisualTokens.SPACING_L_DP)
			params.marginEnd = dp(MiyorareVisualTokens.SPACING_L_DP)
			params.topMargin = dp(3f)
			params.bottomMargin = dp(2f)
			binding.toggleContentType.layoutParams = params
		}
		(binding.tabs.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
			params.topMargin = dp(2f)
			params.bottomMargin = 0
			binding.tabs.layoutParams = params
		}

		val groupRadius = MiyorareVisualTokens.RADIUS_SURFACE_DP * density
		binding.toggleContentType.background = GradientDrawable(
			GradientDrawable.Orientation.LEFT_RIGHT,
			intArrayOf(
				ColorUtils.blendARGB(surfaceContainer, primary, strength * 0.26f),
				ColorUtils.blendARGB(surfaceContainer, tertiary, strength * 0.16f),
				ColorUtils.blendARGB(surfaceContainer, primary, strength * 0.08f),
			),
		).apply {
			cornerRadius = groupRadius
			setStroke(
				dp(0.75f).coerceAtLeast(1),
				ColorUtils.setAlphaComponent(outlineColor, (level.outlineAlpha * 0.36f).roundToInt()),
			)
		}

		val buttonStates = arrayOf(
			intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled),
			intArrayOf(-android.R.attr.state_enabled),
			intArrayOf(),
		)
		val selectedFill = ColorUtils.blendARGB(surfaceContainer, primaryContainer, 0.68f)
		val disabledFill = ColorUtils.blendARGB(surfaceContainer, onSurfaceVariant, 0.06f)
		val buttonBackgrounds = ColorStateList(
			buttonStates,
			intArrayOf(selectedFill, disabledFill, Color.TRANSPARENT),
		)
		val buttonTextColors = ColorStateList(
			buttonStates,
			intArrayOf(
				onPrimaryContainer,
				ColorUtils.setAlphaComponent(onSurfaceVariant, 112),
				onSurfaceVariant,
			),
		)
		val selectedStroke = ColorUtils.setAlphaComponent(
			primary,
			(MiyorareVisualTokens.BORDER_ALPHA_BALANCED * 0.72f * 255f).roundToInt().coerceIn(0, 255),
		)
		val idleStroke = ColorUtils.setAlphaComponent(
			outlineColor,
			(MiyorareVisualTokens.BORDER_ALPHA_LIGHT * 0.45f * 255f).roundToInt().coerceIn(0, 255),
		)
		val buttonStrokeColors = ColorStateList(
			buttonStates,
			intArrayOf(selectedStroke, ColorUtils.setAlphaComponent(onSurfaceVariant, 32), idleStroke),
		)
		val controlRadius = dp(MiyorareVisualTokens.RADIUS_CONTROL_DP)
		for (button in arrayOf(binding.buttonContentManga, binding.buttonContentNovel)) {
			button.backgroundTintList = buttonBackgrounds
			button.setTextColor(buttonTextColors)
			button.cornerRadius = controlRadius
			button.strokeColor = buttonStrokeColors
			button.strokeWidth = dp(0.5f).coerceAtLeast(1)
			button.minimumHeight = dp(32f)
		}

		binding.buttonCategoryPicker.cornerRadius = controlRadius
		binding.buttonCategoryPicker.strokeWidth = dp(0.75f).coerceAtLeast(1)
		binding.buttonCategoryPicker.strokeColor = ColorStateList.valueOf(
			ColorUtils.setAlphaComponent(outlineColor, (level.outlineAlpha * 0.60f).roundToInt().coerceAtLeast(32)),
		)
		binding.buttonCategoryPicker.backgroundTintList = ColorStateList.valueOf(
			ColorUtils.blendARGB(surfaceContainer, primary, strength * 0.26f),
		)
		binding.buttonCategoryPicker.iconTint = ColorStateList.valueOf(primary)
	}

	private fun updateCategoryPickerLabel(
		options: FavouriteDisplayPreferences.Options = displayPreferences.current(contentTypeStore.selectedType.value),
	) {
		val binding = viewBinding ?: return
		val item = categories.getOrNull(binding.pager.currentItem) ?: categories.firstOrNull() ?: return
		val title = item.title ?: getString(R.string.all_favourites)
		binding.buttonCategoryPicker.text = if (options.showCategoryCounts) {
			getString(
				R.string.favourites_category_selector_with_count,
				title,
				item.count.coerceAtMost(MAX_CATEGORY_BADGE_COUNT),
			)
		} else {
			getString(R.string.favourites_category_selector, title)
		}
	}

	private fun showCategoryPicker() {
		if (!displayPreferences.categoryNavigationMode.value.allowsTap) return
		val binding = viewBinding ?: return
		val options = displayPreferences.current(contentTypeStore.selectedType.value)
		PopupMenu(requireContext(), binding.buttonCategoryPicker).apply {
			categories.forEachIndexed { index, item ->
				val title = item.title ?: getString(R.string.all_favourites)
				val label = if (options.showCategoryCounts) {
					"$title • ${item.count.coerceAtMost(MAX_CATEGORY_BADGE_COUNT)}"
				} else {
					title
				}
				menu.add(Menu.NONE, index + MENU_CATEGORY_ID_OFFSET, index, label).isCheckable = true
			}
			menu.findItem(binding.pager.currentItem + MENU_CATEGORY_ID_OFFSET)?.isChecked = true
			setOnMenuItemClickListener { menuItem ->
				val index = menuItem.itemId - MENU_CATEGORY_ID_OFFSET
				if (index !in categories.indices) return@setOnMenuItemClickListener false
				binding.pager.setCurrentItem(index, false)
				updateCategoryPickerLabel(options)
				true
			}
			show()
		}
	}

	private fun findCurrentFragment(): Fragment? {
		return childFragmentManager.findCurrentPagerFragment(
			viewBinding?.pager ?: return null,
		)
	}

	private fun currentCategory(): FavouriteTabModel? {
		val position = viewBinding?.pager?.currentItem ?: return null
		return categories.getOrNull(position)
	}

	private fun rememberCurrentCategory() {
		val type = displayedContentType ?: return
		val category = currentCategory() ?: return
		contentTypeStore.setLastCategoryId(type, category.id)
	}

	private fun currentFavouritesList(): FavouritesListFragment? =
		findCurrentFragment() as? FavouritesListFragment

	private fun installFavouriteSearchHandler() {
		val searchBar = activity?.findViewById<SearchBar>(R.id.search_bar) ?: return
		searchBar.setOnClickListener { enterInlineSearch() }
	}

	private fun restoreGlobalSearchHandler() {
		val host = activity ?: return
		val searchBar = host.findViewById<SearchBar>(R.id.search_bar) ?: return
		val searchView = host.findViewById<SearchView>(R.id.search_view) ?: return
		searchBar.hint = getString(R.string.search_manga)
		searchBar.setOnClickListener { searchView.show() }
	}

	private fun enterInlineSearch() {
		if (inlineSearchActive) return
		val host = activity ?: return
		val searchBar = host.findViewById<SearchBar>(R.id.search_bar) ?: return
		val edit = inlineSearchEdit ?: createInlineSearchEdit(searchBar) ?: return
		searchSessionActive.value = true
		inlineSearchActive = true
		searchBar.isGone = true
		edit.isVisible = true
		searchBackCallback?.isEnabled = true
		host.findViewById<MaterialButton>(R.id.button_settings)?.apply {
			setIconResource(R.drawable.ic_arrow_back)
			contentDescription = getString(R.string.close)
			setOnClickListener { exitInlineSearch() }
		}
		edit.requestFocus()
		edit.setSelection(edit.text?.length ?: 0)
		edit.post {
			(context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
				?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
		}
	}

	private fun exitInlineSearch(clearQuery: Boolean = true, endSession: Boolean = true) {
		inlineSearchActive = false
		searchBackCallback?.isEnabled = false
		if (endSession) {
			searchSessionActive.value = false
		}
		if (clearQuery) {
			searchQuery.value = ""
		}
		val host = activity ?: return
		val searchBar = host.findViewById<SearchBar>(R.id.search_bar) ?: return
		val edit = inlineSearchEdit
		edit?.apply {
			if (clearQuery) setText("")
			clearFocus()
			isGone = true
		}
		searchBar.isVisible = true
		host.findViewById<MaterialButton>(R.id.button_settings)?.apply {
			setIconResource(R.drawable.ic_settings)
			contentDescription = getString(R.string.settings)
			setOnClickListener { router.openSettings() }
		}
		if (edit != null) {
			(host.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
				?.hideSoftInputFromWindow(edit.windowToken, 0)
		}
	}

	private fun createInlineSearchEdit(searchBar: SearchBar): AppCompatEditText? {
		val parent = searchBar.parent as? LinearLayout ?: return null
		val density = resources.displayMetrics.density
		val searchBarParams = searchBar.layoutParams as? LinearLayout.LayoutParams
		val edit = AppCompatEditText(requireContext()).apply {
			layoutParams = LinearLayout.LayoutParams(
				0,
				searchBarParams?.height ?: LinearLayout.LayoutParams.WRAP_CONTENT,
				1f,
			).apply {
				if (searchBarParams != null) {
					marginStart = searchBarParams.marginStart
					marginEnd = searchBarParams.marginEnd
					topMargin = searchBarParams.topMargin
					bottomMargin = searchBarParams.bottomMargin
					gravity = searchBarParams.gravity
				}
			}
			background = searchBar.background?.constantState?.newDrawable(resources)?.mutate()
			hint = if (contentTypeStore.selectedType.value == FavouriteContentType.NOVEL) {
				getString(R.string.search_novel)
			} else {
				getString(R.string.search_manga)
			}
			setTextColor(searchBar.textView.currentTextColor)
			setHintTextColor(searchBar.textView.currentHintTextColor)
			textSize = searchBar.textView.textSize / resources.displayMetrics.scaledDensity
			gravity = Gravity.CENTER_VERTICAL
			isSingleLine = true
			maxLines = 1
			minimumHeight = (56f * density).toInt()
			setPadding((20f * density).toInt(), 0, (20f * density).toInt(), 0)
			setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
			compoundDrawablePadding = resources.getDimensionPixelOffset(R.dimen.margin_small)
			isGone = true
			setText(searchQuery.value)
			doAfterTextChanged { searchQuery.value = it?.toString().orEmpty() }
		}
		parent.addView(edit, parent.indexOfChild(searchBar) + 1)
		inlineSearchEdit = edit
		return edit
	}

	fun attachTabsToAppBar() {
		val header = viewBinding?.layoutCategoryHeader ?: return
		val appBar = (activity as? AppBarOwner)?.appBar ?: return
		if (header.parent === appBar) return
		(header.parent as? ViewGroup)?.removeView(header)
		appBar.addView(
			header,
			AppBarLayout.LayoutParams(
				AppBarLayout.LayoutParams.MATCH_PARENT,
				AppBarLayout.LayoutParams.WRAP_CONTENT,
			).apply {
				scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
					AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS or
					AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
			},
		)
	}

	fun detachTabsFromAppBar() {
		val binding = viewBinding ?: return
		val header = binding.layoutCategoryHeader
		if (header.parent === binding.layoutContent) return
		(header.parent as? ViewGroup)?.removeView(header)
		binding.layoutContent.addView(header, 0)
	}

	companion object {
		private const val STATE_INLINE_SEARCH_ACTIVE = "favourites_inline_search_active"
		private const val STATE_SEARCH_QUERY = "favourites_search_query"
		private const val MAX_CATEGORY_BADGE_COUNT = 99_999
		private const val MENU_CATEGORY_ID_OFFSET = 1
		internal val searchScopeActive = MutableStateFlow(false)
		internal val searchSessionActive = MutableStateFlow(false)
		internal val searchQuery = MutableStateFlow("")
	}
}
