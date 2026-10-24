package org.koitharu.kotatsu.discovery.ui

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
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import javax.inject.Inject

@AndroidEntryPoint
class DiscoveryHubFragment : Fragment() {
	@Inject lateinit var imageLoader: ImageLoader
	private val viewModel by viewModels<DiscoveryHubViewModel>()
	private val scrollRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
	fun scrollToTop() { scrollRequests.tryEmit(Unit) }
	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = ComposeView(requireContext()).apply {
		id = R.id.discovery_hub_compose
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent { MiyorareTheme {
			val state by viewModel.state.collectAsState()
			DiscoveryHubScreen(state, viewModel.services, imageLoader, viewModel::selectProvider, viewModel::selectFilter, viewModel::refresh,
				onSearch = { startActivity(TrackerCatalogActivity.searchIntent(requireContext(), state.request.service, state.request.filter)) },
				onItem = { startActivity(TrackerCatalogActivity.detailsIntent(requireContext(), it)) },
				onSection = { startActivity(TrackerCatalogActivity.sectionIntent(requireContext(), it)) },
				onAttribution = { router.openExternalBrowser(when (state.request.service) { ScrobblerService.ANILIST -> "https://anilist.co"; ScrobblerService.MAL -> "https://myanimelist.net"; else -> "https://kitsu.app" }) },
				scrollRequests = scrollRequests,
			)
		} }
	}
}
