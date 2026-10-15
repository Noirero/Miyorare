package org.koitharu.kotatsu.details.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import androidx.core.view.isVisible
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.details.data.ChapterPersonalMetadata

/** The existing list/grid star plus compact personal state, with every value reset on rebind. */
class ChapterPersonalIndicatorsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
	LinearLayout(context, attrs) {

	private val note: ImageView
	private val number: TextView
	private val star: ImageView
	private var stackWhenNarrow = false

	init {
		orientation = HORIZONTAL
		gravity = Gravity.CENTER_VERTICAL
		inflate(context, R.layout.view_chapter_personal_indicators, this)
		note = findViewById(R.id.imageView_personal_note)
		number = findViewById(R.id.textView_personal_rating)
		star = findViewById(R.id.imageView_personal_star)
		context.withStyledAttributes(attrs, R.styleable.ChapterPersonalIndicatorsView) {
			stackWhenNarrow = getBoolean(R.styleable.ChapterPersonalIndicatorsView_stackWhenNarrow, false)
		}
		if (stackWhenNarrow) gravity = Gravity.CENTER_HORIZONTAL
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		if (stackWhenNarrow) {
			// Measure the two actions at their natural widths before choosing rows. Recalculate on
			// every bind/resize so a recycled narrow grid cell can become horizontal again.
			val unconstrainedWidth = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
			var rowWidth = paddingLeft + paddingRight
			for (index in 0 until childCount) {
				val child = getChildAt(index)
				if (child.visibility == GONE) continue
				measureChildWithMargins(child, unconstrainedWidth, 0, heightMeasureSpec, 0)
				val params = child.layoutParams as LayoutParams
				rowWidth += child.measuredWidth + params.leftMargin + params.rightMargin
			}
			orientation = if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED &&
				rowWidth > MeasureSpec.getSize(widthMeasureSpec)
			) VERTICAL else HORIZONTAL
		}
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)
	}

	fun setOnNoteClickListener(listener: View.OnClickListener?) {
		note.setOnClickListener(listener)
		note.isClickable = listener != null
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
