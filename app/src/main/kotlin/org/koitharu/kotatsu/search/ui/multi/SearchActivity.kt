package org.koitharu.kotatsu.search.ui.multi

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.appcompat.view.ActionMode
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.badge.BadgeUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.StatusBarScrim
import org.koitharu.kotatsu.core.ui.widgets.TipView
import org.koitharu.kotatsu.core.util.ShareHelper
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.invalidateNestedItemDecorations
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.ActivitySearchBinding
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.ui.MangaSelectionDecoration
import org.koitharu.kotatsu.list.ui.adapter.MangaListListener
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.DynamicItemSizeResolver
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.search.domain.LANGUAGE_OTHER
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchSourceMode
import org.koitharu.kotatsu.search.ui.multi.adapter.SearchAdapter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

@AndroidEntryPoint
class SearchActivity :
	BaseActivity<ActivitySearchBinding>(),
	MangaListListener,
	ListSelectionController.Callback {

	@Inject
	lateinit var settings: AppSettings

	private val viewModel by viewModels<SearchViewModel>()
	private lateinit var selectionController: ListSelectionController
	private var filterBadge: BadgeDrawable? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivitySearchBinding.inflate(layoutInflater))
		title = when (viewModel.kind) {
			SearchKind.SIMPLE,
			SearchKind.TITLE -> viewModel.query
			SearchKind.AUTHOR -> getString(R.string.inline_preference_pattern, getString(R.string.author), viewModel.query)
			SearchKind.TAG -> getString(R.string.inline_preference_pattern, getString(R.string.genre), viewModel.query)
		}

		val itemClickListener = OnListItemClickListener<SearchResultsListModel> { item, _ ->
			if (item.listFilter == null) {
				router.openSearch(item.source, viewModel.query)
			} else {
				router.openList(item.source, item.listFilter, item.sortOrder)
			}
		}
		val sizeResolver = DynamicItemSizeResolver(resources, this, settings, adjustWidth = true)
		val selectionDecoration = MangaSelectionDecoration(this)
		selectionController = ListSelectionController(
			appCompatDelegate = delegate,
			decoration = selectionDecoration,
			registryOwner = this,
			callback = this,
		)
		val adapter = SearchAdapter(
			listener = this,
			itemClickListener = itemClickListener,
			sizeResolver = sizeResolver,
			selectionDecoration = selectionDecoration,
		)
		with(viewBinding.recyclerView) {
			this.adapter = adapter
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(this@SearchActivity, true))
			addItemDecoration(selectionDecoration)
		}

		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		supportActionBar?.setSubtitle(R.string.search_results)
		addMenuProvider(SearchMenuProvider(this, viewModel))
		viewBinding.statusBarScrim.background = StatusBarScrim.drawable(this)
		viewBinding.toolbar.setOnClickListener { showSearchQueryDialog() }

		viewBinding.chipSearchMode.setOnClickListener { showSourceModeDialog() }
		viewBinding.chipLanguage.setOnClickListener { showLanguageDialog() }
		viewBinding.chipHasResults.setOnCheckedChangeListener { _, checked -> viewModel.setHasResultsOnly(checked) }
		viewBinding.chipFlatView.setOnCheckedChangeListener { _, checked -> viewModel.setFlatView(checked) }
		viewBinding.chipHideLibrary.setOnCheckedChangeListener { _, checked -> viewModel.setHideLibrary(checked) }
		viewBinding.chipResetFilters.setOnClickListener { viewModel.resetFilters() }

		viewModel.sourceMode.observe(this, ::updateSourceModeChip)
		viewModel.preferredLanguages.observe(this) { updateLanguageChip(it) }
		viewModel.hasResultsOnly.observe(this) { viewBinding.chipHasResults.isChecked = it }
		viewModel.flatView.observe(this) { viewBinding.chipFlatView.isChecked = it }
		viewModel.hideLibrary.observe(this) { viewBinding.chipHideLibrary.isChecked = it }
		viewModel.searchProgress.observe(this, ::updateProgress)
		viewModel.hasActiveFilters.observe(this, ::onActiveFiltersChanged)
		viewModel.list.observe(this, adapter)
		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.recyclerView, null))
	}

	private fun showSearchQueryDialog() {
		val editText = TextInputEditText(this).apply {
			setText(viewModel.query)
			setSelection(text?.length ?: 0)
			setSingleLine(true)
			inputType = InputType.TYPE_CLASS_TEXT
			imeOptions = EditorInfo.IME_ACTION_SEARCH
			setHint(R.string.search)
		}
		val padding = resources.getDimensionPixelOffset(R.dimen.margin_normal)
		val container = FrameLayout(this).apply {
			setPadding(padding, padding / 2, padding, 0)
			addView(
				editText,
				FrameLayout.LayoutParams(
					FrameLayout.LayoutParams.MATCH_PARENT,
					FrameLayout.LayoutParams.WRAP_CONTENT,
				),
			)
		}
		val dialog = MaterialAlertDialogBuilder(this)
			.setTitle(R.string.search)
			.setView(container)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.search) { _, _ -> submitSearchQuery(editText.text) }
			.create()
		editText.setOnEditorActionListener { _, actionId, _ ->
			if (actionId == EditorInfo.IME_ACTION_SEARCH) {
				if (submitSearchQuery(editText.text)) dialog.dismiss()
				true
			} else {
				false
			}
		}
		dialog.setOnShowListener {
			editText.requestFocus()
			dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
		}
		dialog.show()
	}

	private fun submitSearchQuery(value: CharSequence?): Boolean {
		val query = value?.toString()?.trim().orEmpty()
		if (query.isEmpty()) return false
		if (query == viewModel.query) return true
		router.openSearch(query, viewModel.kind)
		finish()
		return true
	}

	private fun showSourceModeDialog() {
		val modes = SearchSourceMode.entries
		val labels = modes.map { mode -> getString(mode.labelRes()) }.toTypedArray()
		val checked = modes.indexOf(viewModel.sourceMode.value)
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.search_source_mode)
			.setSingleChoiceItems(labels, checked) { dialog, which ->
				viewModel.setSourceMode(modes[which])
				dialog.dismiss()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun showLanguageDialog() {
		val languages = (viewModel.availableLanguages.value + viewModel.preferredLanguages.value)
			.distinct()
			.sorted()
		if (languages.isEmpty()) return
		val selected = viewModel.preferredLanguages.value.toMutableSet()
		val labels = languages.map(::languageLabel).toTypedArray()
		val checked = BooleanArray(languages.size) { languages[it] in selected }
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.search_filter_language)
			.setMultiChoiceItems(labels, checked) { _, which, isChecked ->
				if (isChecked) selected += languages[which] else selected -= languages[which]
			}
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ -> viewModel.setPreferredLanguages(selected) }
			.show()
	}

	private fun updateSourceModeChip(mode: SearchSourceMode) {
		viewBinding.chipSearchMode.setText(mode.labelRes())
	}

	private fun updateLanguageChip(languages: Set<String>) {
		val value = languages.sorted().joinToString(", ") { code ->
			if (code == LANGUAGE_OTHER) getString(R.string.search_language_other) else code.uppercase(Locale.ROOT)
		}
		viewBinding.chipLanguage.text = getString(R.string.search_filter_language_value, value)
	}

	private fun languageLabel(code: String): String {
		if (code == LANGUAGE_OTHER) return getString(R.string.search_language_other)
		val locale = Locale.forLanguageTag(code)
		val name = locale.getDisplayLanguage(Locale.getDefault()).ifBlank { code.uppercase(Locale.ROOT) }
		return "$name (${code.uppercase(Locale.ROOT)})"
	}

	private fun updateProgress(progress: GlobalSearchProgress) {
		with(viewBinding.progressSearch) {
			isVisible = progress.total > 0 && progress.completed < progress.total
			max = progress.total.coerceAtLeast(1)
			setProgressCompat(progress.completed, true)
		}
	}

	private fun SearchSourceMode.labelRes(): Int = when (this) {
		SearchSourceMode.PINNED_ONLY -> R.string.search_mode_pinned
		SearchSourceMode.PREFERRED_LANGUAGES -> R.string.search_mode_preferred_languages
		SearchSourceMode.ALL_SOURCES -> R.string.search_mode_all_sources
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.toolbar.updatePadding(top = barsInsets.top, left = barsInsets.left, right = barsInsets.right)
		viewBinding.statusBarScrim.updateLayoutParams {
			height = if (settings.isStatusBarHidden) 0 else (barsInsets.top * StatusBarScrim.HEIGHT_FACTOR).roundToInt()
		}
		viewBinding.recyclerView.setPadding(
			barsInsets.left,
			0,
			barsInsets.right,
			barsInsets.bottom,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	private fun onActiveFiltersChanged(hasFilters: Boolean) {
		viewBinding.toolbar.post { applyFilterBadge(hasFilters) }
	}

	private fun applyFilterBadge(hasFilters: Boolean) {
		val item = viewBinding.toolbar.menu.findItem(R.id.action_search_kind) ?: return
		filterBadge?.let { BadgeUtils.detachBadgeDrawable(it, viewBinding.toolbar, R.id.action_search_kind) }
		filterBadge = if (hasFilters) {
			BadgeDrawable.createFromResource(this, R.xml.badge_search_filter).also {
				BadgeUtils.attachBadgeDrawable(it, viewBinding.toolbar, R.id.action_search_kind, viewBinding.toolbarContainer)
			}
		} else null
		item.icon?.mutate()?.setTint(
			MaterialColors.getColor(
				viewBinding.toolbar,
				if (hasFilters) appcompatR.attr.colorPrimary else materialR.attr.colorOnSurfaceVariant,
			),
		)
	}

	override fun onItemClick(item: MangaListModel, view: View) {
		if (!selectionController.onItemClick(item.id)) router.openDetails(item.toMangaWithOverride())
	}

	override fun onItemLongClick(item: MangaListModel, view: View): Boolean =
		selectionController.onItemLongClick(view, item.id)

	override fun onItemContextClick(item: MangaListModel, view: View): Boolean =
		selectionController.onItemContextClick(view, item.id)

	override fun onReadClick(manga: Manga, view: View) {
		if (!selectionController.onItemClick(manga.id)) router.openReader(manga)
	}

	override fun onTagClick(manga: Manga, tag: MangaTag, view: View) {
		if (!selectionController.onItemClick(manga.id)) router.openList(tag)
	}

	override fun onRetryClick(error: Throwable) = viewModel.retry()
	override fun onFilterOptionClick(option: ListFilterOption) = Unit
	override fun onFilterClick(view: View?) = Unit
	override fun onEmptyActionClick() = Unit
	override fun onListHeaderClick(item: ListHeader, view: View) = Unit
	override fun onFooterButtonClick() = Unit
	override fun onPrimaryButtonClick(tipView: TipView) = Unit
	override fun onSecondaryButtonClick(tipView: TipView) = Unit

	override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
		viewBinding.recyclerView.invalidateNestedItemDecorations()
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu,
	): Boolean {
		menuInflater.inflate(R.menu.mode_remote, menu)
		return true
	}

	override fun onPrepareActionMode(
		controller: ListSelectionController,
		mode: ActionMode?,
		menu: Menu,
	): Boolean {
		mode?.title = controller.count.toString()
		menu.findItem(R.id.action_open_browser)?.isEnabled = controller.count == 1
		return true
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_share -> {
				ShareHelper(this).shareMangaLinks(collectSelectedItems())
				mode?.finish()
				true
			}
			R.id.action_favourite -> {
				router.showFavoriteDialog(collectSelectedItems())
				mode?.finish()
				true
			}
			R.id.action_save -> {
				router.showDownloadDialog(collectSelectedItems(), viewBinding.recyclerView)
				mode?.finish()
				true
			}
			R.id.action_open_browser -> {
				collectSelectedItems().singleOrNull()?.let(router::openBrowser)
				mode?.finish()
				true
			}
			else -> false
		}
	}

	private fun collectSelectedItems(): Set<Manga> = viewModel.getItems(selectionController.peekCheckedIds())
}
