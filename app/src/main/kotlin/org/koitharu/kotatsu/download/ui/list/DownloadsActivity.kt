package org.koitharu.kotatsu.download.ui.list

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.view.ActionMode
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.work.WorkInfo
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
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.RecyclerScrollKeeper
import org.koitharu.kotatsu.core.ui.miyorareViewPalette
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.databinding.ActivityDownloadsBinding
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.model.ListModel
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class DownloadsActivity : BaseActivity<ActivityDownloadsBinding>(),
	DownloadItemListener,
	ListSelectionController.Callback {

	@Inject
	lateinit var coil: ImageLoader

	@Inject
	lateinit var scheduler: DownloadWorker.Scheduler

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var visualEffectPreferences: VisualEffectPreferences

	private val viewModel by viewModels<DownloadsViewModel>()
	private lateinit var selectionController: ListSelectionController
	private var isModernDownloads = false
	private val isPrivateDownloads: Boolean
		get() = intent?.getIntExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.NORMAL.dbValue) ==
			FavouriteSpace.PRIVATE.dbValue

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityDownloadsBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		isModernDownloads = settings.miyorareDesignStyle == MiyorareDesignStyle.MODERN
		setupModernDownloadsHeader()
		val downloadsAdapter = DownloadsAdapter(this, this, isModernDownloads)
		val decoration = TypedListSpacingDecoration(this, false)
		selectionController = ListSelectionController(
			appCompatDelegate = delegate,
			decoration = DownloadsSelectionDecoration(this),
			registryOwner = this,
			callback = this,
		)
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			// Download rows receive frequent WorkManager progress payloads. Structural/change
			// animations add no useful information here and can fight an active user scroll.
			itemAnimator = null
			addItemDecoration(decoration)
			adapter = downloadsAdapter
			selectionController.attachToRecyclerView(this)
			RecyclerScrollKeeper(this).attach()
		}
		addMenuProvider(
			DownloadsMenuProvider(
				activity = this,
				viewModel = viewModel,
				useModernQuickControls = isModernDownloads,
			),
		)
		viewModel.items.observe(this, downloadsAdapter)
		if (isModernDownloads) {
			visualEffectPreferences.level.observe(this, ::applyModernDownloadsVisuals)
			viewModel.items.observe(this) { renderModernDownloadsHeader(it) }
		}
		viewModel.onActionDone.observeEvent(this, ReversibleActionObserver(viewBinding.recyclerView))
		val menuInvalidator = MenuInvalidator(this)
		viewModel.hasActiveWorks.observe(this, menuInvalidator)
		viewModel.hasPausedWorks.observe(this, menuInvalidator)
		viewModel.hasCancellableWorks.observe(this, menuInvalidator)
	}

	private fun setupModernDownloadsHeader() {
		viewBinding.modernDownloadsSummary.isVisible = isModernDownloads
		if (!isModernDownloads) return
		viewBinding.buttonPauseAll.setOnClickListener { viewModel.pauseAll() }
		viewBinding.buttonResumeAll.setOnClickListener { viewModel.resumeAll() }
	}

	/**
	 * Downloads intentionally uses the Clean visual class: flat semantic surfaces, one restrained
	 * outline and preset-aware status color. No background gradient or decorative glow is added to a
	 * screen that users need to scan quickly while work is active.
	 */
	private fun applyModernDownloadsVisuals(level: VisualEffectLevel) {
		val palette = miyorareViewPalette(settings, level)
		val density = resources.displayMetrics.density
		val cardRadius = MiyorareVisualTokens.RADIUS_SURFACE_DP * density
		val controlRadius = (MiyorareVisualTokens.RADIUS_CONTROL_DP * density).roundToInt()

		if (isPrivateDownloads) {
			viewBinding.root.setBackgroundColor(palette.background)
		} else {
			viewBinding.root.background = MiyorareHeaderShapeDrawable(
				palette = palette,
				variant = MiyorareHeaderShapeDrawable.Variant.APP_BACKGROUND,
				density = resources.displayMetrics.density,
			)
		}
		val lightMode = ColorUtils.calculateLuminance(palette.background) >= 0.5
		val chromeSurface = if (isPrivateDownloads) {
			palette.surface
		} else {
			ColorUtils.setAlphaComponent(palette.surface, if (lightMode) 204 else 218)
		}
		viewBinding.appbar.apply {
			setBackgroundColor(if (isPrivateDownloads) chromeSurface else Color.TRANSPARENT)
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

		viewBinding.modernDownloadsSummary.apply {
			setCardBackgroundColor(
				if (lightMode) ColorUtils.setAlphaComponent(palette.surfaceContainer, 218) else palette.surfaceContainer,
			)
			radius = cardRadius
			cardElevation = 0f
			strokeWidth = density.roundToInt().coerceAtLeast(1)
			strokeColor = palette.borderHighlight
		}
		viewBinding.modernDownloadsStatus.setTextColor(palette.onSurface)
		viewBinding.modernDownloadsIconContainer.setCardBackgroundColor(palette.selectedSurface)
		viewBinding.modernDownloadsIcon.imageTintList = ColorStateList.valueOf(palette.primary)
		viewBinding.modernDownloadsPercent.apply {
			setTextColor(palette.primary)
			backgroundTintList = ColorStateList.valueOf(palette.selectedSurface)
		}
		viewBinding.modernDownloadsProgress.apply {
			setIndicatorColor(palette.primary)
			trackColor = ColorUtils.setAlphaComponent(palette.outline, 36)
		}
		for (button in arrayOf(viewBinding.buttonPauseAll, viewBinding.buttonResumeAll)) {
			button.backgroundTintList = ColorStateList.valueOf(palette.selectedSurface)
			button.setTextColor(palette.primary)
			button.iconTint = ColorStateList.valueOf(palette.primary)
			button.cornerRadius = controlRadius
			button.strokeWidth = 0
		}
	}

	private fun renderModernDownloadsHeader(models: List<ListModel>) {
		val downloads = models.filterIsInstance<DownloadItemModel>()
		var active = 0
		var paused = 0
		var queued = 0
		var completed = 0
		var failed = 0
		var cancelled = 0
		val activeItems = ArrayList<DownloadItemModel>()

		for (item in downloads) {
			when (item.workState) {
				WorkInfo.State.RUNNING -> if (item.isPaused) {
					paused++
				} else {
					active++
					activeItems += item
				}

				WorkInfo.State.BLOCKED,
				WorkInfo.State.ENQUEUED -> queued++

				WorkInfo.State.SUCCEEDED -> completed++
				WorkInfo.State.FAILED -> failed++
				WorkInfo.State.CANCELLED -> cancelled++
			}
		}

		val statusParts = ArrayList<String>(6)
		if (active > 0) statusParts += "${getString(R.string.in_progress)} $active"
		if (paused > 0) statusParts += "${getString(R.string.paused)} $paused"
		if (queued > 0) statusParts += "${getString(R.string.queued)} $queued"
		if (completed > 0) statusParts += "${getString(R.string.download_complete)} $completed"
		if (failed > 0) statusParts += "${getString(R.string.error_occurred)} $failed"
		if (cancelled > 0) statusParts += "${getString(R.string.canceled)} $cancelled"
		viewBinding.modernDownloadsStatus.text = statusParts.joinToString("  •  ").ifEmpty {
			getString(R.string.text_downloads_list_holder)
		}

		viewBinding.modernDownloadsPercent.isVisible = false
		with(viewBinding.modernDownloadsProgress) {
			isVisible = activeItems.isNotEmpty()
			if (activeItems.isNotEmpty()) {
				// Do not let one startup/finalization job make the whole summary bar flip back to
				// indeterminate. Aggregate the workers that have measurable progress; use the
				// indeterminate animation only while none of the active jobs can be measured yet.
				val measurableItems = activeItems.filter { !it.isIndeterminate && it.max > 0 }
				val indeterminate = measurableItems.isEmpty()
				isIndeterminate = indeterminate
				if (!indeterminate) {
					val totalMax = measurableItems.sumOf { it.max.toLong() }
					val totalProgress = measurableItems.sumOf { it.progress.coerceAtMost(it.max).toLong() }
					val percent = if (totalMax > 0L) {
						((totalProgress * 100L) / totalMax).toInt().coerceIn(0, 100)
					} else {
						0
					}
					max = 100
					setProgressCompat(percent, true)
					viewBinding.modernDownloadsPercent.text = "$percent%"
					viewBinding.modernDownloadsPercent.isVisible = true
				}
			}
		}

		viewBinding.buttonPauseAll.isVisible = downloads.any { it.canPause }
		viewBinding.buttonResumeAll.isVisible = downloads.any { it.canResume }
		viewBinding.modernDownloadsControls.isVisible =
			viewBinding.buttonPauseAll.isVisible || viewBinding.buttonResumeAll.isVisible
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.recyclerView.updatePadding(
			left = bars.left,
			right = bars.right,
			bottom = bars.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
			top = bars.top,
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	override fun onItemClick(item: DownloadItemModel, view: View) {
		if (selectionController.onItemClick(item.id.mostSignificantBits)) {
			return
		}
		router.openDetails(item.manga ?: return)
	}

	override fun onItemLongClick(item: DownloadItemModel, view: View): Boolean {
		return selectionController.onItemLongClick(view, item.id.mostSignificantBits)
	}

	override fun onItemContextClick(item: DownloadItemModel, view: View): Boolean {
		return selectionController.onItemContextClick(view, item.id.mostSignificantBits)
	}

	override fun onExpandClick(item: DownloadItemModel) {
		if (!selectionController.onItemClick(item.id.mostSignificantBits)) {
			viewModel.expandCollapse(item)
		}
	}

	override fun onCancelClick(item: DownloadItemModel) {
		viewModel.cancel(item.id)
	}

	override fun onPauseClick(item: DownloadItemModel) {
		viewModel.pause(item.id)
	}

	override fun onResumeClick(item: DownloadItemModel) {
		viewModel.resume(item.id)
	}

	override fun onSkipClick(item: DownloadItemModel) {
		scheduler.skip(item.id)
	}

	override fun onSkipAllClick(item: DownloadItemModel) {
		scheduler.skipAll(item.id)
	}

	override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
		viewBinding.recyclerView.invalidateItemDecorations()
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu
	): Boolean {
		menuInflater.inflate(R.menu.mode_downloads, menu)
		return true
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_resume -> {
				viewModel.resume(controller.snapshot())
				mode?.finish()
				true
			}

			R.id.action_pause -> {
				viewModel.pause(controller.snapshot())
				mode?.finish()
				true
			}

			R.id.action_cancel -> {
				viewModel.cancel(controller.snapshot())
				mode?.finish()
				true
			}

			R.id.action_remove -> {
				viewModel.remove(controller.snapshot())
				mode?.finish()
				true
			}

			R.id.action_select_all -> {
				controller.addAll(viewModel.allIds())
				true
			}

			else -> false
		}
	}

	override fun onPrepareActionMode(controller: ListSelectionController, mode: ActionMode?, menu: Menu): Boolean {
		val snapshot = viewModel.snapshot(controller.peekCheckedIds())
		var canPause = true
		var canResume = true
		var canCancel = true
		var canRemove = true
		for (item in snapshot) {
			canPause = canPause and item.canPause
			canResume = canResume and item.canResume
			canCancel = canCancel and item.canCancel
			canRemove = canRemove and item.workState.isFinished && item.uiAction == null
		}
		menu.findItem(R.id.action_pause)?.isVisible = canPause
		menu.findItem(R.id.action_resume)?.isVisible = canResume
		menu.findItem(R.id.action_cancel)?.isVisible = canCancel
		menu.findItem(R.id.action_remove)?.isVisible = canRemove
		return super.onPrepareActionMode(controller, mode, menu)
	}
}
