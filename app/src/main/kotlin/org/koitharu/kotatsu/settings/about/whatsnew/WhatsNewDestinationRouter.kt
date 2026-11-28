package org.koitharu.kotatsu.settings.about.whatsnew

import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.main.ui.MainActivity
import org.koitharu.kotatsu.settings.AppearanceSettingsFragment
import org.koitharu.kotatsu.settings.BackupSettingsFragment
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.about.WhatsNewFragment
import org.koitharu.kotatsu.settings.nav.NavConfigFragment
import org.koitharu.kotatsu.settings.nav.NavigationTransitionSettingsFragment
import org.koitharu.kotatsu.settings.sources.ExtensionsSettingsFragment

/** Explicit allowlist: no arbitrary Intent, reflection, private-space or destructive actions. */
internal object WhatsNewDestinationRouter {
    const val ACTION_OPEN = "org.noirero.miyorare.action.WHATS_NEW_DESTINATION"
    const val EXTRA_DESTINATION = "whats_new_destination"

    fun settingsFragment(destination: FeatureDestination): Fragment? = when (destination) {
        FeatureDestination.SCREEN_TRANSITIONS -> NavigationTransitionSettingsFragment()
        FeatureDestination.APPEARANCE -> AppearanceSettingsFragment()
        FeatureDestination.SOURCES_EXTENSIONS -> ExtensionsSettingsFragment()
        FeatureDestination.BACKUP_RESTORE -> BackupSettingsFragment()
        FeatureDestination.NAVIGATION_CONFIGURATION -> NavConfigFragment()
        FeatureDestination.WHATS_NEW -> WhatsNewFragment()
        else -> null
    }

    fun open(host: FragmentActivity, destination: FeatureDestination): Boolean {
        if (host.isFinishing || host.isDestroyed || host.supportFragmentManager.isStateSaved ||
            !host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        return when (destination) {
            FeatureDestination.DOWNLOADS -> { host.router.openDownloads(); true }
            FeatureDestination.READER_JOURNEY -> { host.router.openStatistic(); true }
            FeatureDestination.DISCOVERY_HUB -> {
                if (host is MainActivity) host.openWhatsNewDiscovery() else {
                    host.startActivity(Intent(host, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(MainActivity.EXTRA_OPEN_WHATS_NEW_DISCOVERY, true)
                    })
                    true
                }
            }
            else -> {
                val fragment = settingsFragment(destination) ?: return false
                if (host is SettingsActivity) {
                    host.openFragment(fragment.javaClass, null, isFromRoot = false)
                } else {
                    host.startActivity(Intent(host, SettingsActivity::class.java).apply {
                        action = ACTION_OPEN
                        putExtra(EXTRA_DESTINATION, destination.name)
                    })
                }
                true
            }
        }
    }
}
