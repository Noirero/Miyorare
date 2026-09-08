package org.koitharu.kotatsu.favourites.ui

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
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
        navigation.setOnItemSelectedListener { item ->
            showDestination(item.itemId)
            true
        }
        navigation.selectedItemId = selectedItemId
        if (childFragmentManager.findFragmentById(R.id.private_workspace_content) == null) {
            showDestination(selectedItemId)
        } else {
            updateTitle(selectedItemId)
            backToLibrary.isEnabled = selectedItemId != R.id.private_nav_favourites
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED, selectedItemId)
        super.onSaveInstanceState(outState)
    }

    private fun showDestination(itemId: Int) {
        selectedItemId = itemId
        backToLibrary.isEnabled = itemId != R.id.private_nav_favourites
        updateTitle(itemId)
        val fragment = when (itemId) {
            R.id.private_nav_feed -> FeedFragment().withPrivateScope()
            R.id.private_nav_history -> HistoryListFragment().withPrivateScope()
            R.id.private_nav_explore -> ExploreFragment().withPrivateScope()
            R.id.private_nav_settings -> PrivateFavouritesSettingsFragment()
            else -> FavouritesContainerFragment().withPrivateScope()
        }
        childFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.private_workspace_content, fragment, "private-workspace-$itemId")
        }
    }

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
