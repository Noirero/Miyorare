package org.koitharu.kotatsu.settings.sources.migration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.doOnDetach
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.google.android.material.appbar.AppBarLayout
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.MigrationDirectSearchOptions
import org.koitharu.kotatsu.alternatives.domain.MigrationDirectTitleMode
import org.koitharu.kotatsu.alternatives.domain.MigrationMatchCandidate
import org.koitharu.kotatsu.alternatives.domain.MigrationMatchState
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchPhase
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchScope
import org.koitharu.kotatsu.alternatives.domain.MigrationSourceOrigin
import org.koitharu.kotatsu.alternatives.domain.migrationSourceOrigin
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import javax.inject.Inject

@AndroidEntryPoint
class MigrationReviewFragment : BaseComposeSettingsFragment(R.string.manga_migration) {
	private val viewModel by viewModels<MigrationReviewViewModel>()

	@Inject
	lateinit var imageLoader: ImageLoader

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
		ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				MiyorareTheme {
					val state by viewModel.state.collectAsState()
					MigrationReviewScreen(
						state = state,
						imageLoader = imageLoader,
						onRetry = viewModel::retry,
						onMigrate = viewModel::migrate,
						onOpenResult = { router.openDetails(it) },
						onOpenCandidate = { router.openDetails(it) },
						onScopeChange = viewModel::setSearchScope,
						onSearch = viewModel::search,
						onManualSearch = viewModel::enableManualSearch,
						onReloadSources = viewModel::reloadSearchSources,
						onSetSourcesSelected = viewModel::setSourcesSelected,
						onOptionsChange = viewModel::updateDirectOptions,
						onBackToSources = viewModel::backToSourceSelection,
					)
				}
			}
		}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		val appBar = (requireActivity() as AppBarOwner).appBar
		val listener = AppBarLayout.OnOffsetChangedListener { bar, offset ->
			view.updatePadding(bottom = bar.totalScrollRange + offset)
		}
		appBar.addOnOffsetChangedListener(listener)
		view.doOnDetach { appBar.removeOnOffsetChangedListener(listener) }
	}

	companion object {
		const val ARG_MANGA_ID = "migration_manga_id"
		fun args(mangaId: Long) = Bundle(1).apply { putLong(ARG_MANGA_ID, mangaId) }
	}
}

