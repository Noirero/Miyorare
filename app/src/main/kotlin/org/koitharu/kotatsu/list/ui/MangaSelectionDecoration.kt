package org.koitharu.kotatsu.list.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.cardview.widget.CardView
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
		color = ColorUtils.setAlphaComponent(fillColor, 0x24)
	}
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
	}
}
