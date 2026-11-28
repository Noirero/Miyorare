package org.koitharu.kotatsu.settings.about.whatsnew

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R

@Composable
internal fun ReleaseHighlightsContent(
    highlights: ReleaseHighlights,
    onDestination: (FeatureDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalConfiguration.current.locales[0].language
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.whats_new_version_label, highlights.version),
            style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        if (highlights.fixture) {
            Text(stringResource(R.string.whats_new_fixture_notice), style = MaterialTheme.typography.labelMedium)
        }
        Text(highlights.summary.forLanguage(language), style = MaterialTheme.typography.bodyMedium)
        for (category in ReleaseCategory.entries) {
            val entries = highlights.entries.filter { it.category == category }
            if (entries.isEmpty()) continue
            Text(stringResource(when (category) {
                ReleaseCategory.NEW -> R.string.whats_new_badge_new
                ReleaseCategory.IMPROVED -> R.string.whats_new_badge_improved
                ReleaseCategory.FIXED -> R.string.whats_new_fixed_label
            }), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            for (entry in entries) {
                val destination = entry.destination
                Card(modifier = Modifier.fillMaxWidth().testTag("whats_new_entry_" + entry.id)
                    .then(if (destination != null) Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.whats_new_open_feature),
                    ) { onDestination(destination) } else Modifier)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(entry.title.forLanguage(language), style = MaterialTheme.typography.titleMedium)
                        Text(entry.description.forLanguage(language), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (destination != null) Text(stringResource(R.string.whats_new_open_feature),
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
internal fun WhatsNewPopup(
    highlights: ReleaseHighlights,
    onDestination: (FeatureDestination) -> Unit,
    onClose: () -> Unit,
) {
    val height = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(120).dp
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = height),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.whats_new_title), style = MaterialTheme.typography.headlineSmall)
                ReleaseHighlightsContent(highlights, onDestination)
                TextButton(onClick = { onDestination(FeatureDestination.WHATS_NEW) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.whats_new_view_all))
                }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("whats_new_close")) {
                    Text(stringResource(R.string.whats_new_continue))
                }
            }
        }
    }
}
