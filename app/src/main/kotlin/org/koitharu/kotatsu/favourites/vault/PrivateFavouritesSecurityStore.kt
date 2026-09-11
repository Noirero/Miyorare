package org.koitharu.kotatsu.favourites.vault

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Security-only settings stored under noBackupFilesDir. The PIN itself is never persisted; only a
 * salted PBKDF2 verifier is stored. Private backup inclusion defaults to false.
 *
 * The properties file is loaded once into a process-local snapshot. Every mutation remains guarded
 * by [lock] and is persisted immediately, avoiding repeated disk reads on resume/unlock hot paths.
 */
@Singleton
class PrivateFavouritesSecurityStore @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val file = File(context.noBackupFilesDir, FILE_NAME)
	private val lock = Any()
	private val properties = readFromDisk()
	private val allowPrivateScreenshotsState = MutableStateFlow(
		properties.getProperty(KEY_ALLOW_SCREENSHOTS)?.toBooleanStrictOrNull() ?: false,
	)
	val allowPrivateScreenshotsFlow: StateFlow<Boolean> = allowPrivateScreenshotsState.asStateFlow()

	val isConfigured: Boolean
		get() = synchronized(lock) {
			val mode = properties.getProperty(KEY_PROTECTION)?.let {
				runCatching { PrivateFavouritesProtection.valueOf(it) }.getOrNull()
			} ?: return@synchronized false
			when (mode) {
				PrivateFavouritesProtection.PIN,
				PrivateFavouritesProtection.BIOMETRIC_PIN,
				-> hasPin(properties)
				PrivateFavouritesProtection.NONE,
				PrivateFavouritesProtection.BIOMETRIC,
				-> true
			}
		}

	var protection: PrivateFavouritesProtection
		get() = synchronized(lock) {
			properties.getProperty(KEY_PROTECTION)?.let {
				runCatching { PrivateFavouritesProtection.valueOf(it) }.getOrNull()
			} ?: PrivateFavouritesProtection.BIOMETRIC
		}
		set(value) = synchronized(lock) {
			properties.setProperty(KEY_PROTECTION, value.name)
			persistLocked()
		}

	var includePrivateInBackup: Boolean
		get() = synchronized(lock) {
			properties.getProperty(KEY_INCLUDE_BACKUP)?.toBooleanStrictOrNull() ?: false
		}
		set(value) = synchronized(lock) {
			properties.setProperty(KEY_INCLUDE_BACKUP, value.toString())
			persistLocked()
		}

	/** Independent from the general screenshot policy and false on every existing install. */
	var allowPrivateScreenshots: Boolean
		get() = allowPrivateScreenshotsState.value
		set(value) = synchronized(lock) {
			properties.setProperty(KEY_ALLOW_SCREENSHOTS, value.toString())
			persistLocked()
			allowPrivateScreenshotsState.value = value
		}

	var privateScreenshotWarningAcknowledged: Boolean
		get() = synchronized(lock) {
			properties.getProperty(KEY_SCREENSHOT_WARNING_ACK)?.toBooleanStrictOrNull() ?: false
		}
		set(value) = synchronized(lock) {
			properties.setProperty(KEY_SCREENSHOT_WARNING_ACK, value.toString())
			persistLocked()
		}

	val hasPin: Boolean
		get() = synchronized(lock) { hasPin(properties) }

	fun setPin(pin: String) {
		require(pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH)
		require(pin.all(Char::isDigit))
		val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
		val hash = derive(pin, salt)
		synchronized(lock) {
			properties.setProperty(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
			properties.setProperty(KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
			persistLocked()
		}
	}

	fun clearPin() = synchronized(lock) {
		properties.remove(KEY_PIN_SALT)
		properties.remove(KEY_PIN_HASH)
		persistLocked()
	}

	/** Clears every Private-specific security setting while preserving user content. */
	fun disableAllPrivateProtection(includePrivateInBackup: Boolean) = synchronized(lock) {
		properties.setProperty(KEY_PROTECTION, PrivateFavouritesProtection.NONE.name)
		properties.remove(KEY_PIN_SALT)
		properties.remove(KEY_PIN_HASH)
		properties.setProperty(KEY_INCLUDE_BACKUP, includePrivateInBackup.toString())
		properties.setProperty(KEY_ALLOW_SCREENSHOTS, true.toString())
		properties.remove(KEY_SCREENSHOT_WARNING_ACK)
		persistLocked()
		allowPrivateScreenshotsState.value = true
	}

	fun verifyPin(pin: String): Boolean = synchronized(lock) {
		val salt = properties.getProperty(KEY_PIN_SALT)?.let(::decode) ?: return@synchronized false
		val expected = properties.getProperty(KEY_PIN_HASH)?.let(::decode) ?: return@synchronized false
		val actual = derive(pin, salt)
		MessageDigest.isEqual(expected, actual)
	}

	private fun hasPin(properties: Properties): Boolean =
		!properties.getProperty(KEY_PIN_SALT).isNullOrEmpty() &&
			!properties.getProperty(KEY_PIN_HASH).isNullOrEmpty()

	private fun derive(pin: String, salt: ByteArray): ByteArray {
		val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_BITS)
		return try {
			SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
		} finally {
			spec.clearPassword()
		}
	}

	private fun decode(value: String): ByteArray? = runCatching {
		Base64.decode(value, Base64.NO_WRAP)
	}.getOrNull()

	private fun readFromDisk(): Properties = Properties().apply {
		if (file.isFile) runCatching { file.inputStream().use(::load) }
	}

	/** Must be called with [lock] held. */
	private fun persistLocked() {
		file.parentFile?.mkdirs()
		val temp = File(file.parentFile, file.name + ".tmp")
		temp.outputStream().use { properties.store(it, null) }
		if (!temp.renameTo(file)) {
			file.outputStream().use { properties.store(it, null) }
			temp.delete()
		}
	}

	private companion object {
		const val FILE_NAME = "private_favourites_security.properties"
		const val KEY_PROTECTION = "protection"
		const val KEY_PIN_SALT = "pin_salt"
		const val KEY_PIN_HASH = "pin_hash"
		const val KEY_INCLUDE_BACKUP = "include_private_backup"
		const val KEY_ALLOW_SCREENSHOTS = "allow_private_screenshots"
		const val KEY_SCREENSHOT_WARNING_ACK = "private_screenshot_warning_ack"
		const val MIN_PIN_LENGTH = 4
		const val MAX_PIN_LENGTH = 24
		const val SALT_BYTES = 16
		const val PBKDF2_ITERATIONS = 120_000
		const val HASH_BITS = 256
	}
}
