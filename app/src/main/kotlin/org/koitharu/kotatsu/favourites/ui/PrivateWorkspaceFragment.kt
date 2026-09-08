package org.koitharu.kotatsu.favourites.ui

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
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
    private val preferences by lazy { PreferenceManager.getDefaultSharedPreferences(requireContext().applicationContext) }
    private val themeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MiyorareAppearance.KEY_PRIVATE_FAVOURITES_THEME ||
            key == MiyorareAppearance.KEY_THEME_PRESET ||
            key == MiyorareAppearance.KEY_CUSTOM_ACCENT
        ) {
            view?.post(::applyPrivateTheme)
        }
    }

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
        ViewCompat.setOnApplyWindowInsetsListener(navigation) { nav, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val gestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            nav.updatePadding(bottom = maxOf(bars.bottom, gestures.bottom))
            insets
        }

        navigation.menu.findItem(selectedItemId)?.isChecked = true
        navigation.setOnItemSelectedListener { item ->
            showDestination(item.itemId)
            true
        }
        navigation.setOnItemReselectedListener {
            // Keep the current child and its scroll/filter state.
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
        applyPrivateTheme()
        ViewCompat.requestApplyInsets(navigation)
    }

    override fun onStart() {
        super.onStart()
        preferences.registerOnSharedPreferenceChangeListener(themeListener)
    }

    override fun onStop() {
        preferences.unregisterOnSharedPreferenceChangeListener(themeListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        applyPrivateTheme()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED, selectedItemId)
        super.onSaveInstanceState(outState)
    }

    private fun applyPrivateTheme() {
        val root = view ?: return
        val palette = requireContext().miyorareViewPaletteFromPreferences(privateFavourites = true) ?: return
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.blendARGB(palette.background, palette.primary, 0.055f),
                palette.background,
                ColorUtils.blendARGB(palette.background, palette.surface, 0.32f),
            ),
        )
        root.findViewById<View>(R.id.private_workspace_content)?.setBackgroundColor(Color.TRANSPARENT)

        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        )
        navigation.setBackgroundColor(palette.surfaceContainer)
        navigation.itemIconTintList = ColorStateList(
            states,
            intArrayOf(palette.primary, palette.onSurfaceVariant),
        )
        navigation.itemTextColor = ColorStateList(
            states,
            intArrayOf(palette.primary, palette.onSurfaceVariant),
        )
        navigation.itemRippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.primary, 28))
        navigation.itemActiveIndicatorColor = ColorStateList.valueOf(
            ColorUtils.blendARGB(palette.surfaceContainerHigh, palette.primary, 0.18f),
        )
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
