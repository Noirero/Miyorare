package org.koitharu.kotatsu.settings.sources.migration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.doOnDetach
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchDiscoveryMode
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchStatus
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import javax.inject.Inject

@AndroidEntryPoint
class SourceMangaListFragment : BaseComposeSettingsFragment(0) {

	@Inject
	lateinit var imageLoader: ImageLoader

	private val viewModel by viewModels<SourceMangaListViewModel>()
	private var actionMode: ActionMode? = null

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				val state by viewModel.state.collectAsState()
				SourceMangaListScreen(
					state = state,
					imageLoader = imageLoader,
					onRetry = viewModel::retry,
					onSearch = viewModel::searchAll,
					onMigrateReady = viewModel::migrateReady,
					onCopyReady = viewModel::copyReady,
					onCancel = viewModel::cancelBatch,
					onFilter = viewModel::setFilter,
					onToggleSource = viewModel::toggleSource,
					onSelectAllSources = viewModel::selectAllSources,
					onClearSources = viewModel::clearSources,
					onMoveSource = viewModel::moveSource,
					onStrategy = viewModel::setSourceStrategy,
					onExtraSearch = viewModel::setExtraSearchParams,
					onSkipSetup = viewModel::setSkipSetup,
					onRetrySources = viewModel::reloadSearchSources,
					onMangaClick = { manga ->
						if (state.selectedIds.isEmpty()) {
							val snapshot = state.batchItems[manga.id]
							val selection = snapshot?.outcome?.selection?.takeIf {
								snapshot.discoveryMode == MigrationBatchDiscoveryMode.YOKAI &&
									snapshot.configKey == state.configKey
							}
							(requireActivity() as SettingsActivity).openFragment(
								MigrationManualSearchFragment::class.java,
								MigrationManualSearchFragment.args(
									mangaId = manga.id,
									selectedSourceNames = state.selectedSourceNames,
									strategy = state.sourceStrategy,
									extraSearchParams = state.extraSearchParams,
									configKey = state.configKey,
									initialTargetSource = selection?.source,
									initialTargetId = selection?.id,
								),
								false,
							)
						} else {
							viewModel.toggleSelection(manga.id)
						}
					},
					onMangaLongClick = { viewModel.toggleSelection(it.id) },
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		val appBar = (requireActivity() as AppBarOwner).appBar
		val listener = AppBarLayout.OnOffsetChangedListener { bar, offset -> view.updatePadding(bottom = bar.totalScrollRange + offset) }
		appBar.addOnOffsetChangedListener(listener)
		view.doOnDetach { appBar.removeOnOffsetChangedListener(listener) }
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				viewModel.state
					.map { it.selectedIds }
					.distinctUntilChanged()
					.collect(::syncActionMode)
			}
		}
	}

	override fun onResume() {
		super.onResume()
		requireActivity().title = viewModel.sourceTitle
		viewModel.refresh()
	}

	private fun syncActionMode(selectedIds: Set<Long>) {
		if (selectedIds.isEmpty()) {
			actionMode?.finish()
			return
		}
		if (actionMode == null) {
			actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(actionModeCallback)
		}
		actionMode?.title = selectedIds.size.toString()
		actionMode?.invalidate()
	}

	private val actionModeCallback = object : ActionMode.Callback {
		override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
			menu.add(Menu.NONE, ACTION_REMOVE, Menu.NONE, R.string.remove_from_favourites_and_history).apply {
				setIcon(R.drawable.ic_delete)
				setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
			}
			menu.add(Menu.NONE, ACTION_SELECT_RANGE, Menu.NONE, R.string.select_range).apply {
				setIcon(R.drawable.ic_select_range)
				setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
			}
			menu.add(Menu.NONE, ACTION_SELECT_ALL, Menu.NONE, android.R.string.selectAll).apply {
				setIcon(R.drawable.ic_select_group)
				setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
			}
			return true
		}

		override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
			val state = viewModel.state.value
			mode.title = state.selectedIds.size.toString()
			menu.findItem(ACTION_SELECT_ALL)?.isVisible = state.selectedIds.size < state.visibleManga.size
			val selectedIndices = state.visibleManga.mapIndexedNotNull { index, manga -> index.takeIf { manga.id in state.selectedIds } }
			menu.findItem(ACTION_SELECT_RANGE)?.isVisible =
				selectedIndices.size >= 2 && selectedIndices.last() - selectedIndices.first() + 1 > selectedIndices.size
			return true
		}

		override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
			return when (item.itemId) {
				ACTION_REMOVE -> {
					MaterialAlertDialogBuilder(requireContext())
						.setTitle(R.string.remove_from_favourites_and_history)
						.setMessage(R.string.remove_from_favourites_and_history_confirm)
						.setNegativeButton(android.R.string.cancel, null)
						.setPositiveButton(R.string.remove) { _, _ ->
							viewModel.removeSelected()
							mode.finish()
						}
						.show()
					true
				}
				ACTION_SELECT_RANGE -> { viewModel.selectRange(); true }
				ACTION_SELECT_ALL -> { viewModel.selectAll(); true }
				else -> false
			}
		}

		override fun onDestroyActionMode(mode: ActionMode) {
			actionMode = null
			viewModel.clearSelection()
		}
	}

	companion object {
		private const val ACTION_REMOVE = 0x524D56
		private const val ACTION_SELECT_RANGE = 0x524E47
		private const val ACTION_SELECT_ALL = 0x53414C
		const val ARG_SOURCE_KEYS = "source_keys"
		const val ARG_SOURCE_TITLE = "source_title"
		const val ARG_MANGA_IDS = "manga_ids"

		fun args(sourceKeys: Collection<String>, sourceTitle: String) = Bundle(2).apply {
			putStringArray(ARG_SOURCE_KEYS, sourceKeys.toTypedArray())
			putString(ARG_SOURCE_TITLE, sourceTitle)
		}

		fun argsForMangaIds(mangaIds: Collection<Long>, title: String) = Bundle(2).apply {
			putLongArray(ARG_MANGA_IDS, mangaIds.toLongArray())
			putString(ARG_SOURCE_TITLE, title)
		}
	}
}

