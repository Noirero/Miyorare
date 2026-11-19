package org.koitharu.kotatsu.reader.ui.colorfilter

import android.content.res.Resources
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.CompoundButton
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.MiyorareViewPalette
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.setChecked
import org.koitharu.kotatsu.core.util.ext.setValueRounded
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.core.util.progress.ImageRequestIndicatorListener
import org.koitharu.kotatsu.databinding.ActivityColorFilterBinding
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.format
import org.koitharu.kotatsu.reader.domain.ReaderColorFilter
import org.koitharu.kotatsu.reader.domain.ReaderCustomTint
import org.koitharu.kotatsu.reader.domain.ReaderTintBlendMode
import javax.inject.Inject

@AndroidEntryPoint
class ColorFilterConfigActivity :
	BaseActivity<ActivityColorFilterBinding>(),
	Slider.OnChangeListener,
	View.OnClickListener, CompoundButton.OnCheckedChangeListener {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel: ColorFilterConfigViewModel by viewModels()
    private var updatingUi = false
    private var uiLoading = true
    private var customEnabled = false
    private var advancedExpanded = false

    private val blendIds = mapOf(
        R.id.chip_blend_default to ReaderTintBlendMode.DEFAULT,
        R.id.chip_blend_multiply to ReaderTintBlendMode.MULTIPLY,
        R.id.chip_blend_screen to ReaderTintBlendMode.SCREEN,
        R.id.chip_blend_overlay to ReaderTintBlendMode.OVERLAY,
        R.id.chip_blend_dodge to ReaderTintBlendMode.DODGE_LIGHTEN,
        R.id.chip_blend_burn to ReaderTintBlendMode.BURN_DARKEN,
    )

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityColorFilterBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		applyModernColorFilterPalette()
		viewBinding.sliderBrightness.addOnChangeListener(this)
		viewBinding.sliderContrast.addOnChangeListener(this)
        val custom = viewBinding.customFilterControls
        advancedExpanded = savedInstanceState?.getBoolean(KEY_ADVANCED_EXPANDED) ?: false
        custom.buttonExpandTint.setOnClickListener {
            advancedExpanded = !advancedExpanded
            updateAdvancedVisibility()
        }
        updateAdvancedVisibility()
        custom.sliderTintRed.addOnChangeListener(this)
        custom.sliderTintGreen.addOnChangeListener(this)
        custom.sliderTintBlue.addOnChangeListener(this)
        custom.sliderTintAlpha.addOnChangeListener(this)
        custom.switchCustomTint.setOnCheckedChangeListener(this)
        custom.groupBlendModes.setOnCheckedStateChangeListener { _, checkedIds ->
            if (!updatingUi) {
                checkedIds.firstOrNull()?.let(blendIds::get)?.let(viewModel::setCustomMode)
            }
        }
		val formatter = PercentLabelFormatter(resources)
		viewBinding.sliderContrast.setLabelFormatter(formatter)
		viewBinding.sliderBrightness.setLabelFormatter(formatter)
		viewBinding.switchInvert.setOnCheckedChangeListener(this)
		viewBinding.switchGrayscale.setOnCheckedChangeListener(this)
		viewBinding.switchBook.setOnCheckedChangeListener(this)
		viewBinding.switchMangaProfile.setOnCheckedChangeListener(this)
		viewBinding.buttonDone.setOnClickListener(this)
		viewBinding.buttonReset.setOnClickListener(this)

		onBackPressedDispatcher.addCallback(ColorFilterConfigBackPressedDispatcher(this, viewModel))

		viewModel.colorFilter.observe(this, this::onColorFilterChanged)
		viewModel.isMangaProfileEnabled.observe(this) { enabled ->
			viewBinding.switchMangaProfile.setChecked(enabled, false)
		}
		viewModel.isLoading.observe(this, this::onLoadingChanged)
		viewModel.onDismiss.observeEvent(this) {
			finishAfterTransition()
		}
		loadPreview(viewModel.preview)
	}

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_ADVANCED_EXPANDED, advancedExpanded)
        super.onSaveInstanceState(outState)
    }

    private fun updateAdvancedVisibility() {
        val custom = viewBinding.customFilterControls
        custom.customAdvancedContent.isVisible = advancedExpanded
        custom.buttonExpandTint.isSelected = advancedExpanded
    }

	override fun onResume() {
		super.onResume()
		applyModernColorFilterPalette()
	}

	/**
	 * Color correction uses native Material Views, whose XML theme may still
	 * provide the default purple even when the active Modern theme is custom.
	 * Tint UI chrome only: do not touch the before/after image or RGB filter
	 * values, filter math, per-manga profile, or save/reset callbacks.
	 */
	private fun applyModernColorFilterPalette() {
		val palette = miyorareViewPaletteFromPreferences() ?: return
		viewBinding.root.setBackgroundColor(palette.background)
		viewBinding.toolbar.setBackgroundColor(palette.background)
		viewBinding.toolbar.setTitleTextColor(palette.onSurface)
		viewBinding.toolbar.navigationIcon?.mutate()?.setTint(palette.onSurface)
		applyModernFilterColors(viewBinding.root, palette)
	}

	private fun applyModernFilterColors(root: View, palette: MiyorareViewPalette) {
		val primary = ColorStateList.valueOf(palette.primary)
		val foreground = ColorStateList.valueOf(palette.onSurface)
		val checkedState = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
		when (root) {
			is MaterialCardView -> root.setCardBackgroundColor(palette.surfaceContainerHigh)
			is MaterialButton -> {
				val isReset = root.id == R.id.button_reset
				root.backgroundTintList = ColorStateList.valueOf(
					if (isReset) palette.surfaceContainerHigh else palette.primary,
				)
				root.setTextColor(if (isReset) palette.primary else palette.onPrimary)
				root.iconTint = ColorStateList.valueOf(if (isReset) palette.primary else palette.onPrimary)
				if (isReset) root.strokeColor = ColorStateList.valueOf(palette.outlineVariant)
			}
			is MaterialSwitch -> {
				root.setTextColor(palette.onSurface)
				root.thumbTintList = ColorStateList(
					checkedState, intArrayOf(palette.onPrimary, palette.onSurfaceVariant),
				)
				root.trackTintList = ColorStateList(
					checkedState, intArrayOf(palette.primary, palette.surfaceContainerHighest),
				)
			}
			is Chip -> {
				root.chipBackgroundColor = ColorStateList(
					checkedState, intArrayOf(palette.primaryContainer, palette.surfaceContainerHigh),
				)
				root.chipStrokeColor = ColorStateList.valueOf(palette.outlineVariant)
				root.setTextColor(ColorStateList(
					checkedState, intArrayOf(palette.onPrimaryContainer, palette.onSurfaceVariant),
				))
				root.checkedIconTint = ColorStateList.valueOf(palette.onPrimaryContainer)
			}
			is Slider -> {
				root.trackActiveTintList = primary
				root.trackInactiveTintList = ColorStateList.valueOf(palette.surfaceContainerHighest)
				root.thumbTintList = primary
				root.haloTintList = ColorStateList.valueOf(palette.primaryContainer)
				root.tickActiveTintList = primary
				root.tickInactiveTintList = ColorStateList.valueOf(palette.outlineVariant)
			}
			is TextView -> root.setTextColor(foreground)
		}
		if (root is ViewGroup) {
			for (i in 0 until root.childCount) applyModernFilterColors(root.getChildAt(i), palette)
		}
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.root.setPadding(
			barsInsets.left,
			barsInsets.top,
			barsInsets.right,
			barsInsets.bottom,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
		if (fromUser) {
			when (slider.id) {
				R.id.slider_brightness -> viewModel.setBrightness(value)
				R.id.slider_contrast -> viewModel.setContrast(value)
                R.id.slider_tint_red -> viewModel.setCustomRed(value.toInt())
                R.id.slider_tint_green -> viewModel.setCustomGreen(value.toInt())
                R.id.slider_tint_blue -> viewModel.setCustomBlue(value.toInt())
                R.id.slider_tint_alpha -> viewModel.setCustomAlpha(value.toInt())
			}
		}
	}

	override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
		when (buttonView.id) {
			R.id.switch_invert -> viewModel.setInversion(isChecked)
			R.id.switch_grayscale -> viewModel.setGrayscale(isChecked)
			R.id.switch_book -> viewModel.setBookEffect(isChecked)
            R.id.switch_custom_tint -> if (!updatingUi) viewModel.setCustomEnabled(isChecked)
			R.id.switch_manga_profile -> viewModel.setMangaProfileEnabled(isChecked)
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_done -> showSaveConfirmation()
			R.id.button_reset -> viewModel.reset()
		}
	}

	fun showSaveConfirmation() {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.apply)
			.setMessage(R.string.color_correction_apply_text)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.this_manga) { _, _ ->
				viewModel.save()
			}.setNeutralButton(R.string.globally) { _, _ ->
				viewModel.saveGlobally()
			}.show()
	}

	private fun onColorFilterChanged(readerColorFilter: ReaderColorFilter?) {
        updatingUi = true
        try {
            viewBinding.sliderBrightness.setValueRounded(readerColorFilter?.brightness ?: 0f)
            viewBinding.sliderContrast.setValueRounded(readerColorFilter?.contrast ?: 0f)
            viewBinding.switchInvert.setChecked(readerColorFilter?.isInverted == true, false)
            viewBinding.switchGrayscale.setChecked(readerColorFilter?.isGrayscale == true, false)
            viewBinding.switchBook.setChecked(readerColorFilter?.isBookBackground == true, false)
            val custom = viewBinding.customFilterControls
            val tint = readerColorFilter?.custom ?: ReaderCustomTint.DEFAULT
            customEnabled = tint.enabled
            custom.switchCustomTint.setChecked(tint.enabled, false)
            custom.sliderTintRed.setValueRounded(tint.red.toFloat().coerceIn(0f, 255f))
            custom.sliderTintGreen.setValueRounded(tint.green.toFloat().coerceIn(0f, 255f))
            custom.sliderTintBlue.setValueRounded(tint.blue.toFloat().coerceIn(0f, 255f))
            custom.sliderTintAlpha.setValueRounded(tint.alpha.toFloat().coerceIn(0f, 255f))
            custom.valueTintRed.text = tint.red.toString()
            custom.valueTintGreen.text = tint.green.toString()
            custom.valueTintBlue.text = tint.blue.toString()
            custom.valueTintAlpha.text = tint.alpha.toString()
            blendIds.entries.firstOrNull { it.value == tint.mode }?.key?.let(custom.groupBlendModes::check)
            ReaderCustomTint.applyToImage(viewBinding.imageViewAfter, readerColorFilter) {
                viewBinding.imageViewAfter.colorFilter = it
            }
        } finally {
            updatingUi = false
        }
        updateCustomControlsEnabled()
	}

	private fun loadPreview(page: MangaPage) = with(viewBinding.imageViewBefore) {
		addImageRequestListener(
			ImageRequestIndicatorListener(
				listOf(
					viewBinding.progressBefore,
					viewBinding.progressAfter,
				),
			),
		)
		addImageRequestListener(ShadowImageListener(viewBinding.imageViewAfter))
		setImageAsync(page)
	}

	private fun onLoadingChanged(isLoading: Boolean) {
        uiLoading = isLoading
		viewBinding.sliderContrast.isEnabled = !isLoading
		viewBinding.sliderBrightness.isEnabled = !isLoading
		viewBinding.switchInvert.isEnabled = !isLoading
		viewBinding.switchGrayscale.isEnabled = !isLoading
        viewBinding.switchBook.isEnabled = !isLoading
		viewBinding.switchMangaProfile.isEnabled = !isLoading
		viewBinding.buttonDone.isEnabled = !isLoading
        viewBinding.customFilterControls.switchCustomTint.isEnabled = !isLoading
        updateCustomControlsEnabled()
	}

    private fun updateCustomControlsEnabled() {
        val custom = viewBinding.customFilterControls
        val enabled = customEnabled && !uiLoading
        custom.sliderTintRed.isEnabled = enabled
        custom.sliderTintGreen.isEnabled = enabled
        custom.sliderTintBlue.isEnabled = enabled
        custom.sliderTintAlpha.isEnabled = enabled
        blendIds.keys.forEach { id ->
            custom.groupBlendModes.findViewById<View>(id)?.isEnabled = enabled
        }
    }

    private companion object {
        const val KEY_ADVANCED_EXPANDED = "color_studio_advanced_expanded"
    }

	private class PercentLabelFormatter(resources: Resources) : LabelFormatter {

		private val pattern = resources.getString(R.string.percent_string_pattern)

		override fun getFormattedValue(value: Float): String {
			val percent = ((value + 1f) * 100).format(0)
			return pattern.format(percent)
		}
	}

	private class ShadowImageListener(
		private val imageView: ImageView
	) : ImageRequest.Listener {

		override fun onError(request: ImageRequest, result: ErrorResult) {
			super.onError(request, result)
			imageView.setImageDrawable(result.image?.asDrawable(imageView.resources))
		}

		override fun onStart(request: ImageRequest) {
			super.onStart(request)
			imageView.setImageDrawable(request.placeholder()?.asDrawable(imageView.resources))
		}

		override fun onSuccess(request: ImageRequest, result: SuccessResult) {
			super.onSuccess(request, result)
			imageView.setImageDrawable(result.image.asDrawable(imageView.resources))
		}
	}
}
