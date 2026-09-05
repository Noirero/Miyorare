package org.koitharu.kotatsu.settings.sources.catalog

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.ui.widgets.ChipsView.ChipModel
import org.koitharu.kotatsu.core.util.LocaleComparator
import org.koitharu.kotatsu.core.util.ext.bindExpandedSearchTitle
import org.koitharu.kotatsu.core.util.ext.getDisplayName
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.recyclerView
import org.koitharu.kotatsu.core.util.ext.smoothScrollToTop
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.databinding.ActivitySourcesCatalogBinding
import org.koitharu.kotatsu.extensions.install.ExtensionInstallerMethod
import org.koitharu.kotatsu.extensions.install.ExtensionInstallerPreferences
import org.koitharu.kotatsu.extensions.install.ExtensionUpdateWorker
import org.koitharu.kotatsu.extensions.install.SHIZUKU_PACKAGE_NAME
import org.koitharu.kotatsu.extensions.install.ShizukuExtensionInstaller
import org.koitharu.kotatsu.extensions.install.ShizukuInstallerStatus
import org.koitharu.kotatsu.extensions.install.currentStatus
import org.koitharu.kotatsu.extensions.install.extensionInstallerChoiceLabel
import org.koitharu.kotatsu.extensions.install.extensionInstallerMethodSummary
import org.koitharu.kotatsu.extensions.install.extensionInstallerMethodTitle
import org.koitharu.kotatsu.extensions.install.shizukuInstallerStatusText
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageLabel
import org.koitharu.kotatsu.list.ui.adapter.ListHeaderClickListener
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.lnreader.LnPluginManager
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.parsers.model.ContentType
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject

/** Prefix of the extension apks cached in [android.content.Context.getCacheDir] while installing. */
internal const val EXTENSION_APK_PREFIX = "extension_"

