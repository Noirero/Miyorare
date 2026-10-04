package org.koitharu.kotatsu.local.ui

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R

/** Shared two-level removal policy for scanned titles, including Details/Reader chapter selection. */
fun showLocalLibraryDeletionDialog(context: Context, hide: () -> Unit, deleteFromDevice: () -> Unit) {
    MaterialAlertDialogBuilder(context).setTitle(R.string.delete_manga)
        .setItems(arrayOf(context.getString(R.string.smart_local_hide), context.getString(R.string.smart_local_delete_device))) { _, choice ->
            if (choice == 0) hide() else MaterialAlertDialogBuilder(context)
                .setTitle(R.string.smart_local_delete_device).setMessage(R.string.smart_local_delete_device_message)
                .setPositiveButton(R.string.delete) { _, _ -> deleteFromDevice() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }.setNegativeButton(android.R.string.cancel, null).show()
}
