package org.koitharu.kotatsu.download.ui.list

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.activity.viewModels
import androidx.appcompat.view.ActionMode
import androidx.core.graphics.ColorUtils
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
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
		viewBinding.toolbar.post { decorateToolbarIconButtons(viewBinding.toolbar) }
	}

	private fun decorateToolbarIconButtons(root: ViewGroup) {
		val density = resources.displayMetrics.density
		for (index in 0 until root.childCount) {
			val child = root.getChildAt(index)
			if (child is ImageButton) {
				child.background = GradientDrawable().apply {
					shape = GradientDrawable.OVAL
					setColor(Color.argb(132, 7, 13, 23))
					setStroke((density).roundToInt().coerceAtLeast(1), Color.argb(118, 111, 151, 198))
				}
				val size = (44f * density).roundToInt()
				child.layoutParams = child.layoutParams.apply {
					width = size
					height = size
				}
				val padding = (10f * density).roundToInt()
				child.setPadding(padding, padding, padding, padding)
			} else if (child is ViewGroup) {
				decorateToolbarIconButtons(child)
			}
		}
	}

	/**
	 * Golden-reference Downloads treatment. The background blur is still produced once by the shared
	 * app-background renderer; cards use static translucent fills, thin strokes and finite highlights
	 * so scrolling stays cheap while matching the cinematic blue/amber reference.
	 */
	private fun applyModernDownloadsVisuals(level: VisualEffectLevel) {
		val palette = miyorareViewPalette(settings, level)
		val density = resources.displayMetrics.density
		val goldenAmber = Color.rgb(255, 182, 84)
		val goldenCyan = Color.rgb(69, 230, 244)
		val goldenText = Color.rgb(245, 243, 250)
		val goldenMuted = Color.rgb(168, 173, 191)
		val glassSurface = Color.rgb(6, 12, 20)
		val cardRadius = 24f * density
		val controlRadius = (24f * density).roundToInt()
		val strokeWidth = density.roundToInt().coerceAtLeast(1)

		if (isPrivateDownloads) {
			viewBinding.root.setBackgroundColor(palette.background)
		} else {
			viewBinding.root.background = MiyorareHeaderShapeDrawable(
				palette = palette,
				variant = MiyorareHeaderShapeDrawable.Variant.APP_BACKGROUND,
				density = density,
			)
		}
		viewBinding.appbar.apply {
			setBackgroundColor(Color.TRANSPARENT)
			elevation = 0f
		}
		viewBinding.collapsingToolbarLayout.apply {
			setContentScrimColor(ColorUtils.setAlphaComponent(glassSurface, 234))
			setStatusBarScrimColor(ColorUtils.setAlphaComponent(glassSurface, 232))
			setCollapsedTitleTextColor(goldenText)
			setExpandedTitleColor(goldenText)
		}
		viewBinding.toolbar.apply {
			setBackgroundColor(Color.TRANSPARENT)
			setTitleTextColor(goldenText)
			navigationIcon?.setTint(goldenText)
			overflowIcon?.setTint(goldenText)
		}

		viewBinding.modernDownloadsSummary.apply {
			setCardBackgroundColor(ColorUtils.setAlphaComponent(glassSurface, 228))
			radius = cardRadius
			cardElevation = 0f
			strokeWidth = strokeWidth
			strokeColor = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(goldenCyan, goldenAmber, 0.46f), 176)
		}
		viewBinding.modernDownloadsStatus.setTextColor(goldenText)
		viewBinding.modernDownloadsTotal.setTextColor(goldenMuted)
		viewBinding.modernDownloadsIconContainer.apply {
			setCardBackgroundColor(ColorUtils.blendARGB(glassSurface, goldenAmber, 0.16f))
			strokeWidth = strokeWidth
			strokeColor = ColorUtils.setAlphaComponent(goldenAmber, 194)
		}
		viewBinding.modernDownloadsIcon.imageTintList = ColorStateList.valueOf(goldenAmber)
		viewBinding.modernDownloadsPercent.isVisible = false
		viewBinding.modernDownloadsProgress.isVisible = false

		for (button in arrayOf(viewBinding.buttonPauseAll, viewBinding.buttonResumeAll)) {
			button.backgroundTintList = ColorStateList.valueOf(ColorUtils.blendARGB(glassSurface, goldenAmber, 0.24f))
			button.setTextColor(goldenAmber)
			button.iconTint = ColorStateList.valueOf(goldenAmber)
			button.cornerRadius = controlRadius
			button.strokeWidth = strokeWidth
			button.strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(goldenAmber, 204))
		}
	}
	private fun renderModernDownloadsHeader(models: List<ListModel>) {
		val downloads = models.filterIsInstance<DownloadItemModel>()
		var active = 0
		var paused = 0
		var completed = 0
		var cancelled = 0
		for (item in downloads) {
			when (item.workState) {
				WorkInfo.State.RUNNING -> if (item.isPaused) paused++ else active++
				WorkInfo.State.SUCCEEDED -> completed++
				WorkInfo.State.CANCELLED -> cancelled++
				else -> Unit
			}
		}

		val amber = Color.rgb(255, 182, 84)
		val cyan = Color.rgb(69, 230, 244)
		val red = Color.rgb(255, 100, 122)
		val text = Color.rgb(245, 243, 250)
		viewBinding.modernDownloadsStatus.text = buildSpannedString {
			append(getString(R.string.paused))
			append(' ')
			color(amber) { bold { append(paused.toString()) } }
			color(text) { append("  •  ") }
			append(getString(R.string.download_complete))
			append(' ')
			color(cyan) { bold { append(completed.toString()) } }
			color(text) { append("  •  ") }
			append(getString(R.string.canceled))
			append(' ')
			color(red) { bold { append(cancelled.toString()) } }
		}
		viewBinding.modernDownloadsTotal.text = getString(R.string.downloads_total_count, downloads.size)

		viewBinding.modernDownloadsPercent.isVisible = false
		viewBinding.modernDownloadsProgress.isVisible = false
		viewBinding.buttonPauseAll.isVisible = active > 0
		viewBinding.buttonResumeAll.isVisible = paused > 0
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
