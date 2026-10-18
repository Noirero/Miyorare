package org.koitharu.kotatsu.settings.sources.migration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.MigrationMatchState
import org.koitharu.kotatsu.alternatives.domain.MigrationMatchCandidate
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme

@AndroidEntryPoint
class MigrationReviewFragment : BaseComposeSettingsFragment(R.string.manga_migration) {
	private val viewModel by viewModels<MigrationReviewViewModel>()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
		ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				MiyorareTheme {
					val state by viewModel.state.collectAsState()
					MigrationReviewScreen(state, viewModel::retry, viewModel::migrate) { id -> router.openDetails(id) }
				}
			}
		}

	companion object {
		const val ARG_MANGA_ID = "migration_manga_id"
		fun args(mangaId: Long) = Bundle(1).apply { putLong(ARG_MANGA_ID, mangaId) }
	}
}

@Composable
internal fun MigrationReviewScreen(
	state: MigrationReviewState,
	onRetry: () -> Unit,
	onMigrate: (Manga, Boolean) -> Unit,
	onOpen: (Long) -> Unit,
) {
	// Only user intent is saveable. Network matching evidence remains transient.
	var selectedSource by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
	var selectedId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
	var selectedFingerprint by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
	val discovery = state.discovery
	val ready = discovery != null && !discovery.incomplete && discovery.assessment?.state == MigrationMatchState.READY
	val candidates = if (ready) listOfNotNull(discovery?.assessment?.best) else discovery?.assessment?.candidates.orEmpty()
	val selected = candidates.firstOrNull { it.manga.id == selectedId && it.manga.source.name == selectedSource &&
		org.koitharu.kotatsu.alternatives.domain.MigrationSelection.of(it.manga).fingerprint == selectedFingerprint }
	val context = LocalContext.current
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp)) {
		state.completedId?.let { id ->
			Text(stringResource(R.string.migration_completed), style = MaterialTheme.typography.titleLarge)
			Button(onClick = { onOpen(id) }) { Text(stringResource(R.string.migration_open_result)) }
			return@Column
		}
		discovery?.seed?.let { seed ->
			Text(seed.title, style = MaterialTheme.typography.titleLarge)
			Text(stringResource(R.string.migration_current_source, seed.source.getTitle(context)))
		}
		if (state.loading) {
			CircularProgressIndicator(Modifier.testTag("migration-loading"))
			Text(stringResource(R.string.migration_checking))
			return@Column
		}
		if (state.changed) Text(stringResource(R.string.migration_changed))
		if (state.failed) Text(stringResource(R.string.migration_blocked), color = MaterialTheme.colorScheme.error)
		when {
			discovery == null -> Unit
			discovery.sourceHealthy -> Text(stringResource(R.string.migration_source_healthy))
			else -> {
				Text(stringResource(R.string.migration_source_problem))
				val status = when {
					discovery.incomplete -> R.string.migration_incomplete
					discovery.assessment?.state == MigrationMatchState.READY -> R.string.migration_ready
					discovery.assessment?.state == MigrationMatchState.NEEDS_REVIEW -> R.string.migration_review
					else -> R.string.migration_no_replacement
				}
				Text(stringResource(status), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("migration-status"))
				for (candidate in candidates) {
					MigrationCandidateCard(candidate, ready || candidate == selected) {
						selectedSource = candidate.manga.source.name
						selectedId = candidate.manga.id
						selectedFingerprint = org.koitharu.kotatsu.alternatives.domain.MigrationSelection.of(candidate.manga).fingerprint
					}
				}
				if (candidates.isNotEmpty()) {
					Text(stringResource(R.string.migration_preservation), style = MaterialTheme.typography.bodySmall)
					val chosen = if (ready) discovery.assessment?.best else selected
					Button(onClick = { chosen?.let { onMigrate(it.manga, !ready) } },
						enabled = chosen != null, modifier = Modifier.fillMaxWidth().testTag("migration-execute")) {
						Text(stringResource(if (ready) R.string.migration_migrate else R.string.migration_confirm_review))
					}
				}
			}
		}
		TextButton(onClick = onRetry, modifier = Modifier.testTag("migration-retry")) { Text(stringResource(R.string.migration_retry)) }
	}
}

@Composable
private fun MigrationCandidateCard(candidate: MigrationMatchCandidate, selected: Boolean, onSelect: () -> Unit) {
	val context = LocalContext.current
	OutlinedCard(onClick = onSelect, modifier = Modifier.fillMaxWidth().testTag("migration-candidate:${candidate.manga.source.name}:${candidate.manga.id}")) {
		Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Row {
				RadioButton(selected = selected, onClick = onSelect)
				Column(Modifier.weight(1f)) {
					Text(candidate.manga.title, style = MaterialTheme.typography.titleMedium)
					Text(stringResource(R.string.migration_replacement_source, candidate.manga.source.getTitle(context)))
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