@Composable
internal fun MigrationReviewScreen(
	state: MigrationReviewState,
	imageLoader: ImageLoader,
	onRetry: () -> Unit,
	onMigrate: (Manga, Boolean) -> Unit,
	onOpenResult: (Long) -> Unit,
	onOpenCandidate: (Manga) -> Unit,
	onScopeChange: (MigrationSearchScope) -> Unit = {},
	onSearch: () -> Unit = {},
	onManualSearch: () -> Unit = {},
	onReloadSources: () -> Unit = {},
	onSetSourcesSelected: (Set<String>, Boolean) -> Unit = { _, _ -> },
	onOptionsChange: (MigrationDirectSearchOptions) -> Unit = {},
	onBackToSources: () -> Unit = {},
) {
	var selectedSource by rememberSaveable { mutableStateOf<String?>(null) }
	var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
	var sourceQuery by rememberSaveable { mutableStateOf("") }
	var advancedExpanded by rememberSaveable { mutableStateOf(false) }
	val discovery = state.discovery
	val finalDecision = !state.loading && state.hasSearched && !state.searchFailed
	val ready = finalDecision && discovery != null && !discovery.incomplete &&
		discovery.assessment?.state == MigrationMatchState.READY && !state.manualSearchEnabled
	val candidates = when {
		state.searching -> discovery?.assessment?.candidates.orEmpty()
		!state.hasSearched -> emptyList()
		ready -> listOfNotNull(discovery?.assessment?.best)
		else -> discovery?.assessment?.candidates.orEmpty()
	}
	val selected = candidates.firstOrNull { it.manga.id == selectedId && it.manga.source.name == selectedSource }
	val context = LocalContext.current

	LaunchedEffect(state.hasSearched, state.searching, state.changed) {
		if (!state.hasSearched || state.changed) {
			selectedSource = null
			selectedId = null
		}
	}
	LaunchedEffect(state.hasSearched, state.searching, state.directOptions.autoSelectStrongestResult, candidates) {
		if (state.manualSearchEnabled && state.hasSearched && !state.searching &&
			state.directOptions.autoSelectStrongestResult && selectedSource == null) {
			candidates.maxByOrNull { it.evidence.score }?.let {
				selectedSource = it.manga.source.name
				selectedId = it.manga.id
			}
		}
	}

	Column(
		Modifier.fillMaxSize().nestedScroll(rememberNestedScrollInteropConnection()).verticalScroll(rememberScrollState())
			.navigationBarsPadding().padding(20.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		state.completedId?.let { id ->
			Text(stringResource(R.string.migration_completed), style = MaterialTheme.typography.titleLarge)
			Button(onClick = { onOpenResult(id) }) { Text(stringResource(R.string.migration_open_result)) }
			return@Column
		}
		discovery?.seed?.let { seed ->
			Text(seed.title, style = MaterialTheme.typography.titleLarge)
			Text(stringResource(R.string.migration_current_source, seed.source.getTitle(context)))
		}

		if (!state.loading && !state.sourceSelectionLoading && discovery != null &&
			!state.currentSourceChecked && !state.manualSearchEnabled && !state.checkFailed) {
			Text(stringResource(R.string.migration_choose_path), style = MaterialTheme.typography.titleMedium)
			Text(stringResource(R.string.migration_choose_path_description), style = MaterialTheme.typography.bodyMedium)
			Button(
				onClick = onRetry,
				modifier = Modifier.fillMaxWidth().testTag("migration-check-current"),
			) { Text(stringResource(R.string.migration_check_current_action)) }
			OutlinedButton(
				onClick = onManualSearch,
				modifier = Modifier.fillMaxWidth().testTag("migration-direct-search"),
			) { Text(stringResource(R.string.migration_search_direct_action)) }
			return@Column
		}

		if (state.checkFailed) {
			Text(stringResource(R.string.migration_check_failed), color = MaterialTheme.colorScheme.error)
			TextButton(onClick = onRetry, modifier = Modifier.testTag("migration-check-retry")) {
				Text(stringResource(R.string.migration_retry))
			}
			OutlinedButton(
				onClick = onManualSearch,
				modifier = Modifier.fillMaxWidth().testTag("migration-direct-after-check-failure"),
			) { Text(stringResource(R.string.migration_search_direct_action)) }
			return@Column
		}

		if (state.sourceSelectionLoading) {
			CircularProgressIndicator(Modifier.testTag("migration-source-list-loading"))
			Text(stringResource(R.string.migration_loading_sources))
			return@Column
		}

		if (state.sourceSelectionFailed) {
			Text(stringResource(R.string.migration_source_list_failed), color = MaterialTheme.colorScheme.error)
			Button(onClick = onReloadSources, modifier = Modifier.testTag("migration-reload-sources")) {
				Text(stringResource(R.string.migration_retry))
			}
			return@Column
		}

		val sourcePickerRequested = !state.hasSearched &&
			(state.manualSearchEnabled || (state.currentSourceChecked && discovery?.currentSourceReadable == false))
		if (sourcePickerRequested && state.availableSources.isEmpty()) {
			Text(stringResource(R.string.migration_no_sources_available), color = MaterialTheme.colorScheme.error)
			if (state.sourcePoolFailureCount > 0) {
				Text(stringResource(R.string.migration_partial_provider_pool), style = MaterialTheme.typography.bodySmall)
			}
			Button(onClick = onReloadSources, modifier = Modifier.testTag("migration-reload-sources")) {
				Text(stringResource(R.string.migration_retry))
			}
			return@Column
		}

		val showingSourcePicker = sourcePickerRequested && state.availableSources.isNotEmpty()
		if (showingSourcePicker) {
			if (state.manualSearchEnabled) {
				Text(stringResource(R.string.migration_direct_choose_sources), style = MaterialTheme.typography.titleMedium)
				Text(stringResource(R.string.migration_direct_choose_sources_description), style = MaterialTheme.typography.bodyMedium)
			} else {
				Text(stringResource(R.string.migration_source_unavailable))
				Text(stringResource(R.string.migration_checked_choose_sources), style = MaterialTheme.typography.bodyMedium)
			}
			MigrationSourcePicker(
				sources = state.availableSources,
				selectedSourceNames = state.selectedSourceNames,
				selectedScope = state.selectedScope,
				sourceQuery = sourceQuery,
				onSourceQueryChange = { sourceQuery = it },
				onScopeChange = onScopeChange,
				onSetSourcesSelected = onSetSourcesSelected,
			)
			if (state.sourcePoolFailureCount > 0) {
				Text(stringResource(R.string.migration_partial_provider_pool), style = MaterialTheme.typography.bodySmall)
			}
			if (state.manualSearchEnabled) {
				MigrationAdvancedOptions(
					options = state.directOptions,
					expanded = advancedExpanded,
					onExpandedChange = { advancedExpanded = it },
					onOptionsChange = onOptionsChange,
				)
			}
			Text(
				stringResource(R.string.migration_sources_selected, state.selectedSourceNames.size),
				style = MaterialTheme.typography.bodySmall,
			)
			Button(
				onClick = onSearch,
				enabled = state.selectedSourceNames.isNotEmpty(),
				modifier = Modifier.fillMaxWidth().testTag("migration-search"),
			) { Text(stringResource(R.string.migration_search_replacements)) }
			return@Column
		}

		if (state.currentSourceChecked && discovery?.currentSourceReadable == true && !state.manualSearchEnabled) {
			Text(stringResource(R.string.migration_source_healthy))
			TextButton(onClick = onManualSearch, modifier = Modifier.testTag("migration-manual-search")) {
				Text(stringResource(R.string.migration_find_another_source))
			}
			return@Column
		}

		if (state.loading) {
			CircularProgressIndicator(Modifier.testTag("migration-loading"))
			when (val progress = discovery?.progress) {
				null -> Text(stringResource(if (state.manualSearchEnabled) R.string.migration_searching_sources else R.string.migration_checking))
				else -> when (progress.phase) {
					MigrationSearchPhase.CHECKING_SOURCE -> {
						val sourceTitle = discovery.seed.source.getTitle(context)
						Text(stringResource(R.string.migration_checking_source, sourceTitle))
					}
					MigrationSearchPhase.SEARCHING_SOURCES -> {
						Text(
							if (progress.totalSources > 0) {
								stringResource(R.string.migration_searching_sources_progress, progress.completedSources, progress.totalSources)
							} else stringResource(R.string.migration_searching_sources),
						)
						progress.query?.let { query ->
							Text(stringResource(R.string.migration_search_query, query), style = MaterialTheme.typography.bodySmall)
						}
					}
					MigrationSearchPhase.VERIFYING_CANDIDATES -> {
						Text(stringResource(R.string.migration_verifying_candidates, progress.candidateCount))
					}
				}
			}
			if (state.searching && candidates.isNotEmpty()) {
				MigrationCandidateGroups(
					candidates = candidates,
					selected = selected,
					imageLoader = imageLoader,
					showEvidence = !state.manualSearchEnabled,
					onSelect = { candidate ->
						selectedSource = candidate.manga.source.name
						selectedId = candidate.manga.id
					},
					onOpenCandidate = onOpenCandidate,
				)
			}
			return@Column
		}

		if (state.changed) {
			Text(
				stringResource(if (state.manualSearchEnabled) R.string.migration_selected_result_changed else R.string.migration_changed),
				color = MaterialTheme.colorScheme.error,
			)
		}
		if (state.failed) Text(stringResource(R.string.migration_blocked), color = MaterialTheme.colorScheme.error)
		if (state.searchFailed) {
			Text(stringResource(R.string.migration_search_failed), color = MaterialTheme.colorScheme.error)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				TextButton(onClick = onSearch, modifier = Modifier.testTag("migration-retry")) {
					Text(stringResource(R.string.migration_retry))
				}
				TextButton(onClick = onBackToSources, modifier = Modifier.testTag("migration-change-sources")) {
					Text(stringResource(R.string.migration_change_sources))
				}
			}
			return@Column
		}

		if (!state.hasSearched || discovery == null) return@Column
		if (discovery.searchPartial) {
			Text(stringResource(R.string.migration_partial_search), style = MaterialTheme.typography.bodySmall)
		}

		if (state.manualSearchEnabled && selected != null) {
			MigrationSelectionSummary(seed = discovery.seed, candidate = selected, imageLoader = imageLoader)
			Text(stringResource(R.string.migration_preservation), style = MaterialTheme.typography.bodySmall)
			OutlinedButton(
				onClick = { selectedSource = null; selectedId = null },
				modifier = Modifier.fillMaxWidth().testTag("migration-change-result"),
			) { Text(stringResource(R.string.migration_change_result)) }
			Button(
				onClick = { onMigrate(selected.manga, true) },
				enabled = !discovery.incomplete,
				modifier = Modifier.fillMaxWidth().testTag("migration-execute"),
			) { Text(stringResource(R.string.migration_migrate)) }
			return@Column
		}

		val status = when {
			discovery.incomplete -> R.string.migration_incomplete
			ready -> R.string.migration_ready
			discovery.assessment?.state == MigrationMatchState.NEEDS_REVIEW -> R.string.migration_review
			else -> R.string.migration_no_replacement
		}
		Text(stringResource(status), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("migration-status"))
		MigrationCandidateGroups(
			candidates = candidates,
			selected = selected,
			imageLoader = imageLoader,
			showEvidence = !state.manualSearchEnabled,
			onSelect = { candidate ->
				selectedSource = candidate.manga.source.name
				selectedId = candidate.manga.id
			},
			onOpenCandidate = onOpenCandidate,
		)
		if (candidates.isNotEmpty() && !state.manualSearchEnabled) {
			Text(stringResource(R.string.migration_preservation), style = MaterialTheme.typography.bodySmall)
			val chosen = if (ready) discovery.assessment?.best else selected
			Button(
				onClick = { chosen?.let { onMigrate(it.manga, !ready) } },
				enabled = chosen != null && !discovery.incomplete,
				modifier = Modifier.fillMaxWidth().testTag("migration-execute"),
			) {
				Text(stringResource(if (ready) R.string.migration_migrate else R.string.migration_confirm_review))
			}
		}
		TextButton(onClick = onBackToSources, modifier = Modifier.testTag("migration-change-sources")) {
			Text(stringResource(R.string.migration_change_sources))
		}
	}
}

