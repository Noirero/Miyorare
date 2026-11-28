package org.koitharu.kotatsu.settings.about.whatsnew

import android.content.Context
import android.util.AtomicFile
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.DialogFragment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.KotatsuApp
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.main.ui.MainActivity
import java.io.File
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/** Installation-local state, outside Android backup and AppSettings export. */
internal class WhatsNewStateStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "whats-new-state.json"))

    @Synchronized
    fun read(): ReleaseIdentity? = runCatching {
        val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
        ReleaseIdentity(json.getString("version"), json.getLong("code"))
    }.getOrNull()

    @Synchronized
    fun write(identity: ReleaseIdentity): Boolean {
        if ((read()?.code ?: -1) > identity.code) return true
        val stream = runCatching { file.startWrite() }.getOrNull() ?: return false
        return try {
            stream.write(JSONObject().put("version", identity.version).put("code", identity.code)
                .toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            file.failWrite(stream)
            false
        }
    }
}

/** Activity-scoped presentation; process-wide claims prevent concurrent duplicate dialogs. */
@Singleton
class WhatsNewCoordinator @Inject constructor(@ApplicationContext private val context: Context) {
    private val identity = ReleaseIdentity(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
    private val store by lazy { WhatsNewStateStore(context) }
    private val catalogLoader = lazy {
        runCatching {
            context.assets.open(ReleaseCatalogParser.ASSET).bufferedReader().use { reader ->
                ReleaseCatalogParser.parse(reader.readText(), identity.version, BuildConfig.BUILD_TYPE == "debug")
            }
        }.getOrNull()
    }
    private var owner: WeakReference<MainActivity>? = null
    private var evaluating: WeakReference<MainActivity>? = null
    private var acknowledgedInProcess = false
    private var noPendingReleaseInProcess = false
    private var waitingHost: WeakReference<MainActivity>? = null

    internal suspend fun load(): ReleaseHighlights? = withContext(Dispatchers.IO) { catalogLoader.value }

    // The coordinator loads and validates the asset before presenting a dialog.
    internal fun preparedCatalog(): ReleaseHighlights? =
        if (catalogLoader.isInitialized()) catalogLoader.value else null

    internal fun request(host: MainActivity) {
        if (acknowledgedInProcess || noPendingReleaseInProcess || !host.canPresentWhatsNew() ||
            owner?.get()?.let { !it.isDestroyed } == true ||
            host.supportFragmentManager.fragments.any { it is DialogFragment && !it.isRemoving }) return
        if (evaluating?.get()?.let { !it.isDestroyed && !it.isFinishing } == true) {
            waitingHost = WeakReference(host)
            return
        }
        evaluating = WeakReference(host)
        host.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if ((context as? KotatsuApp)?.hasPendingCrashRecovery() == true) return@withContext null
                    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }
                        .getOrNull() ?: return@withContext null
                    val upgraded = info.lastUpdateTime > info.firstInstallTime
                    val previous = store.read()
                    if (WhatsNewEligibility.shouldRecordBaseline(previous, upgraded)) store.write(identity)
                    Prepared(catalogLoader.value?.takeIf { WhatsNewEligibility.shouldShow(identity, previous, upgraded) })
                } ?: return@launch
                val prepared = result.highlights
                if (prepared == null) {
                    noPendingReleaseInProcess = true
                    return@launch
                }
                if (acknowledgedInProcess || !host.canPresentWhatsNew() ||
                    owner?.get()?.let { !it.isDestroyed } == true ||
                    host.supportFragmentManager.fragments.any { it is DialogFragment && !it.isRemoving }) return@launch
                owner = WeakReference(host)
                try {
                    WhatsNewDialogFragment.newInstance(prepared.contentId).showNow(
                        host.supportFragmentManager, WhatsNewDialogFragment.TAG,
                    )
                } catch (_: IllegalStateException) {
                    release(host)
                }
            } finally {
                if (evaluating?.get() === host) {
                    evaluating = null
                    val next = waitingHost?.get()
                    waitingHost = null
                    if (next != null) request(next)
                }
            }
        }
    }

    private data class Prepared(val highlights: ReleaseHighlights?)

    internal fun release(host: MainActivity) {
        if (owner?.get() === host) owner = null
    }

    internal fun acknowledge() {
        if (acknowledgedInProcess) return
        acknowledgedInProcess = true
        // Only a user action reaches this method. Persist independently of a navigation or
        // Activity recreation; no Activity is captured or launched from process scope.
        processLifecycleScope.launch(Dispatchers.IO) { store.write(identity) }
    }
}
