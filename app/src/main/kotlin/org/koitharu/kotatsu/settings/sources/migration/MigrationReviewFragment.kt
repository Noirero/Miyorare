package org.koitharu.kotatsu.settings.sources.migration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import org.koitharu.kotatsu.alternatives.domain.MigrationMatchCandidate
import org.koitharu.kotatsu.alternatives.domain.MigrationMatchState
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchAvailability
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchPhase
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchScope
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.Manga
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
) {
	// Only user intent is saveable. Network matching evidence remains transient.
	var selectedSource by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
	var selectedId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
	var selectedFingerprint by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
	val discovery = state.discovery
	val finalDecision = !state.loading && state.hasSearched && !state.searchFailed
	val ready = finalDecision && discovery != null && !discovery.incomplete &&
		discovery.assessment?.state == MigrationMatchState.READY
	val candidates = when {
		state.searching -> discovery?.assessment?.candidates.orEmpty()
		!finalDecision -> emptyList()
		ready -> listOfNotNull(discovery?.assessment?.best)
		else -> discovery?.assessment?.candidates.orEmpty()
	}
	val selected = candidates.firstOrNull { it.manga.id == selectedId && it.manga.source.name == selectedSource &&
		org.koitharu.kotatsu.alternatives.domain.MigrationSelection.of(it.manga).fingerprint == selectedFingerprint }
	val context = LocalContext.current
	Column(Modifier.fillMaxSize().nestedScroll(rememberNestedScrollInteropConnection()).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp)) {
		state.completedId?.let { id ->
			Text(stringResource(R.string.migration_completed), style = MaterialTheme.typography.titleLarge)
			Button(onClick = { onOpenResult(id) }) { Text(stringResource(R.string.migration_open_result)) }
			return@Column
		}
		discovery?.seed?.let { seed ->
			Text(seed.title, style = MaterialTheme.typography.titleLarge)
			Text(stringResource(R.string.migration_current_source, seed.source.getTitle(context)))
		}
		if (state.loading) {
			CircularProgressIndicator(Modifier.testTag("migration-loading"))
			when (val progress = discovery?.progress) {
				null -> Text(stringResource(R.string.migration_checking))
				else -> when (progress.phase) {
					MigrationSearchPhase.CHECKING_SOURCE -> {
						val sourceTitle = discovery.seed.source.getTitle(context)
						Text(stringResource(R.string.migration_checking_source, sourceTitle))
					}
					MigrationSearchPhase.SEARCHING_SOURCES -> {
						Text(
							if (progress.totalSources > 0) {
								stringResource(
									R.string.migration_searching_sources_progress,
									progress.completedSources,
									progress.totalSources,
								)
							} else {
								stringResource(R.string.migration_searching_sources)
							},
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
			if (!state.searching || candidates.isEmpty()) return@Column
			for (candidate in candidates) {
				MigrationCandidateCard(
					candidate = candidate,
					selected = false,
					imageLoader = imageLoader,
					onSelect = {},
					onOpenDetails = { onOpenCandidate(candidate.manga) },
				)
			}
			return@Column
		}
		if (state.changed) Text(stringResource(R.string.migration_changed))
		if (state.failed) Text(stringResource(R.string.migration_blocked), color = MaterialTheme.colorScheme.error)
		if (state.searchFailed) Text(stringResource(R.string.migration_search_failed), color = MaterialTheme.colorScheme.error)
		when {
			discovery == null -> Unit
			discovery.currentSourceReadable -> {
				Text(stringResource(R.string.migration_source_healthy))
			}
			else -> {
				Text(stringResource(R.string.migration_source_unavailable))
				MigrationScopeSelector(
					availability = state.availability,
					selectedScope = state.selectedScope,
					onScopeChange = onScopeChange,
					onSearch = onSearch,
				)
				if ((state.availability?.providerFailureCount ?: 0) > 0) {
					Text(stringResource(R.string.migration_partial_provider_pool), style = MaterialTheme.typography.bodySmall)
				}
				if (!state.hasSearched || state.searchFailed) return@Column
				if (discovery.searchPartial) {
					Text(stringResource(R.string.migration_partial_search), style = MaterialTheme.typography.bodySmall)
				}
				val status = when {
					discovery.incomplete -> R.string.migration_incomplete
					discovery.assessment?.state == MigrationMatchState.READY -> R.string.migration_ready
					discovery.assessment?.state == MigrationMatchState.NEEDS_REVIEW -> R.string.migration_review
					else -> R.string.migration_no_replacement
				}
				Text(stringResource(status), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("migration-status"))
				for (candidate in candidates) {
					MigrationCandidateCard(
						candidate = candidate,
						selected = ready || candidate == selected,
						imageLoader = imageLoader,
						onSelect = {
							selectedSource = candidate.manga.source.name
							selectedId = candidate.manga.id
							selectedFingerprint = org.koitharu.kotatsu.alternatives.domain.MigrationSelection.of(candidate.manga).fingerprint
						},
						onOpenDetails = { onOpenCandidate(candidate.manga) },
					)
				}
				if (candidates.isNotEmpty()) {
					Text(stringResource(R.string.migration_preservation), style = MaterialTheme.typography.bodySmall)
					val chosen = if (ready) discovery.assessment?.best else selected
					Button(onClick = { chosen?.let { onMigrate(it.manga, !ready) } },
						enabled = chosen != null && !discovery.incomplete, modifier = Modifier.fillMaxWidth().testTag("migration-execute")) {
						Text(stringResource(if (ready) R.string.migration_migrate else R.string.migration_confirm_review))
					}
				}
			}
		}
		TextButton(onClick = onRetry, modifier = Modifier.testTag("migration-retry")) { Text(stringResource(R.string.migration_retry)) }
	}
}

@Composable
private fun MigrationScopeSelector(
	availability: MigrationSearchAvailability?,
	selectedScope: MigrationSearchScope,
	onScopeChange: (MigrationSearchScope) -> Unit,
	onSearch: () -> Unit,
) {
	Text(stringResource(R.string.migration_search_scope_title), style = MaterialTheme.typography.titleMedium)
	Row(
		modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("migration-scope-row"),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		for (scope in MigrationSearchScope.entries) {
			val count = availability?.count(scope) ?: 0
			FilterChip(
				selected = selectedScope == scope,
				onClick = { onScopeChange(scope) },
				enabled = availability != null && count > 0,
				label = { Text(stringResource(R.string.migration_scope_with_count, stringResource(scope.labelRes()), count)) },
				modifier = Modifier.testTag("migration-scope-${scope.name.lowercase()}"),
			)
		}
	}
	val selectedCount = availability?.count(selectedScope) ?: 0
	if (availability != null && selectedCount == 0) {
		Text(stringResource(R.string.migration_scope_empty), style = MaterialTheme.typography.bodySmall)
	}
	Button(
		onClick = onSearch,
		enabled = availability != null && selectedCount > 0,
		modifier = Modifier.fillMaxWidth().testTag("migration-search"),
	) {
		Text(stringResource(R.string.migration_search_replacements))
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

@Composable
private fun MigrationCandidateCard(
	candidate: MigrationMatchCandidate,
	selected: Boolean,
	imageLoader: ImageLoader,
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
						modifier = Modifier
							.size(width = 72.dp, height = 96.dp)
							.clip(RoundedCornerShape(8.dp))
							.testTag("migration-candidate-cover:${candidate.manga.source.name}:${candidate.manga.id}"),
					)
				}
				Column(Modifier.weight(1f)) {
					Text(candidate.manga.title, style = MaterialTheme.typography.titleMedium)
					Text(stringResource(R.string.migration_replacement_source, candidate.manga.source.getTitle(context)))
					Text(stringResource(confidence), style = MaterialTheme.typography.labelMedium)
					TextButton(
						onClick = onOpenDetails,
						modifier = Modifier.testTag("migration-candidate-details:${candidate.manga.source.name}:${candidate.manga.id}"),
					) {
						Text(stringResource(R.string.details))
					}
				}
			}
			if (candidate.manga.altTitles.isNotEmpty()) Text(candidate.manga.altTitles.take(2).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
			if (candidate.manga.authors.isNotEmpty()) Text(candidate.manga.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall)
			Text(stringResource(R.string.migration_chapter_evidence, candidate.manga.chapters.orEmpty().size, candidate.evidence.chapterMatches), style = MaterialTheme.typography.bodySmall)
			if (candidate.evidence.authorConflict || candidate.evidence.chapterConflict) {
				Text(stringResource(R.string.migration_evidence_conflict), color = MaterialTheme.colorScheme.error)
			} else if (candidate.evidence.authorMatch || candidate.evidence.strongChapterMatch) {
				Text(stringResource(R.string.migration_evidence_agrees), style = MaterialTheme.typography.bodySmall)
			}
		}
	}
}
