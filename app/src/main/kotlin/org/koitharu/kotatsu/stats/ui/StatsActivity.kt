package org.koitharu.kotatsu.stats.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePaddingRelative
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.MiyorareHeaderShapeDrawable
import org.koitharu.kotatsu.core.ui.miyorareViewPalette
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.end
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.start
import org.koitharu.kotatsu.databinding.ActivityStatsBinding
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import javax.inject.Inject

/**
 * Reading statistics, rendered with Jetpack Compose inside the same medium collapsing app bar the
 * settings screens use — see [StatsScreen] for the content. The activity only owns the window: the
 * toolbar, the destructive "clear" dialog and the undo snackbar.
 */
@AndroidEntryPoint
class StatsActivity : BaseActivity<ActivityStatsBinding>() {

	@Inject
	lateinit var coil: ImageLoader

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var visualEffectPreferences: VisualEffectPreferences

	private val viewModel: StatsViewModel by viewModels()

	private val bottomInset = mutableIntStateOf(0)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityStatsBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		setTitle(R.string.reader_journey)
		if (settings.miyorareDesignStyle == MiyorareDesignStyle.MODERN) {
			visualEffectPreferences.level.observe(this, ::applyModernStatsBackground)
		}
		viewBinding.composeView.setViewCompositionStrategy(
			ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
		)
		viewBinding.composeView.setContent {
			MiyorareTheme {
				val density = LocalDensity.current
				val stats by viewModel.stats.collectAsState()
				val isLoading by viewModel.isLoading.collectAsState()
				val period by viewModel.period.collectAsState()
				val scope by viewModel.scope.collectAsState()
				val matureMode by viewModel.matureMode.collectAsState()
				val selectedCategories by viewModel.selectedCategories.collectAsState()
				val categories by viewModel.favoriteCategories.collectAsState(emptyList())
				val readerProfile by viewModel.readerProfile.collectAsState()

				StatsScreen(
					stats = stats,
					isLoading = isLoading,
					period = period,
					scope = scope,
					matureMode = matureMode,
					categories = categories,
					selectedCategories = selectedCategories,
					imageLoader = coil,
					profile = readerProfile,
					bottomInset = with(density) { bottomInset.intValue.toDp() },
					onPeriodChange = { viewModel.period.value = it },
					onScopeChange = { viewModel.scope.value = it },
					onMatureModeChange = viewModel::setMatureMode,
					onCategoryToggle = viewModel::toggleCategory,
					onCategoriesClear = viewModel::clearCategories,
					onProfileUpdate = viewModel::updateReaderProfile,
					onMangaClick = { router.openDetails(it) },
				)
			}
		}
		viewModel.onActionDone.observeEvent(this, ReversibleActionObserver(viewBinding.composeView))
	}


	private fun applyModernStatsBackground(level: VisualEffectLevel) {
		val palette = miyorareViewPalette(settings, level)
		viewBinding.root.background = MiyorareHeaderShapeDrawable(
			palette = palette,
			variant = MiyorareHeaderShapeDrawable.Variant.APP_BACKGROUND,
			density = resources.displayMetrics.density,
		)
		val chromeSurface = ColorUtils.setAlphaComponent(
			palette.surface,
			if (ColorUtils.calculateLuminance(palette.background) >= 0.5) 204 else 218,
		)
		viewBinding.appbar.apply {
			setBackgroundColor(Color.TRANSPARENT)
			elevation = 0f
		}
		viewBinding.collapsingToolbarLayout.apply {
			setContentScrimColor(chromeSurface)
			setStatusBarScrimColor(chromeSurface)
			setCollapsedTitleTextColor(palette.onSurface)
			setExpandedTitleColor(palette.onSurface)
		}
		viewBinding.toolbar.apply {
			setBackgroundColor(Color.TRANSPARENT)
			setTitleTextColor(palette.onSurface)
			navigationIcon?.setTint(palette.onSurface)
			overflowIcon?.setTint(palette.onSurfaceVariant)
		}
	}


	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		menuInflater.inflate(R.menu.opt_stats, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		R.id.action_clear -> {
			showClearConfirmDialog()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.appbar.updatePaddingRelative(
			start = bars.start(v),
			top = bars.top,
			end = bars.end(v),
		)
		bottomInset.intValue = bars.bottom
		return insets
	}

	private fun showClearConfirmDialog() {
		buildAlertDialog(this, isCentered = true) {
			setMessage(R.string.clear_stats_confirm)
			setTitle(R.string.clear_stats)
			setIcon(R.drawable.ic_delete_all)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.clear) { _, _ -> viewModel.clearStats() }
		}.show()
	}
}
