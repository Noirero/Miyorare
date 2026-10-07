package org.koitharu.kotatsu.core.os

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityOptionsCompat

// https://stackoverflow.com/questions/77555641/saf-no-activity-found-to-handle-intent-android-intent-action-open-document-tr
class OpenDocumentTreeHelper(
	activityResultCaller: ActivityResultCaller,
	flags: Int,
	callback: ActivityResultCallback<Uri?>
) : ActivityResultLauncher<Uri?>() {

	constructor(activityResultCaller: ActivityResultCaller, callback: ActivityResultCallback<Uri?>) : this(
		activityResultCaller,
		DEFAULT_TREE_GRANT_FLAGS,
		callback,
	)

	private val pickFileTreeLauncherPrimaryStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
		activityResultCaller.registerForActivityResult(OpenDocumentTreeContractPrimaryStorage(flags), callback)
	} else {
		null
	}
	private val pickFileTreeLauncherDefault = activityResultCaller.registerForActivityResult(
		contract = OpenDocumentTreeContractDefault(flags),
		callback = callback,
	)

	override fun launch(input: Uri?, options: ActivityOptionsCompat?) {
		try {
			pickFileTreeLauncherDefault.launch(input, options)
		} catch (e: Exception) {
			if (pickFileTreeLauncherPrimaryStorage != null) {
				try {
					pickFileTreeLauncherPrimaryStorage.launch(input, options)
				} catch (e2: Exception) {
					e.addSuppressed(e2)
					throw e
				}
			} else {
				throw e
			}
		}
	}

	override fun unregister() {
		pickFileTreeLauncherPrimaryStorage?.unregister()
		pickFileTreeLauncherDefault.unregister()
	}

	override val contract: ActivityResultContract<Uri?, *>
		get() = pickFileTreeLauncherPrimaryStorage?.contract ?: pickFileTreeLauncherDefault.contract

	private open class OpenDocumentTreeContractDefault(
		private val flags: Int,
	) : ActivityResultContracts.OpenDocumentTree() {
		private var resolver: ContentResolver? = null

		override fun createIntent(context: Context, input: Uri?): Intent {
			resolver = context.applicationContext.contentResolver
			val intent = super.createIntent(context, input)
			intent.addFlags(flags)
			return intent
		}

		override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
			val uri = super.parseResult(resultCode, intent) ?: return null
			// Detaching a Smart Local root intentionally keeps device files untouched. Older
			// builds also left its persisted SAF grant behind. When the same (or another)
			// tree is selected later, refresh an existing persisted grant while the picker
			// result still carries a fresh temporary grant. SmartLocalLibrary.addRoot() then
			// persists that fresh grant before registering/scanning the root. This prevents a
			// stale detached grant from poisoning the detach -> re-attach lifecycle.
			resolver?.persistedUriPermissions?.firstOrNull { it.uri == uri }?.let { permission ->
				var releaseFlags = 0
				if (permission.isReadPermission) releaseFlags = releaseFlags or Intent.FLAG_GRANT_READ_URI_PERMISSION
				if (permission.isWritePermission) releaseFlags = releaseFlags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
				if (releaseFlags != 0) runCatching {
					resolver?.releasePersistableUriPermission(uri, releaseFlags)
				}
			}
			return uri
		}
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	private class OpenDocumentTreeContractPrimaryStorage(
		private val flags: Int,
	) : OpenDocumentTreeContractDefault(flags) {

		override fun createIntent(context: Context, input: Uri?): Intent {
			// Always let the base contract capture the resolver used by parseResult(), even
			// when primaryStorageVolume supplies the actual picker intent.
			val fallback = super.createIntent(context, input)
			val intent = (context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager)
				?.primaryStorageVolume
				?.createOpenDocumentTreeIntent()
				?: return fallback
			intent.addFlags(flags)
			if (input != null) {
				intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
			}
			return intent
		}
	}

	private companion object {
		const val DEFAULT_TREE_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
			Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
			Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
			Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
	}
}
