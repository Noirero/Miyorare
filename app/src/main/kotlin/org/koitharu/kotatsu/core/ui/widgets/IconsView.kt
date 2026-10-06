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

class IconsView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

	private var iconSize = LayoutParams.WRAP_CONTENT
	private var iconSpacing = 0
	private var nextIconIndex = 0

	val iconsCount: Int
		get() {
			// Commit the binding pass: only icons removed from the final set are hidden.
			for (i in nextIconIndex until childCount) {
				val child = getChildAt(i)
				if (child.isVisible) child.isVisible = false
			}
			return nextIconIndex
		}

	init {
		context.withStyledAttributes(attrs, R.styleable.IconsView) {
			iconSize = getDimensionPixelSize(R.styleable.IconsView_iconSize, iconSize)
			iconSpacing = getDimensionPixelOffset(R.styleable.IconsView_iconSpacing, iconSpacing)
		}
	}

	fun clearIcons() {
		// Start a binding pass without temporarily hiding icons that will be reused.
		nextIconIndex = 0
	}

	fun addIcon(drawable: Drawable) {
		val imageView = getNextImageView()
		if (imageView.drawable !== drawable) imageView.setImageDrawable(drawable)
		imageView.tag = null
		if (!imageView.isVisible) imageView.isVisible = true
		nextIconIndex++
	}

	fun addIcon(@DrawableRes resId: Int) {
		val imageView = getNextImageView()
		if (imageView.tag != resId) {
			imageView.setImageResource(resId)
			imageView.tag = resId
		}
		if (!imageView.isVisible) imageView.isVisible = true
		nextIconIndex++
	}

	private fun getNextImageView(): ImageView {
		val existing = getChildAt(nextIconIndex)
		return if (existing is ImageView) existing else addImageView()
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
