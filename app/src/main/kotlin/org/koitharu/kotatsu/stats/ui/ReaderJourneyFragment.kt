package org.koitharu.kotatsu.stats.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.ui.util.ActivityRecreationHandle
import org.koitharu.kotatsu.core.util.ShareHelper
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.stats.domain.ReaderProfileShareModel
import org.koitharu.kotatsu.stats.domain.YearInReview
import org.koitharu.kotatsu.stats.share.ReaderProfileShareCard
import org.koitharu.kotatsu.stats.share.YearInReviewShareCard
import javax.inject.Inject

/**
 * Main-navigation host for Reader Journey.
 *
 * This intentionally reuses the existing StatsViewModel and StatsScreen rather than introducing a
 * second statistics implementation. StatsActivity remains available for legacy/deep-link entry
 * points while the primary app navigation can keep Reader Journey inside the normal Miyorare shell.
 */
@AndroidEntryPoint
class ReaderJourneyFragment : Fragment(), MenuProvider {

	@Inject
	lateinit var imageLoader: ImageLoader

	@Inject
	lateinit var activityRecreationHandle: ActivityRecreationHandle

	private val viewModel by viewModels<StatsViewModel>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				val stats by viewModel.stats.collectAsState()
				val isLoading by viewModel.isLoading.collectAsState()
				val period by viewModel.period.collectAsState()
				val scope by viewModel.scope.collectAsState()
				val matureMode by viewModel.matureMode.collectAsState()
				val selectedCategories by viewModel.selectedCategories.collectAsState()
				val categories by viewModel.favoriteCategories.collectAsState(emptyList())
				val readerProfile by viewModel.readerProfile.collectAsState()
				val yearInReview by viewModel.yearInReview.collectAsState()

				StatsScreen(
					stats = stats,
					isLoading = isLoading,
					period = period,
					scope = scope,
					matureMode = matureMode,
					categories = categories,
					selectedCategories = selectedCategories,
					imageLoader = imageLoader,
					profile = readerProfile,
					yearInReview = yearInReview,
					bottomInset = 0.dp,
					onPeriodChange = { viewModel.period.value = it },
					onScopeChange = { viewModel.scope.value = it },
					onMatureModeChange = viewModel::setMatureMode,
					onCategoryToggle = viewModel::toggleCategory,
					onCategoriesClear = viewModel::clearCategories,
					onProfileUpdate = viewModel::updateReaderProfile,
					onCosmeticsUpdate = { loadout ->
						viewModel.updateReaderCosmetics(loadout)
						view?.post { activityRecreationHandle.recreateAll() }
					},
					onShareReaderProfile = ::shareReaderProfile,
					onShareYearInReview = ::shareYearInReview,
					onMangaClick = { router.openDetails(it) },
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
		viewModel.onActionDone.observeEvent(
			viewLifecycleOwner,
			ReversibleActionObserver(view),
		)
	}

	private fun shareReaderProfile(model: ReaderProfileShareModel) {
		viewLifecycleOwner.lifecycleScope.launch {
			val context = requireContext()
			val uri = withContext(Dispatchers.Default) {
				ReaderProfileShareCard.renderToShareUri(context, model)
			}
			ShareHelper(context).shareImage(uri)
		}
	}

	private fun shareYearInReview(review: YearInReview) {
		viewLifecycleOwner.lifecycleScope.launch {
			val context = requireContext()
			val uri = withContext(Dispatchers.Default) {
				YearInReviewShareCard.renderToShareUri(context, review)
			}
			ShareHelper(context).shareImage(uri)
		}
	}

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_stats, menu)
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_clear -> {
			showClearConfirmDialog()
			true
		}

		else -> false
	}

	private fun showClearConfirmDialog() {
		buildAlertDialog(requireContext(), isCentered = true) {
			setMessage(R.string.clear_stats_confirm)
			setTitle(R.string.clear_stats)
			setIcon(R.drawable.ic_delete_all)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.clear) { _, _ -> viewModel.clearStats() }
		}.show()
	}
}
