package org.koitharu.kotatsu.settings.about.changelog

import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseViewModel
import javax.inject.Inject

@HiltViewModel
class ChangelogViewModel @Inject constructor(
	@ApplicationContext context: Context,
) : BaseViewModel() {

	val changelog = MutableStateFlow<String?>(
		buildString {
			append("# ")
			append(BuildConfig.VERSION_NAME)
			append("\n\n")
			append(context.getString(R.string.changelog_current_body))
		},
	)
}
