package org.koitharu.kotatsu.settings.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.AsyncListDiffer.ListListener
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.consumeAll
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.FragmentSearchSuggestionBinding
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.listHeaderAD
import org.koitharu.kotatsu.list.ui.model.ListModel

@AndroidEntryPoint
class SettingsSearchFragment : BaseFragment<FragmentSearchSuggestionBinding>(),
	OnListItemClickListener<SettingsItem>,
	ListListener<ListModel> {

	private val viewModel: SettingsSearchViewModel by activityViewModels()

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentSearchSuggestionBinding {
		return FragmentSearchSuggestionBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: FragmentSearchSuggestionBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val adapter = BaseListAdapter<ListModel>()
			.addDelegate(ListItemType.HEADER, listHeaderAD(null))
			.addDelegate(ListItemType.NAV_ITEM, settingsItemAD(this))
		adapter.addListListener(this)
		// Bare FastScrollRecyclerView root: its floating scroller would be added to the settings
		// search container on the shared CoordinatorLayout, drawing a stray track line on the right.
		binding.root.isFastScrollerEnabled = false
		binding.root.adapter = adapter
		binding.root.setHasFixedSize(true)
		viewModel.content.observe(viewLifecycleOwner, adapter)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val type = WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(type)
		v.setPadding(
			barsInsets.left,
			0,
			barsInsets.right,
			barsInsets.bottom,
		)
		return insets.consumeAll(type)
	}

	override fun onItemClick(item: SettingsItem, view: View) = viewModel.navigateToPreference(item)

	override fun onCurrentListChanged(
		previousList: List<ListModel?>,
		currentList: List<ListModel?>
	) {
		if (currentList.size != previousList.size) {
			(viewBinding?.root?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
		}
	}
}
