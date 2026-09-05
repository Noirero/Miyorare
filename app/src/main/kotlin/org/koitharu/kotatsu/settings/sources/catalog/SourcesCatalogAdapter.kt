package org.koitharu.kotatsu.settings.sources.catalog

import android.content.Context
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.fastscroll.FastScroller
import org.koitharu.kotatsu.databinding.ItemExtensionUpdateFooterBinding
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.ListHeaderClickListener
import org.koitharu.kotatsu.list.ui.adapter.listHeaderAD
import org.koitharu.kotatsu.list.ui.adapter.loadingStateAD
import org.koitharu.kotatsu.list.ui.model.ButtonFooter
import org.koitharu.kotatsu.list.ui.model.ListModel

data class InstallerFooterInfo(
	val title: CharSequence,
	val summary: CharSequence,
)

class SourcesCatalogAdapter(
	extensionActionListener: ExtensionActionListener,
	headerClickListener: ListHeaderClickListener,
	onUpdateAll: () -> Unit,
	installerMethodInfo: () -> InstallerFooterInfo = { InstallerFooterInfo("", "") },
	onInstallerMethodClick: () -> Unit = {},
) : BaseListAdapter<ListModel>(), FastScroller.SectionIndexer {

	init {
		addDelegate(ListItemType.NAV_ITEM, sourceCatalogItemExtensionAD(extensionActionListener))
		addDelegate(ListItemType.HINT_EMPTY, sourceCatalogItemHintAD())
		addDelegate(ListItemType.HEADER, listHeaderAD(headerClickListener))
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
		addDelegate(
			ListItemType.FOOTER_BUTTON,
			updateAllFooterAD(onUpdateAll, installerMethodInfo, onInstallerMethodClick),
		)
	}

	override fun getSectionText(context: Context, position: Int): CharSequence? {
		return (items.getOrNull(position) as? SourceCatalogItem.Extension)?.title?.take(1)
	}
}

private fun updateAllFooterAD(
	onUpdateAll: () -> Unit,
	installerMethodInfo: () -> InstallerFooterInfo,
	onInstallerMethodClick: () -> Unit,
) = adapterDelegateViewBinding<ButtonFooter, ListModel, ItemExtensionUpdateFooterBinding>(
	{ inflater, parent -> ItemExtensionUpdateFooterBinding.inflate(inflater, parent, false) },
) {
	binding.button.setOnClickListener { onUpdateAll() }
	binding.buttonMethod.setOnClickListener { onInstallerMethodClick() }
	bind {
		binding.button.setText(item.textResId)
		val info = installerMethodInfo()
		binding.buttonMethod.text = info.title
		binding.textMethodSummary.text = info.summary
	}
}
