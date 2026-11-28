package org.koitharu.kotatsu.settings.about.whatsnew

import android.content.DialogInterface
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.main.ui.MainActivity
import javax.inject.Inject

@AndroidEntryPoint
class WhatsNewDialogFragment : ComposeAlertDialogFragment() {
    @Inject lateinit var coordinator: WhatsNewCoordinator
    private var consumed = false
    private var restored = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Re-evaluate startup/security after recreation instead of restoring the old window.
        restored = savedInstanceState != null
        if (restored) setShowsDialog(false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.setCanceledOnTouchOutside(false)
    }

    override fun onResume() {
        super.onResume()
        if (restored) {
            val host = activity as? MainActivity
            dismiss()
            host?.window?.decorView?.post { host.requestWhatsNew() }
        }
    }

    override fun onStop() {
        super.onStop()
        // An interrupted, unacknowledged showcase is ephemeral UI. Remove its window
        // so resume, rotation or protection rechecks cannot restore it above another prompt.
        if (!consumed && !restored && !isRemoving) dismissAllowingStateLoss()
    }

    @Composable
    override fun Content() {
        if (restored) return
        val highlights = coordinator.preparedCatalog()
        highlights?.takeIf { it.contentId == arguments?.getString(KEY_CONTENT) }?.let { release ->
            WhatsNewPopup(release, onDestination = { complete(it) }, onClose = { complete(null) })
        }
    }

    private fun complete(destination: FeatureDestination?) {
        val host = activity as? MainActivity ?: return
        if (consumed || host.isFinishing || host.isDestroyed || parentFragmentManager.isStateSaved ||
            !host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            !host.appProtectHelper.isUnlockedFlow.value || coordinator.preparedCatalog() == null) return
        consumed = true
        dismiss()
        if (destination == null || WhatsNewDestinationRouter.open(host, destination)) {
            coordinator.acknowledge()
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        if (!restored && !consumed && coordinator.preparedCatalog() != null &&
            (activity as? MainActivity)?.appProtectHelper?.isUnlockedFlow?.value == true) {
            consumed = true
            coordinator.acknowledge()
        }
        super.onCancel(dialog)
    }

    override fun onDestroy() {
        (activity as? MainActivity)?.let(coordinator::release)
        super.onDestroy()
    }

    companion object {
        const val TAG = "whats_new_popup"
        private const val KEY_CONTENT = "content_id"

        internal fun newInstance(contentId: String) = WhatsNewDialogFragment().apply {
            arguments = Bundle().apply { putString(KEY_CONTENT, contentId) }
        }
    }
}
