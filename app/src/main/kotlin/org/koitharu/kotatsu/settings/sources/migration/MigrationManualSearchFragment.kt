package org.koitharu.kotatsu.settings.sources.migration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.doOnDetach
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.google.android.material.appbar.AppBarLayout
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.MigrationExecutionMode
import org.koitharu.kotatsu.alternatives.domain.MigrationSourceStrategy
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import javax.inject.Inject

@AndroidEntryPoint
class MigrationManualSearchFragment : BaseComposeSettingsFragment(R.string.manga_migration) {
	@Inject
	lateinit var imageLoader: ImageLoader

	private val viewModel by viewModels<MigrationManualSearchViewModel>()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
		ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				MiyorareTheme {
					Surface(
						modifier = Modifier.fillMaxSize(),
						color = Color.Transparent,
						contentColor = MaterialTheme.colorScheme.onSurface,
					) {
						val state by viewModel.state.collectAsState()
						LaunchedEffect(state.skipped) {
							if (state.skipped) requireActivity().onBackPressedDispatcher.onBackPressed()
						}
						MigrationManualSearchScreen(
							state = state,
							imageLoader = imageLoader,
							onQueryChange = viewModel::setQuery,
							onSearch = viewModel::search,
							onSelect = viewModel::select,
							onClearSelection = viewModel::clearSelection,
							onMigrate = viewModel::migrateSelected,
							onCopy = viewModel::copySelected,
							onSkip = viewModel::skip,
							onOpenResult = { router.openDetails(it) },
						)
					}
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
	imageLoader: ImageLoader,
	onQueryChange: (String) -> Unit,
	onSearch: () -> Unit,
	onSelect: (Manga) -> Unit,
	onClearSelection: () -> Unit,
	onMigrate: () -> Unit,
	onCopy: () -> Unit,
	onSkip: () -> Unit,
	onOpenResult: (Manga) -> Unit,
) {
	val context = LocalContext.current
	val busy = state.migrating || state.skipping
	if (state.loading) {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
		return
	}
	state.completed?.let { manga ->
		Column(
			Modifier.fillMaxSize().padding(20.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(
				stringResource(
					if (state.completedMode == MigrationExecutionMode.COPY) R.string.migration_copied else R.string.migration_completed,
				),
				style = MaterialTheme.typography.titleLarge,
			)
			Button(onClick = { onOpenResult(manga) }) { Text(stringResource(R.string.migration_open_result)) }
		}
		return
	}

	val selected = state.selected
	val selectionIsInResults = selected != null && state.groups[selected.source.name]
		?.manga
		.orEmpty()
		.any { candidate -> candidate.sameMigrationIdentity(selected) }

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
				enabled = !state.searching && !busy,
				modifier = Modifier.fillMaxWidth().testTag("migration-manual-query"),
				label = { Text(stringResource(R.string.search)) },
				singleLine = true,
			)
			Spacer(Modifier.height(8.dp))
			Button(
				onClick = onSearch,
				enabled = !state.searching && !busy && state.query.isNotBlank() && state.sources.isNotEmpty(),
				modifier = Modifier.fillMaxWidth().testTag("migration-manual-search"),
			) {
				if (state.searching) {
					CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
					Spacer(Modifier.width(8.dp))
				}
				Text(stringResource(R.string.migration_yokai_manual_search))
			}
			TextButton(
				onClick = onSkip,
				enabled = !busy,
				modifier = Modifier.fillMaxWidth().testTag("migration-manual-skip"),
			) {
				if (state.skipping) {
					CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
					Spacer(Modifier.width(8.dp))
				}
				Text(stringResource(R.string.migration_yokai_skip_manga))
			}
		}

		state.errorMessage?.let { message ->
			item { Text(message, color = MaterialTheme.colorScheme.error) }
		}