@AndroidEntryPoint
class SourcesCatalogActivity : BaseActivity<ActivitySourcesCatalogBinding>(),
	ExtensionActionListener,
	AppBarOwner,
	ListHeaderClickListener,
	MenuItem.OnActionExpandListener,
	ChipsView.OnChipClickListener {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var installerPreferences: ExtensionInstallerPreferences

	@Inject
	lateinit var shizukuInstaller: ShizukuExtensionInstaller

	@Inject
	lateinit var extensionUpdateScheduler: ExtensionUpdateWorker.Scheduler

	@Inject
	lateinit var storeManager: ExtensionStoreManager

	@Inject
	lateinit var lnPluginManager: LnPluginManager

	@Inject
	@BaseHttpClient
	lateinit var httpClient: OkHttpClient

	private val viewModel by viewModels<SourcesCatalogViewModel>()
	private val isExternalOnly by lazy(LazyThreadSafetyMode.NONE) {
		intent?.getBooleanExtra(AppRouter.KEY_SOURCE_CATALOG_EXTERNAL_ONLY, false) == true
	}
	private val isAutoMigrate by lazy(LazyThreadSafetyMode.NONE) {
		intent?.getBooleanExtra(AppRouter.KEY_SOURCE_CATALOG_AUTO_MIGRATE, false) == true
	}
	private var isScrollToTopShown = false
	private val pendingInstallQueue = ArrayDeque<SourcesCatalogViewModel.InstallRequest>()
	private val pendingDownloadedInstalls = ArrayDeque<Long>()
	private val downloadRequestsById = HashMap<Long, PendingDownload>()
	private val cancelledDownloadIds = HashSet<Long>()
	private var nextDownloadId = 1L
	private var activeInstallerPackage: String? = null
	private var activeInstallerFileName: String? = null
	private var activeInstallerDownloadId = 0L
	private var activeInstallerStoreId: String? = null
	private var activeInstallerMode: ExtensionInstallMode? = null
	private var isInstallerActive = false
	private var isInstallerQueuePaused = false
	private var pendingUninstallPackage: String? = null
	private var pendingReplacementDownloadId: Long? = null
	private var awaitingUpdateAllRequests = false
	private var updateBatch: UpdateBatchState? = null
	private var pendingShizukuReadyAction: (() -> Unit)? = null
	private var pendingShizukuCancelAction: (() -> Unit)? = null
	private lateinit var pagesAdapter: SourcesCatalogPagesAdapter
	private var selectedPageId = ExtensionCatalogPage.Available.id

	private val shizukuPermissionListener = object : Shizuku.OnRequestPermissionResultListener {
		override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
			if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return
			runCatching { Shizuku.removeRequestPermissionResultListener(this) }
			refreshInstallerMethodUi()
			if (grantResult == PackageManager.PERMISSION_GRANTED) {
				resumePendingShizukuAction()
			} else {
				Toast.makeText(this@SourcesCatalogActivity, R.string.shizuku_permission_denied, Toast.LENGTH_LONG).show()
				cancelPendingShizukuAction()
			}
		}
	}

	private val installPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		if (!canInstallPackages()) {
			Toast.makeText(this, R.string.extension_install_permission_required_message, Toast.LENGTH_LONG).show()
			cancelPendingInstalls(recordAsFailed = true)
			return@registerForActivityResult
		}
		processInstallQueue()
		processDownloadedInstallerQueue()
	}

	private val packageInstallerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		val wasBatch = activeInstallerPackage?.let { updateBatch?.pendingPackages?.contains(it) == true } == true
		finishActiveInstaller(
			refresh = !wasBatch,
			installSucceeded = result.resultCode == Activity.RESULT_OK,
		)
		processDownloadedInstallerQueue()
	}

	private val uninstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		val replacementDownloadId = pendingReplacementDownloadId
		pendingReplacementDownloadId = null
		if (replacementDownloadId != null) {
			val pending = downloadRequestsById[replacementDownloadId]
			val isRemoved = pending?.packageName?.let { packageName ->
				runCatching { packageManager.getPackageInfo(packageName, 0) }.isFailure
			} == true
			if (isRemoved && pending != null) {
				downloadRequestsById[replacementDownloadId] = pending.copy(systemRemovalCompleted = true)
				pendingDownloadedInstalls.addFirst(replacementDownloadId)
			} else {
				viewModel.clearExtensionInProgress(pending?.packageName)
				removeDownloadedApk(pending?.fileName)
				downloadRequestsById.remove(replacementDownloadId)
				pending?.packageName?.let { recordBatchResult(it, false) }
			}
			processDownloadedInstallerQueue()
			return@registerForActivityResult
		}
		viewModel.clearExtensionInProgress(pendingUninstallPackage)
		pendingUninstallPackage?.takeIf { packageName ->
			runCatching { packageManager.getPackageInfo(packageName, 0) }.isFailure
		}?.let { packageName ->
			storeManager.removeOwner(ExtensionInstallMode.SYSTEM, packageName)
		}
		pendingUninstallPackage = null
		viewModel.refresh()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		clearOldApks()
		setContentView(ActivitySourcesCatalogBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		if (isExternalOnly) title = getString(R.string.extension_management)

		pagesAdapter = SourcesCatalogPagesAdapter(
			extensionActionListener = this,
			headerClickListener = this,
			installerMethodInfo = ::installerFooterInfo,
			onInstallerMethodClick = ::onInstallationMethodRequested,
			listener = object : SourcesCatalogPagesAdapter.Listener {
				override fun onRefresh() = viewModel.refresh()
				override fun onPageScrolled() = updateScrollToTopVisibility()
				override fun onUpdateAll() = onUpdateAllRequested()
			},
		)
		viewBinding.pager.adapter = pagesAdapter
		viewBinding.pager.offscreenPageLimit = 1
		viewBinding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
			override fun onPageSelected(position: Int) {
				val page = pagesAdapter.pageAt(position) ?: return
				selectedPageId = page.id
				viewModel.selectPage(page.id)
				updateScrollToTopVisibility()
			}
		})
		TabLayoutMediator(viewBinding.tabs, viewBinding.pager) { tab, position ->
			when (val page = pagesAdapter.pageAt(position)) {
				ExtensionCatalogPage.Available -> tab.text = getString(R.string.installed)
				ExtensionCatalogPage.Empty -> tab.text = ""
				is ExtensionCatalogPage.Store -> {
					tab.text = page.title
					if (shouldShowStoreTabDivider(position, page)) {
						tab.view.setBackgroundResource(R.drawable.bg_extension_store_tab_separator)
					}
				}
				null -> tab.text = ""
			}
		}.attach()
		viewBinding.chipsFilter.onChipClickListener = this

		viewModel.pages.observe(this) { pages ->
			pagesAdapter.submitPages(pages)
			viewBinding.tabs.isVisible = pages.none { it == ExtensionCatalogPage.Empty }
			viewBinding.scrollViewChips.isVisible = shouldShowCatalogFilters(pages)
			val selected = pages.indexOfFirst { it.id == selectedPageId }.let { if (it >= 0) it else 0 }
			pages.getOrNull(selected)?.let { page ->
				selectedPageId = page.id
				viewModel.selectPage(page.id)
			}
			if (viewBinding.pager.currentItem != selected) {
				viewBinding.pager.setCurrentItem(selected, false)
			}
			invalidateOptionsMenu()
		}
		viewModel.content.observe(this) { page ->
			pagesAdapter.submitContent(page.pageId, page.items)
		}
		viewModel.hasUpdates.observe(this) { hasUpdates ->
			if (hasUpdates && installerPreferences.hasUserSelection && settings.isAutoUpdateExtensionsEnabled) {
				when (installerPreferences.method) {
					ExtensionInstallerMethod.SHIZUKU,
					ExtensionInstallerMethod.PRIVATE -> lifecycleScope.launch { extensionUpdateScheduler.startNow() }
					ExtensionInstallerMethod.SYSTEM -> Unit
				}
			}
		}
		viewModel.isRefreshing.observe(this) {
			pagesAdapter.setRefreshing(it)
		}
		viewModel.onOpenPackageInstaller.observeEvent(this) { requests ->
			if (awaitingUpdateAllRequests) {
				awaitingUpdateAllRequests = false
				beginUpdateBatch(requests)
			}
			handleInstallRequests(requests)
		}
		viewModel.onOpenUninstall.observeEvent(this) { pkg ->
			if (settings.isPrivateInstallEnabled) {
				MihonExtensionLoader.uninstallPrivateExtension(this, pkg)
				storeManager.removeOwner(ExtensionInstallMode.SANDBOX, pkg)
				viewModel.clearExtensionInProgress(pkg)
				viewModel.onPrivateExtensionChanged()
				return@observeEvent
			}
			val uri = Uri.fromParts("package", pkg, null)
			val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				Intent.ACTION_DELETE
			} else {
				@Suppress("DEPRECATION")
				Intent.ACTION_UNINSTALL_PACKAGE
			}
			try {
				pendingUninstallPackage = pkg
				viewModel.setExtensionInProgress(pkg, true)
				uninstallLauncher.launch(Intent(action, uri))
			} catch (_: ActivityNotFoundException) {
				pendingUninstallPackage = null
				viewModel.clearExtensionInProgress(pkg)
				Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
			}
		}
		viewModel.onShowMessage.observeEvent(this) { msg ->
			awaitingUpdateAllRequests = false
			Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
		}
		viewModel.onExtensionInstalled.observeEvent(this) { sourceName ->
			val source = MangaSource(sourceName)
			Snackbar.make(viewBinding.pager, R.string.extension_installed, Snackbar.LENGTH_LONG)
				.setAction(R.string.action_open) { router.openList(source, null, null) }
				.show()
		}
		combine(
			viewModel.appliedFilter,
			viewModel.contentTypes,
			viewModel.locales,
			viewModel.isNsfwDisabled,
		) { filter, contentTypes, locales, isNsfwDisabled ->
			CatalogUiState(filter, contentTypes, locales, isNsfwDisabled)
		}.observe(this) {
			updateFilers(it.filter, it.contentTypes, it.locales, it.isNsfwDisabled)
		}
		addMenuProvider(SourcesCatalogMenuProvider(this, viewModel, this))
		if (isAutoMigrate && installerPreferences.method == ExtensionInstallerMethod.PRIVATE) {
			offerPrivateMigration()
		}
		viewBinding.buttonScrollToTop.setOnClickListener {
			currentRecyclerView()?.smoothScrollToTop()
		}
		updateScrollToTopVisibility()
	}

	override fun onResume() {
		super.onResume()
		refreshInstallerMethodUi()
		val readyAction = pendingShizukuReadyAction
		if (readyAction != null) {
			if (shizukuInstaller.currentStatus() == ShizukuInstallerStatus.READY) {
				resumePendingShizukuAction()
			} else {
				val cancelAction = pendingShizukuCancelAction ?: {}
				clearPendingShizukuActions()
				viewBinding.root.post { showShizukuNotReadyDialog(readyAction, cancelAction) }
			}
		}
	}

	override fun onDestroy() {
		runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
		super.onDestroy()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		applyRecyclerPadding(bars)
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
			top = bars.top,
		)
		viewBinding.buttonScrollToTop.updateLayoutParams<androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams> {
			leftMargin = bars.left
			rightMargin = bars.right
			bottomMargin = bars.bottom + resources.getDimensionPixelOffset(R.dimen.margin_normal)
		}
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	override fun onChipClick(chip: Chip, data: Any?) {
		when (data) {
			is ContentType -> viewModel.setContentType(data, !chip.isChecked)
			FilterChip.NSFW_DISABLED -> viewModel.setNsfwDisabled(!chip.isChecked)
			FilterChip.LOCALE -> showLocalesMenu(chip)
			else -> Unit
		}
	}

	override fun onExtensionActionClick(item: SourceCatalogItem.Extension) {
		viewModel.onInstallEntryClick(item)
	}

	override fun onListHeaderClick(item: ListHeader, view: View) {
		// Section headers on store tabs are labels only.
	}

	override fun onExtensionSettingsClick(item: SourceCatalogItem.Extension) {
		val sourceName = item.sourceName ?: return
		router.openSourceSettings(MangaSource(sourceName))
	}

	override fun onExtensionItemClick(item: SourceCatalogItem.Extension) {
		val sourceName = item.sourceName ?: return
		router.openList(MangaSource(sourceName), null, null)
	}

	override fun onExtensionHideClick(item: SourceCatalogItem.Extension) {
		viewModel.setExtensionHidden(item.packageName, !item.isHidden)
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		pagesAdapter.setSearching(true)
		setSearchTitleExpanded(true)
		viewBinding.toolbar.contentInsetStartWithNavigation =
			resources.getDimensionPixelSize(R.dimen.top_bar_navigation_button_margin_start) +
			resources.getDimensionPixelSize(R.dimen.top_bar_navigation_button_size)
		val sq = (item.actionView as? SearchView)?.query?.trim()?.toString().orEmpty()
		viewModel.performSearch(sq)
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		pagesAdapter.setSearching(false)
		setSearchTitleExpanded(false)
		viewBinding.toolbar.contentInsetStartWithNavigation =
			resources.getDimensionPixelSize(R.dimen.top_bar_title_inset_with_navigation)
		viewModel.performSearch(null)
		return true
	}

	private fun setSearchTitleExpanded(expanded: Boolean) {
		val ctl = viewBinding.collapsingToolbarLayout
		viewBinding.textTitleSearch.bindExpandedSearchTitle(ctl, ctl.title ?: title, expanded)
	}

	private fun updateFilers(
		appliedFilter: SourcesCatalogFilter,
		contentTypes: List<ContentType>,
		locales: Set<String?>,
		isNsfwDisabled: Boolean,
	) {
		val chips = ArrayList<ChipModel>(contentTypes.size + 2)
		if (locales.size > 1) {
			chips += ChipModel(
				title = appliedFilter.locale?.let(::getExternalExtensionLanguageLabel)
					?: (null as Locale?).getDisplayName(this),
				icon = R.drawable.ic_language,
				isDropdown = true,
				data = FilterChip.LOCALE,
			)
		}
		chips += ChipModel(
			title = getString(R.string.disable_nsfw),
			icon = R.drawable.ic_nsfw,
			isChecked = isNsfwDisabled,
			data = FilterChip.NSFW_DISABLED,
		)
		contentTypes.mapTo(chips) { type ->
			ChipModel(
				title = getString(type.titleResId),
				isChecked = type in appliedFilter.types,
				data = type,
			)
		}
		viewBinding.chipsFilter.setChips(chips)
	}

	private fun showLocalesMenu(anchor: View) {
		val locales = viewModel.locales.value.mapTo(ArrayList(viewModel.locales.value.size)) {
			it to it?.toLocale()
		}
		locales.sortWith(compareBy(nullsFirst(LocaleComparator())) { it.second })
		val menu = PopupMenu(this, anchor)
		for ((i, lc) in locales.withIndex()) {
			menu.menu.add(
				Menu.NONE,
				Menu.NONE,
				i,
				lc.first?.let(::getExternalExtensionLanguageLabel) ?: lc.second.getDisplayName(this),
			)
		}
		menu.setOnMenuItemClickListener {
			viewModel.setLocale(locales.getOrNull(it.order)?.first)
			true
		}
		menu.show()
	}

	fun onManageRepoRequested() {
		router.openExtensionStores()
	}

	fun onInstallationMethodRequested() {
		showInstallerMethodDialog(verifyShizukuAfterSelection = true)
	}

	private fun onUpdateAllRequested() {
		ensureInstallerMethodReady(
			onReady = {
				awaitingUpdateAllRequests = true
				viewModel.updateAllExtensions()
			},
			onCancel = { awaitingUpdateAllRequests = false },
		)
	}

	private fun installerFooterInfo(): InstallerFooterInfo {
		if (!installerPreferences.hasUserSelection) {
			return InstallerFooterInfo(
				title = getString(R.string.extension_installer_footer_not_selected),
				summary = getString(R.string.extension_installer_footer_not_selected_summary),
			)
		}
		val method = installerPreferences.method
		return InstallerFooterInfo(
			title = getString(R.string.extension_installer_footer_title, extensionInstallerMethodTitle(method)),
			summary = extensionInstallerMethodSummary(method, shizukuInstaller.currentStatus()),
		)
	}

	private fun refreshInstallerMethodUi() {
		if (::pagesAdapter.isInitialized) pagesAdapter.refreshInstallerMethod()
	}

	private fun showInstallerMethodDialog(
		onSelected: (() -> Unit)? = null,
		onCancel: (() -> Unit)? = null,
		verifyShizukuAfterSelection: Boolean = false,
	) {
		val status = shizukuInstaller.currentStatus()
		val methods = listOf(
			ExtensionInstallerMethod.SHIZUKU,
			ExtensionInstallerMethod.SYSTEM,
			ExtensionInstallerMethod.PRIVATE,
		)
		val labels = methods.map { extensionInstallerChoiceLabel(it, status) }.toTypedArray()
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.extension_installer_choose_title)
			.setItems(labels) { _, which ->
				val method = methods.getOrNull(which) ?: return@setItems
				val hadSelection = installerPreferences.hasUserSelection
				val previous = installerPreferences.method
				installerPreferences.select(method)
				refreshInstallerMethodUi()
				if (
					method == ExtensionInstallerMethod.PRIVATE &&
					(!hadSelection || previous != ExtensionInstallerMethod.PRIVATE)
				) {
					offerPrivateMigration()
				}
				if (
					verifyShizukuAfterSelection &&
					method == ExtensionInstallerMethod.SHIZUKU &&
					shizukuInstaller.currentStatus() != ShizukuInstallerStatus.READY
				) {
					showShizukuNotReadyDialog(onSelected ?: {}, onCancel ?: {})
				} else {
					onSelected?.invoke()
				}
			}
			.setOnCancelListener { onCancel?.invoke() }
			.show()
	}

	private fun ensureInstallerMethodReady(
		onReady: () -> Unit,
		onCancel: () -> Unit,
	) {
		if (!installerPreferences.hasUserSelection) {
			showInstallerMethodDialog(
				onSelected = { ensureInstallerMethodReady(onReady, onCancel) },
				onCancel = onCancel,
			)
			return
		}
		if (installerPreferences.method != ExtensionInstallerMethod.SHIZUKU) {
			onReady()
			return
		}
		if (shizukuInstaller.currentStatus() == ShizukuInstallerStatus.READY) {
			onReady()
		} else {
			showShizukuNotReadyDialog(onReady, onCancel)
		}
	}

	private fun showShizukuNotReadyDialog(
		onReady: () -> Unit,
		onCancel: () -> Unit,
	) {
		val status = shizukuInstaller.currentStatus()
		if (status == ShizukuInstallerStatus.READY) {
			onReady()
			return
		}
		val positive = if (status == ShizukuInstallerStatus.PERMISSION_REQUIRED) {
			R.string.extension_installer_grant_permission
		} else {
			R.string.extension_installer_check_shizuku
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.extension_installer_shizuku_not_ready_title)
			.setMessage(
				getString(
					R.string.extension_installer_shizuku_not_ready_message,
					shizukuInstallerStatusText(status),
				),
			)
			.setPositiveButton(positive) { _, _ -> checkOrRequestShizuku(status, onReady, onCancel) }
			.setNeutralButton(R.string.extension_installer_change_method) { _, _ ->
				showInstallerMethodDialog(
					onSelected = { ensureInstallerMethodReady(onReady, onCancel) },
					onCancel = onCancel,
				)
			}
			.setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
			.setOnCancelListener { onCancel() }
			.show()
	}

	private fun checkOrRequestShizuku(
		status: ShizukuInstallerStatus,
		onReady: () -> Unit,
		onCancel: () -> Unit,
	) {
		when (status) {
			ShizukuInstallerStatus.READY -> onReady()
			ShizukuInstallerStatus.PERMISSION_REQUIRED -> {
				pendingShizukuReadyAction = onReady
				pendingShizukuCancelAction = onCancel
				runCatching {
					Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
					Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
				}.onFailure {
					runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
					clearPendingShizukuActions()
					Toast.makeText(this, R.string.shizuku_permission_denied, Toast.LENGTH_LONG).show()
					onCancel()
				}
			}
			ShizukuInstallerStatus.NOT_RUNNING -> {
				val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE_NAME)
				if (intent == null) {
					Toast.makeText(this, R.string.shizuku_not_running, Toast.LENGTH_LONG).show()
					viewBinding.root.post { showShizukuNotReadyDialog(onReady, onCancel) }
				} else {
					pendingShizukuReadyAction = onReady
					pendingShizukuCancelAction = onCancel
					startActivity(intent)
				}
			}
			ShizukuInstallerStatus.NOT_INSTALLED -> {
				Toast.makeText(this, R.string.shizuku_not_installed, Toast.LENGTH_LONG).show()
				viewBinding.root.post { showShizukuNotReadyDialog(onReady, onCancel) }
			}
		}
	}

	private fun resumePendingShizukuAction() {
		val action = pendingShizukuReadyAction ?: return
		clearPendingShizukuActions()
		isInstallerQueuePaused = false
		action()
	}

	private fun cancelPendingShizukuAction() {
		val action = pendingShizukuCancelAction
		clearPendingShizukuActions()
		isInstallerQueuePaused = false
		action?.invoke()
	}

	private fun clearPendingShizukuActions() {
		pendingShizukuReadyAction = null
		pendingShizukuCancelAction = null
	}

	private fun offerPrivateMigration() {
		lifecycleScope.launch {
			viewModel.isRefreshing.value = true
			val count = withContext(Dispatchers.IO) { viewModel.reloadAndCheckMigration() }
			viewModel.isRefreshing.value = false
			if (count <= 0) return@launch
			if (!viewModel.hasExternalRepoConfigured()) {
				Toast.makeText(
					this@SourcesCatalogActivity,
					R.string.private_extensions_no_repo,
					Toast.LENGTH_LONG,
				).show()
				return@launch
			}
			MaterialAlertDialogBuilder(this@SourcesCatalogActivity)
				.setTitle(R.string.private_extensions_migration_title)
				.setMessage(getString(R.string.private_extensions_migration_message, count))
				.setPositiveButton(R.string.extension_installer_migrate) { _, _ -> viewModel.performMigration() }
				.setNegativeButton(R.string.extension_installer_later, null)
				.show()
		}
	}

	private data class CatalogUiState(
		val filter: SourcesCatalogFilter,
		val contentTypes: List<ContentType>,
		val locales: Set<String?>,
		val isNsfwDisabled: Boolean,
	)

	private enum class FilterChip {
		LOCALE,
		NSFW_DISABLED,
	}

	private fun updateScrollToTopVisibility() {
		val layoutManager = currentRecyclerView()?.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
		val shouldShow = layoutManager.findFirstVisibleItemPosition() >= 6
		if (shouldShow == isScrollToTopShown) return
		isScrollToTopShown = shouldShow
		viewBinding.buttonScrollToTop.animate().cancel()
		if (shouldShow) {
			viewBinding.buttonScrollToTop.alpha = 0f
			viewBinding.buttonScrollToTop.visibility = View.VISIBLE
			viewBinding.buttonScrollToTop.animate().alpha(1f).setDuration(160L).start()
		} else {
			viewBinding.buttonScrollToTop.animate()
				.alpha(0f)
				.setDuration(160L)
				.withEndAction { viewBinding.buttonScrollToTop.visibility = View.GONE }
				.start()
		}
	}

	private fun applyRecyclerPadding(systemBars: Insets) {
		if (::pagesAdapter.isInitialized) pagesAdapter.setInsets(systemBars)
	}

	private fun currentRecyclerView() =
		(viewBinding.pager.recyclerView
			?.findViewHolderForAdapterPosition(viewBinding.pager.currentItem) as? SourcesCatalogPagesAdapter.Holder)
			?.binding
			?.recyclerView

	private fun handleInstallRequests(requests: List<SourcesCatalogViewModel.InstallRequest>) {
		if (requests.isEmpty()) return
		if (requests.all { it.isNovelPlugin }) {
			requests.forEach(::confirmAndEnqueueInstall)
			return
		}
		ensureInstallerMethodReady(
			onReady = {
				val targetMode = installerPreferences.method.installMode
				for (request in requests) {
					val mapped = if (request.isNovelPlugin) {
						request
					} else {
						request.copy(
							mode = targetMode,
							replacement = request.replacement.takeIf { request.mode == targetMode },
						)
					}
					confirmAndEnqueueInstall(mapped)
				}
			},
			onCancel = {
				requests.forEach { request ->
					viewModel.clearExtensionInProgress(request.packageName)
					recordBatchResult(request.packageName, false)
				}
			},
		)
	}

	private fun enqueueInstall(request: SourcesCatalogViewModel.InstallRequest) {
		val alreadyQueued = pendingInstallQueue.any { it.packageName == request.packageName && it.mode == request.mode } ||
			downloadRequestsById.values.any { it.packageName == request.packageName && it.mode == request.mode } ||
			(activeInstallerPackage == request.packageName && activeInstallerMode == request.mode)
		if (alreadyQueued) return
		pendingInstallQueue += request
		viewModel.setExtensionInProgress(request.packageName, true)
		processInstallQueue()
	}

	private fun confirmAndEnqueueInstall(request: SourcesCatalogViewModel.InstallRequest) {
		val replacement = request.replacement
		if (replacement == null) {
			enqueueInstall(request)
			return
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.replace_extension_store_title)
			.setMessage(
				getString(
					R.string.replace_extension_store_message,
					request.packageName,
					replacement.currentStoreName,
					replacement.newStoreName,
				),
			)
			.setNegativeButton(R.string.keep_current_store) { _, _ -> rejectInstallRequest(request) }
			.setPositiveButton(R.string.replace_store) { _, _ -> enqueueInstall(request) }
			.setOnCancelListener { rejectInstallRequest(request) }
			.show()
	}

	private fun rejectInstallRequest(request: SourcesCatalogViewModel.InstallRequest) {
		viewModel.clearExtensionInProgress(request.packageName)
		recordBatchResult(request.packageName, false)
	}

	private fun processInstallQueue() {
		val next = pendingInstallQueue.firstOrNull() ?: return
		if (
			!next.isNovelPlugin &&
			next.mode == ExtensionInstallMode.SYSTEM &&
			installerPreferences.method == ExtensionInstallerMethod.SYSTEM &&
			!canInstallPackages()
		) {
			requestInstallPackagesPermission()
			return
		}
		downloadAndInstallExtension(pendingInstallQueue.removeFirst())
	}

	private fun downloadAndInstallExtension(requestModel: SourcesCatalogViewModel.InstallRequest) {
		if (requestModel.isNovelPlugin) {
			installNovelPlugin(requestModel)
			return
		}
		val downloadId = nextDownloadId++
		val fileName = "$EXTENSION_APK_PREFIX${requestModel.packageName}.apk"
		downloadRequestsById[downloadId] = PendingDownload(
			packageName = requestModel.packageName,
			fileName = fileName,
			storeId = requestModel.storeId,
			mode = requestModel.mode,
			isProviderReplacement = requestModel.replacement != null,
		)
		if (updateBatch?.pendingPackages?.contains(requestModel.packageName) != true) {
			Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
		}
		lifecycleScope.launch {
			val result = runCatching {
				withContext(Dispatchers.IO) { downloadApk(requestModel.url, fileName) }
			}
			if (cancelledDownloadIds.remove(downloadId)) {
				removeDownloadedApk(fileName)
				return@launch
			}
			if (result.isSuccess) {
				pendingDownloadedInstalls += downloadId
				processDownloadedInstallerQueue()
			} else {
				val pending = downloadRequestsById.remove(downloadId)
				viewModel.clearExtensionInProgress(pending?.packageName)
				pending?.packageName?.let { recordBatchResult(it, false) }
				Toast.makeText(this@SourcesCatalogActivity, R.string.extension_download_failed, Toast.LENGTH_LONG).show()
			}
		}
		processInstallQueue()
	}

	private fun installNovelPlugin(requestModel: SourcesCatalogViewModel.InstallRequest) {
		val isBatch = updateBatch?.pendingPackages?.contains(requestModel.packageName) == true
		if (!isBatch) Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
		lifecycleScope.launch {
			val result = runCatching {
				val code = withContext(Dispatchers.IO) { downloadText(requestModel.url) }
				lnPluginManager.install(
					pluginId = requestModel.packageName,
					rawCode = code,
					iconUrl = requestModel.iconUrl.orEmpty(),
					lang = requestModel.lang.orEmpty(),
					storeId = requestModel.storeId,
				)
			}
			viewModel.clearExtensionInProgress(requestModel.packageName)
			if (result.isSuccess) {
				viewModel.onPrivateExtensionChanged()
				if (!isBatch) viewModel.notifyExtensionInstalled(requestModel.packageName)
				extensionUpdateScheduler.schedule()
			} else {
				Toast.makeText(this@SourcesCatalogActivity, R.string.extension_download_failed, Toast.LENGTH_LONG).show()
			}
			recordBatchResult(requestModel.packageName, result.isSuccess)
			processInstallQueue()
		}
	}

	private fun downloadText(url: String): String {
		val request = Request.Builder().url(url).get().build()
		return httpClient.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
			response.body.string()
		}
	}

	private fun downloadApk(url: String, fileName: String) {
		val destination = File(cacheDir, fileName)
		val tmp = File(cacheDir, "$fileName.tmp")
		try {
			val request = Request.Builder().url(url).get().build()
			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
				response.body.byteStream().use { input ->
					tmp.outputStream().buffered().use { output -> input.copyTo(output) }
				}
			}
			if (!tmp.renameTo(destination)) tmp.copyTo(destination, overwrite = true)
		} finally {
			tmp.delete()
		}
	}

	private fun installDownloadedApk(downloadId: Long) {
		val pendingDownload = downloadRequestsById.remove(downloadId)
		val apkFile = getDownloadedApkFile(pendingDownload?.fileName)
		val archive = apkFile?.let(::getArchivePackageInfo)
		if (
			pendingDownload == null ||
			apkFile == null ||
			!apkFile.isFile ||
			archive == null ||
			archive.packageName != pendingDownload.packageName ||
			!MihonExtensionLoader.isPackageAnExtensionStatic(archive) ||
			MihonExtensionLoader.getSignatures(archive).isEmpty()
		) {
			viewModel.clearExtensionInProgress(pendingDownload?.packageName)
			removeDownloadedApk(pendingDownload?.fileName)
			pendingDownload?.packageName?.let { recordBatchResult(it, false) }
			Toast.makeText(this, R.string.shizuku_invalid_package, Toast.LENGTH_LONG).show()
			processDownloadedInstallerQueue()
			return
		}

		val expectedPackage = pendingDownload.packageName
		val method = if (pendingDownload.mode == ExtensionInstallMode.SANDBOX) {
			ExtensionInstallerMethod.PRIVATE
		} else {
			installerPreferences.method
		}

		if (method == ExtensionInstallerMethod.PRIVATE) {
			activeInstallerPackage = expectedPackage
			activeInstallerStoreId = pendingDownload.storeId
			activeInstallerMode = ExtensionInstallMode.SANDBOX
			activeInstallerFileName = pendingDownload.fileName
			activeInstallerDownloadId = downloadId
			isInstallerActive = true
			lifecycleScope.launch {
				val success = MihonExtensionLoader.installPrivateExtensionFile(
					this@SourcesCatalogActivity,
					apkFile,
					replaceExistingProvider = pendingDownload.isProviderReplacement &&
						pendingDownload.mode == ExtensionInstallMode.SANDBOX,
					expectedPackageName = expectedPackage,
				)
				if (!success) {
					Toast.makeText(
						this@SourcesCatalogActivity,
						getString(R.string.private_extension_install_failed, expectedPackage),
						Toast.LENGTH_LONG,
					).show()
				}
				finishActiveInstaller(refresh = false, installSucceeded = success)
				if (success) viewModel.onPrivateExtensionChanged()
				processDownloadedInstallerQueue()
			}
			return
		}

		if (
			pendingDownload.isProviderReplacement &&
			!pendingDownload.systemRemovalCompleted &&
			needsSystemRemovalForReplacement(expectedPackage, apkFile)
		) {
			downloadRequestsById[downloadId] = pendingDownload
			pendingReplacementDownloadId = downloadId
			launchSystemUninstall(expectedPackage)
			return
		}

		if (method == ExtensionInstallerMethod.SHIZUKU) {
			if (shizukuInstaller.currentStatus() != ShizukuInstallerStatus.READY) {
				downloadRequestsById[downloadId] = pendingDownload
				pendingDownloadedInstalls.addFirst(downloadId)
				isInstallerQueuePaused = true
				showShizukuNotReadyDialog(
					onReady = {
						isInstallerQueuePaused = false
						processDownloadedInstallerQueue()
					},
					onCancel = {
						isInstallerQueuePaused = false
						cancelPendingInstalls(recordAsFailed = true)
					},
				)
				return
			}
			activeInstallerPackage = expectedPackage
			activeInstallerStoreId = pendingDownload.storeId
			activeInstallerMode = ExtensionInstallMode.SYSTEM
			activeInstallerFileName = pendingDownload.fileName
			activeInstallerDownloadId = downloadId
			isInstallerActive = true
			lifecycleScope.launch {
				when (val result = shizukuInstaller.install(apkFile, expectedPackage)) {
					ShizukuExtensionInstaller.InstallResult.Success -> {
						finishActiveInstaller(refresh = updateBatch == null, installSucceeded = true)
						processDownloadedInstallerQueue()
					}
					ShizukuExtensionInstaller.InstallResult.Unavailable -> {
						// Shizuku disappeared after the preflight check. Keep this APK and queue entry so
						// the exact same extension resumes after permission/service is restored.
						isInstallerActive = false
						downloadRequestsById[downloadId] = pendingDownload
						pendingDownloadedInstalls.addFirst(downloadId)
						activeInstallerPackage = null
						activeInstallerFileName = null
						activeInstallerDownloadId = 0L
						activeInstallerStoreId = null
						activeInstallerMode = null
						isInstallerQueuePaused = true
						showShizukuNotReadyDialog(
							onReady = {
								isInstallerQueuePaused = false
								processDownloadedInstallerQueue()
							},
							onCancel = {
								isInstallerQueuePaused = false
								cancelPendingInstalls(recordAsFailed = true)
							},
						)
					}
					ShizukuExtensionInstaller.InstallResult.InvalidPackage -> {
						Toast.makeText(this@SourcesCatalogActivity, R.string.shizuku_invalid_package, Toast.LENGTH_LONG).show()
						finishActiveInstaller(refresh = false, installSucceeded = false)
						processDownloadedInstallerQueue()
					}
					is ShizukuExtensionInstaller.InstallResult.Failure -> {
						Toast.makeText(
							this@SourcesCatalogActivity,
							getString(R.string.shizuku_install_failed, result.message.orEmpty()),
							Toast.LENGTH_LONG,
						).show()
						finishActiveInstaller(refresh = false, installSucceeded = false)
						processDownloadedInstallerQueue()
					}
				}
			}
			return
		}

		downloadRequestsById[downloadId] = pendingDownload
		installDownloadedApkWithSystem(downloadId)
	}

	private fun installDownloadedApkWithSystem(downloadId: Long) {
		if (!canInstallPackages()) {
			pendingDownloadedInstalls.addFirst(downloadId)
			requestInstallPackagesPermission()
			return
		}
		val pendingDownload = downloadRequestsById.remove(downloadId)
		activeInstallerPackage = pendingDownload?.packageName
		activeInstallerStoreId = pendingDownload?.storeId
		activeInstallerMode = ExtensionInstallMode.SYSTEM
		activeInstallerFileName = pendingDownload?.fileName
		activeInstallerDownloadId = downloadId
		isInstallerActive = true
		val apkUri = getDownloadedApkUri(pendingDownload?.fileName) ?: run {
			finishActiveInstaller(refresh = false, installSucceeded = false)
			return
		}
		val installIntent = createInstallIntent(apkUri)
		try {
			grantInstallerUriPermissions(installIntent, apkUri)
			packageInstallerLauncher.launch(installIntent)
		} catch (_: ActivityNotFoundException) {
			finishActiveInstaller(refresh = false, installSucceeded = false)
			Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
			processDownloadedInstallerQueue()
		} catch (_: SecurityException) {
			isInstallerActive = false
			pendingDownload?.let { downloadRequestsById[downloadId] = it }
			pendingDownloadedInstalls.addFirst(downloadId)
			activeInstallerPackage = null
			activeInstallerFileName = null
			activeInstallerDownloadId = 0L
			activeInstallerStoreId = null
			activeInstallerMode = null
			requestInstallPackagesPermission()
			Toast.makeText(this, R.string.extension_install_permission_required_message, Toast.LENGTH_LONG).show()
		}
	}

	@Suppress("DEPRECATION")
	@SuppressLint("RequestInstallPackagesPolicy")
	private fun createInstallIntent(apkUri: Uri): Intent {
		val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
			.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			.setDataAndType(apkUri, "application/vnd.android.package-archive")
			.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
			.putExtra(Intent.EXTRA_RETURN_RESULT, true)
		findPackageInstallerPackage(installIntent)?.let(installIntent::setPackage)
		return installIntent
	}

	private fun getDownloadedApkUri(fileName: String?): Uri? {
		getDownloadedApkFile(fileName)?.takeIf { it.isFile }?.let { file ->
			return FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.files", file)
		}
		return null
	}

	private fun getDownloadedApkFile(fileName: String?): File? {
		if (fileName.isNullOrBlank()) return null
		return File(cacheDir, fileName).takeIf { it.exists() }
	}

	@Suppress("DEPRECATION")
	private fun getArchivePackageInfo(apkFile: File): PackageInfo? {
		val flags = PackageManager.GET_META_DATA or
			PackageManager.GET_CONFIGURATIONS or
			PackageManager.GET_SIGNATURES or
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
		return packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
	}

	@Suppress("DEPRECATION")
	private fun needsSystemRemovalForReplacement(packageName: String, apkFile: File): Boolean {
		val flags = PackageManager.GET_SIGNATURES or
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
		val installed = runCatching { packageManager.getPackageInfo(packageName, flags) }.getOrNull()
			?: return false
		val archive = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return true
		val signerChanged = MihonExtensionLoader.getSignatures(installed).toSet() !=
			MihonExtensionLoader.getSignatures(archive).toSet()
		val isDowngrade = PackageInfoCompat.getLongVersionCode(archive) <
			PackageInfoCompat.getLongVersionCode(installed)
		return signerChanged || isDowngrade
	}

	private fun launchSystemUninstall(packageName: String) {
		val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			Intent.ACTION_DELETE
		} else {
			@Suppress("DEPRECATION")
			Intent.ACTION_UNINSTALL_PACKAGE
		}
		try {
			uninstallLauncher.launch(Intent(action, Uri.fromParts("package", packageName, null)))
		} catch (_: ActivityNotFoundException) {
			val downloadId = pendingReplacementDownloadId
			pendingReplacementDownloadId = null
			val pending = downloadId?.let(downloadRequestsById::remove)
			viewModel.clearExtensionInProgress(pending?.packageName)
			removeDownloadedApk(pending?.fileName)
			pending?.packageName?.let { recordBatchResult(it, false) }
			Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
		}
	}

	@Suppress("DEPRECATION")
	private fun grantInstallerUriPermissions(installIntent: Intent, apkUri: Uri) {
		val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
		for (resolveInfo in packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)) {
			val targetPackage = resolveInfo.activityInfo?.packageName ?: continue
			grantUriPermission(targetPackage, apkUri, flags)
		}
	}

	@Suppress("DEPRECATION")
	private fun findPackageInstallerPackage(installIntent: Intent): String? {
		return packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
			.firstOrNull { resolveInfo ->
				val targetPackage = resolveInfo.activityInfo?.packageName ?: return@firstOrNull false
				targetPackage.contains("packageinstaller", ignoreCase = true) ||
					targetPackage.contains("package.installer", ignoreCase = true)
			}?.activityInfo?.packageName
	}

	private fun finishActiveInstaller(
		packageName: String? = activeInstallerPackage,
		downloadId: Long = activeInstallerDownloadId,
		refresh: Boolean,
		installSucceeded: Boolean = false,
	) {
		val isCurrentInstaller = activeInstallerDownloadId == 0L ||
			downloadId == 0L || activeInstallerDownloadId == downloadId
		val wasBatch = packageName?.let { updateBatch?.pendingPackages?.contains(it) == true } == true
		if (isCurrentInstaller) isInstallerActive = false
		viewModel.clearExtensionInProgress(packageName)
		if (installSucceeded && packageName != null && activeInstallerStoreId != null && activeInstallerMode != null) {
			storeManager.setOwner(activeInstallerMode!!, packageName, activeInstallerStoreId!!)
		}
		if (installSucceeded && packageName != null && !wasBatch) {
			viewModel.notifyExtensionInstalled(packageName)
		}
		removeDownloadedApk(activeInstallerFileName)
		if (isCurrentInstaller) {
			activeInstallerPackage = null
			activeInstallerFileName = null
			activeInstallerDownloadId = 0L
			activeInstallerStoreId = null
			activeInstallerMode = null
		}
		if (packageName != null) recordBatchResult(packageName, installSucceeded)
		if (refresh && !wasBatch) viewModel.refresh()
	}

	private fun beginUpdateBatch(requests: List<SourcesCatalogViewModel.InstallRequest>) {
		val packages = requests.mapTo(LinkedHashSet()) { it.packageName }
		updateBatch = UpdateBatchState(pendingPackages = packages)
	}

	private fun recordBatchResult(packageName: String, success: Boolean) {
		val batch = updateBatch ?: return
		if (!batch.pendingPackages.remove(packageName)) return
		if (success) batch.successCount++ else batch.failureCount++
		if (batch.pendingPackages.isNotEmpty()) return
		updateBatch = null
		val message = if (batch.failureCount == 0) {
			getString(R.string.extension_update_all_success, batch.successCount)
		} else {
			getString(R.string.extension_update_all_partial, batch.successCount, batch.failureCount)
		}
		Toast.makeText(this, message, Toast.LENGTH_LONG).show()
		viewModel.refresh()
	}

	private fun cancelPendingInstalls(recordAsFailed: Boolean) {
		val packages = LinkedHashSet<String>()
		while (pendingInstallQueue.isNotEmpty()) {
			packages += pendingInstallQueue.removeFirst().packageName
		}
		for ((downloadId, pending) in downloadRequestsById) {
			cancelledDownloadIds += downloadId
			packages += pending.packageName
			removeDownloadedApk(pending.fileName)
		}
		downloadRequestsById.clear()
		pendingDownloadedInstalls.clear()
		isInstallerQueuePaused = false
		for (packageName in packages) {
			viewModel.clearExtensionInProgress(packageName)
			if (recordAsFailed) recordBatchResult(packageName, false)
		}
	}

	private fun removeDownloadedApk(fileName: String?) {
		getDownloadedApkFile(fileName)?.delete()
	}

	private fun processDownloadedInstallerQueue() {
		if (isInstallerActive || isInstallerQueuePaused) return
		while (!isInstallerActive && !isInstallerQueuePaused) {
			val downloadId = pendingDownloadedInstalls.removeFirstOrNull() ?: break
			installDownloadedApk(downloadId)
		}
	}

	private fun canInstallPackages(): Boolean {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
		return packageManager.canRequestPackageInstalls()
	}

	private fun requestInstallPackagesPermission() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
		val intent = Intent(
			Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
			Uri.parse("package:$packageName"),
		)
		installPermissionLauncher.launch(intent)
	}

	private fun clearOldApks() {
		try {
			cacheDir.listFiles()?.forEach { file ->
				if (
					file.name.startsWith(EXTENSION_APK_PREFIX) &&
					System.currentTimeMillis() - file.lastModified() > STALE_APK_AGE_MS
				) file.delete()
			}
		} catch (_: Exception) {
			// Ignore cache cleanup failures.
		}
	}

	private data class PendingDownload(
		val packageName: String,
		val fileName: String,
		val storeId: String,
		val mode: ExtensionInstallMode,
		val isProviderReplacement: Boolean,
		val systemRemovalCompleted: Boolean = false,
	)

	private data class UpdateBatchState(
		val pendingPackages: MutableSet<String>,
		var successCount: Int = 0,
		var failureCount: Int = 0,
	)

	private companion object {
		const val STALE_APK_AGE_MS = 24L * 60L * 60L * 1000L
		const val SHIZUKU_PERMISSION_REQUEST_CODE = 14047

		/** Novel plugins are raw javascript files and never Android packages. */
		val SourcesCatalogViewModel.InstallRequest.isNovelPlugin: Boolean
			get() = url.substringBefore('?').endsWith(".js", ignoreCase = true)
	}
}
