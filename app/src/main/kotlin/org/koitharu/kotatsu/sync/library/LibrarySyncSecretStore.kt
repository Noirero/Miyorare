package org.koitharu.kotatsu.sync.library

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** AES-GCM authenticated ciphertext, excluded from Android/Drive preference backups. */
@Singleton
class LibrarySyncSecretStore @Inject constructor(@ApplicationContext context: Context) {
	private val directory =
		File(context.noBackupFilesDir, "library-sync-secrets").apply { mkdirs() }

	@Synchronized
	fun put(service: LibrarySyncServiceId, key: String, value: String?) {
		val file = file(service, key)
		if (value == null) {
			file.delete()
			return
		}
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, secretKey())
		cipher.updateAAD("${service.name}:$key".toByteArray(Charsets.UTF_8))
		val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
		val output = file.startWrite()
		try {
			output.write(bytes)
			file.finishWrite(output)
		} catch (e: Exception) {
			file.failWrite(output)
			throw e
		}
	}

	@Synchronized
	fun get(service: LibrarySyncServiceId, key: String): String? {
		val file = file(service, key)
		if (!file.baseFile.exists()) return null
		return try {
			val payload = file.readFully()
			require(payload.size >= IV_BYTES + 16)
			val cipher = Cipher.getInstance(TRANSFORMATION)
			cipher.init(
				Cipher.DECRYPT_MODE,
				secretKey(),
				GCMParameterSpec(128, payload.copyOfRange(0, IV_BYTES)),
			)
			cipher.updateAAD("${service.name}:$key".toByteArray(Charsets.UTF_8))
			String(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)), Charsets.UTF_8)
		} catch (_: Exception) {
			file.delete()
			null
		} // lost/invalid key requires reauthentication
	}

	@Synchronized
	fun clear(service: LibrarySyncServiceId) {
		directory
			.listFiles()
			?.filter { it.name.startsWith("${service.name}-") }
			?.forEach { it.delete() }
	}

	private fun file(service: LibrarySyncServiceId, key: String): AtomicFile {
		require(key.matches(Regex("[a-z_]+")))
		return AtomicFile(File(directory, "${service.name}-$key"))
	}

	private fun secretKey(): SecretKey {
		val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
		(store.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
			return it
		}
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
		generator.init(
			KeyGenParameterSpec.Builder(
					KEY_ALIAS,
					KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
				)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.build()
		)
		return generator.generateKey()
	}

	private companion object {
		const val KEY_ALIAS = "miyorare_library_sync_v2"
		const val TRANSFORMATION = "AES/GCM/NoPadding"
		const val IV_BYTES = 12
	}
}
