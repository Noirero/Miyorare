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
 * [LayoutInflater] for a clone whose factory creates views exactly the way
 * the AppCompat delegate would (same view inflater class from the theme,
 * same creation flags for API 26+) and then re-points every inflated
 * [TextView] at the pack typeface via [AppFontHolder]. When no pack is
 * active the context is returned unwrapped.
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

	private var viewInflater: AppCompatViewInflater? = null

	/** Mirrors how the AppCompat delegate resolves its view inflater class. */
	private fun viewInflater(context: Context): AppCompatViewInflater {
		viewInflater?.let { return it }
		val created = runCatching {
			val attrs = context.obtainStyledAttributes(
				androidx.appcompat.R.styleable.AppCompatTheme,
			)
			val className = attrs.getString(
				androidx.appcompat.R.styleable.AppCompatTheme_viewInflaterClass,
			)
			attrs.recycle()
			if (className == null) {
				AppCompatViewInflater()
			} else {
				Class.forName(className)
					.asSubclass(AppCompatViewInflater::class.java)
					.getDeclaredConstructor()
					.newInstance()
			}
		}.getOrElse { AppCompatViewInflater() }
		viewInflater = created
		return created
	}

	override fun onCreateView(
		parent: View?,
		name: String,
		context: Context,
		attrs: AttributeSet,
	): View? {
		// The flags mirror AppCompatDelegateImpl.createView on API 26+:
		// inheritContext and readAndroidTheme only apply below API 24/21, and
		// vector-tint context wrapping only below API 21.
		val view = viewInflater(context).createView(
			parent, name, context, attrs,
			false, false, true, false,
		)
		if (view is TextView) {
			AppFontHolder.applyTo(view)
		}
		return view
	}

	override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
		return onCreateView(null, name, context, attrs)
	}
}
