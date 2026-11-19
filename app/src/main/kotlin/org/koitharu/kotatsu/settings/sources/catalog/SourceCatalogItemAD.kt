package org.koitharu.kotatsu.settings.sources.catalog

import android.content.res.ColorStateList
import androidx.core.view.isVisible
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.parser.favicon.extensionPackageFaviconUri
import org.koitharu.kotatsu.core.ui.image.FaviconDrawable
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.databinding.ItemEmptyCardBinding
import org.koitharu.kotatsu.databinding.ItemSourceCatalogBinding
import org.koitharu.kotatsu.list.ui.model.ListModel

interface ExtensionActionListener {
	fun onExtensionActionClick(item: SourceCatalogItem.Extension)
	fun onExtensionSettingsClick(item: SourceCatalogItem.Extension)
	fun onExtensionItemClick(item: SourceCatalogItem.Extension)
	fun onExtensionHideClick(item: SourceCatalogItem.Extension)
}


fun sourceCatalogItemExtensionAD(
	listener: ExtensionActionListener,
) = adapterDelegateViewBinding<SourceCatalogItem.Extension, ListModel, ItemSourceCatalogBinding>(
	{ layoutInflater, parent ->
		ItemSourceCatalogBinding.inflate(layoutInflater, parent, false)
	},
) {

	binding.imageViewAdd.setOnClickListener {
		listener.onExtensionActionClick(item)
	}
	binding.root.setOnClickListener {
		listener.onExtensionItemClick(item)
	}
	bind {
		val isInProgress = item.isInProgress
		val isPrivateMode = item.isPrivateMode
		binding.imageViewAdd.isVisible = true
		binding.imageViewAdd.isEnabled = !isInProgress
		binding.imageViewAdd.alpha = if (isInProgress) 0.45f else 1f
		binding.progressIcon.isVisible = isInProgress
		// An enabled sandbox extension is as installed as a system one, so it gets the same
		// settings + hide actions; only the INSTALL/ENABLE rows (nothing there yet) go without.
		val isInstalled = if (isPrivateMode) {
			item.action == SourceCatalogItem.Extension.Action.DISABLE ||
				item.action == SourceCatalogItem.Extension.Action.UPDATE ||
				item.action == SourceCatalogItem.Extension.Action.UNINSTALL
		} else {
			item.action != SourceCatalogItem.Extension.Action.INSTALL
		}
		// Keep the primary install/update/uninstall action visible, but place secondary
		// settings and Explore visibility controls in one accessible overflow menu.
		binding.imageViewMore.isVisible = isInstalled
		binding.imageViewMore.isEnabled = !isInProgress
		binding.imageViewMore.setOnClickListener { anchor ->
			PopupMenu(context, anchor).apply {
				if (item.sourceName != null) menu.add(0, 1, 0, R.string.settings)
				menu.add(0, 2, 1, if (item.isHidden) R.string.unhide else R.string.hide)
				setOnMenuItemClickListener { option ->
					when (option.itemId) {
						1 -> listener.onExtensionSettingsClick(item)
						2 -> listener.onExtensionHideClick(item)
						else -> return@setOnMenuItemClickListener false
					}
					true
				}
			}.show()
		}
		binding.buttonGroupActions.isVisible = true
		// The action button already communicates Update / Install / Enable / Disable.
		// Show a separate badge only when it conveys different information.
		binding.textViewStatus.isVisible = !isInProgress &&
			item.action == SourceCatalogItem.Extension.Action.UNINSTALL
		if (binding.textViewStatus.isVisible) binding.textViewStatus.setText(R.string.installed)
		// The Android View theme may differ from Miyorare's active Modern color scheme.
		// Bridge only in Modern; leave Classic's authored theme and components intact.
		context.miyorareViewPaletteFromPreferences()?.let { palette ->
			binding.root.backgroundTintList = ColorStateList.valueOf(palette.surfaceContainer)
			binding.textViewTitle.setTextColor(palette.onSurface)
			binding.textViewDescription.setTextColor(palette.onSurfaceVariant)
			binding.textViewStatus.backgroundTintList = ColorStateList.valueOf(palette.primaryContainer)
			binding.textViewStatus.setTextColor(palette.onPrimaryContainer)
			binding.imageViewAdd.backgroundTintList = ColorStateList.valueOf(palette.primary)
			binding.imageViewAdd.setTextColor(palette.onPrimary)
			binding.imageViewAdd.iconTint = ColorStateList.valueOf(palette.onPrimary)
			binding.imageViewMore.iconTint = ColorStateList.valueOf(palette.onSurfaceVariant)
		}
		binding.imageViewAdd.setIconResource(item.action.iconRes)
		val actionDescription = if (isInProgress) {
			context.getString(R.string.in_progress)
		} else {
			context.getString(item.action.titleRes)
		}
		binding.imageViewAdd.contentDescription = actionDescription
		binding.imageViewAdd.text = actionDescription
		TooltipCompat.setTooltipText(binding.imageViewAdd, actionDescription)
		binding.textViewTitle.text = item.title
		binding.textViewDescription.text = item.subtitle
		binding.textViewDescription.drawableStart = null
		binding.imageViewIcon.applyExternalSourceStyle(true)
		val sourceIconName = item.sourceIconName
		val iconUrl = item.iconUrl
		// An installed APK extension carries its icon inside itself, so read it from there rather
		// than from the repo's icon url: that url needs the network, and survives only as long as
		// the shared thumbnail disk cache doesn't evict it — which is why installed rows dropped to
		// the letter placeholder at random. Novel plugins are not packages (their icon only ever
		// exists as a url), so they keep the url path.
		val isLnPlugin = sourceIconName?.startsWith("LN_") == true
		if (isInstalled && !isLnPlugin) {
			binding.imageViewIcon.setImageFromUrlAsync(
				url = extensionPackageFaviconUri(item.packageName),
				fallbackName = sourceIconName ?: item.packageName,
			)
		} else if (iconUrl != null) {
			binding.imageViewIcon.setImageFromUrlAsync(
				url = iconUrl,
				fallbackName = sourceIconName ?: item.packageName,
			)
		} else if (sourceIconName != null) {
			binding.imageViewIcon.setImageAsync(MangaSource(sourceIconName))
		} else {
			// Cancel any pending async load left over from a recycled row first; otherwise a
			// late-resolving request can clobber this generated favicon with another extension's icon.
			binding.imageViewIcon.disposeImage()
			binding.imageViewIcon.setImageDrawable(
				FaviconDrawable(
					context = context,
					styleResId = R.style.FaviconDrawable_Small,
					name = item.packageName,
				),
			)
		}
	}
}


fun sourceCatalogItemHintAD() = adapterDelegateViewBinding<SourceCatalogItem.Hint, ListModel, ItemEmptyCardBinding>(
	{ inflater, parent -> ItemEmptyCardBinding.inflate(inflater, parent, false) },
) {

	binding.buttonRetry.isVisible = false

	bind {
		binding.icon.setImageAsync(item.icon)
		binding.textPrimary.setText(item.title)
		binding.textSecondary.setTextAndVisible(item.text)
	}
}

