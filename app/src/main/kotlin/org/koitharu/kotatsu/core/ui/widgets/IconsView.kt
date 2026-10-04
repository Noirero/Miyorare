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
			// clearIcons() starts a new binding pass without hiding the old children immediately.
			// Commit the final count here so icons that remain present never flash off/on during a rebind.
			for (i in nextIconIndex until childCount) {
				getChildAt(i).isVisible = false
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
		// Treat clear + addIcon calls as one update. RecyclerView rebinds can happen several times while
		// async card metadata settles; eagerly hiding every child here made unchanged status icons blink
		// and caused avoidable visibility/layout churn on the first Favourites render.
		nextIconIndex = 0
	}

	fun addIcon(drawable: Drawable) {
		val imageView = getNextImageView()
		imageView.setImageDrawable(drawable)
		imageView.tag = null
		imageView.isVisible = true
		nextIconIndex++
	}

	fun addIcon(@DrawableRes resId: Int) {
		val imageView = getNextImageView()
		if (imageView.tag != resId) {
			imageView.setImageResource(resId)
			imageView.tag = resId
		}
		imageView.isVisible = true
		nextIconIndex++
	}

	private fun getNextImageView(): ImageView {
		val existing = getChildAt(nextIconIndex)
		if (existing is ImageView) return existing
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
