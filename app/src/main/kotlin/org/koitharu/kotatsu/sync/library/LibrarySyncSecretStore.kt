package org.koitharu.kotatsu.sync.library

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class LibrarySyncSecretStore(context: Context) {
	private val prefs = context.getSharedPreferences("library_sync_secrets", Context.MODE_PRIVATE)

	fun put(service: LibrarySyncServiceId, key: String, value: String?) {
		val name = "${service.name}:$key"
		if (value == null) {
			prefs.edit().remove(name).apply()
			return
		}
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, secretKey())
		val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
		prefs.edit().putString(name, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
	}

	fun get(service: LibrarySyncServiceId, key: String): String? {
		val encoded = prefs.getString("${service.name}:$key", null) ?: return null
		val payload = Base64.decode(encoded, Base64.NO_WRAP)
		if (payload.size <= IV_BYTES) return null
		return runCatching {
			val cipher = Cipher.getInstance(TRANSFORMATION)
			cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, payload.copyOfRange(0, IV_BYTES)))
			String(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)), Charsets.UTF_8)
		}.getOrNull()
	}

	fun clear(service: LibrarySyncServiceId) {
		prefs.edit().also { edit ->
			prefs.all.keys.filter { it.startsWith("${service.name}:") }.forEach(edit::remove)
		}.apply()
	}

	private fun secretKey(): SecretKey {
		val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
		(store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
		generator.init(
			KeyGenParameterSpec.Builder(
				KEY_ALIAS,
				KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
			).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.build(),
		)
		return generator.generateKey()
	}

	private companion object {
		const val KEY_ALIAS = "miyorare_library_sync"
		const val TRANSFORMATION = "AES/GCM/NoPadding"
		const val IV_BYTES = 12
	}
}
