package org.koitharu.kotatsu.favourites.groups.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.core.model.getLocalizedTitle
import org.koitharu.kotatsu.favourites.groups.domain.NaturalTitleComparator
import org.koitharu.kotatsu.parsers.model.MangaChapter
import kotlin.math.abs

data class LibraryGroupTimelineKey(
	val mangaId: Long,
	val chapterId: Long,
)

data class LibraryGroupTimelineEditorItem(
	val mangaId: Long,
	val mangaTitle: String,
	val memberPosition: Int,
	val chapter: MangaChapter,
	val chapterIndex: Int,
) {
	val key: LibraryGroupTimelineKey
		get() = LibraryGroupTimelineKey(mangaId, chapter.id)
}

class LibraryGroupTimelineAdapter(
	items: List<LibraryGroupTimelineEditorItem>,
) : RecyclerView.Adapter<LibraryGroupTimelineAdapter.ViewHolder>() {

	private val items = items.toMutableList()

	init {
		setHasStableIds(true)
	}

	override fun getItemId(position: Int): Long {
		val item = items[position]
		return (item.mangaId * 31L) xor item.chapter.id
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val view = LayoutInflater.from(parent.context)
			.inflate(android.R.layout.simple_list_item_2, parent, false)
		return ViewHolder(view)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		holder.bind(items[position], position)
	}

	override fun getItemCount(): Int = items.size

	fun move(from: Int, to: Int): Boolean {
		if (from !in items.indices || to !in items.indices || from == to) return false
		val item = items.removeAt(from)
		items.add(to, item)
		notifyItemMoved(from, to)
		notifyItemRangeChanged(minOf(from, to), abs(from - to) + 1)
		return true
	}

	fun naturalSort() {
		items.sortWith { left, right ->
			val leftNumber = left.chapter.number.validNumberOrNull()
			val rightNumber = right.chapter.number.validNumberOrNull()
			when {
				leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
				leftNumber != null -> -1
				rightNumber != null -> 1
				else -> NaturalTitleComparator.compare(chapterTitle(left), chapterTitle(right))
			}.takeIf { it != 0 }
				?: left.memberPosition.compareTo(right.memberPosition).takeIf { it != 0 }
				?: left.chapterIndex.compareTo(right.chapterIndex)
		}
		notifyDataSetChanged()
	}

	fun snapshot(): List<LibraryGroupTimelineEditorItem> = items.toList()

	private fun chapterTitle(item: LibraryGroupTimelineEditorItem): String =
		item.chapter.title?.takeIf { it.isNotBlank() }.orEmpty()

	private fun Float.validNumberOrNull(): Float? =
		takeUnless { it.isNaN() || it.isInfinite() || it < 0f }

	inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
		private val title = view.findViewById<TextView>(android.R.id.text1)
		private val subtitle = view.findViewById<TextView>(android.R.id.text2)

		fun bind(item: LibraryGroupTimelineEditorItem, position: Int) {
			val resources = itemView.resources
			val chapterTitle = item.chapter.title?.takeIf { it.isNotBlank() }
				?: item.chapter.getLocalizedTitle(resources)
			title.text = "${position + 1}. $chapterTitle"
			subtitle.text = buildString {
				append(item.mangaTitle)
				item.chapter.branch?.takeIf { it.isNotBlank() }?.let {
					append(" • ")
					append(it)
				}
			}
		}
	}
}
