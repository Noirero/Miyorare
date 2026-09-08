package org.koitharu.kotatsu.favourites.ui

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.explore.ui.ExploreFragment
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.history.ui.HistoryListFragment
import org.koitharu.kotatsu.settings.PrivateFavouritesSettingsFragment
import org.koitharu.kotatsu.tracker.ui.feed.FeedFragment

/**
 * Persistent, authenticated Private workspace. The five top-level destinations live inside the
 * same FLAG_SECURE FavouritesActivity and therefore never have to fall back to Normal navigation.
 *
 * Destinations are detached instead of replaced when the user switches tabs. This keeps each
 * Fragment/ViewModel state (filters, paging and scroll restoration) without retaining five heavy
 * view hierarchies at the same time.
 */
class PrivateWorkspaceFragment : Fragment(R.layout.fragment_private_workspace) {

    private var selectedItemId: Int = R.id.private_nav_favourites
    private lateinit var navigation: BottomNavigationView

    private val backToLibrary = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            navigation.selectedItemId = R.id.private_nav_favourites
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectedItemId = savedInstanceState?.getInt(STATE_SELECTED, R.id.private_nav_favourites)
            ?: R.id.private_nav_favourites
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backToLibrary)

        navigation = view.findViewById(R.id.private_workspace_navigation)
        // Keep all five labels above the gesture/navigation bar. FragmentContainerActivity leaves
        // system-bar insets available to its child, so the workspace owns only this bottom padding.
        ViewCompat.setOnApplyWindowInsetsListener(navigation) { nav, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            nav.updatePadding(bottom = bottom)
            insets
        }

        // Mark the restored item before installing the listener. Doing this in the opposite order
        // can dispatch an initial selection callback and then create the same destination again.
        navigation.menu.findItem(selectedItemId)?.isChecked = true
        navigation.setOnItemSelectedListener { item ->
            showDestination(item.itemId)
            true
        }
        navigation.setOnItemReselectedListener {
            // Reselecting the current destination must be a no-op. In particular, do not rebuild
            // Explore/Feed or reset the user's current list position.
        }

        val current = childFragmentManager.primaryNavigationFragment
        if (current == null) {
            showDestination(selectedItemId)
        } else {
            selectedItemId = itemIdFor(current)
            navigation.menu.findItem(selectedItemId)?.isChecked = true
            updateTitle(selectedItemId)
            backToLibrary.isEnabled = selectedItemId != R.id.private_nav_favourites
        }
        ViewCompat.requestApplyInsets(navigation)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED, selectedItemId)
        super.onSaveInstanceState(outState)
    }

    private fun showDestination(itemId: Int) {
        val fragmentManager = childFragmentManager
        val tag = destinationTag(itemId)
        val current = fragmentManager.primaryNavigationFragment
        val existing = fragmentManager.findFragmentByTag(tag)
        if (current === existing && existing != null && !existing.isDetached) {
            selectedItemId = itemId
            updateTitle(itemId)
            backToLibrary.isEnabled = itemId != R.id.private_nav_favourites
            return
        }

        val target = existing ?: createDestination(itemId)
        selectedItemId = itemId
        backToLibrary.isEnabled = itemId != R.id.private_nav_favourites
        updateTitle(itemId)

        fragmentManager.commit {
            setReorderingAllowed(true)
            if (current != null && current !== target && !current.isDetached) {
                detach(current)
            }
            if (target.isDetached) {
                attach(target)
            } else if (!target.isAdded) {
                add(R.id.private_workspace_content, target, tag)
            }
            setPrimaryNavigationFragment(target)
        }
    }

    private fun createDestination(itemId: Int): Fragment = when (itemId) {
        R.id.private_nav_feed -> FeedFragment().withPrivateScope()
        R.id.private_nav_history -> HistoryListFragment().withPrivateScope()
        R.id.private_nav_explore -> ExploreFragment().withPrivateScope()
        R.id.private_nav_settings -> PrivateFavouritesSettingsFragment()
        else -> FavouritesContainerFragment().withPrivateScope()
    }

    private fun itemIdFor(fragment: Fragment): Int = when (fragment) {
        is FeedFragment -> R.id.private_nav_feed
        is HistoryListFragment -> R.id.private_nav_history
        is ExploreFragment -> R.id.private_nav_explore
        is PrivateFavouritesSettingsFragment -> R.id.private_nav_settings
        else -> R.id.private_nav_favourites
    }

    private fun destinationTag(itemId: Int): String = "private-workspace-$itemId"

    private fun updateTitle(itemId: Int) {
        requireActivity().setTitle(
            when (itemId) {
                R.id.private_nav_feed -> R.string.private_workspace_feed
                R.id.private_nav_history -> R.string.private_workspace_history
                R.id.private_nav_explore -> R.string.private_workspace_explore
                R.id.private_nav_settings -> R.string.private_workspace_settings
                else -> R.string.private_favourites
            },
        )
    }

    private fun <T : Fragment> T.withPrivateScope(): T = apply {
        arguments = Bundle().apply {
            putInt(EXTRA_FAVOURITE_SPACE, FavouriteSpace.PRIVATE.dbValue)
        }
    }

    private companion object {
        const val STATE_SELECTED = "private_workspace_selected"
    }
}
