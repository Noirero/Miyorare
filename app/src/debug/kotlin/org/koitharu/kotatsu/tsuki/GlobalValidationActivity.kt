package org.koitharu.kotatsu.tsuki

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.main.ui.MainActivity
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/** Owner validation only. No external input, network installation, or stable-app data access. */
@AndroidEntryPoint
class GlobalValidationActivity : FragmentActivity() {
	@Inject lateinit var plugins: TsukiPluginManager

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val status = TextView(this).apply { text = "Verifying bundled Miyorare-Global…" }
		setContentView(status)
		lifecycleScope.launch {
			try {
				val proof = withContext(Dispatchers.IO) { installBundledGlobal() }
				Log.i(TAG, proof)
				startActivity(Intent(this@GlobalValidationActivity, MainActivity::class.java))
				finish()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.e(TAG, "Global validation setup failed", e)
				status.text = "Global validation setup failed: ${e.message}"
			}
		}
	}

	private fun installBundledGlobal(): String = synchronized(installLock) {
		val directory = "global-validation"
		val revision = assets.open("$directory/revision").bufferedReader().use { it.readText().trim() }
		val expected = assets.open("$directory/sha256").bufferedReader().use { it.readText().trim() }
		require(Regex("[0-9a-f]{40}").matches(revision) && Regex("[0-9a-f]{64}").matches(expected))
		val pack = MiyorareOfficialSourcePacks.packs.single { it.displayName == "Miyorare-Global" }
		val shard = pack.shards.single()
		val jar = File.createTempFile("global-validation-", ".jar", cacheDir)
		try {
			val sha256 = MessageDigest.getInstance("SHA-256")
			assets.open("$directory/${shard.assetName}").use { input ->
				jar.outputStream().use { output ->
					val buffer = ByteArray(32 * 1024)
					while (true) {
						val read = input.read(buffer)
						if (read < 0) break
						sha256.update(buffer, 0, read)
						output.write(buffer, 0, read)
					}
				}
			}
			require(sha256.digest().joinToString("") { "%02x".format(it) } == expected) { "Global JAR hash mismatch" }
			val provider = TsukiPluginProvider.MIYORARE
			plugins.installLocalJar(jar, TsukiPluginManager.InstallRequest(
				pluginId = shard.pluginId,
				displayName = shard.displayName,
				provider = provider,
				origin = "debug://bundled-global/$revision",
			))
			check(plugins.getAvailableSources().any { EhentaiSourceFamily.isOfficialSource(it.name) }) {
				"Official ExHentai identity not available after installing bundled Global"
			}
			"GLOBAL_VALIDATION_OK revision=$revision sha256=$expected"
		} finally {
			jar.delete()
		}
	}

	private companion object {
		const val TAG = "GlobalValidation"
		val installLock = Any()
	}
}
