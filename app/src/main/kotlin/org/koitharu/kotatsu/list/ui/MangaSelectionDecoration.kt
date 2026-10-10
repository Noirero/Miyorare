package org.koitharu.kotatsu.list.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearancePathProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.NO_ID
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.decor.AbstractSelectionItemDecoration
import org.koitharu.kotatsu.core.util.ext.getItem
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

open class MangaSelectionDecoration(context: Context) : AbstractSelectionItemDecoration() {

	protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	protected val strokeColor = context.getThemeColor(appcompatR.attr.colorPrimary, Color.RED)
	protected val fillColor = ColorUtils.setAlphaComponent(
		ColorUtils.blendARGB(strokeColor, context.getThemeColor(materialR.attr.colorSurface), 0.8f),
		0x74,
	)
	protected val defaultRadius = context.resources.getDimension(R.dimen.list_selector_corner)
	private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = ColorUtils.setAlphaComponent(fillColor, 0x30)
	}
	private val selectionIcon = ContextCompat.getDrawable(context, R.drawable.ic_check)?.mutate()?.apply {
		setTint(context.getThemeColor(materialR.attr.colorOnPrimary))
	}
	private val selectionSize = context.resources.getDimension(R.dimen.manga_status_ribbon_width)
	private val selectionInset = context.resources.getDimension(R.dimen.card_indicator_offset)
	private val selectionControlOffset = selectionSize + context.resources.getDimension(R.dimen.library_indicator_spacing)
	private val coverBounds = Rect()
	private val coverBoundsF = RectF()
	private val coverPath = Path()

	init {
		hasBackground = false
		hasForeground = true
		isIncludeDecorAndMargins = false

		paint.strokeWidth = context.resources.getDimension(R.dimen.selection_stroke_width)
	}

	override fun getItemId(parent: RecyclerView, child: View): Long {
		val holder = parent.getChildViewHolder(child) ?: return NO_ID
		val item = holder.getItem(MangaListModel::class.java) ?: return NO_ID
		return item.id
	}

	override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
		// Update before children draw, using the same selection authority as the fill/outline.
		for (child in parent.children) {
			val indicators = child.findViewById<View>(R.id.iconsView) as? MangaIndicatorsView ?: continue
			val selected = getItemId(parent, child) in checkedItemsIds
			indicators.applySelectionPresentation(selected)
			if (child.isSelected != selected) child.isSelected = selected
			// Status cards already reserve this corner during binding. Normal cards need the
			// same temporary space for the selected marker, without hiding existing controls.
			val controls = child.findViewById<View>(R.id.layout_indicators)
			if (controls != null) {
				val gravity = (controls.layoutParams as? FrameLayout.LayoutParams)?.gravity ?: 0
				controls.translationY = if (selected && indicators.status == MangaCardStatus.NONE &&
					(gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP) selectionControlOffset else 0f
			} else {
				child.findViewById<View>(R.id.imageView_pin)?.translationY =
					if (selected && indicators.status == MangaCardStatus.NONE) selectionControlOffset else 0f
			}
		}
		super.onDraw(canvas, parent, state)
	}

	override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
		val checkpoint = canvas.save()
		if (parent.clipToPadding) {
			canvas.clipRect(parent.paddingLeft, parent.paddingTop, parent.width - parent.paddingRight, parent.height - parent.paddingBottom)
		}
		for (child in parent.children) {
			// Selection owns the stronger existing fill and border; never add status dimming to it.
			if (getItemId(parent, child) in checkedItemsIds) continue
			val indicators = child.findViewById<View>(R.id.iconsView) as? MangaIndicatorsView ?: continue
			if (indicators.status == MangaCardStatus.NONE) continue
			val cover = child.findViewById<View>(R.id.imageView_cover) ?: continue
			cover.getDrawingRect(coverBounds)
			parent.offsetDescendantRectToMyCoords(cover, coverBounds)
			coverBoundsF.set(coverBounds)
			coverBoundsF.offset(child.translationX, child.translationY)
			if (cover is ShapeableImageView) {
				ShapeAppearancePathProvider.getInstance().calculatePath(cover.shapeAppearanceModel, 1f, coverBoundsF, coverPath)
				canvas.drawPath(coverPath, statusPaint)
			} else {
				canvas.drawRoundRect(coverBoundsF, defaultRadius, defaultRadius, statusPaint)
			}
		}
		canvas.restoreToCount(checkpoint)
		super.onDrawOver(canvas, parent, state)
	}

	override fun onDrawForeground(
		canvas: Canvas,
		parent: RecyclerView,
		child: View,
		bounds: RectF,
		state: RecyclerView.State,
	) {
		val radius = (child as? CardView)?.radius ?: defaultRadius
		paint.color = fillColor
		paint.style = Paint.Style.FILL
		canvas.drawRoundRect(bounds, radius, radius, paint)
		paint.color = strokeColor
		paint.style = Paint.Style.STROKE
		canvas.drawRoundRect(bounds, radius, radius, paint)
		if (child.findViewById<View>(R.id.iconsView) !is MangaIndicatorsView) return
		val cover = child.findViewById<View>(R.id.imageView_cover) ?: return
		cover.getDrawingRect(coverBounds)
		parent.offsetDescendantRectToMyCoords(cover, coverBounds)
		val left = if (child.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
			coverBounds.left + child.translationX + selectionInset
		} else {
			coverBounds.right + child.translationX - selectionInset - selectionSize
		}
		val top = coverBounds.top + child.translationY + selectionInset
		paint.style = Paint.Style.FILL
		canvas.drawCircle(left + selectionSize / 2, top + selectionSize / 2, selectionSize / 2, paint)
		val iconInset = selectionSize / 6
		selectionIcon?.apply {
			setBounds((left + iconInset).toInt(), (top + iconInset).toInt(),
				(left + selectionSize - iconInset).toInt(), (top + selectionSize - iconInset).toInt())
			draw(canvas)
		}
	}
}
