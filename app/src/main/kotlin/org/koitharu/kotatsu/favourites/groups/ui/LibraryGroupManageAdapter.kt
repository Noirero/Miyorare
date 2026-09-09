package org.koitharu.kotatsu.favourites.groups.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemMangaListBinding
import org.koitharu.kotatsu.favourites.groups.domain.NaturalTitleComparator

class LibraryGroupManageAdapter(
	items: List<LibraryGroupManageItem>,
	private val onClick: (LibraryGroupManageItem) -> Unit,
) : RecyclerView.Adapter<LibraryGroupManageAdapter.ViewHolder>() {

	private val items = items.toMutableList()

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
		ItemMangaListBinding.inflate(LayoutInflater.from(parent.context), parent, false),
	)

	override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

	override fun getItemCount(): Int = items.size

	fun move(from: Int, to: Int): Boolean {
		if (from !in items.indices || to !in items.indices || from == to) return false
		val item = items.removeAt(from)
		items.add(to, item)
		notifyItemMoved(from, to)
		return true
	}

	fun naturalSort() {
		items.sortWith { left, right ->
			NaturalTitleComparator.compare(left.member.displayTitle, right.member.displayTitle)
		}
		notifyDataSetChanged()
	}

	fun snapshotIds(): List<Long> = items.map { it.member.mangaId }

	inner class ViewHolder(
		private val binding: ItemMangaListBinding,
	) : RecyclerView.ViewHolder(binding.root) {

		init {
			binding.root.setOnClickListener {
				items.getOrNull(bindingAdapterPosition)?.let(onClick)
			}
		}

		fun bind(item: LibraryGroupManageItem) {
			binding.textViewTitle.text = item.member.displayTitle
			binding.textViewSubtitle.textAndVisible = item.manga.source.getTitle(binding.root.context)
			binding.imageViewCover.setImageAsync(item.member.displayCoverUrl, item.manga)
			binding.imageViewPin.isVisible = false
			binding.badge.isVisible = false
			binding.imageViewContinue.isVisible = false
			binding.imageViewContinue.setOnClickListener(null)
		}
	}
}
