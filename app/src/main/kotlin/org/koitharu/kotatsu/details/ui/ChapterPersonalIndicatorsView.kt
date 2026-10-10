package org.koitharu.kotatsu.details.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.details.data.ChapterPersonalMetadata

/** The existing list/grid star plus compact personal state, with every value reset on rebind. */
class ChapterPersonalIndicatorsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
	LinearLayout(context, attrs) {

	private val note: ImageView
	private val number: TextView
	private val star: ImageView

	init {
		orientation = HORIZONTAL
		gravity = Gravity.CENTER_VERTICAL
		inflate(context, R.layout.view_chapter_personal_indicators, this)
		note = findViewById(R.id.imageView_personal_note)
		number = findViewById(R.id.textView_personal_rating)
		star = findViewById(R.id.imageView_personal_star)
	}

	fun bind(metadata: ChapterPersonalMetadata, accent: Int, inactive: Int) {
		val presentation = metadata.presentation(resources.configuration.locales[0])
		note.isVisible = presentation.hasNote
		note.imageTintList = ColorStateList.valueOf(accent)
		number.text = presentation.ratingText.orEmpty()
		number.isVisible = presentation.isRated
		number.setTextColor(accent)
		star.isActivated = presentation.isRated
		star.imageTintList = ColorStateList.valueOf(if (presentation.isRated) accent else inactive)
		star.contentDescription = if (metadata.rating == null) context.getString(R.string.chapter_personal_unrated)
			else context.getString(R.string.chapter_personal_rating_value, metadata.rating)
	}
}
