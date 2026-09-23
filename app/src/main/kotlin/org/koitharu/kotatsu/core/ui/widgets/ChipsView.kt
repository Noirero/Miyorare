package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.core.view.children
import androidx.lifecycle.findViewTreeLifecycleOwner
import coil3.ImageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.request.error
import coil3.request.fallback
import coil3.request.lifecycle
import coil3.request.placeholder
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.chip.ChipGroup
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.image.ChipIconTarget
import org.koitharu.kotatsu.core.util.ext.enqueueWith
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.setProgressIcon
import org.koitharu.kotatsu.parsers.util.ifZero
import javax.inject.Inject
import com.google.android.material.R as materialR

@AndroidEntryPoint
class ChipsView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = materialR.attr.chipGroupStyle,
) : ChipGroup(context, attrs, defStyleAttr) {

	@Inject
	lateinit var coil: ImageLoader

	private var isLayoutSuppressedCompat = false
	private var isLayoutCalledOnSuppressed = false
	private var fixedChildWidth: Int? = null
	private val chipOnClickListener = InternalChipClickListener()
	private val chipOnCloseListener = OnClickListener {
		val chip = it as Chip
		val data = it.tag
		onChipCloseClickListener?.onChipCloseClick(chip, data) ?: onChipClickListener?.onChipClick(chip, data)
	}
	private val chipOnLongClickListener = OnLongClickListener {
		val chip = it as Chip
		val data = it.tag
		onChipLongClickListener?.onChipLongClick(chip, data) ?: false
	}
	private val chipStyle: Int
	private val iconsVisible: Boolean
	var onChipClickListener: OnChipClickListener? = null
		set(value) {
			field = value
			val isChipClickable = value != null
			children.forEach { it.isClickable = isChipClickable }
		}
	var onChipCloseClickListener: OnChipCloseClickListener? = null

	var onChipLongClickListener: OnChipLongClickListener? = null

	// Cover accent shared from the details page; recolors the checked chip to match the rest of the UI.
	var chipAccentColor: Int? = null
		set(value) {
			if (field == value) return
			field = value
			children.forEach { (it as? DataChip)?.applyAccent() }
		}

	init {
		val ta = context.obtainStyledAttributes(attrs, R.styleable.ChipsView, defStyleAttr, 0)
		chipStyle = ta.getResourceId(R.styleable.ChipsView_chipStyle, R.style.Widget_Kotatsu_Chip)
		iconsVisible = ta.getBoolean(R.styleable.ChipsView_chipIconVisible, true)
		ta.recycle()

		if (isInEditMode) {
			setChips(
				List(5) {
					ChipModel(title = "Chip $it")
				},
			)
		}
	}

	/**
	 * Opt-in fixed width for every chip in this group. Used by compact action rails that must divide
	 * their available width evenly; ordinary ChipsView instances remain intrinsic-width.
	 */
	fun setFixedChildWidth(width: Int?) {
		val normalized = width?.takeIf { it > 0 }
		if (fixedChildWidth == normalized) return
		fixedChildWidth = normalized
		requestLayout()
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		fixedChildWidth?.let { width ->
			for (index in 0 until childCount) {
				getChildAt(index).layoutParams?.width = width
			}
		}
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)
	}

	override fun requestLayout() {
		if (isLayoutSuppressedCompat) {
			isLayoutCalledOnSuppressed = true
		} else {
			super.requestLayout()
		}
	}

	fun setChips(items: Collection<ChipModel>) {
		suppressLayoutCompat(true)
		try {
			for ((i, model) in items.withIndex()) {
				val chip = getChildAt(i) as DataChip? ?: addChip()
				chip.bind(model)
			}
			if (childCount > items.size) {
				removeViews(items.size, childCount - items.size)
			}
		} finally {
			suppressLayoutCompat(false)
		}
	}

	private fun addChip() = DataChip(context).also { addView(it) }

	private fun suppressLayoutCompat(suppress: Boolean) {
		isLayoutSuppressedCompat = suppress
		if (!suppress) {
			if (isLayoutCalledOnSuppressed) {
				requestLayout()
				isLayoutCalledOnSuppressed = false
			}
		}
	}

	data class ChipModel(
		val title: CharSequence? = null,
		@StringRes val titleResId: Int = 0,
		@DrawableRes val icon: Int = 0,
		val iconData: Any? = null,
		@ColorRes val tint: Int = 0,
		val counter: Int = 0,
		val isChecked: Boolean = false,
		val isLoading: Boolean = false,
		val isDropdown: Boolean = false,
		val isCloseable: Boolean = false,
		val isCheckedIconVisible: Boolean = true,
		val isIconOnly: Boolean = false,
		val data: Any? = null,
	)

	private inner class DataChip(context: Context) : Chip(context) {

		private var model: ChipModel? = null
		private var imageRequest: Disposable? = null

		private val defaultStrokeColor = chipStrokeColor
		private val defaultTextColor = textColors
		private val defaultBackgroundColor = chipBackgroundColor
		private val defaultCheckedIconTint = checkedIconTint
		private var defaultChipStartPadding = 0f
		private var defaultChipEndPadding = 0f
		private var defaultIconStartPadding = 0f
		private var defaultIconEndPadding = 0f
		private var defaultTextStartPadding = 0f
		private var defaultTextEndPadding = 0f
		private var defaultChipMinHeight = 0f
		private var defaultChipIconSize = 0f
		private var defaultMinimumWidth = 0
		private var defaultMinimumHeight = 0
		private var defaultMinWidth = 0
		private var defaultGravity = Gravity.CENTER_VERTICAL

		init {
			val drawable = ChipDrawable.createFromAttributes(context, null, 0, chipStyle)
			setChipDrawable(drawable)
			defaultChipStartPadding = chipStartPadding
			defaultChipEndPadding = chipEndPadding
			defaultIconStartPadding = iconStartPadding
			defaultIconEndPadding = iconEndPadding
			defaultTextStartPadding = textStartPadding
			defaultTextEndPadding = textEndPadding
			defaultChipMinHeight = chipMinHeight
			defaultChipIconSize = chipIconSize
			defaultMinimumWidth = minimumWidth
			defaultMinimumHeight = minimumHeight
			defaultMinWidth = minWidth
			defaultGravity = gravity
			isChipIconVisible = false
			setOnCloseIconClickListener(chipOnCloseListener)
			setEnsureMinTouchTargetSize(false)
			setOnClickListener(chipOnClickListener)
			setOnLongClickListener(chipOnLongClickListener)
			isElegantTextHeight = false
		}

		fun bind(model: ChipModel) {
			if (this.model == model) {
				return
			}
			this.model = model

			if (model.counter > 0) {
				text = buildSpannedString {
					if (model.titleResId == 0) {
						append(model.title)
					} else {
						append(context.getString(model.titleResId))
					}
					append(' ')
					append(' ')
					inSpans(
						ForegroundColorSpan(
							context.getThemeColor(
								android.R.attr.textColorSecondary,
								Color.LTGRAY,
							),
						),
						RelativeSizeSpan(0.74f),
					) {
						append(model.counter.toString())
					}
				}
			} else if (model.titleResId == 0) {
				text = model.title
			} else {
				setText(model.titleResId)
			}
			applyIconOnlyStyle(model.isIconOnly)
			isClickable = onChipClickListener != null
			if (model.isChecked) {
				isCheckable = true
				isChecked = true
			} else {
				isChecked = false
				isCheckable = false
			}
			if (model.tint == 0) {
				chipStrokeColor = defaultStrokeColor
				setTextColor(defaultTextColor)
			} else {
				val tint = ContextCompat.getColorStateList(context, model.tint)
				chipStrokeColor = tint
				setTextColor(tint)
			}
			bindIcon(model)
			isCheckedIconVisible = model.isChecked && !model.isDropdown && model.isCheckedIconVisible
			isCloseIconVisible = if (model.isCloseable || model.isDropdown) {
				setCloseIconResource(
					if (model.isDropdown) R.drawable.ic_expand_more else materialR.drawable.ic_m3_chip_close,
				)
				true
			} else {
				false
			}
			tag = model.data
			applyAccent()
		}

		// When an accent is set, the checked chip fills with a TONED-DOWN tint of it (a subtle tonal
		// container rather than the full vibrant accent, which had visibility issues); otherwise the
		// style/model defaults are restored.
		fun applyAccent() {
			val accent = chipAccentColor
			if (accent != null && isChecked) {
				val container = context.getThemeColor(materialR.attr.colorSurfaceContainerHighest, Color.GRAY)
				val muted = ColorUtils.blendARGB(container, accent, CHIP_ACCENT_BLEND)
				val onMuted = context.getThemeColor(materialR.attr.colorOnSurface, Color.BLACK)
				chipBackgroundColor = ColorStateList.valueOf(muted)
				setTextColor(onMuted)
				checkedIconTint = ColorStateList.valueOf(onMuted)
			} else {
				chipBackgroundColor = defaultBackgroundColor
				checkedIconTint = defaultCheckedIconTint
				if (model?.tint == 0) {
					setTextColor(defaultTextColor)
				}
			}
		}

		private fun applyIconOnlyStyle(isIconOnly: Boolean) {
			if (isIconOnly) {
				text = null
				val chipSize = defaultChipMinHeight
				val iconSize = resources.getDimension(materialR.dimen.m3_chip_icon_size)
				val horizontalPadding = (chipSize - iconSize) / 2f
				// fixed square width: minWidth alone lets a recycled chip keep a wider intrinsic
				// width from its previous text state, rendering wide with the icon pinned left
				layoutParams?.let { lp ->
					if (lp.width != chipSize.toInt()) {
						lp.width = chipSize.toInt()
						layoutParams = lp
					}
				}
				minimumWidth = chipSize.toInt()
				minWidth = chipSize.toInt()
				minimumHeight = chipSize.toInt()
				gravity = Gravity.CENTER
				chipMinHeight = chipSize
				chipIconSize = iconSize
				chipStartPadding = horizontalPadding
				chipEndPadding = horizontalPadding
				iconStartPadding = 0f
				iconEndPadding = 0f
				textStartPadding = 0f
				textEndPadding = 0f
			} else {
				layoutParams?.let { lp ->
					if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
						lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
						layoutParams = lp
					}
				}
				minimumWidth = defaultMinimumWidth
				minWidth = defaultMinWidth
				minimumHeight = defaultMinimumHeight
				gravity = defaultGravity
				chipMinHeight = defaultChipMinHeight
				chipIconSize = defaultChipIconSize
				chipStartPadding = defaultChipStartPadding
				chipEndPadding = defaultChipEndPadding
				iconStartPadding = defaultIconStartPadding
				iconEndPadding = defaultIconEndPadding
				textStartPadding = defaultTextStartPadding
				textEndPadding = defaultTextEndPadding
			}
		}

		override fun toggle() = Unit

		private fun bindIcon(model: ChipModel) {
			when {
				model.isChecked && !model.isDropdown && model.isCheckedIconVisible -> disposeIcon()

				model.isLoading -> {
					imageRequest?.dispose()
					imageRequest = null
					isChipIconVisible = true
					setProgressIcon()
				}

				!iconsVisible -> disposeIcon()

				model.isIconOnly && model.icon != 0 -> {
					imageRequest?.dispose()
					imageRequest = null
					setChipIconResource(model.icon)
					isChipIconVisible = true
				}

				model.iconData != null -> {
					val placeholder = model.icon.ifZero { materialR.drawable.navigation_empty_icon }
					imageRequest = ImageRequest.Builder(context)
						.data(model.iconData)
						.crossfade(false)
						.size(resources.getDimensionPixelSize(materialR.dimen.m3_chip_icon_size))
						.target(ChipIconTarget(this))
						.placeholder(placeholder)
						.fallback(placeholder)
						.lifecycle(this@ChipsView.findViewTreeLifecycleOwner())
						.error(placeholder)
						.transformations(RoundedCornersTransformation(resources.getDimension(R.dimen.chip_icon_corner)))
						.allowRgb565(true)
						.enqueueWith(coil)
					isChipIconVisible = true
				}

				model.icon != 0 -> {
					imageRequest?.dispose()
					imageRequest = null
					setChipIconResource(model.icon)
					isChipIconVisible = true
				}

				else -> disposeIcon()
			}
		}

		private fun disposeIcon() {
			imageRequest?.dispose()
			imageRequest = null
			chipIcon = null
			isChipIconVisible = false
		}
	}

	private inner class InternalChipClickListener : OnClickListener {
		override fun onClick(v: View?) {
			val chip = v as? DataChip ?: return
			onChipClickListener?.onChipClick(chip, chip.tag)
		}
	}

	fun interface OnChipClickListener {

		fun onChipClick(chip: Chip, data: Any?)
	}

	fun interface OnChipCloseClickListener {

		fun onChipCloseClick(chip: Chip, data: Any?)
	}

	fun interface OnChipLongClickListener {

		fun onChipLongClick(chip: Chip, data: Any?): Boolean
	}

	private companion object {

		// How far the checked chip's background is blended from the surface toward the accent.
		// Low so the chip stays a subtle tonal container (legible) rather than a vivid fill.
		const val CHIP_ACCENT_BLEND = 0.30f
	}
}
