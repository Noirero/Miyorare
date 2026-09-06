package org.koitharu.kotatsu.download.ui.list

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.view.ActionMode
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
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.RecyclerScrollKeeper
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.databinding.ActivityDownloadsBinding
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.model.ListModel
import javax.inject.Inject

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

	private val viewModel by viewModels<DownloadsViewModel>()
	private lateinit var selectionController: ListSelectionController
	private var isModernDownloads = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityDownloadsBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		isModernDownloads = settings.miyorareDesignStyle == MiyorareDesignStyle.MODERN
		setupModernDownloadsHeader()
		val downloadsAdapter = DownloadsAdapter(this, this)
		val decoration = TypedListSpacingDecoration(this, false)
		selectionController = ListSelectionController(
			appCompatDelegate = delegate,
			decoration = DownloadsSelectionDecoration(this),
			registryOwner = this,
			callback = this,
		)
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
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

		with(viewBinding.modernDownloadsProgress) {
			isVisible = activeItems.isNotEmpty()
			if (activeItems.isNotEmpty()) {
				val indeterminate = activeItems.any { it.isIndeterminate || it.max <= 0 }
				isIndeterminate = indeterminate
				if (!indeterminate) {
					val totalMax = activeItems.sumOf { it.max.toLong() }
					val totalProgress = activeItems.sumOf { it.progress.coerceAtMost(it.max).toLong() }
					val percent = if (totalMax > 0L) {
						((totalProgress * 100L) / totalMax).toInt().coerceIn(0, 100)
					} else {
						0
					}
					max = 100
					setProgressCompat(percent, true)
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
		// Stop the worker from starting any more page work immediately while WorkManager performs
		// cancellation/cleanup. The worker owns a pause receiver for its whole RUNNING lifetime.
		scheduler.pause(item.id)
		viewModel.cancel(item.id)
	}

	override fun onPauseClick(item: DownloadItemModel) {
		scheduler.pause(item.id)
	}

	override fun onResumeClick(item: DownloadItemModel) {
		scheduler.resume(item.id)
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
			canCancel = canCancel and !item.workState.isFinished
			canRemove = canRemove and item.workState.isFinished
		}
		menu.findItem(R.id.action_pause)?.isVisible = canPause
		menu.findItem(R.id.action_resume)?.isVisible = canResume
		menu.findItem(R.id.action_cancel)?.isVisible = canCancel
		menu.findItem(R.id.action_remove)?.isVisible = canRemove
		return super.onPrepareActionMode(controller, mode, menu)
	}
}
