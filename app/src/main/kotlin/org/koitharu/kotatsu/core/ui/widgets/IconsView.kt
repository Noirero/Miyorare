package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.content.withStyledAttributes
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import org.koitharu.kotatsu.R
import android.graphics.Typeface
import android.text.TextUtils
import android.widget.TextView
import org.koitharu.kotatsu.core.util.ext.getThemeColor

class IconsView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

	private var iconSize = LayoutParams.WRAP_CONTENT
	private var iconSpacing = 0

	val iconsCount: Int
		get() {
			var count = 0
			repeat(childCount) { i ->
				if (getChildAt(i).isVisible) {
					count++
				}
			}
			return count
		}

	init {
		gravity = android.view.Gravity.CENTER_VERTICAL
		context.withStyledAttributes(attrs, R.styleable.IconsView) {
			iconSize = getDimensionPixelSize(R.styleable.IconsView_iconSize, iconSize)
			iconSpacing = getDimensionPixelOffset(R.styleable.IconsView_iconSpacing, iconSpacing)
		}
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
		if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
			val visible = (0 until childCount).map { getChildAt(it) }.filter { it.isVisible }
			val occupied = visible.filterIsInstance<ImageView>().sumOf { it.layoutParams.width.coerceAtLeast(0) }
			val available = (maxWidth - paddingLeft - paddingRight - occupied - iconSpacing * (visible.size - 1).coerceAtLeast(0)).coerceAtLeast(0)
			visible.filterIsInstance<TextView>().forEach {
				val limit = minOf(available, (60f * resources.displayMetrics.density).toInt())
				if (it.maxWidth != limit) it.maxWidth = limit
			}
		}
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)
	}

	fun clearIcons() {
		repeat(childCount) { i ->
			getChildAt(i).isVisible = false
		}
	}

	fun addIcon(drawable: Drawable) {
		val imageView = getNextImageView()
		imageView.setImageDrawable(drawable)
		imageView.layoutParams = imageView.layoutParams.apply { width = iconSize; height = iconSize }
		imageView.contentDescription = null
		imageView.isVisible = true
	}

	fun addIcon(@DrawableRes resId: Int, size: Int = iconSize, description: String? = null) {
		val imageView = getNextImageView()
		imageView.setImageResource(resId)
		imageView.layoutParams = imageView.layoutParams.apply { width = size; height = size }
		imageView.contentDescription = description
		imageView.isVisible = true
	}

	fun addLabel(text: String) {
		val label = (0 until childCount).map { getChildAt(it) }
			.filterIsInstance<TextView>().firstOrNull { !it.isVisible }
			?: TextView(context).also {
				it.textSize = 12f
				it.typeface = Typeface.DEFAULT_BOLD
				it.setSingleLine()
				it.ellipsize = TextUtils.TruncateAt.END
				it.maxWidth = (60f * resources.displayMetrics.density).toInt()
				it.setTextColor(context.getThemeColor(android.R.attr.textColorPrimary))
				addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = iconSpacing })
			}
		label.text = text
		label.isVisible = true
	}

	private fun getNextImageView(): ImageView {
		repeat(childCount) { i ->
			val child = getChildAt(i)
			if (child is ImageView && !child.isVisible) {
				return child
			}
		}
		return addImageView()
	}

	private fun addImageView() = ImageView(context).also {
		it.scaleType = ImageView.ScaleType.FIT_CENTER
		val lp = LayoutParams(iconSize, iconSize)
		if (isNotEmpty()) {
			lp.marginStart = iconSpacing
		}
		addView(it, lp)
	}
}
