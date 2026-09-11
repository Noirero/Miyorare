package org.koitharu.kotatsu.details.ui.related

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject

@AndroidEntryPoint
class RelatedListFragment : Fragment() {

	@Inject lateinit var imageLoader: ImageLoader
	private val viewModel by viewModels<RelatedListViewModel>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val state by viewModel.state.collectAsState()
				RelatedGroupsScreen(
					state = state,
					imageLoader = imageLoader,
					onRetry = viewModel::retry,
					onMangaClick = { router.openDetails(it) },
					onShowAll = { keyword ->
						router.openList(
							viewModel.source,
							MangaListFilter(query = keyword),
							null,
						)
					},
				)
			}
		}
	}

	override fun onStart() {
		super.onStart()
		viewModel.resumeIfNeeded()
	}

	override fun onStop() {
		viewModel.pause()
		super.onStop()
	}
}
