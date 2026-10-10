package org.koitharu.kotatsu.scrobbling.mangaupdates.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.databinding.ActivityMangaupdatesAuthBinding
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.MangaUpdatesRepository
import javax.inject.Inject
import javax.inject.Provider

@AndroidEntryPoint
class MangaUpdatesAuthActivity : BaseActivity<ActivityMangaupdatesAuthBinding>() {
	@Inject lateinit var repository: Provider<MangaUpdatesRepository>
	private var login: Job? = null
	override fun isPrivacySensitiveContent() = flowOf(true)
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		setContentView(ActivityMangaupdatesAuthBinding.inflate(layoutInflater))
		viewBinding.buttonCancel.setOnClickListener { login?.cancel(); finish() }
		viewBinding.buttonSignIn.setOnClickListener { signIn() }
		viewBinding.editPassword.setOnEditorActionListener { _, _, _ -> signIn(); true }
	}
	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
		v.updatePadding(top = bars.top, bottom = bars.bottom)
		return insets
	}
	private fun signIn() {
		if (login?.isActive == true) return
		val username = viewBinding.editUsername.text?.toString().orEmpty()
		val password = viewBinding.editPassword.text?.toString().orEmpty()
		if (username.isBlank() || password.isEmpty()) return
		// No credentials enter Intents, saved instance state, logs, or persistent storage.
		viewBinding.editPassword.text?.clear()
		login = lifecycleScope.launch {
			viewBinding.buttonSignIn.isEnabled = false
			viewBinding.progressLogin.isVisible = true
			viewBinding.textError.isVisible = false
			try {
				withContext(Dispatchers.IO) { repository.get().signIn(username, password) }
				router.openScrobblerSettings(ScrobblerService.MANGAUPDATES)
				finish()
			} catch (e: CancellationException) { throw e } catch (_: Exception) {
				viewBinding.textError.setText(R.string.mangaupdates_login_failed)
				viewBinding.textError.isVisible = true
			} finally {
				viewBinding.progressLogin.isVisible = false
				viewBinding.buttonSignIn.isEnabled = true
			}
		}
	}
}
