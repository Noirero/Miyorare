package org.koitharu.kotatsu.list.ui.adapter

import android.view.View
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemSmartLocalGroupRowBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.SmartLocalGroupRowModel

fun smartLocalGroupRowAD(listener: MangaListListener) =
    adapterDelegateViewBinding<SmartLocalGroupRowModel, ListModel, ItemSmartLocalGroupRowBinding>(
        { inflater, parent -> ItemSmartLocalGroupRowBinding.inflate(inflater, parent, false) },
    ) {
        binding.cardFirst.setOnClickListener { listener.onSmartLocalGroupSelected(item.first.name) }
        binding.cardSecond.setOnClickListener { item.second?.let { group -> listener.onSmartLocalGroupSelected(group.name) } }
        bind {
            binding.firstName.text = item.first.name
            binding.firstCount.text = context.getString(R.string.smart_local_group_count_short, item.first.count)
            binding.cardFirst.contentDescription = context.getString(R.string.smart_local_group_card, item.first.name, item.first.count)
            val second = item.second
            binding.cardSecond.visibility = if (second == null) View.INVISIBLE else View.VISIBLE
            if (second != null) {
                binding.secondName.text = second.name
                binding.secondCount.text = context.getString(R.string.smart_local_group_count_short, second.count)
                binding.cardSecond.contentDescription = context.getString(R.string.smart_local_group_card, second.name, second.count)
            }
        }
    }
