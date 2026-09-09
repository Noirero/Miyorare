package org.koitharu.kotatsu.settings.about.changelog

import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.jsoup.internal.StringUtil
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.github.AppUpdateRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import javax.inject.Inject

@HiltViewModel
class ChangelogViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val appUpdateRepository: AppUpdateRepository,
) : BaseViewModel() {

	val changelog = MutableStateFlow<String?>(null)

	init {
		launchLoadingJob(Dispatchers.Default) {
			val versions = appUpdateRepository.getAvailableVersions()
			val stringJoiner = StringUtil.StringJoiner("\n\n\n")
			val currentBaseVersion = BuildConfig.VERSION_NAME.substringBefore('-')
			if (currentBaseVersion == LOCAL_RELEASE_VERSION) {
				stringJoiner.add("# ")
					.append(LOCAL_RELEASE_VERSION)
					.append("\n\n")
					.append(context.getString(R.string.changelog_1_0_0_body))
			}
			for (version in versions) {
				val remoteBaseVersion = version.name.trim().removePrefix("v").removePrefix("V").substringBefore('-')
				if (currentBaseVersion == LOCAL_RELEASE_VERSION && remoteBaseVersion == LOCAL_RELEASE_VERSION) {
					continue
				}
				stringJoiner.add("# ")
					.append(version.name)
					.append("\n\n")
					.append(version.description.formatChangelogDescription())
			}
			changelog.value = stringJoiner.complete()
		}
	}

	private fun String.formatChangelogDescription(): String {
		return replace(Regex("(?<!\\n)\\nIf this is your first"), "\n\nIf this is your first")
	}

	private companion object {
		const val LOCAL_RELEASE_VERSION = "1.0.0"
	}
}
