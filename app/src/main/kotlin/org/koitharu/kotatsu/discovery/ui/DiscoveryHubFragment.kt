package org.koitharu.kotatsu.discovery.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.ImageLoader
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.main.ui.MainActivity
import org.koitharu.kotatsu.main.ui.hasSharedSearchBar
import org.koitharu.kotatsu.main.ui.installSharedSearchBarOwner
import org.koitharu.kotatsu.main.ui.releaseSharedSearchBarOwner
import org.koitharu.kotatsu.main.ui.updateSharedSearchBarHint
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.ui.ScrobblerAuthHelper
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import javax.inject.Inject

@AndroidEntryPoint
class DiscoveryHubFragment : Fragment() {
	@Inject lateinit var imageLoader: ImageLoader
	@Inject lateinit var scrobblerAuthHelper: ScrobblerAuthHelper
	private val viewModel by viewModels<DiscoveryHubViewModel>()
	private val scrollRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

	fun scrollToTop() { scrollRequests.tryEmit(Unit) }

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = ComposeView(requireContext()).apply {
		id = R.id.discovery_hub_compose
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent { MiyorareTheme {
			CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
				val state by viewModel.state.collectAsState()
				val account by viewModel.account.collectAsState()
				val knownTracking by viewModel.knownTracking.collectAsState()
				val mangaBakaPersonal by viewModel.mangaBakaPersonal.collectAsState()
				DiscoveryHubScreen(
					state = state,
					services = viewModel.services,
					imageLoader = imageLoader,
					onProvider = viewModel::selectProvider,
					onFilter = viewModel::selectFilter,
					onRefresh = viewModel::refresh,
					onSearch = ::openTrackerSearch,
					onItem = { startActivity(TrackerCatalogActivity.detailsIntent(requireContext(), it)) },
					onSection = { startActivity(TrackerCatalogActivity.sectionIntent(requireContext(), it)) },
					onAttribution = { router.openExternalBrowser(providerHome(state.request.service)) },
					scrollRequests = scrollRequests,
					showLocalSearch = (activity as? MainActivity)?.hasSharedSearchBar() != true,
					account = account,
					knownTracking = knownTracking,
					mangaBakaPersonal = mangaBakaPersonal,
					onAccountLogin = { startAccountAuth(state.request.service) },
					onAccountProfile = { account.profileUrl?.let(router::openExternalBrowser) },
					onAccountMyList = { router.openScrobblerSettings(state.request.service) },
					onAccountManage = router::openSettings,
					onAccountLogout = viewModel::logoutAccount,
					onMangaBakaPersonalRetry = viewModel::retryMangaBakaPersonal,
					onMangaBakaReconnect = { startAccountAuth(ScrobblerService.MANGABAKA) },
					onMangaBakaLibrarySeeAll = { router.openExternalBrowser(MANGABAKA_LIBRARY_URL) },
					onMangaBakaExploreType = viewModel::selectMangaBakaExploreType,
				)
			}
		} }
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				viewModel.state.collect { state ->
					if (!isHidden) {
						(activity as? MainActivity)?.updateSharedSearchBarHint(this@DiscoveryHubFragment, searchHint(state.request.service))
					}
				}
			}
		}
		if (!isHidden) installDiscoverySearchHandler()
	}

	override fun onResume() {
		super.onResume()
		viewModel.refreshAccount()
		if (!isHidden) installDiscoverySearchHandler()
	}

	override fun onHiddenChanged(hidden: Boolean) {
		super.onHiddenChanged(hidden)
		if (hidden) {
			releaseDiscoverySearchHandler()
		} else if (view != null) {
			viewModel.refreshAccount()
			installDiscoverySearchHandler()
		}
	}

	override fun onDestroyView() {
		releaseDiscoverySearchHandler()
		super.onDestroyView()
	}

	private fun startAccountAuth(service: ScrobblerService) {
		scrobblerAuthHelper.startAuth(requireContext(), service).onFailure {
			Snackbar.make(requireView(), it.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
		}
	}

	private fun installDiscoverySearchHandler() {
		val host = activity as? MainActivity ?: return
		val request = viewModel.state.value.request
		host.installSharedSearchBarOwner(
			owner = this,
			hint = searchHint(request.service),
			onClick = ::openTrackerSearch,
		)
	}

	private fun releaseDiscoverySearchHandler() {
		(activity as? MainActivity)?.releaseSharedSearchBarOwner(this)
	}

	private fun searchHint(service: ScrobblerService): String =
		getString(R.string.hub_search_hint_provider, catalogProviderName(service))

	private fun providerHome(service: ScrobblerService): String = when (service) {
		ScrobblerService.ANILIST -> "https://anilist.co"
		ScrobblerService.MAL -> "https://myanimelist.net"
		ScrobblerService.KITSU -> "https://kitsu.app"
		ScrobblerService.MANGABAKA -> "https://mangabaka.org"
		else -> error("Unsupported Tracker Home provider $service")
	}

	private fun openTrackerSearch() {
		val request = viewModel.state.value.request
		startActivity(TrackerCatalogActivity.searchIntent(requireContext(), request.service, request.filter))
	}

	private companion object {
		const val MANGABAKA_LIBRARY_URL = "https://mangabaka.org/my/library"
	}
}
