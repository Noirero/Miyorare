package org.koitharu.kotatsu.core.fonts

import android.graphics.Typeface
import android.os.Build
import android.widget.TextView

/**
 * Process-wide holder for the typeface of the currently active Font Pack.
 *
 * The View system and the EPUB reader cannot easily reach the Hilt graph at
 * inflation/render time, so [FontPackRepository] publishes the active pack's
 * base typeface here. When no pack is active everything in this object is a
 * no-op and the app renders exactly as before.
 */
object AppFontHolder {

	@Volatile
	private var base: Typeface? = null

	private val styledCache = HashMap<Int, Typeface>()

	val hasFont: Boolean get() = base != null

	val baseTypeface: Typeface? get() = base

	fun set(typeface: Typeface?) {
		synchronized(styledCache) {
			base = typeface
			styledCache.clear()
		}
	}

	/** The active pack's typeface at [weight]/[italic], or null when no pack is active. */
	fun styled(weight: Int, italic: Boolean): Typeface? {
		val b = base ?: return null
		if (weight == 400 && !italic) return b
		val key = weight * 2 + if (italic) 1 else 0
		return synchronized(styledCache) {
			styledCache.getOrPut(key) { createStyled(b, weight, italic) }
		}
	}

	private fun createStyled(base: Typeface, weight: Int, italic: Boolean): Typeface {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			Typeface.create(base, weight.coerceIn(1, 1000), italic)
		} else {
			val style = when {
				weight >= 600 && italic -> Typeface.BOLD_ITALIC
				weight >= 600 -> Typeface.BOLD
				italic -> Typeface.ITALIC
				else -> Typeface.NORMAL
			}
			Typeface.create(base, style)
		}
	}

	/** Re-points an inflated [textView] at the active pack font, keeping its style. */
	fun applyTo(textView: TextView) {
		val current = textView.typeface ?: return
		if (base == null) return
		val weight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			current.weight
		} else if (current.isBold) {
			700
		} else {
			400
		}
		textView.typeface = styled(weight, current.isItalic)
	}
}