		// A candidate restored from the previous Yokai batch can exist before its manual-search group
		// has produced results. Keep that selection actionable until the matching row appears; normal
		// user selections render inline at the exact row that was tapped below.
		if (selected != null && !selectionIsInResults) {
			item(key = "selection-fallback:${selected.source.name}:${selected.id}") {
				MigrationManualSelectionCard(
					state = state,
					selected = selected,
					imageLoader = imageLoader,
					busy = busy,
					onOpenResult = onOpenResult,
					onMigrate = onMigrate,
					onCopy = onCopy,
					onClearSelection = onClearSelection,
				)
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
				Column(Modifier.fillMaxWidth()) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable(enabled = !busy) { onSelect(manga) }
							.padding(vertical = 10.dp)
							.testTag("migration-manual-result"),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(12.dp),
					) {
						MigrationMangaCover(
							manga = manga,
							imageLoader = imageLoader,
							modifier = Modifier.width(64.dp).aspectRatio(0.72f),
						)
						Column(Modifier.weight(1f)) {
							Text(
								manga.title,
								style = MaterialTheme.typography.bodyLarge,
								maxLines = 2,
								overflow = TextOverflow.Ellipsis,
							)
							manga.authors.firstOrNull()?.let { author ->
								Text(
									author,
									style = MaterialTheme.typography.bodySmall,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
						}
					}
					if (manga.sameMigrationIdentity(selected)) {
						MigrationManualSelectionCard(
							state = state,
							selected = manga,
							imageLoader = imageLoader,
							busy = busy,
							onOpenResult = onOpenResult,
							onMigrate = onMigrate,
							onCopy = onCopy,
							onClearSelection = onClearSelection,
						)
					}
					HorizontalDivider()
				}
			}
		}
	}
}

@Composable
private fun MigrationManualSelectionCard(
	state: MigrationManualSearchState,
	selected: Manga,
	imageLoader: ImageLoader,
	busy: Boolean,
	onOpenResult: (Manga) -> Unit,
	onMigrate: () -> Unit,
	onCopy: () -> Unit,
	onClearSelection: () -> Unit,
) {
	val context = LocalContext.current
	Card(Modifier.fillMaxWidth().testTag("migration-manual-confirmation")) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(stringResource(R.string.migration_current_selection), style = MaterialTheme.typography.labelLarge)
			Text(state.seed?.title.orEmpty())
			Text("→", style = MaterialTheme.typography.titleLarge)
			Text(stringResource(R.string.migration_new_selection), style = MaterialTheme.typography.labelLarge)
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				MigrationMangaCover(
					manga = selected,
					imageLoader = imageLoader,
					modifier = Modifier.width(68.dp).aspectRatio(0.72f),
				)
				Column(Modifier.weight(1f)) {
					Text(
						selected.title,
						style = MaterialTheme.typography.titleMedium,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
					Text(selected.source.getTitle(context), style = MaterialTheme.typography.bodySmall)
					selected.authors.firstOrNull()?.let { author ->
						Text(
							author,
							style = MaterialTheme.typography.bodySmall,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
				}
			}
			OutlinedButton(
				onClick = { onOpenResult(selected) },
				enabled = !busy,
				modifier = Modifier.fillMaxWidth().testTag("migration-manual-details"),
			) {
				Text(stringResource(R.string.hub_view_details))
			}
			Text(stringResource(R.string.migration_preserve_all_fixed), style = MaterialTheme.typography.bodySmall)
			Button(
				onClick = onMigrate,
				enabled = !busy,
				modifier = Modifier.fillMaxWidth().testTag("migration-manual-migrate"),
			) {
				if (state.migrating) {
					CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
					Spacer(Modifier.width(8.dp))
				}
				Text(stringResource(R.string.migration_yokai_migrate_now))
			}
			OutlinedButton(
				onClick = onCopy,
				enabled = !busy,
				modifier = Modifier.fillMaxWidth().testTag("migration-manual-copy"),
			) {
				Text(stringResource(R.string.migration_yokai_copy_now))
			}
			TextButton(onClick = onClearSelection, enabled = !busy) {
				Text(stringResource(R.string.migration_change_result))
			}
		}
	}
}

private fun Manga.sameMigrationIdentity(other: Manga?): Boolean =
	other != null && id == other.id && source.name == other.source.name

@Composable
private fun MigrationMangaCover(
	manga: Manga,
	imageLoader: ImageLoader,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val coverRequest = remember(manga.id, manga.coverUrl, manga.source) {
		manga.coverUrl?.takeIf { it.isNotBlank() }?.let { coverUrl ->
			ImageRequest.Builder(context)
				.data(coverUrl)
				.mangaSourceExtra(manga.source)
				.build()
		}
	}
	Box(
		modifier = modifier
			.clip(RoundedCornerShape(8.dp))
			.background(MaterialTheme.colorScheme.surfaceContainerHighest),
		contentAlignment = Alignment.Center,
	) {
		Icon(
			painter = painterResource(R.drawable.ic_placeholder),
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
			modifier = Modifier.size(32.dp),
		)
		if (coverRequest != null) {
			AsyncImage(
				model = coverRequest,
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
		}
	}
}
