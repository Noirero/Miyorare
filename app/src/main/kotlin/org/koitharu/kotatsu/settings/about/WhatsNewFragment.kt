package org.koitharu.kotatsu.settings.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.about.changelog.ChangelogFragment
import org.koitharu.kotatsu.settings.about.whatsnew.ReleaseHighlights
import org.koitharu.kotatsu.settings.about.whatsnew.ReleaseHighlightsContent
import org.koitharu.kotatsu.settings.about.whatsnew.WhatsNewCoordinator
import org.koitharu.kotatsu.settings.about.whatsnew.WhatsNewDestinationRouter
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareSettingsTheme
import javax.inject.Inject

@AndroidEntryPoint
class WhatsNewFragment : BaseComposeSettingsFragment(R.string.whats_new_title) {
    @Inject lateinit var coordinator: WhatsNewCoordinator
    private var navigationInFlight = false

    override fun onResume() {
        super.onResume()
        navigationInFlight = false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MiyorareSettingsTheme("about") {
                    val content by produceState<Pair<Boolean, ReleaseHighlights?>>(false to null) {
                        value = true to coordinator.load()
                    }
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        val highlights = content.second
                        when {
                            !content.first -> CircularProgressIndicator()
                            highlights == null -> Text(
                                stringResource(R.string.whats_new_no_content),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            else -> ReleaseHighlightsContent(highlights, onDestination = { destination ->
                                val host = activity as? SettingsActivity
                                if (!navigationInFlight && host != null) {
                                    navigationInFlight = WhatsNewDestinationRouter.open(host, destination)
                                }
                            })
                        }
                        TextButton(onClick = {
                            val host = activity as? SettingsActivity
                            if (!navigationInFlight && host != null && !host.supportFragmentManager.isStateSaved) {
                                navigationInFlight = true
                                host.openFragment(ChangelogFragment::class.java, null, isFromRoot = false)
                            }
                        }) { Text(stringResource(R.string.changelog)) }
                    }
                }
            }
        }
}
