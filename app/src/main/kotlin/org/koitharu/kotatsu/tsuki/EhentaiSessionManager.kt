package org.koitharu.kotatsu.tsuki

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-owned session bridge for the global E-Hentai / ExHentai source.
 *
 * Credentials never enter source-pack metadata, logs, or ordinary backup-capable preferences.
 * They are encrypted with Android Keystore and written under noBackupFilesDir. Runtime cookies are
 * injected only when needed by the EXHENTAI parser. E-Hentai-only mode removes auth cookies while
 * keeping the encrypted credentials available for a later mode switch.
 */
@Singleton
class EhentaiSessionManager @Inject constructor(
	@ApplicationContext private val context: Context,
	private val cookieJar: MutableCookieJar,
) {

	enum class Mode {
		AUTO,
		EHENTAI_ONLY,
		EXHENTAI_PREFERRED,
	}

	data class Credentials(
		val ipbMemberId: String = "",
		val ipbPassHash: String = "",
		val igneous: String = "",
	) {
		val isComplete: Boolean
			get() = ipbMemberId.isNotBlank() && ipbPassHash.isNotBlank() && igneous.isNotBlank()
	}

	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val secretFile = File(context.noBackupFilesDir, SECRET_FILE)

	fun getMode(): Mode = runCatching {
		Mode.valueOf(prefs.getString(KEY_MODE, Mode.AUTO.name).orEmpty())
	}.getOrDefault(Mode.AUTO)

	fun getCredentials(): Credentials = decryptCredentials() ?: Credentials()

	@Synchronized
	fun save(mode: Mode, credentials: Credentials) {
		prefs.edit().putString(KEY_MODE, mode.name).apply()
		if (credentials.ipbMemberId.isBlank() && credentials.ipbPassHash.isBlank() && credentials.igneous.isBlank()) {
			secretFile.delete()
		} else {
			encryptCredentials(credentials)
		}
		syncRuntimeCookies(mode, credentials)
	}

	/** Called immediately before creating the EXHENTAI parser so process restarts restore the session. */
	@Synchronized
	fun prepareForRuntime() {
		syncRuntimeCookies(getMode(), getCredentials())
	}

	@Synchronized
	fun clearCredentials() {
		secretFile.delete()
		removeAuthCookies()
	}

	private fun syncRuntimeCookies(mode: Mode, credentials: Credentials) {
		removeAuthCookies()
		if (mode == Mode.EHENTAI_ONLY || !credentials.isComplete) return

		val eHentai = EHENTAI_URL.toHttpUrl()
		val exHentai = EXHENTAI_URL.toHttpUrl()
		cookieJar.saveFromResponse(
			eHentai,
			listOf(
				cookie(IPB_MEMBER_ID, credentials.ipbMemberId, EHENTAI_DOMAIN),
				cookie(IPB_PASS_HASH, credentials.ipbPassHash, EHENTAI_DOMAIN),
			),
		)
		cookieJar.saveFromResponse(
			exHentai,
			listOf(
				cookie(IPB_MEMBER_ID, credentials.ipbMemberId, EXHENTAI_DOMAIN),
				cookie(IPB_PASS_HASH, credentials.ipbPassHash, EXHENTAI_DOMAIN),
				cookie(IGNEOUS, credentials.igneous, EXHENTAI_DOMAIN),
			),
		)
	}

	private fun removeAuthCookies() {
		val names = AUTH_COOKIE_NAMES
		cookieJar.removeCookies(EHENTAI_URL.toHttpUrl()) { it.name in names }
		cookieJar.removeCookies(EXHENTAI_URL.toHttpUrl()) { it.name in names }
	}

	private fun cookie(name: String, value: String, domain: String): Cookie = Cookie.Builder()
		.name(name)
		.value(value.trim())
		.domain(domain)
		.path("/")
		.secure()
		.build()

	private fun encryptCredentials(credentials: Credentials) {
		val payload = JSONObject()
			.put(IPB_MEMBER_ID, credentials.ipbMemberId.trim())
			.put(IPB_PASS_HASH, credentials.ipbPassHash.trim())
			.put(IGNEOUS, credentials.igneous.trim())
			.toString()
			.toByteArray(Charsets.UTF_8)
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
		val encoded = JSONObject()
			.put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
			.put("data", Base64.encodeToString(cipher.doFinal(payload), Base64.NO_WRAP))
			.toString()
		secretFile.parentFile?.mkdirs()
		val temp = File(secretFile.parentFile, "$SECRET_FILE.tmp")
		temp.writeText(encoded, Charsets.UTF_8)
		if (!temp.renameTo(secretFile)) {
			temp.copyTo(secretFile, overwrite = true)
			temp.delete()
		}
	}

	private fun decryptCredentials(): Credentials? = runCatching {
		if (!secretFile.isFile) return null
		val root = JSONObject(secretFile.readText(Charsets.UTF_8))
		val iv = Base64.decode(root.getString("iv"), Base64.NO_WRAP)
		val encrypted = Base64.decode(root.getString("data"), Base64.NO_WRAP)
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
		val data = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
		Credentials(
			ipbMemberId = data.optString(IPB_MEMBER_ID),
			ipbPassHash = data.optString(IPB_PASS_HASH),
			igneous = data.optString(IGNEOUS),
		)
	}.getOrNull()

	private fun getOrCreateKey(): SecretKey {
		val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
		(keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
		generator.init(
			KeyGenParameterSpec.Builder(
				KEY_ALIAS,
				KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
			)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.build(),
		)
		return generator.generateKey()
	}

	private companion object {
		const val PREFS_NAME = "miyorare_ehentai_session"
		const val KEY_MODE = "mode"
		const val SECRET_FILE = "miyorare-ehentai-session-v1.json"
		const val KEY_ALIAS = "miyorare.ehentai.credentials.v1"
		const val ANDROID_KEYSTORE = "AndroidKeyStore"
		const val TRANSFORMATION = "AES/GCM/NoPadding"
		const val EHENTAI_DOMAIN = "e-hentai.org"
		const val EXHENTAI_DOMAIN = "exhentai.org"
		const val EHENTAI_URL = "https://e-hentai.org/"
		const val EXHENTAI_URL = "https://exhentai.org/"
		const val IPB_MEMBER_ID = "ipb_member_id"
		const val IPB_PASS_HASH = "ipb_pass_hash"
		const val IGNEOUS = "igneous"
		val AUTH_COOKIE_NAMES = setOf(IPB_MEMBER_ID, IPB_PASS_HASH, IGNEOUS)
	}
}
