package org.koitharu.kotatsu.download.ui.list

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import org.koitharu.kotatsu.core.ui.MiyorareViewPalette
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.RecyclerScrollKeeper
import org.koitharu.kotatsu.core.ui.miyorareViewPalette
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.getThemeColor
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
	private var currentModernPalette: MiyorareViewPalette? = null
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
		val decoration = if (isModernDownloads) {
			DownloadsGoldenSpacingDecoration(this)
		} else {
			TypedListSpacingDecoration(this, false)
		}
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

	private fun decorateToolbarIconButtons(
		root: ViewGroup,
		fillColor: Int,
		strokeColor: Int,
		iconColor: Int,
	) {
		val density = resources.displayMetrics.density
		for (index in 0 until root.childCount) {
			val child = root.getChildAt(index)
			if (child is ImageButton) {
				child.background = GradientDrawable().apply {
					shape = GradientDrawable.OVAL
					setColor(ColorUtils.setAlphaComponent(fillColor, 168))
					setStroke(
						(density).roundToInt().coerceAtLeast(1),
						ColorUtils.setAlphaComponent(strokeColor, 154),
					)
				}
				child.imageTintList = ColorStateList.valueOf(iconColor)
				val size = (44f * density).roundToInt()
				child.layoutParams = child.layoutParams.apply {
					width = size
					height = size
				}
				val padding = (10f * density).roundToInt()
				child.setPadding(padding, padding, padding, padding)
			} else if (child is ViewGroup) {
				decorateToolbarIconButtons(child, fillColor, strokeColor, iconColor)
			}
		}
	}

	/**
	 * Golden-reference geometry with palette-driven colour.
	 *
	 * The reference owns spacing, glass hierarchy and component shapes. Actual colour comes from the
	 * active Miyorare palette (including Custom/Rank themes); only destructive states keep semantic
	 * error red. This keeps the Downloads screen visually consistent with whichever theme is active.
	 */
	private fun applyModernDownloadsVisuals(level: VisualEffectLevel) {
		val palette = miyorareViewPalette(settings, level)
		currentModernPalette = palette
		val density = resources.displayMetrics.density
		val cardRadius = 24f * density
		val controlRadius = (24f * density).roundToInt()
		val borderWidth = density.roundToInt().coerceAtLeast(1)
		val glassSurface = ColorUtils.setAlphaComponent(palette.surfaceContainerHigh, 224)

		if (isPrivateDownloads) {
			viewBinding.root.setBackgroundColor(palette.background)
		} else {
			val ambient = MiyorareHeaderShapeDrawable(
				palette = palette,
				variant = MiyorareHeaderShapeDrawable.Variant.APP_BACKGROUND,
				density = density,
			)
			viewBinding.root.background = if (ColorUtils.calculateLuminance(palette.background) < 0.5) {
				LayerDrawable(
					arrayOf(
						ambient,
						ColorDrawable(ColorUtils.setAlphaComponent(Color.BLACK, 52)),
					),
				)
			} else {
				ambient
			}
		}
		viewBinding.appbar.apply {
			setBackgroundColor(Color.TRANSPARENT)
			elevation = 0f
		}
		viewBinding.collapsingToolbarLayout.apply {
			setContentScrimColor(ColorUtils.setAlphaComponent(palette.surfaceContainerHigh, 238))
			setStatusBarScrimColor(ColorUtils.setAlphaComponent(palette.surfaceContainerHigh, 234))
			setCollapsedTitleTextColor(palette.onSurface)
			setExpandedTitleColor(palette.onSurface)
		}
		viewBinding.toolbar.apply {
			setBackgroundColor(Color.TRANSPARENT)
			setTitleTextColor(palette.onSurface)
			navigationIcon?.setTint(palette.onSurface)
			overflowIcon?.setTint(palette.onSurface)
			post {
				decorateToolbarIconButtons(
					root = this,
					fillColor = palette.surfaceContainerHigh,
					strokeColor = palette.outlineVariant,
					iconColor = palette.onSurface,
				)
			}
		}

		viewBinding.modernDownloadsSummary.apply {
			setCardBackgroundColor(glassSurface)
			radius = cardRadius
			cardElevation = 0f
			strokeWidth = borderWidth
			strokeColor = ColorUtils.setAlphaComponent(
				ColorUtils.blendARGB(palette.primary, palette.secondary, 0.46f),
				176,
			)
		}
		viewBinding.modernDownloadsStatus.setTextColor(palette.onSurface)
		viewBinding.modernDownloadsTotal.setTextColor(palette.onSurfaceVariant)
		viewBinding.modernDownloadsIconContainer.apply {
			setCardBackgroundColor(ColorUtils.blendARGB(glassSurface, palette.primary, 0.16f))
			strokeWidth = borderWidth
			strokeColor = ColorUtils.setAlphaComponent(palette.primary, 194)
		}
		viewBinding.modernDownloadsGlowLeft.background = GradientDrawable().apply {
			shape = GradientDrawable.OVAL
			gradientType = GradientDrawable.RADIAL_GRADIENT
			colors = intArrayOf(ColorUtils.setAlphaComponent(palette.secondary, 58), Color.TRANSPARENT)
			gradientRadius = 92f * density
			setGradientCenter(0.28f, 0.48f)
		}
		viewBinding.modernDownloadsGlowRight.background = GradientDrawable().apply {
			shape = GradientDrawable.OVAL
			gradientType = GradientDrawable.RADIAL_GRADIENT
			colors = intArrayOf(ColorUtils.setAlphaComponent(palette.primary, 68), Color.TRANSPARENT)
			gradientRadius = 132f * density
			setGradientCenter(0.72f, 0.56f)
		}
		viewBinding.modernDownloadsIcon.imageTintList = ColorStateList.valueOf(palette.primary)
		viewBinding.modernDownloadsIconRing.setIndicatorColor(palette.primary)
		viewBinding.modernDownloadsIconRing.trackColor = ColorUtils.setAlphaComponent(palette.secondary, 46)
		viewBinding.modernDownloadsWave.imageTintList =
			ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.secondary, 170))
		viewBinding.modernDownloadsPercent.isVisible = false
		viewBinding.modernDownloadsProgress.isVisible = false

		viewBinding.buttonPauseAll.isVisible = false
		viewBinding.buttonResumeAll.apply {
			backgroundTintList = ColorStateList.valueOf(
				ColorUtils.blendARGB(glassSurface, palette.button, 0.72f),
			)
			setTextColor(palette.onButton)
			iconTint = ColorStateList.valueOf(palette.onButton)
			cornerRadius = controlRadius
			strokeWidth = borderWidth
			strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.primary, 220))
		}

		// Header text is model-driven, so recolour it immediately when users change the active theme.
		renderModernDownloadsHeader(viewModel.items.value.orEmpty())
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

		val palette = currentModernPalette
			?: miyorareViewPaletteFromPreferences(privateFavourites = isPrivateDownloads)
			?: return
		val error = getThemeColor(android.R.attr.colorError, Color.RED)
		viewBinding.modernDownloadsStatus.text = buildSpannedString {
			append(getString(R.string.paused))
			append(' ')
			color(palette.primary) { bold { append(paused.toString()) } }
			color(palette.onSurface) { append("  •  ") }
			append(getString(R.string.download_complete))
			append(' ')
			color(palette.secondary) { bold { append(completed.toString()) } }
			color(palette.onSurface) { append("  •  ") }
			append(getString(R.string.canceled))
			append(' ')
			color(error) { bold { append(cancelled.toString()) } }
		}
		viewBinding.modernDownloadsStatus.setTextColor(palette.onSurface)
		viewBinding.modernDownloadsTotal.setTextColor(palette.onSurfaceVariant)
		viewBinding.modernDownloadsTotal.text = getString(R.string.downloads_total_count, downloads.size)

		viewBinding.modernDownloadsPercent.isVisible = false
		viewBinding.modernDownloadsProgress.isVisible = false
		viewBinding.buttonPauseAll.isVisible = false
		viewBinding.buttonResumeAll.isVisible = true
		viewBinding.buttonResumeAll.isEnabled = paused > 0
		viewBinding.buttonResumeAll.alpha = if (paused > 0) 1f else 0.52f
		viewBinding.modernDownloadsControls.isVisible = true
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
