package org.koitharu.kotatsu.extensions.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.extensions.install.shizuku.IShizukuInstallerService
import org.koitharu.kotatsu.extensions.install.shizuku.ShizukuInstallerService
import rikka.shizuku.Shizuku
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class ShizukuExtensionInstaller @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val installMutex = Mutex()

	val isInstalled: Boolean
		get() = runCatching {
			context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
			true
		}.getOrDefault(false)

	val isReady: Boolean
		get() = runCatching {
			Shizuku.pingBinder() &&
				Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
		}.getOrDefault(false)

	suspend fun awaitReady(): Boolean {
		if (isReady) return true
		return runCatching {
			withTimeout(BINDER_READY_TIMEOUT_MS) {
				suspendCancellableCoroutine { continuation ->
					val listener = object : Shizuku.OnBinderReceivedListener {
						override fun onBinderReceived() {
							Shizuku.removeBinderReceivedListener(this)
							if (continuation.isActive) {
								continuation.resume(isReady)
							}
						}
					}
					continuation.invokeOnCancellation {
						Shizuku.removeBinderReceivedListener(listener)
					}
					Shizuku.addBinderReceivedListenerSticky(listener)
				}
			}
		}.getOrDefault(false)
	}

	suspend fun install(apk: File, expectedPackage: String): InstallResult = installMutex.withLock {
		withContext(Dispatchers.IO) {
			if (!awaitReady()) {
				return@withContext InstallResult.Unavailable
			}
			if (!apk.isFile || apk.length() <= 0L) {
				return@withContext InstallResult.InvalidPackage
			}
			val actualPackage = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
			if (actualPackage == null || actualPackage != expectedPackage) {
				return@withContext InstallResult.InvalidPackage
			}

			val args = createUserServiceArgs()
			var connection: ServiceConnection? = null
			var stage = InstallStage.CONNECT_SHIZUKU
			try {
				val bound = withTimeout(BIND_TIMEOUT_MS) {
					suspendCancellableCoroutine { continuation ->
						val callback = object : ServiceConnection {
							override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
								if (continuation.isActive) {
									continuation.resume(IShizukuInstallerService.Stub.asInterface(binder))
								}
							}

							override fun onServiceDisconnected(name: ComponentName?) = Unit
						}
						connection = callback
						continuation.invokeOnCancellation {
							runCatching { Shizuku.unbindUserService(args, callback, true) }
						}
						Shizuku.bindUserService(args, callback)
					}
				}
				stage = InstallStage.INSTALL_PACKAGE
				awaitInstallResult {
					ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
						AssetFileDescriptor(descriptor, 0, apk.length()).use { asset ->
							bound.install(
								asset,
								Process.myUserHandle().hashCode(),
								expectedPackage,
								context.packageName,
								it,
							)
						}
					}
				}
			} catch (e: Exception) {
				InstallResult.Failure(
					status = if (
						e is TimeoutCancellationException && stage == InstallStage.INSTALL_PACKAGE
					) PackageInstaller.STATUS_FAILURE_TIMEOUT else null,
					message = exceptionFailureMessage(stage, e),
				)
			} finally {
				connection?.let { callback ->
					runCatching {
						if (Shizuku.pingBinder()) {
							Shizuku.unbindUserService(args, callback, true)
						}
					}
				}
			}
		}
	}

	private suspend fun awaitInstallResult(start: (android.content.IntentSender) -> Unit): InstallResult =
		withTimeout(INSTALL_TIMEOUT_MS) {
			suspendCancellableCoroutine { continuation ->
				val receiver = object : BroadcastReceiver() {
					override fun onReceive(context: Context?, intent: Intent) {
						if (!continuation.isActive) return
						runCatching { this@ShizukuExtensionInstaller.context.unregisterReceiver(this) }
						val status = intent.getIntExtra(
							PackageInstaller.EXTRA_STATUS,
							PackageInstaller.STATUS_FAILURE,
						)
						val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
						continuation.resume(
							if (status == PackageInstaller.STATUS_SUCCESS) {
								InstallResult.Success
							} else {
								InstallResult.Failure(status, packageInstallerFailureMessage(status, message))
							},
						)
					}
				}
				ContextCompat.registerReceiver(
					context,
					receiver,
					IntentFilter(ACTION_SHIZUKU_INSTALL_RESULT),
					ContextCompat.RECEIVER_NOT_EXPORTED,
				)
				continuation.invokeOnCancellation {
					runCatching { context.unregisterReceiver(receiver) }
				}
				try {
					val statusIntent = PendingIntent.getBroadcast(
						context,
						INSTALL_REQUEST_CODE,
						Intent(ACTION_SHIZUKU_INSTALL_RESULT).setPackage(context.packageName),
						PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
					)
					start(statusIntent.intentSender)
				} catch (e: Exception) {
					runCatching { context.unregisterReceiver(receiver) }
					if (continuation.isActive) {
						continuation.resume(
							InstallResult.Failure(
								status = null,
								message = context.getString(
									R.string.extension_install_error_generic,
									errorDetail(e.message),
								),
							),
						)
					}
				}
			}
		}

	private fun exceptionFailureMessage(stage: InstallStage, error: Exception): String = when {
		error is TimeoutCancellationException && stage == InstallStage.CONNECT_SHIZUKU ->
			context.getString(R.string.extension_install_error_shizuku_connection_timeout)

		error is TimeoutCancellationException ->
			context.getString(R.string.extension_install_error_package_timeout)

		stage == InstallStage.CONNECT_SHIZUKU ->
			context.getString(R.string.extension_install_error_shizuku_connection)

		else -> context.getString(
			R.string.extension_install_error_generic,
			errorDetail(error.message),
		)
	}

	private fun packageInstallerFailureMessage(status: Int, rawMessage: String?): String = when (status) {
		PackageInstaller.STATUS_FAILURE_BLOCKED -> context.getString(R.string.extension_install_error_blocked)
		PackageInstaller.STATUS_FAILURE_ABORTED -> context.getString(R.string.extension_install_error_aborted)
		PackageInstaller.STATUS_FAILURE_INVALID -> context.getString(R.string.extension_install_error_invalid)
		PackageInstaller.STATUS_FAILURE_CONFLICT -> context.getString(R.string.extension_install_error_conflict)
		PackageInstaller.STATUS_FAILURE_STORAGE -> context.getString(R.string.extension_install_error_storage)
		PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> context.getString(R.string.extension_install_error_incompatible)
		PackageInstaller.STATUS_FAILURE_TIMEOUT -> context.getString(R.string.extension_install_error_package_timeout)
		else -> context.getString(
			R.string.extension_install_error_generic,
			errorDetail(rawMessage),
		)
	}

	private fun errorDetail(message: String?): String = message
		?.trim()
		?.takeIf(String::isNotEmpty)
		?: context.getString(R.string.extension_install_error_unknown_detail)

	private fun createUserServiceArgs() = Shizuku.UserServiceArgs(
		ComponentName(context, ShizukuInstallerService::class.java),
	)
		.tag(USER_SERVICE_TAG)
		.processNameSuffix(USER_SERVICE_TAG)
		.debuggable(BuildConfig.DEBUG)
		.daemon(false)

	sealed interface InstallResult {
		data object Success : InstallResult
		data object Unavailable : InstallResult
		data object InvalidPackage : InstallResult
		data class Failure(val status: Int?, val message: String?) : InstallResult
	}

	private enum class InstallStage {
		CONNECT_SHIZUKU,
		INSTALL_PACKAGE,
	}

	private companion object {
		const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
		const val USER_SERVICE_TAG = "extension_installer"
		const val INSTALL_REQUEST_CODE = 14046
		const val ACTION_SHIZUKU_INSTALL_RESULT = "${BuildConfig.APPLICATION_ID}.SHIZUKU_INSTALL_RESULT"
		const val BINDER_READY_TIMEOUT_MS = 5_000L
		const val BIND_TIMEOUT_MS = 15_000L
		const val INSTALL_TIMEOUT_MS = 180_000L
	}
}
