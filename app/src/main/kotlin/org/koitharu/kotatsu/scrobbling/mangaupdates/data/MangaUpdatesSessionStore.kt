package org.koitharu.kotatsu.scrobbling.mangaupdates.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

class MangaUpdatesSession(val token: String, val user: ScrobblerUser, val generation: Long) {
	override fun toString() = "MangaUpdatesSession(redacted)"
}

interface MangaUpdatesSessionStore {
	val generation: StateFlow<Long>
	fun snapshot(): MangaUpdatesSession?
	fun save(token: String, user: ScrobblerUser, expectedGeneration: Long): Boolean
	fun clear(expectedGeneration: Long? = null): Boolean
}

/** Provider-scoped encrypted credentials, excluded from Android and application backups. */
@Singleton
internal class EncryptedMangaUpdatesSessionStore @Inject constructor(@ApplicationContext context: Context) : MangaUpdatesSessionStore {
	private val file = AtomicFile(File(context.noBackupFilesDir, "mangaupdates-session"))
	private val epoch = MutableStateFlow(0L)
	override val generation = epoch.asStateFlow()
	private var session: MangaUpdatesSession? = null
	private var loaded = false

	@Synchronized override fun snapshot(): MangaUpdatesSession? {
		if (!loaded) {
			loaded = true
			if (file.baseFile.exists()) try {
				val envelope = Json.parseToJsonElement(file.readFully().decodeToString()).jsonObject
				val cipher = Cipher.getInstance("AES/GCM/NoPadding")
				cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(envelope.getValue("iv").jsonPrimitive.content, Base64.NO_WRAP)))
				val root = Json.parseToJsonElement(cipher.doFinal(Base64.decode(envelope.getValue("data").jsonPrimitive.content, Base64.NO_WRAP)).decodeToString()).jsonObject
				val user = mangaUpdatesUser(root.getValue("user").jsonObject)
				val token = validMangaUpdatesToken(root.getValue("token").jsonPrimitive.content)
				session = MangaUpdatesSession(token, user, epoch.value)
			} catch (_: Exception) {
				file.delete()
				epoch.value++
			}
		}
		return session
	}

	@Synchronized override fun save(token: String, user: ScrobblerUser, expectedGeneration: Long): Boolean {
		snapshot()
		if (epoch.value != expectedGeneration) return false
		val body = buildJsonObject {
			put("token", validMangaUpdatesToken(token))
			put("user", buildJsonObject { put("user_id", user.id); put("username", user.nickname); user.avatar?.let { put("avatar_url", it) } })
		}.toString().encodeToByteArray()
		val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
		val encrypted = cipher.doFinal(body)
		body.fill(0)
		val envelope = buildJsonObject {
			put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
			put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
		}.toString().encodeToByteArray()
		val output = file.startWrite()
		try { output.write(envelope); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
		epoch.value++
		session = MangaUpdatesSession(token, user, epoch.value)
		return true
	}

	@Synchronized override fun clear(expectedGeneration: Long?): Boolean {
		snapshot()
		if (expectedGeneration != null && epoch.value != expectedGeneration) return false
		file.delete()
		session = null
		epoch.value++
		return true
	}

	private fun key(): SecretKey {
		val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
		(store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
		return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
			init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
		}.generateKey()
	}
	private companion object { const val KEY_ALIAS = "miyorare_mangaupdates_session" }
}

internal fun validMangaUpdatesToken(value: String): String {
	require(value.isNotBlank() && value.length <= 16384 && value.all { it.code in 33..126 }) { "Invalid MangaUpdates session" }
	return value
}
