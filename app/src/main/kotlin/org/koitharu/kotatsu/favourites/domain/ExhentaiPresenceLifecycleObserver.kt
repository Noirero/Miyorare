package org.koitharu.kotatsu.favourites.domain

import android.content.SharedPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore

/** Process-level triggers; never start filesystem work from a Favorites render. */
internal class ExhentaiPresenceLifecycleObserver(
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val scan: suspend (Boolean) -> Unit,
) : DefaultLifecycleObserver, SharedPreferences.OnSharedPreferenceChangeListener {
    override fun onCreate(owner: LifecycleOwner) {
        prefs.registerOnSharedPreferenceChangeListener(this)
        // Startup must run even when foreground refresh is disabled.
        request(full = true)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (prefs.getBoolean(ExhentaiPresenceDiscovery.KEY_SCAN_ON_RESUME, true)) {
            request(full = false)
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == AppSettings.KEY_LOCAL_STORAGE ||
            key == DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT) {
            // An explicit destination change is not a background-resume scan.
            request(full = false)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    private fun request(full: Boolean) {
        scope.launch { scan(full) }
    }
}
