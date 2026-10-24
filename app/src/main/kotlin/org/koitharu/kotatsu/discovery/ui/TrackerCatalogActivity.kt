package org.koitharu.kotatsu.discovery.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.databinding.ActivityTrackerCatalogBinding
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TrackerCatalogActivity : BaseActivity<ActivityTrackerCatalogBinding>() {
	@Inject lateinit var imageLoader: ImageLoader
	private val viewModel by viewModels<TrackerCatalogViewModel>()
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityTrackerCatalogBinding.inflate(layoutInflater))
		viewBinding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		viewBinding.composeView.setContent { MiyorareTheme {
			val state by viewModel.state.collectAsState()
			TrackerCatalogScreen(state, viewModel.section, imageLoader,
				onBack = { onBackPressedDispatcher.onBackPressed() }, onQuery = viewModel::updateQuery, onSearch = viewModel::reload,
				onFilter = viewModel::selectFilter, onRetry = viewModel::retry, onNext = viewModel::nextPage,
				onItem = { startActivity(detailsIntent(this, it)) },
				onProviderPage = { state.details?.item?.providerUrl?.let { router.openExternalBrowser(it) } },
				onRead = viewModel::read,
				onAllSources = { viewModel.startResolution(allSources = true) }, onCloseResolution = viewModel::closeResolution,
				onGlobalSearch = { viewModel.closeResolution(); state.details?.item?.let { router.openSearch(it.title, org.koitharu.kotatsu.search.domain.SearchKind.TITLE, it.key.kind == CatalogMediaKind.NOVEL) } },
				onSource = viewModel::chooseSource,
			)
		} }
		lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.openSource.collect { router.openDetails(it) } } }
	}
	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	companion object {
		fun detailsIntent(context: Context, key: TrackerCatalogKey): Intent = Intent(context, TrackerCatalogActivity::class.java)
			.putExtra(CatalogArguments.MODE, CatalogScreenMode.DETAILS.name).putExtra(CatalogArguments.SERVICE, key.service.name)
			.putExtra(CatalogArguments.KIND, key.kind.name).putExtra(CatalogArguments.ID, key.remoteId)
		fun searchIntent(context: Context, service: ScrobblerService, filter: CatalogFilter): Intent = Intent(context, TrackerCatalogActivity::class.java)
			.putExtra(CatalogArguments.MODE, CatalogScreenMode.SEARCH.name).putExtra(CatalogArguments.SERVICE, service.name).putExtra(CatalogArguments.FILTER, filter.name)
		fun sectionIntent(context: Context, key: CatalogSectionKey): Intent = Intent(context, TrackerCatalogActivity::class.java)
			.putExtra(CatalogArguments.MODE, CatalogScreenMode.SECTION.name).putExtra(CatalogArguments.SERVICE, key.service.name)
			.putExtra(CatalogArguments.KIND, key.kind.name).putExtra(CatalogArguments.SECTION, key.type.name)
	}
}
