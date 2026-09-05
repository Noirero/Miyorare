package org.koitharu.kotatsu.explore.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.view.ActionMode
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.dialog.BigButtonsAlertDialog
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.ActionModeListener
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.ui.util.SpanSizeResolver
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.findAppCompatDelegate
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.recyclerView
import org.koitharu.kotatsu.core.util.ext.setTabsEnabled
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.FragmentExploreBinding
import org.koitharu.kotatsu.explore.data.ExploreContentClass
import org.koitharu.kotatsu.explore.data.ExploreContentFilter
import org.koitharu.kotatsu.explore.data.MihonSourceFilterEntry
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.koitharu.kotatsu.explore.ui.adapter.ExploreAdapter
import org.koitharu.kotatsu.explore.ui.adapter.ExploreListEventListener
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.adapter.bindBadge
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.parsers.model.Manga

@AndroidEntryPoint
class ExploreFragment :
	BaseFragment<FragmentExploreBinding>(),
	ActionModeListener,
	ExploreListEventListener,
	OnListItemClickListener<MangaSourceItem>, ListSelectionController.Callback {

	private val viewModel by viewModels<ExploreViewModel>()
	private var sourceSelectionController: ListSelectionController? = null
	private var manageBadge: BadgeDrawable? = null

	/** Page lists, indexed by page position. Both are created up-front by the pager. */
	private val pages = arrayOfNulls<RecyclerView>(2)
	private var barsInsets: Insets = Insets.NONE

	private data class SourceFilterState(
		val sourceStates: Map<Long, Boolean>,
		val languageStates: Map<String, Boolean>,
	)

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentExploreBinding {
		return FragmentExploreBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: FragmentExploreBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		sourceSelectionController = ListSelectionController(
			appCompatDelegate = checkNotNull(findAppCompatDelegate()),
			decoration = SourceSelectionDecoration(binding.root.context),
			registryOwner = this,
			callback = this,
		)
		val header = binding.header
		val headerAdapter = ExploreAdapter(
			this,
			this,
			mangaClickListener = { manga, _ -> router.openDetails(manga) },
			onTipClose = { viewModel.dismissLanguageTip() },
		)
		with(header.recyclerViewHeader) {
			adapter = headerAdapter
			layoutManager = LinearLayoutManager(context)
			addItemDecoration(TypedListSpacingDecoration(context, false))
		}
		header.buttonManage.setOnClickListener { router.openSourcesCatalog(isExternalOnly = true) }
		header.buttonContentFilterNsfw.isVisible = viewModel.isNsfwVisible.value
		header.toggleContentFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (!isChecked) return@addOnButtonCheckedListener
			val filter = when (checkedId) {
				R.id.button_content_filter_sfw -> ExploreContentFilter.SFW
				R.id.button_content_filter_nsfw -> ExploreContentFilter.NSFW
				else -> ExploreContentFilter.ALL
			}
			if (viewModel.contentFilter.value != filter) {
				viewModel.setContentFilter(filter)
			}
		}

		binding.pager.adapter = ExploreSourcesPagerAdapter(::onPageCreated)
		binding.pager.offscreenPageLimit = 1
		// The pager's internal RecyclerView only handles horizontal paging and does not need to join the
		// vertical nested-scroll chain. The page RecyclerViews below remain nested-scrolling children so
		// the outer Explore header can move away first and the source list can continue scrolling lazily.
		binding.pager.recyclerView?.isNestedScrollingEnabled = false
		// Keep the pager bounded to one viewport. The old wrap-content emulation measured each whole
		// RecyclerView with an UNSPECIFIED height, which inflated every source/favicon on the main thread
		// and could trigger an input-dispatch ANR for large extension libraries.
		binding.pager.updateLayoutParams { height = resources.displayMetrics.heightPixels }
		TabLayoutMediator(header.tabsKind, binding.pager) { tab, position ->
			tab.setText(if (position == 1) R.string.store_kind_novel else R.string.store_kind_manga)
		}.attach()
		actionModeDelegate.addListener(this)
		addMenuProvider(ExploreMenuProvider(router, viewModel, ::showSourceFilterDialog))
		viewModel.headerContent.observe(viewLifecycleOwner, headerAdapter)
		viewModel.contentFilter.observe(viewLifecycleOwner) { filter ->
			val checkedId = when (filter) {
				ExploreContentFilter.SFW -> R.id.button_content_filter_sfw
				ExploreContentFilter.NSFW -> R.id.button_content_filter_nsfw
				ExploreContentFilter.ALL -> R.id.button_content_filter_all
			}
			if (header.toggleContentFilter.checkedButtonId != checkedId) {
				header.toggleContentFilter.check(checkedId)
			}
		}
		viewModel.isNsfwVisible.observe(viewLifecycleOwner) { isVisible ->
			header.buttonContentFilterNsfw.isVisible = isVisible
			if (!isVisible && header.toggleContentFilter.checkedButtonId == R.id.button_content_filter_nsfw) {
				header.toggleContentFilter.check(R.id.button_content_filter_all)
			}
		}
		viewModel.contentState.observe(viewLifecycleOwner) {
			resetSourcePageScrollPositions()
		}
		viewModel.hasExtensionUpdates.observe(viewLifecycleOwner) { hasUpdates ->
			manageBadge = header.buttonManage.bindBadge(manageBadge, if (hasUpdates) "" else null)
		}
		viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.pager, this))
		viewModel.onOpenManga.observeEvent(viewLifecycleOwner, ::onOpenManga)
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.pager))
		viewModel.isGrid.observe(viewLifecycleOwner) { isGrid ->
			pages.forEach { it?.applyLayoutManager(isGrid) }
		}
		viewModel.onShowSuggestionsTip.observeEvent(viewLifecycleOwner) {
			showSuggestionsTip()
		}
	}

	private fun onPageCreated(recyclerView: RecyclerView, isNovel: Boolean) {
		val pageIndex = if (isNovel) 1 else 0
		val adapter = ExploreAdapter(
			this,
			this,
			mangaClickListener = { manga, _ -> router.openDetails(manga) },
			onTipClose = { viewModel.dismissLanguageTip() },
		)
		with(recyclerView) {
			this.adapter = adapter
			SpanSizeResolver(this, resources.getDimensionPixelSize(R.dimen.explore_grid_width)).attach()
			addItemDecoration(TypedListSpacingDecoration(context, false))
			checkNotNull(sourceSelectionController).attachToRecyclerView(this)
			isNestedScrollingEnabled = true
			applyLayoutManager(viewModel.isGrid.value)
		}
		pages[pageIndex] = recyclerView
		viewModel.sources.observe(viewLifecycleOwner) { content ->
			adapter.emit(content[isNovel])
		}
	}

	private fun resetSourcePageScrollPositions() {
		pages.forEach { recyclerView ->
			recyclerView?.postOnAnimation {
				recyclerView.resetPageScrollPosition()
			}
		}
	}

	private fun RecyclerView.applyLayoutManager(isGrid: Boolean) {
		val adapter = adapter as? ExploreAdapter ?: return
		layoutManager = if (isGrid) {
			GridLayoutManager(context, 4).also { lm ->
				lm.spanSizeLookup = ExploreGridSpanSizeLookup(adapter, lm)
			}
		} else {
			LinearLayoutManager(context)
		}
		resetPageScrollPosition()
	}

	private fun RecyclerView.resetPageScrollPosition() {
		stopScroll()
		(layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		barsInsets = insets.systemBarsInsets
		val basePadding = v.resources.getDimensionPixelOffset(R.dimen.list_spacing_normal)
		viewBinding?.layoutContent?.setPadding(
			/* left = */ barsInsets.left + basePadding,
			/* top = */ basePadding,
			/* right = */ barsInsets.right + basePadding,
			/* bottom = */ barsInsets.bottom + basePadding,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onDestroyView() {
		actionModeDelegate.removeListener(this)
		pages.fill(null)
		manageBadge = null
		sourceSelectionController = null
		super.onDestroyView()
	}

	override fun onActionModeStarted(mode: ActionMode) {
		viewBinding?.pager?.isUserInputEnabled = false
		viewBinding?.header?.tabsKind?.setTabsEnabled(false)
		viewBinding?.header?.toggleContentFilter?.isEnabled = false
	}

	override fun onActionModeFinished(mode: ActionMode) {
		viewBinding?.pager?.isUserInputEnabled = true
		viewBinding?.header?.tabsKind?.setTabsEnabled(true)
		viewBinding?.header?.toggleContentFilter?.isEnabled = true
	}

	override fun onListHeaderClick(item: ListHeader, view: View) {
		when (item.payload) {
			R.id.nav_suggestions -> router.openSuggestions()
			ExploreViewModel.HEADER_CONTENT_CLASSIFICATION -> Unit
			ExploreViewModel.HEADER_LANGUAGE_GROUP -> Unit
			else -> router.openSourcesCatalog(isExternalOnly = true)
		}
	}

	private fun showSourceFilterDialog() {
		val entries = viewModel.sourceFilters.value
		val context = requireContext()
		val padding = (20 * resources.displayMetrics.density).toInt()
		val rowPadding = (12 * resources.displayMetrics.density).toInt()
		val content = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(padding, rowPadding, padding, rowPadding)
		}
		content.addView(TextView(context).apply {
			setText(R.string.source_filter_summary)
			setPadding(0, 0, 0, rowPadding)
		})
		val stateProvider = if (entries.isEmpty()) {
			content.addView(TextView(context).apply { setText(R.string.no_external_source_installed) })
			null
		} else {
			addSourceFilterControls(content, entries)
		}
		val scroll = ScrollView(context).apply { addView(content) }
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.source_filter)
			.setView(scroll)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				stateProvider?.invoke()?.let { state ->
					val sourceChanged = entries.any { entry ->
						state.sourceStates[entry.source.sourceId] != entry.isSourceEnabled
					}
					val languageChanged = entries.any { entry ->
						val language = entry.source.language.ifBlank { "other" }.lowercase()
						state.languageStates[language] != entry.isLanguageEnabled
					}
					if (sourceChanged || languageChanged) {
						viewModel.applyMihonSourceFilter(state.sourceStates, state.languageStates)
						resetSourcePageScrollPositions()
					}
				}
			}
			.show()
	}

	private fun addSourceFilterControls(
		container: LinearLayout,
		entries: List<MihonSourceFilterEntry>,
	): () -> SourceFilterState {
		val context = container.context
		val rowPadding = (12 * resources.displayMetrics.density).toInt()
		fun header(text: CharSequence) {
			container.addView(TextView(context).apply {
				this.text = text
				setPadding(0, rowPadding, 0, rowPadding / 2)
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
			})
		}
		fun allLabel(section: String): String {
			val all = getExternalExtensionLanguageDisplayName("all")
			return "$all ${section.replaceFirstChar { it.lowercase() }}"
		}

		val byLanguage = entries.groupBy { it.source.language.ifBlank { "other" }.lowercase() }
		val languageStates = linkedMapOf<String, Boolean>()
		byLanguage.forEach { (language, languageEntries) ->
			languageStates[language] = languageEntries.firstOrNull()?.isLanguageEnabled != false
		}
		val sourceStates = linkedMapOf<Long, Boolean>()
		entries.forEach { entry -> sourceStates[entry.source.sourceId] = entry.isSourceEnabled }

		val languageSwitches = linkedMapOf<String, SwitchMaterial>()
		val sourceSwitches = linkedMapOf<Long, SwitchMaterial>()
		var languageAllSwitch: SwitchMaterial? = null
		var sourceAllSwitch: SwitchMaterial? = null
		var updating = false

		fun setSwitchesCheckedFast(switches: Collection<SwitchMaterial>, checked: Boolean) {
			val wasUpdating = updating
			updating = true
			for (toggle in switches) {
				if (toggle.isChecked == checked) continue
				toggle.isChecked = checked
				// Avoid running hundreds of thumb animations during a bulk operation.
				toggle.jumpDrawablesToCurrentState()
			}
			updating = wasUpdating
		}

		fun updateLanguageAllSwitch() {
			val wasUpdating = updating
			updating = true
			languageAllSwitch?.isChecked = languageStates.isNotEmpty() && languageStates.values.all { it }
			updating = wasUpdating
		}

		fun updateSourceAllSwitch() {
			val wasUpdating = updating
			updating = true
			sourceAllSwitch?.isChecked = sourceStates.isNotEmpty() && sourceStates.values.all { it }
			updating = wasUpdating
		}

		header(getString(R.string.source_filter_languages))
		languageAllSwitch = SwitchMaterial(context).apply {
			text = allLabel(getString(R.string.source_filter_languages))
			isChecked = languageStates.isNotEmpty() && languageStates.values.all { it }
			setPadding(0, rowPadding / 2, 0, rowPadding / 2)
			setOnCheckedChangeListener { _, checked ->
				if (updating) return@setOnCheckedChangeListener
				languageStates.keys.toList().forEach { languageStates[it] = checked }
				setSwitchesCheckedFast(languageSwitches.values, checked)
			}
		}
		container.addView(languageAllSwitch)

		byLanguage.entries
			.sortedBy { getExternalExtensionLanguageDisplayName(it.key) }
			.forEach { (language, languageEntries) ->
				val toggle = SwitchMaterial(context).apply {
					text = getExternalExtensionLanguageDisplayName(language)
					isChecked = languageEntries.firstOrNull()?.isLanguageEnabled != false
					setPadding(0, rowPadding / 2, 0, rowPadding / 2)
					setOnCheckedChangeListener { _, checked ->
						if (updating) return@setOnCheckedChangeListener
						languageStates[language] = checked
						updateLanguageAllSwitch()
					}
				}
				languageSwitches[language] = toggle
				container.addView(toggle)
			}

		header(getString(R.string.source_filter_individual))
		sourceAllSwitch = SwitchMaterial(context).apply {
			text = allLabel(getString(R.string.source_filter_individual))
			isChecked = sourceStates.isNotEmpty() && sourceStates.values.all { it }
			setPadding(0, rowPadding / 2, 0, rowPadding / 2)
			setOnCheckedChangeListener { _, checked ->
				if (updating) return@setOnCheckedChangeListener
				sourceStates.keys.toList().forEach { sourceStates[it] = checked }
				setSwitchesCheckedFast(sourceSwitches.values, checked)
			}
		}
		container.addView(sourceAllSwitch)

		val searchInput = TextInputEditText(context).apply {
			isSingleLine = true
			setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
		}
		container.addView(
			TextInputLayout(context).apply {
				hint = getString(R.string.search_extensions)
				setStartIconDrawable(R.drawable.ic_search)
				endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
				isHintEnabled = true
				addView(
					searchInput,
					LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.MATCH_PARENT,
						LinearLayout.LayoutParams.WRAP_CONTENT,
					),
				)
			},
			LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			).apply { topMargin = rowPadding / 2 },
		)

		val alphabetGroup = ChipGroup(context).apply {
			isSingleSelection = true
			isSelectionRequired = true
			isSingleLine = true
		}
		container.addView(
			HorizontalScrollView(context).apply {
				isHorizontalScrollBarEnabled = false
				addView(alphabetGroup)
			},
			LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			).apply { topMargin = rowPadding / 2 },
		)

		val noMatches = TextView(context).apply {
			setText(R.string.no_matching_extensions)
			setPadding(0, rowPadding, 0, rowPadding)
			isVisible = false
		}
		container.addView(noMatches)

		var selectedInitial: Char? = null
		var searchText = ""
		val normalizedNames = entries.associate { entry ->
			entry.source.sourceId to entry.source.displayName.trim().lowercase()
		}

		fun updateVisibleSources() {
			var visibleCount = 0
			for ((sourceId, toggle) in sourceSwitches) {
				val name = normalizedNames[sourceId].orEmpty()
				val matchesSearch = searchText.isBlank() || searchText in name
				val matchesInitial = selectedInitial == null || name.firstOrNull()?.uppercaseChar() == selectedInitial
				toggle.isVisible = matchesSearch && matchesInitial
				if (toggle.isVisible) visibleCount++
			}
			noMatches.isVisible = visibleCount == 0
		}

		fun addInitialChip(label: String, initial: Char?, enabled: Boolean = true): Chip {
			return Chip(context).apply {
				id = View.generateViewId()
				text = label
				isCheckable = true
				isEnabled = enabled
				setOnClickListener {
					selectedInitial = initial
					updateVisibleSources()
				}
				alphabetGroup.addView(this)
			}
		}

		val allInitialsChip = addInitialChip(getString(R.string.all_short), null)
		val availableInitials = normalizedNames.values.mapNotNullTo(HashSet()) {
			it.firstOrNull()?.uppercaseChar()?.takeIf { char -> char in 'A'..'Z' }
		}
		for (initial in 'A'..'Z') {
			addInitialChip(initial.toString(), initial, initial in availableInitials)
		}
		alphabetGroup.check(allInitialsChip.id)
		searchInput.doAfterTextChanged {
			searchText = it?.toString().orEmpty().trim().lowercase()
			updateVisibleSources()
		}

		entries.sortedWith(
			compareBy<MihonSourceFilterEntry> { getExternalExtensionLanguageDisplayName(it.source.language) }
				.thenBy { it.source.displayName.lowercase() },
		).forEach { entry ->
			val toggle = SwitchMaterial(context).apply {
				text = "${entry.source.displayName} — ${entry.source.languageDisplayName}"
				isChecked = entry.isSourceEnabled
				setPadding(0, rowPadding / 2, 0, rowPadding / 2)
				setOnCheckedChangeListener { _, checked ->
					if (updating) return@setOnCheckedChangeListener
					sourceStates[entry.source.sourceId] = checked
					updateSourceAllSwitch()
				}
			}
			sourceSwitches[entry.source.sourceId] = toggle
			container.addView(toggle)
		}
		updateVisibleSources()

		return {
			SourceFilterState(
				sourceStates = sourceStates.toMap(),
				languageStates = languageStates.toMap(),
			)
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_local -> router.openList(LocalMangaSource, null, null)
			R.id.button_bookmarks -> router.openBookmarks()
			R.id.button_downloads -> router.openDownloads()
		}
	}

	override fun onItemClick(item: MangaSourceItem, view: View) {
		if (sourceSelectionController?.onItemClick(item.id) == true) {
			return
		}
		router.openList(item.source, null, null)
	}

	override fun onItemLongClick(item: MangaSourceItem, view: View): Boolean {
		return sourceSelectionController?.onItemLongClick(view, item.id) == true
	}

	override fun onItemContextClick(item: MangaSourceItem, view: View): Boolean {
		return sourceSelectionController?.onItemContextClick(view, item.id) == true
	}

	override fun onRetryClick(error: Throwable) = Unit

	override fun onEmptyActionClick() {
		router.openSourcesCatalog(isExternalOnly = true)
	}

	override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
		pages.forEach { it?.invalidateItemDecorations() }
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu,
	): Boolean {
		menuInflater.inflate(R.menu.mode_source, menu)
		return true
	}

	override fun onPrepareActionMode(controller: ListSelectionController, mode: ActionMode?, menu: Menu): Boolean {
		val selectedSources = viewModel.sourcesSnapshot(controller.peekCheckedIds())
		val isSingleSelection = selectedSources.size == 1
		menu.findItem(R.id.action_settings)?.isVisible = isSingleSelection
		menu.findItem(R.id.action_shortcut)?.isVisible = isSingleSelection
		menu.findItem(R.id.action_pin)?.isVisible = selectedSources.all { !it.isPinned }
		menu.findItem(R.id.action_unpin)?.isVisible = selectedSources.all { it.isPinned }
		menu.findItem(R.id.action_mark_sfw)?.isVisible = selectedSources.isNotEmpty()
		menu.findItem(R.id.action_mark_nsfw)?.isVisible = selectedSources.isNotEmpty()
		menu.findItem(R.id.action_reset_content_classification)?.isVisible =
			viewModel.hasManualContentClassification(selectedSources)
		menu.findItem(R.id.action_disable)?.isVisible = false
		menu.findItem(R.id.action_delete)?.isVisible = false
		return super.onPrepareActionMode(controller, mode, menu)
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		val selectedSources = viewModel.sourcesSnapshot(controller.peekCheckedIds())
		if (selectedSources.isEmpty()) {
			return false
		}
		when (item.itemId) {
			R.id.action_settings -> {
				val source = selectedSources.singleOrNull() ?: return false
				router.openSourceSettings(source)
				mode?.finish()
			}

			R.id.action_shortcut -> {
				val source = selectedSources.singleOrNull() ?: return false
				viewModel.requestPinShortcut(source)
				mode?.finish()
			}

			R.id.action_pin -> {
				viewModel.setSourcesPinned(selectedSources, isPinned = true)
				mode?.finish()
			}

			R.id.action_unpin -> {
				viewModel.setSourcesPinned(selectedSources, isPinned = false)
				mode?.finish()
			}

			R.id.action_mark_sfw -> {
				viewModel.setContentClassification(selectedSources, ExploreContentClass.SFW)
				mode?.finish()
			}

			R.id.action_mark_nsfw -> {
				viewModel.setContentClassification(selectedSources, ExploreContentClass.NSFW)
				mode?.finish()
			}

			R.id.action_reset_content_classification -> {
				viewModel.setContentClassification(selectedSources, null)
				mode?.finish()
			}

			R.id.action_hide -> {
				viewModel.hideSources(selectedSources)
				mode?.finish()
			}

			else -> return false
		}
		return true
	}

	private fun onOpenManga(manga: Manga) {
		router.openDetails(manga)
	}

	private fun showSuggestionsTip() {
		val listener = DialogInterface.OnClickListener { _, which ->
			viewModel.respondSuggestionTip(which == DialogInterface.BUTTON_POSITIVE)
		}
		BigButtonsAlertDialog.Builder(requireContext())
			.setIcon(R.drawable.ic_suggestion)
			.setTitle(R.string.suggestions_enable_prompt)
			.setPositiveButton(R.string.enable, listener)
			.setNegativeButton(R.string.no_thanks, listener)
			.create()
			.show()
	}
}