package org.koitharu.kotatsu.settings.about

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.viewModels
import androidx.core.text.buildSpannedString
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.github.AppVersion
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.showOrHide
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ActivityAppUpdateBinding

@AndroidEntryPoint
class AppUpdateActivity : BaseActivity<ActivityAppUpdateBinding>() {

	private val viewModel: AppUpdateViewModel by viewModels()
	private var isAutoDownloadStarted = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityAppUpdateBinding.inflate(layoutInflater))
		viewModel.nextVersion.observe(this, ::onNextVersionChanged)
		viewBinding.buttonCancel.setOnClickListener { finishAfterTransition() }
		viewBinding.buttonUpdate.setOnClickListener { doUpdate() }
		combine(viewModel.isLoading, viewModel.downloadProgress, ::Pair)
			.observe(this, ::onProgressChanged)
		viewModel.onError.observeEvent(this, ::onError)
		viewModel.onDownloadDone.observeEvent(this) { intent ->
			try {
				startActivity(intent)
			} catch (e: ActivityNotFoundException) {
				e.printStackTraceDebug()
				onError(e)
			}
		}
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.root.updatePadding(top = barsInsets.top)
		viewBinding.dockedToolbarChild.updateLayoutParams<MarginLayoutParams> {
			leftMargin = barsInsets.left
			rightMargin = barsInsets.right
			bottomMargin = barsInsets.bottom
		}
		viewBinding.scrollView.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	private suspend fun onNextVersionChanged(version: AppVersion?) {
		viewBinding.buttonUpdate.isEnabled = version != null && !viewModel.isLoading.value
		if (version == null) {
			viewBinding.textViewContent.setText(R.string.loading_)
			return
		}
		// Launched straight from the home-screen prompt: start the same in-app download path.
		if (intent?.getBooleanExtra(AppRouter.KEY_AUTO_DOWNLOAD, false) == true && !isAutoDownloadStarted) {
			isAutoDownloadStarted = true
			doUpdate()
		}
		val markwon = Markwon.create(this)
		val message = withContext(Dispatchers.Default) {
			buildSpannedString {
				append(getString(R.string.new_version_s, version.name))
				appendLine()
				append(getString(R.string.size_s, FileSize.BYTES.format(this@AppUpdateActivity, version.apkSize)))
				appendLine()
				appendLine()
				append(markwon.toMarkdown(version.description))
			}
		}
		markwon.setParsedMarkdown(viewBinding.textViewContent, message)
	}

	private fun doUpdate() {
		viewModel.installIntent.value?.let { intent ->
			try {
				startActivity(intent)
			} catch (e: Exception) {
				onError(e)
			}
			return
		}
		viewModel.startDownload()
	}

	private fun onProgressChanged(value: Pair<Boolean, Float>) {
		val (isLoading, downloadProgress) = value
		val indicator = viewBinding.progressBar
		indicator.showOrHide(isLoading)
		indicator.isIndeterminate = downloadProgress < 0f
		if (downloadProgress >= 0f) {
			indicator.setProgressCompat((indicator.max * downloadProgress).toInt(), true)
		}
		viewBinding.buttonUpdate.isEnabled = !isLoading && viewModel.nextVersion.value != null
	}

	private fun onError(e: Throwable) {
		viewBinding.textViewError.textAndVisible = e.getDisplayMessage(resources)
	}
}
