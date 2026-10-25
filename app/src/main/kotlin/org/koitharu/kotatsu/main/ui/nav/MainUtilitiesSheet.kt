package org.koitharu.kotatsu.main.ui.nav

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.settings.compose.MiyorareTheme

/** An auxiliary surface: opening/dismissing it never changes the selected retained root. */
class MainUtilitiesSheet : BottomSheetDialogFragment() {
	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				Column(Modifier.navigationBarsPadding().testTag("main_utilities_sheet")) {
					ListItem(
						headlineContent = { Text(stringResource(R.string.reader_journey)) },
						leadingContent = { Icon(painterResource(R.drawable.ic_auto_stories), null) },
						modifier = Modifier.heightIn(min = 48.dp).testTag("utility_journey").clickable { dismiss(); router.openStatistic() },
					)
					ListItem(
						headlineContent = { Text(stringResource(R.string.settings)) },
						leadingContent = { Icon(painterResource(R.drawable.ic_settings), null) },
						modifier = Modifier.heightIn(min = 48.dp).testTag("utility_settings").clickable { dismiss(); router.openSettings() },
					)
				}
			}
		}
	}
}
