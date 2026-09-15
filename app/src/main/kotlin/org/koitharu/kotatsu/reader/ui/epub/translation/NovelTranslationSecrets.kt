package org.koitharu.kotatsu.reader.ui.epub.translation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small BYOK secret store for Novel translation.
 *
 * API keys are encrypted with an Android Keystore AES key and the encrypted values are persisted in
 * [Context.getNoBackupFilesDir], so they are not included in normal Android app backup/restore.
 * The class never exposes the encrypted payload or key material to logs.
 */
class NovelTranslationSecrets(context: Context) {

	private val file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)
	private val random = SecureRandom()
	private val lock = Any()

	fun get(provider: NovelAiProvider): String? = synchronized(lock) {
		val encoded = loadProperties().getProperty(provider.id) ?: return@synchronized null
		decrypt(encoded).takeIf { it.isNotBlank() }
	}

	fun put(provider: NovelAiProvider, apiKey: String) = synchronized(lock) {
		val values = loadProperties()
		val trimmed = apiKey.trim()
		if (trimmed.isEmpty()) {
			values.remove(provider.id)
		} else {
			values.setProperty(provider.id, encrypt(trimmed))
		}
		storeProperties(values)
	}

	fun clear(provider: NovelAiProvider) = synchronized(lock) {
		val values = loadProperties()
		if (values.remove(provider.id) != null) storeProperties(values)
	}

	fun clearAll() = synchronized(lock) {
		if (file.exists()) file.delete()
	}

	fun has(provider: NovelAiProvider): Boolean = get(provider) != null

	private fun loadProperties(): Properties = Properties().apply {
		if (!file.exists()) return@apply
		runCatching { file.inputStream().buffered().use(::load) }
	}

	private fun storeProperties(values: Properties) {
		file.parentFile?.mkdirs()
		if (values.isEmpty()) {
			file.delete()
			return
		}
		val temporary = File(file.parentFile, "$FILE_NAME.tmp")
		temporary.outputStream().buffered().use { values.store(it, null) }
		if (!temporary.renameTo(file)) {
			temporary.copyTo(file, overwrite = true)
			temporary.delete()
		}
	}

	private fun encrypt(plainText: String): String {
		val iv = ByteArray(IV_BYTES).also(random::nextBytes)
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
		val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
		val blob = ByteArray(iv.size + encrypted.size)
		System.arraycopy(iv, 0, blob, 0, iv.size)
		System.arraycopy(encrypted, 0, blob, iv.size, encrypted.size)
		return Base64.encodeToString(blob, Base64.NO_WRAP)
	}

	private fun decrypt(encoded: String): String? = runCatching {
		val blob = Base64.decode(encoded, Base64.NO_WRAP)
		if (blob.size <= IV_BYTES) return@runCatching null
		val iv = blob.copyOfRange(0, IV_BYTES)
		val encrypted = blob.copyOfRange(IV_BYTES, blob.size)
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
		cipher.doFinal(encrypted).toString(Charsets.UTF_8)
	}.getOrNull()

	private fun secretKey(): SecretKey {
		val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
		(keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
		return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
			init(
				KeyGenParameterSpec.Builder(
					KEY_ALIAS,
					KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
				)
					.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
					.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
					.setRandomizedEncryptionRequired(true)
					.build(),
			)
			generateKey()
		}
	}

	companion object {
		private const val FILE_NAME = "novel_translation_secrets.properties"
		private const val KEY_ALIAS = "miyorare_novel_translation_byok_v1"
		private const val ANDROID_KEYSTORE = "AndroidKeyStore"
		private const val TRANSFORMATION = "AES/GCM/NoPadding"
		private const val IV_BYTES = 12
		private const val TAG_BITS = 128
	}
}
