package org.koitharu.kotatsu.favourites.ui.container

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import org.koitharu.kotatsu.R

/** Sizing only: the header remains the single visual owner of the navigation rail. */
class CategoryNavigationRow @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

	override fun onFinishInflate() {
		super.onFinishInflate()
		findViewById<TextView>(R.id.button_category_set).apply {
			val size = (18 * resources.displayMetrics.density).toInt()
			val icons = listOf(R.drawable.ic_stacks, R.drawable.ic_expand_more).map { id ->
				requireNotNull(context.getDrawable(id)).mutate().apply { setBounds(0, 0, size, size) }
			}
			setCompoundDrawablesRelative(icons[0], null, icons[1], null)
			androidx.core.view.ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
				override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
					super.onInitializeAccessibilityNodeInfo(host, info)
					info.className = android.widget.Button::class.java.name
				}
			})
		}
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
		findViewById<TextView>(R.id.button_category_set).maxWidth = (available * 0.38f).toInt()
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)
	}
}
