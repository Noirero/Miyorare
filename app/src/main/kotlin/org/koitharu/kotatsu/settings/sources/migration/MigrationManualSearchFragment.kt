package org.koitharu.kotatsu.settings.sources.migration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.doOnDetach
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import com.google.android.material.appbar.AppBarLayout
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.MigrationSourceStrategy
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme

@AndroidEntryPoint
class MigrationManualSearchFragment : BaseComposeSettingsFragment(R.string.manga_migration) {
	private val viewModel by viewModels<MigrationManualSearchViewModel>()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
		ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				MiyorareTheme {
					val state by viewModel.state.collectAsState()
					MigrationManualSearchScreen(
						state = state,
						onQueryChange = viewModel::setQuery,
						onSearch = viewModel::search,
						onSelect = viewModel::select,
						onClearSelection = viewModel::clearSelection,
						onMigrate = viewModel::migrateSelected,
						onOpenResult = { router.openDetails(it) },
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
		const val ARG_MANGA_ID = "manual_migration_manga_id"
		const val ARG_SOURCE_NAMES = "manual_migration_source_names"
		const val ARG_STRATEGY = "manual_migration_strategy"
		const val ARG_EXTRA_SEARCH = "manual_migration_extra_search"
		const val ARG_CONFIG = "manual_migration_config"
		const val ARG_TARGET_SOURCE = "manual_migration_target_source"
		const val ARG_TARGET_ID = "manual_migration_target_id"

		fun args(
			mangaId: Long,
			selectedSourceNames: List<String>,
			strategy: MigrationSourceStrategy,
			extraSearchParams: String,
			configKey: String,
			initialTargetSource: String?,
			initialTargetId: Long?,
		) = Bundle().apply {
			putLong(ARG_MANGA_ID, mangaId)
			putStringArray(ARG_SOURCE_NAMES, selectedSourceNames.toTypedArray())
			putString(ARG_STRATEGY, strategy.name)
			putString(ARG_EXTRA_SEARCH, extraSearchParams)
			putString(ARG_CONFIG, configKey)
			putString(ARG_TARGET_SOURCE, initialTargetSource)
			putLong(ARG_TARGET_ID, initialTargetId ?: 0L)
		}
	}
}

@Composable
private fun MigrationManualSearchScreen(
	state: MigrationManualSearchState,
	onQueryChange: (String) -> Unit,
	onSearch: () -> Unit,
	onSelect: (Manga) -> Unit,
	onClearSelection: () -> Unit,
	onMigrate: () -> Unit,
	onOpenResult: (Long) -> Unit,
) {
	val context = androidx.compose.ui.platform.LocalContext.current
	if (state.loading) {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
		return
	}
	state.completedId?.let { id ->
		Column(
			Modifier.fillMaxSize().padding(20.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(stringResource(R.string.migration_completed), style = MaterialTheme.typography.titleLarge)
			Button(onClick = { onOpenResult(id) }) { Text(stringResource(R.string.migration_open_result)) }
		}
		return
	}

	LazyColumn(
		modifier = Modifier.fillMaxSize().nestedScroll(rememberNestedScrollInteropConnection()).navigationBarsPadding(),
		contentPadding = PaddingValues(20.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		item {
			state.seed?.let { seed ->
				Text(seed.title, style = MaterialTheme.typography.titleLarge)
				Text(stringResource(R.string.migration_current_source, seed.source.getTitle(context)))
			}
		}
		item {
			OutlinedTextField(
				value = state.query,
				onValueChange = onQueryChange,
				enabled = !state.searching && !state.migrating,
				modifier = Modifier.fillMaxWidth().testTag("migration-manual-query"),
				label = { Text(stringResource(R.string.search)) },
				singleLine = true,
			)
			Spacer(Modifier.height(8.dp))
			Button(
				onClick = onSearch,
				enabled = !state.searching && !state.migrating && state.query.isNotBlank() && state.sources.isNotEmpty(),
				modifier = Modifier.fillMaxWidth().testTag("migration-manual-search"),
			) {
				if (state.searching) {
					CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
					Spacer(Modifier.width(8.dp))
				}
				Text(stringResource(R.string.migration_yokai_manual_search))
			}
		}

		state.errorMessage?.let { message ->
			item { Text(message, color = MaterialTheme.colorScheme.error) }
		}

		state.selected?.let { selected ->
			item {
				Card(Modifier.fillMaxWidth().testTag("migration-manual-confirmation")) {
					Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
						Text(stringResource(R.string.migration_current_selection), style = MaterialTheme.typography.labelLarge)
						Text(state.seed?.title.orEmpty())
						Text("→", style = MaterialTheme.typography.titleLarge)
						Text(stringResource(R.string.migration_new_selection), style = MaterialTheme.typography.labelLarge)
						Text(selected.title, style = MaterialTheme.typography.titleMedium)
						Text(selected.source.getTitle(context), style = MaterialTheme.typography.bodySmall)
						Text(stringResource(R.string.migration_preserve_all_fixed), style = MaterialTheme.typography.bodySmall)
						Button(
							onClick = onMigrate,
							enabled = !state.migrating,
							modifier = Modifier.fillMaxWidth().testTag("migration-manual-migrate"),
						) {
							if (state.migrating) {
								CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
								Spacer(Modifier.width(8.dp))
							}
							Text(stringResource(R.string.migration_migrate))
						}
						TextButton(onClick = onClearSelection, enabled = !state.migrating) {
							Text(stringResource(R.string.migration_change_result))
						}
					}
				}
			}
		}

		for (source in state.sources) {
			val group = state.groups[source.name]
			item(key = "header:${source.name}") {
				Text(source.getTitle(context), style = MaterialTheme.typography.titleMedium)
				when {
					group == null && state.searching -> LinearProgressIndicator(Modifier.fillMaxWidth())
					group?.loading == true -> LinearProgressIndicator(Modifier.fillMaxWidth())
					group?.errorMessage != null -> Text(
						group.errorMessage,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
					)
					group != null && !group.loading && group.manga.isEmpty() -> Text(
						stringResource(R.string.nothing_found),
						style = MaterialTheme.typography.bodySmall,
					)
				}
			}
			items(
				items = group?.manga.orEmpty(),
				key = { manga -> "${source.name}:${manga.id}" },
			) { manga ->
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.clickable(enabled = !state.migrating) { onSelect(manga) }
						.padding(vertical = 10.dp)
						.testTag("migration-manual-result"),
				) {
					Text(manga.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
					manga.authors.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
				}
				HorizontalDivider()
			}
		}
	}
}
