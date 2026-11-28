package org.koitharu.kotatsu.settings.about.whatsnew

import android.content.Context
import android.util.AtomicFile
import android.util.Log
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

private const val TRACE_TAG = "WhatsNewRuntime"

internal fun traceWhatsNew(message: String) {
    if (BuildConfig.DEBUG) Log.d(TRACE_TAG, message)
}

/** Single process owner for installation-local state, outside backup and AppSettings export. */
@Singleton
class WhatsNewStateStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "whats-new-state.json"))

    @Synchronized
    internal fun read(): ReleaseIdentity? = runCatching {
        val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
        ReleaseIdentity(json.getString("version"), json.getLong("code"))
    }.getOrNull()

    @Synchronized
    internal fun write(identity: ReleaseIdentity): Boolean {
        val previous = read()
        if ((previous?.code ?: -1) > identity.code) return true
        traceWhatsNew("store write begin previous=${previous?.code} requested=${identity.code}")
        val stream = try {
            file.startWrite()
        } catch (error: Exception) {
            Log.w(TRACE_TAG, "Acknowledgement startWrite failed: ${error.javaClass.simpleName}")
            return false
        }
        try {
            stream.write(JSONObject().put("version", identity.version).put("code", identity.code)
                .toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            Log.w(TRACE_TAG, "Acknowledgement write failed: ${error.javaClass.simpleName}")
            return false
        }
        // AtomicFile.finishWrite may log a rename failure without throwing.
        // Verify the committed identity under the same lock before reporting success.
        val persisted = read()
        val success = persisted == identity
        traceWhatsNew("store write end persisted=${persisted?.code} verified=$success")
        if (!success) Log.w(TRACE_TAG, "Acknowledgement readback did not match requested identity")
        return success
    }
}

/** Activity-scoped presentation; process-wide claims prevent concurrent duplicate dialogs. */
@Singleton
class WhatsNewCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: WhatsNewStateStore,
) {
    private val identity = ReleaseIdentity(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
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
    private var acknowledgementInFlight = false
    private var noPendingReleaseInProcess = false
    private var waitingHost: WeakReference<MainActivity>? = null

    internal suspend fun load(): ReleaseHighlights? = withContext(Dispatchers.IO) { catalogLoader.value }

    // The coordinator loads and validates the asset before presenting a dialog.
    internal fun preparedCatalog(): ReleaseHighlights? =
        if (catalogLoader.isInitialized()) catalogLoader.value else null

    private fun presentationBlocker(host: MainActivity): String? = when {
        acknowledgedInProcess -> "acknowledged"
        acknowledgementInFlight -> "acknowledgement_in_flight"
        noPendingReleaseInProcess -> "no_pending_release"
        !host.canPresentWhatsNew() -> "host_${host.whatsNewPresentationBlocker()}"
        owner?.get()?.let { !it.isDestroyed } == true -> "owned"
        host.supportFragmentManager.fragments.any { it is DialogFragment && !it.isRemoving } -> "other_dialog"
        else -> null
    }

    internal fun request(host: MainActivity) {
        val hostId = System.identityHashCode(host)
        presentationBlocker(host)?.let {
            traceWhatsNew("request blocked host=$hostId reason=$it")
            return
        }
        if (evaluating?.get()?.let { !it.isDestroyed && !it.isFinishing } == true) {
            waitingHost = WeakReference(host)
            traceWhatsNew("request waiting host=$hostId")
            return
        }
        traceWhatsNew("request evaluating host=$hostId")
        evaluating = WeakReference(host)
        host.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if ((context as? KotatsuApp)?.hasPendingCrashRecovery() == true) {
                        traceWhatsNew("evaluation blocked host=$hostId reason=crash_recovery")
                        return@withContext null
                    }
                    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }
                        .getOrNull() ?: run {
                        traceWhatsNew("evaluation blocked host=$hostId reason=package_info")
                        return@withContext null
                    }
                    val upgraded = info.lastUpdateTime > info.firstInstallTime
                    val previous = store.read()
                    if (WhatsNewEligibility.shouldRecordBaseline(previous, upgraded)) store.write(identity)
                    val eligible = WhatsNewEligibility.shouldShow(identity, previous, upgraded)
                    val catalog = catalogLoader.value
                    traceWhatsNew("evaluation host=$hostId previous=${previous?.code} current=${identity.code} upgraded=$upgraded eligible=$eligible catalog=${catalog != null}")
                    Prepared(catalog?.takeIf { eligible })
                } ?: return@launch
                val prepared = result.highlights
                if (prepared == null) {
                    noPendingReleaseInProcess = true
                    traceWhatsNew("evaluation no pending release host=$hostId")
                    return@launch
                }
                presentationBlocker(host)?.let {
                    traceWhatsNew("presentation blocked host=$hostId reason=$it")
                    return@launch
                }
                owner = WeakReference(host)
                traceWhatsNew("presentation claim host=$hostId")
                try {
                    WhatsNewDialogFragment.newInstance(prepared.contentId).showNow(
                        host.supportFragmentManager, WhatsNewDialogFragment.TAG,
                    )
                    traceWhatsNew("presentation shown host=$hostId")
                } catch (error: IllegalStateException) {
                    traceWhatsNew("presentation failed host=$hostId error=${error.javaClass.simpleName}")
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
        if (owner?.get() === host) {
            owner = null
            traceWhatsNew("presentation released host=${System.identityHashCode(host)}")
        }
    }

    internal fun acknowledge() {
        if (acknowledgedInProcess || acknowledgementInFlight) return
        acknowledgementInFlight = true
        traceWhatsNew("acknowledgement scheduled")
        // Keep process-owned work independent of Activity recreation, but only mark the
        // release acknowledged after verified persistence. All flags stay on the main thread.
        processLifecycleScope.launch {
            try {
                val persisted = withContext(Dispatchers.IO) {
                    traceWhatsNew("acknowledgement IO started")
                    store.write(identity)
                }
                acknowledgedInProcess = persisted
                traceWhatsNew("acknowledgement completed verified=$persisted")
            } finally {
                acknowledgementInFlight = false
            }
        }
    }
}
