package org.koitharu.kotatsu.core.icons

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide cache of the active Icon Pack's rendered glyphs, keyed by
 * built-in drawable name (for example `ic_appearance`). Populated by
 * [IconPackRepository] whenever the installed set or the active pack
 * changes; empty when the built-in icons are in use. Settings components
 * read this to decide, per glyph slot, whether pack artwork replaces the
 * built-in drawable.
 */
object IconPackHolder {

	private val _glyphs = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
	val glyphs = _glyphs.asStateFlow()

	fun set(glyphs: Map<String, ImageBitmap>) {
		_glyphs.value = glyphs
	}

	fun glyph(key: String): ImageBitmap? = _glyphs.value[key]
}
