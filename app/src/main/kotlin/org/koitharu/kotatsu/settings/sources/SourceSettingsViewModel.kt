package org.koitharu.kotatsu.settings.sources

import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.lnreader.LnPluginManager
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.tsuki.runtime.OfficialGlobalDiagnostic
import org.koitharu.kotatsu.tsuki.runtime.OfficialGlobalDiagnosticStatus
import org.koitharu.kotatsu.tsuki.runtime.OfficialGlobalRequestProfile
import org.koitharu.kotatsu.tsuki.runtime.OfficialGlobalSourceResilience
import org.koitharu.kotatsu.tsuki.runtime.OfficialGlobalSourceSettingsSnapshot
import org.koitharu.kotatsu.tsuki.runtime.TsukiPluginRuntime
import javax.inject.Inject

internal data class OfficialGlobalSourceSettingsUiState(
	val snapshot: OfficialGlobalSourceSettingsSnapshot? = null,
	val isLoading: Boolean = false,
	/** Candidate/test result that may intentionally differ from the persisted active-domain status. */
	val latestActionDiagnostic: OfficialGlobalDiagnostic? = null,
)

@HiltViewModel
class SourceSettingsViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	mangaRepositoryFactory: MangaRepository.Factory,
	private val cookieJar: MutableCookieJar,
	private val lnPluginManager: LnPluginManager,
	private val tsukiRuntime: TsukiPluginRuntime,
) : BaseViewModel(), SharedPreferences.OnSharedPreferenceChangeListener {

	val source = MangaSource(savedStateHandle.get<String>(AppRouter.KEY_SOURCE))
	val repository = mangaRepositoryFactory.create(source)
	private val officialGlobalSource = (source.unwrap() as? TsukiMangaSource)
		?.takeIf(OfficialGlobalSourceResilience::supports)

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val onPluginDeleted = MutableEventFlow<Unit>()

	private val mutableOfficialGlobalState = MutableStateFlow(OfficialGlobalSourceSettingsUiState())
	val officialGlobalState = mutableOfficialGlobalState.asStateFlow()

	init {
		if (officialGlobalSource != null) refreshOfficialGlobalSettings()
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		if (repository is CachingMangaRepository) {
			if (key != SourceSettings.KEY_SLOWDOWN && key != SourceSettings.KEY_SORT_ORDER) {
				repository.invalidateCache()
			}
		}
	}

	/** Clears cookies stored for this source's site. */
	fun clearCookies(baseUrl: String) {
		val url = baseUrl.toHttpUrlOrNull() ?: return
		launchJob(Dispatchers.Default) {
			cookieJar.removeCookies(url, null)
			onActionDone.call(ReversibleAction(R.string.cookies_cleared, null))
		}
	}

	fun refreshOfficialGlobalSettings() {
		val target = officialGlobalSource ?: return
		launchJob(Dispatchers.IO) {
			mutableOfficialGlobalState.update { it.copy(isLoading = true) }
			val snapshot = runCatching { tsukiRuntime.getOfficialGlobalSettings(target) }.getOrNull()
			mutableOfficialGlobalState.update {
				it.copy(snapshot = snapshot, isLoading = false)
			}
		}
	}

	fun selectOfficialGlobalTrustedDomain(domain: String) {
		val target = officialGlobalSource ?: return
		runOfficialGlobalAction(target) {
			tsukiRuntime.validateAndApplyOfficialGlobalTrustedDomain(target, domain)
		}
	}

	fun applyOfficialGlobalCustomDomain(input: String) {
		val target = officialGlobalSource ?: return
		runOfficialGlobalAction(target) {
			tsukiRuntime.validateAndApplyOfficialGlobalCustomDomain(target, input)
		}
	}

	fun resetOfficialGlobalDomain() {
		val target = officialGlobalSource ?: return
		launchJob(Dispatchers.IO) {
			mutableOfficialGlobalState.update { it.copy(isLoading = true, latestActionDiagnostic = null) }
			runCatching { tsukiRuntime.resetOfficialGlobalDomain(target) }
			invalidateSourceCache()
			val snapshot = runCatching { tsukiRuntime.getOfficialGlobalSettings(target) }.getOrNull()
			mutableOfficialGlobalState.value = OfficialGlobalSourceSettingsUiState(snapshot = snapshot)
		}
	}

	fun setOfficialGlobalAutoMirror(enabled: Boolean) {
		val target = officialGlobalSource ?: return
		launchJob(Dispatchers.IO) {
			runCatching { tsukiRuntime.setOfficialGlobalAutoMirror(target, enabled) }
			val snapshot = runCatching { tsukiRuntime.getOfficialGlobalSettings(target) }.getOrNull()
			mutableOfficialGlobalState.update { it.copy(snapshot = snapshot, latestActionDiagnostic = null) }
		}
	}

	fun setOfficialGlobalRequestProfile(profile: OfficialGlobalRequestProfile) {
		val target = officialGlobalSource ?: return
		launchJob(Dispatchers.IO) {
			runCatching { tsukiRuntime.setOfficialGlobalRequestProfile(target, profile) }
			val snapshot = runCatching { tsukiRuntime.getOfficialGlobalSettings(target) }.getOrNull()
			mutableOfficialGlobalState.update { it.copy(snapshot = snapshot, latestActionDiagnostic = null) }
		}
	}

	fun testOfficialGlobalAddress() {
		val target = officialGlobalSource ?: return
		runOfficialGlobalAction(target, invalidateOnHealthy = false) {
			tsukiRuntime.testOfficialGlobalAddress(target)
		}
	}

	fun testOfficialGlobalSource() {
		val target = officialGlobalSource ?: return
		runOfficialGlobalAction(target, invalidateOnHealthy = false) {
			tsukiRuntime.testOfficialGlobalSource(target)
		}
	}

	fun resetOfficialGlobalSession() {
		val target = officialGlobalSource ?: return
		launchJob(Dispatchers.IO) {
			mutableOfficialGlobalState.update { it.copy(isLoading = true, latestActionDiagnostic = null) }
			val snapshot = runCatching { tsukiRuntime.getOfficialGlobalSettings(target) }.getOrNull()
			val domains = buildSet {
				snapshot?.activeDomain?.let(::add)
				snapshot?.presetDomains?.let(::addAll)
			}
			domains.forEach { domain ->
				"https://$domain/".toHttpUrlOrNull()?.let { url -> cookieJar.removeCookies(url, null) }
			}
			runCatching { tsukiRuntime.clearOfficialGlobalDiagnostic(target) }
			invalidateSourceCache()
			val refreshed = runCatching { tsukiRuntime.getOfficialGlobalSettings(target) }.getOrNull()
			mutableOfficialGlobalState.value = OfficialGlobalSourceSettingsUiState(snapshot = refreshed)
			onActionDone.call(ReversibleAction(R.string.cookies_cleared, null))
		}
	}

	private fun runOfficialGlobalAction(
		target: TsukiMangaSource,
		invalidateOnHealthy: Boolean = true,
		block: suspend () -> OfficialGlobalDiagnostic,
	) {
		launchJob(Dispatchers.IO) {
			mutableOfficialGlobalState.update { it.copy(isLoading = true, latestActionDiagnostic = null) }
			val diagnostic = runCatching { block() }.getOrElse { error ->
				OfficialGlobalDiagnostic(
					domain = mutableOfficialGlobalState.value.snapshot?.activeDomain.orEmpty(),
					status = OfficialGlobalDiagnosticStatus.SOURCE_UNAVAILABLE,
					message = error.message,
				)
			}
			if (diagnostic.isHealthy && invalidateOnHealthy) invalidateSourceCache()
			val snapshot = runCatching { tsukiRuntime.getOfficialGlobalSettings(target) }.getOrNull()
			mutableOfficialGlobalState.value = OfficialGlobalSourceSettingsUiState(
				snapshot = snapshot,
				isLoading = false,
				latestActionDiagnostic = diagnostic,
			)
		}
	}

	private fun invalidateSourceCache() {
		(repository as? CachingMangaRepository)?.invalidateCache()
	}

	/** Deletes an installed novel plugin. Unlike an APK there is no system uninstaller to hand off to. */
	fun deleteNovelPlugin(pluginId: String) {
		launchJob(Dispatchers.Default) {
			lnPluginManager.uninstall(pluginId)
			onPluginDeleted.call(Unit)
		}
	}
}