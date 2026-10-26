package org.koitharu.kotatsu.discovery.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.ImageLoader
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.MiyorareHeaderShapeDrawable
import org.koitharu.kotatsu.core.ui.miyorareViewPalette
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.ActivityTrackerCatalogBinding
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.ui.ScrobblerAuthHelper
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import javax.inject.Inject

@AndroidEntryPoint
class TrackerCatalogActivity : BaseActivity<ActivityTrackerCatalogBinding>() {
	@Inject lateinit var imageLoader: ImageLoader
	@Inject lateinit var settings: AppSettings
	@Inject lateinit var visualEffectPreferences: VisualEffectPreferences
	@Inject lateinit var scrobblerAuthHelper: ScrobblerAuthHelper

	private val viewModel by viewModels<TrackerCatalogViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityTrackerCatalogBinding.inflate(layoutInflater))
		if (settings.miyorareDesignStyle == MiyorareDesignStyle.MODERN) {
			visualEffectPreferences.level.observe(this, ::applyModernCatalogBackground)
			viewBinding.composeView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
		}
		viewBinding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		viewBinding.composeView.setContent {
			MiyorareTheme {
				CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
					val state by viewModel.state.collectAsState()
					val account by viewModel.account.collectAsState()
					val tracking by viewModel.tracking.collectAsState()
					val trackingEditor by viewModel.trackingEditor.collectAsState()
					val knownTracking by viewModel.knownTracking.collectAsState()
					TrackerCatalogScreen(
						state = state,
						section = viewModel.section,
						imageLoader = imageLoader,
						onBack = { onBackPressedDispatcher.onBackPressed() },
						onQuery = viewModel::updateQuery,
						onSearch = viewModel::reload,
						onFilter = viewModel::selectFilter,
						onRetry = viewModel::retry,
						onNext = viewModel::nextPage,
						onItem = { startActivity(detailsIntent(this, it)) },
						onProviderPage = { state.details?.item?.providerUrl?.let { router.openExternalBrowser(it) } },
						onRead = viewModel::read,
						onAllSources = { viewModel.startResolution(allSources = true) },
						onCloseResolution = viewModel::closeResolution,
						onGlobalSearch = {
						viewModel.closeResolution()
						state.details?.item?.let {
							router.openSearch(it.title, org.koitharu.kotatsu.search.domain.SearchKind.TITLE, it.key.kind == CatalogMediaKind.NOVEL)
						}
					},
						onSource = viewModel::chooseSource,
						account = account,
						tracking = tracking,
						trackingEditor = trackingEditor,
						knownTracking = knownTracking,
						onLogin = { startAccountAuth(state.service) },
						onAddTracking = viewModel::openTrackingEditor,
						onSaveTracking = viewModel::saveTracking,
						onCloseTrackingEditor = viewModel::closeTrackingEditor,
						onRemoveTracking = viewModel::removeTracking,
						onManageTracking = { router.openScrobblerSettings(state.service) },
					)
				}
			}
		}
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.RESUMED) {
				launch { viewModel.openSource.collect { router.openDetails(it) } }
				launch { viewModel.trackingFeedback.collect(::showTrackingFeedback) }
			}
		}
	}

	override fun onResume() {
		super.onResume()
		viewModel.refreshAccount()
	}

	private fun startAccountAuth(service: ScrobblerService) {
		scrobblerAuthHelper.startAuth(this, service).onFailure {
			Snackbar.make(viewBinding.root, it.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
		}
	}

	private fun showTrackingFeedback(feedback: CatalogTrackingFeedback) {
		val provider = catalogProviderName(feedback.service)
		val message = when (feedback.action) {
			CatalogTrackingFeedbackAction.ADDED -> feedback.status?.let {
				getString(R.string.hub_tracking_feedback_added_status, provider, getString(catalogStatusLabel(it)))
			} ?: getString(R.string.hub_tracking_feedback_added, provider)
			CatalogTrackingFeedbackAction.UPDATED -> getString(R.string.hub_tracking_feedback_updated, provider)
			CatalogTrackingFeedbackAction.REMOVED -> getString(R.string.hub_tracking_feedback_removed, provider)
		}
		Snackbar.make(viewBinding.root, message, Snackbar.LENGTH_SHORT).show()
	}

	private fun applyModernCatalogBackground(level: VisualEffectLevel) {
		val palette = miyorareViewPalette(settings, level)
		viewBinding.root.background = MiyorareHeaderShapeDrawable(
			palette = palette,
			variant = MiyorareHeaderShapeDrawable.Variant.APP_BACKGROUND,
			density = resources.displayMetrics.density,
		)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	companion object {
		fun detailsIntent(context: Context, key: TrackerCatalogKey): Intent = Intent(context, TrackerCatalogActivity::class.java)
			.putExtra(CatalogArguments.MODE, CatalogScreenMode.DETAILS.name)
			.putExtra(CatalogArguments.SERVICE, key.service.name)
			.putExtra(CatalogArguments.KIND, key.kind.name)
			.putExtra(CatalogArguments.ID, key.remoteId)

		fun searchIntent(context: Context, service: ScrobblerService, filter: CatalogFilter): Intent = Intent(context, TrackerCatalogActivity::class.java)
			.putExtra(CatalogArguments.MODE, CatalogScreenMode.SEARCH.name)
			.putExtra(CatalogArguments.SERVICE, service.name)
			.putExtra(CatalogArguments.FILTER, filter.name)

		fun sectionIntent(context: Context, key: CatalogSectionKey): Intent = Intent(context, TrackerCatalogActivity::class.java)
			.putExtra(CatalogArguments.MODE, CatalogScreenMode.SECTION.name)
			.putExtra(CatalogArguments.SERVICE, key.service.name)
			.putExtra(CatalogArguments.KIND, key.kind.name)
			.putExtra(CatalogArguments.SECTION, key.type.name)
	}
}