@Composable
private fun MigrationSourcePicker(
	sources: List<MangaSource>,
	selectedSourceNames: Set<String>,
	selectedScope: MigrationSearchScope,
	sourceQuery: String,
	onSourceQueryChange: (String) -> Unit,
	onScopeChange: (MigrationSearchScope) -> Unit,
	onSetSourcesSelected: (Set<String>, Boolean) -> Unit,
) {
	val context = LocalContext.current
	Text(stringResource(R.string.migration_search_scope_title), style = MaterialTheme.typography.titleMedium)
	Row(
		modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("migration-scope-row"),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		for (scope in MigrationSearchScope.entries) {
			val count = sources.count { scope.includes(it) }
			FilterChip(
				selected = selectedScope == scope,
				onClick = { onScopeChange(scope) },
				enabled = count > 0,
				label = { Text(stringResource(R.string.migration_scope_with_count, stringResource(scope.labelRes()), count)) },
				modifier = Modifier.testTag("migration-scope-${scope.name.lowercase()}"),
			)
		}
	}
	OutlinedTextField(
		value = sourceQuery,
		onValueChange = onSourceQueryChange,
		modifier = Modifier.fillMaxWidth().testTag("migration-source-filter"),
		label = { Text(stringResource(R.string.migration_filter_sources)) },
		singleLine = true,
	)
	val visibleSources = sources.filter { source ->
		selectedScope.includes(source) && (sourceQuery.isBlank() || source.getTitle(context).contains(sourceQuery, ignoreCase = true))
	}
	val visibleNames = visibleSources.mapTo(linkedSetOf()) { it.name }
	val allVisibleSelected = visibleNames.isNotEmpty() && visibleNames.all(selectedSourceNames::contains)
	Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
		Checkbox(
			checked = allVisibleSelected,
			onCheckedChange = { checked -> onSetSourcesSelected(visibleNames, checked) },
			modifier = Modifier.testTag("migration-select-all-visible"),
		)
		Text(
			stringResource(if (allVisibleSelected) R.string.migration_deselect_all_visible else R.string.migration_select_all_visible),
			style = MaterialTheme.typography.bodyMedium,
		)
	}
	if (visibleSources.isEmpty()) {
		Text(stringResource(R.string.migration_scope_empty), style = MaterialTheme.typography.bodySmall)
	} else {
		LazyColumn(
			modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("migration-source-list"),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			items(visibleSources, key = { it.name }) { source ->
				val checked = source.name in selectedSourceNames
				Surface(
					onClick = { onSetSourcesSelected(setOf(source.name), !checked) },
					shape = RoundedCornerShape(10.dp),
					tonalElevation = if (checked) 2.dp else 0.dp,
					modifier = Modifier.fillMaxWidth().testTag("migration-source:${source.name}"),
				) {
					Row(
						modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Checkbox(checked = checked, onCheckedChange = { onSetSourcesSelected(setOf(source.name), it) })
						Column(Modifier.weight(1f)) {
							Text(source.getTitle(context), style = MaterialTheme.typography.bodyLarge)
							Text(stringResource(source.migrationSourceOrigin().labelRes()), style = MaterialTheme.typography.bodySmall)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun MigrationAdvancedOptions(
	options: MigrationDirectSearchOptions,
	expanded: Boolean,
	onExpandedChange: (Boolean) -> Unit,
	onOptionsChange: (MigrationDirectSearchOptions) -> Unit,
) {
	TextButton(onClick = { onExpandedChange(!expanded) }, modifier = Modifier.testTag("migration-advanced-toggle")) {
		Text(stringResource(if (expanded) R.string.migration_advanced_hide else R.string.migration_advanced_show))
	}
	if (!expanded) return
	Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
		Text(stringResource(R.string.migration_title_query_mode), style = MaterialTheme.typography.titleSmall)
		OptionRadioRow(
			selected = options.titleMode == MigrationDirectTitleMode.CLEANED,
			label = stringResource(R.string.migration_title_cleaned),
			onClick = { onOptionsChange(options.copy(titleMode = MigrationDirectTitleMode.CLEANED)) },
		)
		OptionRadioRow(
			selected = options.titleMode == MigrationDirectTitleMode.ORIGINAL,
			label = stringResource(R.string.migration_title_original),
			onClick = { onOptionsChange(options.copy(titleMode = MigrationDirectTitleMode.ORIGINAL)) },
		)
		OptionCheckRow(
			checked = options.includeAlternativeTitles,
			label = stringResource(R.string.migration_include_alt_titles),
			onCheckedChange = { onOptionsChange(options.copy(includeAlternativeTitles = it)) },
		)
		OptionCheckRow(
			checked = options.searchAliasesIfNeeded,
			label = stringResource(R.string.migration_alias_fallback),
			onCheckedChange = { onOptionsChange(options.copy(searchAliasesIfNeeded = it)) },
		)
		OptionCheckRow(
			checked = options.showLowConfidenceResults,
			label = stringResource(R.string.migration_show_low_confidence),
			onCheckedChange = { onOptionsChange(options.copy(showLowConfidenceResults = it)) },
		)
		OptionCheckRow(
			checked = options.autoSelectStrongestResult,
			label = stringResource(R.string.migration_auto_select_strongest),
			onCheckedChange = { onOptionsChange(options.copy(autoSelectStrongestResult = it)) },
		)
		Text(stringResource(R.string.migration_preserve_all_fixed), style = MaterialTheme.typography.bodySmall)
	}
}

@Composable
private fun OptionRadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
		RadioButton(selected = selected, onClick = onClick)
		TextButton(onClick = onClick) { Text(label) }
	}
}

@Composable
private fun OptionCheckRow(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
		Checkbox(checked = checked, onCheckedChange = onCheckedChange)
		Text(label, style = MaterialTheme.typography.bodyMedium)
	}
}

@Composable
private fun MigrationSelectionSummary(
	seed: Manga,
	candidate: MigrationMatchCandidate,
	imageLoader: ImageLoader,
) {
	val context = LocalContext.current
	OutlinedCard(Modifier.fillMaxWidth().testTag("migration-selection-summary")) {
		Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Text(stringResource(R.string.migration_current_selection), style = MaterialTheme.typography.labelLarge)
			Text(seed.title, style = MaterialTheme.typography.titleMedium)
			Text(seed.source.getTitle(context), style = MaterialTheme.typography.bodySmall)
			HorizontalDivider()
			Text(stringResource(R.string.migration_new_selection), style = MaterialTheme.typography.labelLarge)
			Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
				val cover = candidate.manga.coverUrl?.takeIf(String::isNotBlank)
				if (cover != null) {
					AsyncImage(
						model = ImageRequest.Builder(context).data(cover).crossfade(true).mangaSourceExtra(candidate.manga.source).build(),
						imageLoader = imageLoader,
						contentDescription = null,
						contentScale = ContentScale.Crop,
						modifier = Modifier.size(width = 64.dp, height = 88.dp).clip(RoundedCornerShape(8.dp)),
					)
				}
				Column(Modifier.weight(1f)) {
					Text(candidate.manga.title, style = MaterialTheme.typography.titleMedium)
					Text(candidate.manga.source.getTitle(context), style = MaterialTheme.typography.bodySmall)
				}
			}
		}
	}
}