@Composable
private fun SourceMangaListScreen(
	state: SourceMangaListState,
	imageLoader: ImageLoader,
	onRetry: () -> Unit,
	onSearch: () -> Unit,
	onMigrateReady: () -> Unit,
	onCopyReady: () -> Unit,
	onCancel: () -> Unit,
	onFilter: (MigrationBatchStatus?) -> Unit,
	onToggleSource: (String) -> Unit,
	onSelectAllSources: () -> Unit,
	onClearSources: () -> Unit,
	onMoveSource: (String, Int) -> Unit,
	onStrategy: (org.koitharu.kotatsu.alternatives.domain.MigrationSourceStrategy) -> Unit,
	onExtraSearch: (String) -> Unit,
	onSkipSetup: (Boolean) -> Unit,
	onRetrySources: () -> Unit,
	onMangaClick: (Manga) -> Unit,
	onMangaLongClick: (Manga) -> Unit,
) {
	if (state.isLoading) {
		Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
		return
	}
	LazyColumn(
		modifier = Modifier.fillMaxSize().nestedScroll(rememberNestedScrollInteropConnection()).navigationBarsPadding(),
	) {
		if (state.failed) item {
			Text(stringResource(R.string.error_occurred), modifier = Modifier.padding(horizontal = 16.dp))
			androidx.compose.material3.TextButton(onClick = onRetry) {
				Text(stringResource(R.string.migration_retry))
			}
		}
		if (state.manga.isNotEmpty()) item {
			MigrationBatchControls(
				state = state,
				onSearch = onSearch,
				onMigrateReady = onMigrateReady,
				onCopyReady = onCopyReady,
				onCancel = onCancel,
				onFilter = onFilter,
				onToggleSource = onToggleSource,
				onSelectAllSources = onSelectAllSources,
				onClearSources = onClearSources,
				onMoveSource = onMoveSource,
				onStrategy = onStrategy,
				onExtraSearch = onExtraSearch,
				onSkipSetup = onSkipSetup,
				onRetrySources = onRetrySources,
			)
		}
		if (state.manga.isEmpty() && !state.failed) item {
			Text(stringResource(R.string.migration_batch_empty), modifier = Modifier.padding(20.dp))
		}
		items(state.visibleManga, key = Manga::id) { manga ->
			SourceMangaRow(
				manga = manga,
				imageLoader = imageLoader,
				isSelected = manga.id in state.selectedIds,
				status = state.status(manga.id),
				onClick = { onMangaClick(manga) },
				onLongClick = { onMangaLongClick(manga) },
			)
			HorizontalDivider(
				modifier = Modifier.padding(start = 92.dp, end = 16.dp),
				color = MaterialTheme.colorScheme.outlineVariant,
			)
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceMangaRow(
	manga: Manga,
	imageLoader: ImageLoader,
	isSelected: Boolean,
	status: MigrationBatchStatus,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
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
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 8.dp, vertical = 2.dp)
			.clip(RoundedCornerShape(12.dp))
			.heightIn(min = 84.dp)
			.background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
			.then(
				if (isSelected) {
					Modifier.border(
						width = 1.dp,
						color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
						shape = RoundedCornerShape(12.dp),
					)
				} else Modifier,
			)
			.combinedClickable(onClick = onClick, onLongClick = onLongClick)
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			modifier = Modifier
				.width(60.dp)
				.aspectRatio(0.72f)
				.clip(RoundedCornerShape(8.dp))
				.background(MaterialTheme.colorScheme.surfaceContainerHighest),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				painter = painterResource(R.drawable.ic_placeholder),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
				modifier = Modifier.size(36.dp),
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
		Spacer(Modifier.width(16.dp))
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = manga.title,
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 2,
			)
			manga.authors.firstOrNull()?.let { author ->
				Text(
					text = author,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
				)
			}
			Text(stringResource(status.labelRes()), style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.primary)
		}
	}
}
