package org.koitharu.kotatsu.favourites.ui.container

import android.content.SharedPreferences
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.FlowCollector
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.util.ContinuationResumeRunnable
import org.koitharu.kotatsu.core.util.ext.withArgs
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment
import org.koitharu.kotatsu.favourites.ui.list.LocalFavouritesListFragment
import kotlin.coroutines.suspendCoroutine

class FavouritesContainerAdapter(
	private val fragment: Fragment,
	private val showCategoryCounts: () -> Boolean,
	private val onListCommitted: (List<FavouriteTabModel>) -> Unit = {},
) : FragmentStateAdapter(fragment), FlowCollector<List<FavouriteTabModel>> {

	private val favouriteSpace = FavouriteSpace.fromArgument(
		fragment.arguments?.getInt(EXTRA_FAVOURITE_SPACE, FavouriteSpace.NORMAL.dbValue)
			?: FavouriteSpace.NORMAL.dbValue,
	)
	private val differ = AsyncListDiffer(
		DeferredPagerUpdateCallback,
		AsyncDifferConfig.Builder(FavouriteTabDiffCallback)
			.setBackgroundThreadExecutor(Dispatchers.Default.limitedParallelism(2).asExecutor())
			.build(),
	)
	private val preferences = PreferenceManager.getDefaultSharedPreferences(fragment.requireContext())
	private val categoryCountPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		if (key?.endsWith(CATEGORY_COUNT_PREFERENCE_SUFFIX) == true) {
			fragment.view?.post { updateTabBadgeNumbers(differ.currentList) }
		}
	}

	init {
		preferences.registerOnSharedPreferenceChangeListener(categoryCountPreferenceListener)
		fragment.viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
			override fun onDestroy(owner: LifecycleOwner) {
				preferences.unregisterOnSharedPreferenceChangeListener(categoryCountPreferenceListener)
			}
		})
	}

	override fun getItemCount(): Int = differ.currentList.size

	override fun getItemId(position: Int): Long {
		return differ.currentList.getOrNull(position)?.id ?: RecyclerView.NO_ID
	}

	override fun containsItem(itemId: Long): Boolean {
		return differ.currentList.any { x -> x.id == itemId }
	}

	override fun createFragment(position: Int): Fragment {
		val item = differ.currentList[position]
		return if (item.id == LOCAL_FAVOURITES_CATEGORY_ID) {
			LocalFavouritesListFragment().withArgs(1) {
				putInt(EXTRA_FAVOURITE_SPACE, favouriteSpace.dbValue)
			}
		} else {
			FavouritesListFragment().withArgs(2) {
				putLong(AppRouter.KEY_ID, item.id)
				putInt(EXTRA_FAVOURITE_SPACE, favouriteSpace.dbValue)
			}
		}
	}

	override suspend fun emit(value: List<FavouriteTabModel>) = suspendCoroutine { cont ->
		val pager = fragment.view?.findViewById<ViewPager2>(R.id.pager)
		val previousItems = differ.currentList
		val previousIndex = pager?.currentItem ?: RecyclerView.NO_POSITION
		val previousId = previousItems.getOrNull(previousIndex)?.id
		val activeCategoryRemoved = previousId != null && value.none { it.id == previousId }
		val pagerStructureChanged = !hasSamePagerStructure(previousItems, value)
		differ.submitList(value) {
			// DiffUtil may emit many insert/remove/change callbacks for one logical category refresh.
			// TabLayoutMediator reacts to each callback by removeAllTabs() + full repopulation, which can
			// monopolize the main thread and cause input-dispatch ANRs. Suppress those granular adapter
			// callbacks above and publish one stable-id-aware refresh only after the differ has committed.
			if (pagerStructureChanged) {
				notifyDataSetChanged()
			}
			if (activeCategoryRemoved && value.isNotEmpty()) {
				val allIndex = value.indexOfFirst { it.id == FavouritesListFragment.NO_ID }
				val nearestVisibleIndex = previousIndex.coerceIn(0, value.lastIndex)
				pager?.setCurrentItem(if (allIndex >= 0) allIndex else nearestVisibleIndex, false)
			}
			// Count-only changes never alter ViewPager structure/content. Update the attached badge and
			// its reserved space directly, avoiding any TabLayoutMediator rebuild for live count updates.
			updateTabBadgeNumbers(value)
			onListCommitted(differ.currentList)
			ContinuationResumeRunnable(cont).run()
		}
	}

	fun getItem(position: Int): FavouriteTabModel = differ.currentList[position]

	private fun hasSamePagerStructure(
		oldItems: List<FavouriteTabModel>,
		newItems: List<FavouriteTabModel>,
	): Boolean {
		if (oldItems.size != newItems.size) return false
		return oldItems.indices.all { index ->
			val oldItem = oldItems[index]
			val newItem = newItems[index]
			oldItem.id == newItem.id && oldItem.title == newItem.title
		}
	}

	private fun updateTabBadgeNumbers(items: List<FavouriteTabModel>) {
		val tabs = fragment.view?.findViewById<TabLayout>(R.id.tabs)
			?: fragment.activity?.findViewById<TabLayout>(R.id.tabs)
			?: return
		if (tabs.tabCount != items.size) return
		val showCounts = showCategoryCounts()
		for (index in items.indices) {
			val item = items[index]
			val tab = tabs.getTabAt(index) ?: continue
			updateFavouriteTabBadge(tab, item.count, showCounts && item.count > 0)
		}
	}

	private object DeferredPagerUpdateCallback : ListUpdateCallback {
		override fun onInserted(position: Int, count: Int) = Unit

		override fun onRemoved(position: Int, count: Int) = Unit

		override fun onMoved(fromPosition: Int, toPosition: Int) = Unit

		override fun onChanged(position: Int, count: Int, payload: Any?) = Unit
	}

	private object FavouriteTabDiffCallback : DiffUtil.ItemCallback<FavouriteTabModel>() {

		override fun areItemsTheSame(oldItem: FavouriteTabModel, newItem: FavouriteTabModel): Boolean {
			return oldItem.id == newItem.id
		}

		override fun areContentsTheSame(oldItem: FavouriteTabModel, newItem: FavouriteTabModel): Boolean {
			// Count changes do not alter ViewPager structure/content. They are applied directly to badges
			// after the differ commits the new list, avoiding TabLayoutMediator's full tab repopulation.
			return oldItem.id == newItem.id && oldItem.title == newItem.title
		}
	}

	private companion object {
		const val CATEGORY_COUNT_PREFERENCE_SUFFIX = "_show_category_counts"
	}
}
