package org.koitharu.kotatsu.alternatives.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import coil3.ImageLoader
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.ActivityAlternativesBinding
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.ListStateHolderListener
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.adapter.buttonFooterAD
import org.koitharu.kotatsu.list.ui.adapter.emptyStateListAD
import org.koitharu.kotatsu.list.ui.adapter.listHeaderAD
import org.koitharu.kotatsu.list.ui.adapter.loadingFooterAD
import org.koitharu.kotatsu.list.ui.adapter.loadingStateAD
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.search.domain.LANGUAGE_OTHER
import org.koitharu.kotatsu.search.domain.SearchSourceMode
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class AlternativesActivity : BaseActivity<ActivityAlternativesBinding>(),
	ListStateHolderListener,
	OnListItemClickListener<MangaAlternativeModel> {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by viewModels<AlternativesViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityAlternativesBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		viewBinding.collapsingToolbarLayout.subtitle = null
		val listAdapter = BaseListAdapter<ListModel>()
			.addDelegate(ListItemType.HEADER, listHeaderAD(null))
			.addDelegate(ListItemType.MANGA_LIST_DETAILED, alternativeAD(coil, this, this))
			.addDelegate(ListItemType.STATE_EMPTY, emptyStateListAD(null))
			.addDelegate(ListItemType.FOOTER_LOADING, loadingFooterAD())
			.addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
			.addDelegate(ListItemType.FOOTER_BUTTON, buttonFooterAD(this))
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, addHorizontalPadding = false))
			adapter = listAdapter
		}

		setupSearchEditor()

		viewModel.hasPinnedSources.observe(this) { hasPinned ->
			viewBinding.buttonScopePinned.isEnabled = hasPinned
		}
		viewBinding.toggleSearchScope.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (!isChecked) return@addOnButtonCheckedListener
			viewModel.setSearchMode(
				when (checkedId) {
					R.id.button_scope_pinned -> SearchSourceMode.PINNED_ONLY
					R.id.button_scope_preferred -> SearchSourceMode.PREFERRED_LANGUAGES
					else -> SearchSourceMode.ALL_SOURCES
				},
			)
		}
		viewBinding.chipAlternativeLanguage.setOnClickListener { showLanguageDialog() }
		viewBinding.chipAlternativeHasResults.setOnCheckedChangeListener { _, checked ->
			viewModel.setHasResultsOnly(checked)
		}
		viewBinding.chipAlternativeReset.setOnClickListener { viewModel.resetFilters() }

		viewModel.query.observe(this) { query ->
			if (viewBinding.inputAlternativeQuery.text?.toString() != query) {
				viewBinding.inputAlternativeQuery.setText(query)
				viewBinding.inputAlternativeQuery.setSelection(query.length)
			}
		}
		viewModel.titleSuggestions.observe(this) { updateQueryChips() }
		viewModel.recentQueries.observe(this) { updateQueryChips() }
		viewModel.isSearchRunning.observe(this) { running ->
			viewBinding.buttonAlternativeSearch.isVisible = !running
			viewBinding.buttonAlternativeStop.isVisible = running
			updateProgress(viewModel.searchProgress.value)
		}
		viewModel.searchMode.observe(this, ::updateSearchMode)
		viewModel.preferredLanguages.observe(this, ::updateLanguageChip)
		viewModel.hasResultsOnly.observe(this) { viewBinding.chipAlternativeHasResults.isChecked = it }
		viewModel.searchProgress.observe(this, ::updateProgress)
		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.recyclerView, null))
		viewModel.list.observe(this, listAdapter)
		viewModel.onMigrated.observeEvent(this) {
			Toast.makeText(this, R.string.migration_completed, Toast.LENGTH_SHORT).show()
			router.openDetails(it)
			finishAfterTransition()
		}
	}

	private fun setupSearchEditor() {
		viewBinding.inputAlternativeQuery.setText(viewModel.query.value)
		viewBinding.inputAlternativeQuery.setSelection(viewBinding.inputAlternativeQuery.text?.length ?: 0)
		viewBinding.layoutAlternativeQuery.helperText = getString(
			if (viewModel.manga.source.isNovelSource) {
				R.string.alternative_search_scope_novel
			} else {
				R.string.alternative_search_scope_manga
			},
		)
		viewBinding.buttonAlternativeSearch.setOnClickListener { submitAlternativeSearch() }
		viewBinding.buttonAlternativeStop.setOnClickListener { viewModel.stopSearch() }
		viewBinding.inputAlternativeQuery.setOnEditorActionListener { _, actionId, event ->
			val isKeyboardSearch = actionId == EditorInfo.IME_ACTION_SEARCH
			val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
			if (isKeyboardSearch || isEnter) {
				submitAlternativeSearch()
				true
			} else {
				false
			}
		}
		updateQueryChips()
	}

	private fun submitAlternativeSearch() {
		val query = viewBinding.inputAlternativeQuery.text?.toString()?.trim().orEmpty()
		if (query.isEmpty()) return
		viewModel.search(query)
		viewBinding.inputAlternativeQuery.clearFocus()
	}

	private fun updateQueryChips() {
		val group = viewBinding.chipGroupAlternativeQueries
		group.removeAllViews()
		val suggestions = viewModel.titleSuggestions.value
		for (title in suggestions) {
			group.addView(createQueryChip(title, title))
		}
		for (recent in viewModel.recentQueries.value) {
			if (suggestions.any { it.equals(recent, ignoreCase = true) }) continue
			group.addView(createQueryChip("↶ $recent", recent))
		}
		viewBinding.scrollAlternativeQuerySuggestions.isVisible = group.childCount > 0
	}

	private fun createQueryChip(label: String, query: String): Chip = Chip(this).apply {
		text = label
		isCheckable = false
		setOnClickListener {
			viewBinding.inputAlternativeQuery.setText(query)
			viewBinding.inputAlternativeQuery.setSelection(query.length)
			viewModel.search(query)
			viewBinding.inputAlternativeQuery.clearFocus()
		}
	}

	private fun updateSearchMode(mode: SearchSourceMode) {
		val checkedId = when (mode) {
			SearchSourceMode.PINNED_ONLY -> R.id.button_scope_pinned
			SearchSourceMode.PREFERRED_LANGUAGES -> R.id.button_scope_preferred
			SearchSourceMode.ALL_SOURCES -> R.id.button_scope_all
		}
		if (viewBinding.toggleSearchScope.checkedButtonId != checkedId) {
			viewBinding.toggleSearchScope.check(checkedId)
		}
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

	private fun updateLanguageChip(languages: Set<String>) {
		val value = languages.sorted().joinToString(", ") { code ->
			if (code == LANGUAGE_OTHER) getString(R.string.search_language_other) else code.uppercase(Locale.ROOT)
		}
		viewBinding.chipAlternativeLanguage.text = getString(R.string.search_filter_language_value, value)
	}

	private fun languageLabel(code: String): String {
		if (code == LANGUAGE_OTHER) return getString(R.string.search_language_other)
		val locale = Locale.forLanguageTag(code)
		val name = locale.getDisplayLanguage(Locale.getDefault()).ifBlank { code.uppercase(Locale.ROOT) }
		return "$name (${code.uppercase(Locale.ROOT)})"
	}

	private fun updateProgress(progress: AlternativeSearchProgress) {
		val running = viewModel.isSearchRunning.value
		with(viewBinding.progressAlternativeSearch) {
			isVisible = running && progress.total > 0 && progress.completed < progress.total
			max = progress.total.coerceAtLeast(1)
			setProgressCompat(progress.completed, true)
		}
		viewBinding.collapsingToolbarLayout.subtitle = when {
			progress.total <= 0 -> null
			!running && progress.completed < progress.total -> getString(
				R.string.alternative_search_stopped,
				progress.completed,
				progress.total,
				progress.errors,
			)
			else -> getString(
				R.string.search_progress_sources,
				progress.completed,
				progress.total,
				progress.errors,
			)
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.recyclerView.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			bottom = barsInsets.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			top = barsInsets.top,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onItemClick(item: MangaAlternativeModel, view: View) {
		when (view.id) {
			R.id.chip_source -> router.openSearch(item.manga.source, viewModel.query.value)
			R.id.button_migrate -> confirmMigration(item.manga)
			else -> router.openDetails(item.manga)
		}
	}

	override fun onItemLongClick(item: MangaAlternativeModel, view: View): Boolean {
		router.openBrowser(item.manga)
		return true
	}

	override fun onRetryClick(error: Throwable) = viewModel.retry()
	override fun onEmptyActionClick() = Unit
	override fun onFooterButtonClick() = Unit

	private fun confirmMigration(target: Manga) {
		buildAlertDialog(this, isCentered = true) {
			setIcon(R.drawable.ic_swap)
			setTitle(R.string.manga_migration)
			setMessage(
				getString(
					R.string.migrate_confirmation,
					viewModel.manga.title,
					viewModel.manga.source.getTitle(context),
					target.title,
					target.source.getTitle(context),
				),
			)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.migrate) { _, _ -> viewModel.migrate(target) }
		}.show()
	}
}
