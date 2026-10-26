package org.koitharu.kotatsu.reader.ui.epub.translation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isVisible
import org.koitharu.kotatsu.R

/**
 * Reader-side attribution for the on-device Built-in translation service.
 *
 * This view deliberately lives outside the novel text projection so attribution never participates
 * in source/display offsets, pagination, bookmarks, highlights, or TTS text.
 */
class BuiltInTranslationAttributionView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

	private val translationSettings by lazy(LazyThreadSafetyMode.NONE) {
		NovelTranslationSettings(context.applicationContext)
	}

	init {
		setText(R.string.novel_translation_powered_by_google_translate)
		setOnClickListener {
			context.startActivity(
				Intent(Intent.ACTION_VIEW, Uri.parse(GOOGLE_TRANSLATE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
			)
		}
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		refreshVisibility()
	}

	override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
		super.onWindowFocusChanged(hasWindowFocus)
		if (hasWindowFocus) refreshVisibility()
	}

	fun refreshVisibility() {
		isVisible = translationSettings.engine == NovelTranslationEngineKind.AI &&
			translationSettings.provider == NovelAiProvider.BUILT_IN
	}

	private companion object {
		const val GOOGLE_TRANSLATE_URL = "https://translate.google.com/"
	}
}