@StringRes
internal fun MigrationSearchScope.labelRes(): Int = when (this) {
	MigrationSearchScope.ALL -> R.string.migration_scope_all
	MigrationSearchScope.STORE -> R.string.migration_scope_store
	MigrationSearchScope.UMA -> R.string.migration_scope_uma
	MigrationSearchScope.GEKKOUSHI -> R.string.migration_scope_gekkoushi
	MigrationSearchScope.MIYORARE -> R.string.migration_scope_miyorare
}

private fun MigrationSearchScope.includes(source: MangaSource): Boolean = when (this) {
	MigrationSearchScope.ALL -> true
	MigrationSearchScope.STORE -> source.migrationSourceOrigin() == MigrationSourceOrigin.STORE
	MigrationSearchScope.UMA -> source.migrationSourceOrigin() == MigrationSourceOrigin.UMA
	MigrationSearchScope.GEKKOUSHI -> source.migrationSourceOrigin() == MigrationSourceOrigin.GEKKOUSHI
	MigrationSearchScope.MIYORARE -> source.migrationSourceOrigin() == MigrationSourceOrigin.MIYORARE
}

@StringRes
private fun MigrationSourceOrigin.labelRes(): Int = when (this) {
	MigrationSourceOrigin.STORE -> R.string.migration_scope_store
	MigrationSourceOrigin.UMA -> R.string.migration_scope_uma
	MigrationSourceOrigin.GEKKOUSHI -> R.string.migration_scope_gekkoushi
	MigrationSourceOrigin.MIYORARE -> R.string.migration_scope_miyorare
	MigrationSourceOrigin.OTHER -> R.string.migration_source_origin_other
}

