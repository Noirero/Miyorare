package org.koitharu.kotatsu.details.ui.pager.chapters

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import com.google.android.material.R as materialR
import kotlin.math.abs

/**
 * Read-state actions never remove chapters. Grid items, headers and active selection are excluded.
 * Only an applicable direction can commit: left marks unread items read, right marks read items unread.
 */
internal class ChapterReadSwipeCallback(
    context: Context,
    private val findChapter: (RecyclerView.ViewHolder) -> ChapterListItem?,
    private val isSelectionActive: () -> Boolean,
    private val onAction: (ChapterListItem, Boolean) -> Unit,
    private val onRebind: (Int) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val readIcon = ContextCompat.getDrawable(context, R.drawable.ic_eye_check)?.mutate()
    private val unreadIcon = ContextCompat.getDrawable(context, R.drawable.ic_eye_off)?.mutate()
    private val readColor = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
    private val unreadColor = context.getThemeColor(materialR.attr.colorSecondary)

    override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        if (isSelectionActive()) return 0
        val item = findChapter(viewHolder) ?: return 0
        if (item.isGrid) return 0
        return if (item.isUnread) ItemTouchHelper.LEFT else ItemTouchHelper.RIGHT
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 0.27f

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        val chapter = findChapter(viewHolder)
        if (chapter != null && (direction == ItemTouchHelper.LEFT || direction == ItemTouchHelper.RIGHT)) {
            onAction(chapter, direction == ItemTouchHelper.LEFT)
        }
        if (position != RecyclerView.NO_POSITION) onRebind(position)
    }

    override fun onChildDraw(
        canvas: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && abs(dX) > 0f) {
            val view = viewHolder.itemView
            val toRead = dX < 0
            val left = if (toRead) view.right + dX else view.left.toFloat()
            val right = if (toRead) view.right.toFloat() else view.left + dX
            val top = view.top.toFloat()
            val bottom = view.bottom.toFloat()
            if (right > left) {
                fill.color = if (toRead) readColor else unreadColor
                val radius = (bottom - top) / 2f
                canvas.drawRoundRect(left, top, right, bottom, radius, radius, fill)
                val icon = if (toRead) readIcon else unreadIcon
                if (icon != null && right - left > icon.intrinsicWidth.toFloat()) {
                    icon.setTint(Color.WHITE)
                    val cx = ((left + right) / 2).toInt()
                    val cy = ((top + bottom) / 2).toInt()
                    icon.setBounds(
                        cx - icon.intrinsicWidth / 2,
                        cy - icon.intrinsicHeight / 2,
                        cx + icon.intrinsicWidth / 2,
                        cy + icon.intrinsicHeight / 2,
                    )
                    icon.draw(canvas)
                }
            }
        }
        super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
