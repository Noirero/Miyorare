package org.koitharu.kotatsu.core.fonts

import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatViewInflater

/**
 * Applies the active Font Pack to inflated Views.
 *
 * Installed in `BaseActivity.attachBaseContext`. The wrapper swaps the
 * [LayoutInflater] for a clone whose factory delegates view creation to
 * [AppCompatViewInflater] (so AppCompat widget behaviour is unchanged) and
 * then re-points every inflated [TextView] at the pack typeface via
 * [AppFontHolder]. When no pack is active the context is returned unwrapped.
 */
class AppFontContextWrapper(base: Context) : ContextWrapper(base) {

	private var inflater: LayoutInflater? = null

	override fun getSystemService(name: String): Any? {
		if (name == Context.LAYOUT_INFLATER_SERVICE) {
			if (inflater == null) {
				inflater = LayoutInflater.from(baseContext).cloneInContext(this).apply {
					if (factory2 == null) {
						factory2 = AppFontFactory2()
					}
				}
			}
			return inflater
		}
		return super.getSystemService(name)
	}

	companion object {
		fun wrap(base: Context): Context {
			return if (AppFontHolder.hasFont) AppFontContextWrapper(base) else base
		}
	}
}

private class AppFontFactory2 : LayoutInflater.Factory2 {

	private val delegate = AppCompatViewInflater()

	override fun onCreateView(
		parent: View?,
		name: String,
		context: Context,
		attrs: AttributeSet,
	): View? {
		val view = delegate.onCreateView(parent, name, context, attrs)
		if (view is TextView) {
			AppFontHolder.applyTo(view)
		}
		return view
	}

	override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
		return onCreateView(null, name, context, attrs)
	}
}