@Composable
private fun MigrationCandidateGroups(
	candidates: List<MigrationMatchCandidate>,
	selected: MigrationMatchCandidate?,
	imageLoader: ImageLoader,
	showEvidence: Boolean,
	onSelect: (MigrationMatchCandidate) -> Unit,
	onOpenCandidate: (Manga) -> Unit,
) {
	val context = LocalContext.current
	for ((_, sourceCandidates) in candidates.groupBy { it.manga.source.name }) {
		val first = sourceCandidates.firstOrNull() ?: continue
		Text(first.manga.source.getTitle(context), style = MaterialTheme.typography.titleMedium)
		for (candidate in sourceCandidates) {
			MigrationCandidateCard(
				candidate = candidate,
				selected = candidate == selected,
				imageLoader = imageLoader,
				showEvidence = showEvidence,
				onSelect = { onSelect(candidate) },
				onOpenDetails = { onOpenCandidate(candidate.manga) },
			)
		}
	}
}

@Composable
private fun MigrationCandidateCard(
	candidate: MigrationMatchCandidate,
	selected: Boolean,
	imageLoader: ImageLoader,
	showEvidence: Boolean,
	onSelect: () -> Unit,
	onOpenDetails: () -> Unit,
) {
	val context = LocalContext.current
	val coverUrl = candidate.manga.coverUrl?.takeIf(String::isNotBlank)
	val confidence = when {
		candidate.evidence.autoEligible -> R.string.migration_confidence_strong
		candidate.evidence.exactTitleMatch || candidate.evidence.matchedSeedQuery || candidate.evidence.score >= 30 ->
			R.string.migration_confidence_possible
		else -> R.string.migration_confidence_low
	}
	OutlinedCard(onClick = onSelect, modifier = Modifier.fillMaxWidth().testTag("migration-candidate:${candidate.manga.source.name}:${candidate.manga.id}")) {
		Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.Top,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				RadioButton(selected = selected, onClick = onSelect)
				if (coverUrl != null) {
					AsyncImage(
						model = ImageRequest.Builder(context)
							.data(coverUrl)
							.crossfade(true)
							.mangaSourceExtra(candidate.manga.source)
							.build(),
						imageLoader = imageLoader,
						contentDescription = null,
						contentScale = ContentScale.Crop,
						modifier = Modifier.size(width = 72.dp, height = 96.dp).clip(RoundedCornerShape(8.dp))
							.testTag("migration-candidate-cover:${candidate.manga.source.name}:${candidate.manga.id}"),
					)
				}
				Column(Modifier.weight(1f)) {
					Text(candidate.manga.title, style = MaterialTheme.typography.titleMedium)
					Text(stringResource(R.string.migration_replacement_source, candidate.manga.source.getTitle(context)))
					if (showEvidence) Text(stringResource(confidence), style = MaterialTheme.typography.labelMedium)
					TextButton(
						onClick = onOpenDetails,
						modifier = Modifier.testTag("migration-candidate-details:${candidate.manga.source.name}:${candidate.manga.id}"),
					) { Text(stringResource(R.string.details)) }
				}
			}
			if (candidate.manga.altTitles.isNotEmpty()) Text(candidate.manga.altTitles.take(2).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
			if (candidate.manga.authors.isNotEmpty()) Text(candidate.manga.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall)
			if (showEvidence) {
				Text(stringResource(R.string.migration_chapter_evidence, candidate.manga.chapters.orEmpty().size, candidate.evidence.chapterMatches), style = MaterialTheme.typography.bodySmall)
				if (candidate.evidence.authorConflict || candidate.evidence.chapterConflict) {
					Text(stringResource(R.string.migration_evidence_conflict), color = MaterialTheme.colorScheme.error)
				} else if (candidate.evidence.authorMatch || candidate.evidence.strongChapterMatch) {
					Text(stringResource(R.string.migration_evidence_agrees), style = MaterialTheme.typography.bodySmall)
				}
			}
		}
	}
}
