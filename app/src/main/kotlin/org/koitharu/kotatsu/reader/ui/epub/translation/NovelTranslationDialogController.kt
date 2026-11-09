package org.koitharu.kotatsu.reader.ui.epub.translation

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.R

/** Selection returned to the reader. Presentation mode is intentionally not part of cache identity. */
data class NovelTranslationSelection(
	val engine: NovelTranslationEngineKind,
	val sourceLanguage: String,
	val targetLanguage: String,
	val style: NovelTranslationStyle,
	val contextAware: Boolean,
	val displayMode: NovelTranslationDisplayMode = NovelTranslationDisplayMode.TRANSLATED_ONLY,
	val scope: NovelTranslationScope = NovelTranslationScope.CHAPTER,
)

data class NovelTranslationSheetState(
	val displayMode: NovelTranslationDisplayMode = NovelTranslationDisplayMode.ORIGINAL,
	val hasCachedTranslation: Boolean = false,
)

private enum class NovelTranslationService {
	ONLINE,
	AI,
}

/**
 * UI-only controller for the Novel translator. It is created only when the user taps Translate, so
 * optional translation providers add no startup or idle Reader work.
 */
class NovelTranslationDialogController(
	private val fragment: Fragment,
	private val httpClient: OkHttpClient,
	private val state: NovelTranslationSheetState = NovelTranslationSheetState(),
	private val mangaId: Long? = null,
	private val pagedReader: Boolean = false,
	private val onRestoreOriginal: () -> Unit,
	private val onShowTranslation: (NovelTranslationDisplayMode) -> Unit = {},
	private val onAutoTranslateChanged: (Boolean) -> Unit = {},
	private val onTranslate: (NovelTranslationSelection) -> Unit,
) {

	private val context get() = fragment.requireContext()
	private val settings by lazy { NovelTranslationSettings(context) }
	private val secrets by lazy { NovelTranslationSecrets(context) }

	fun show() {
		val selectionInvocation = isActiveTextSelectionInvocation()
		val projectionActionsEnabled = state.hasCachedTranslation && !selectionInvocation
		val autoMangaId = mangaId?.takeIf { it > 0L && !selectionInvocation }
		val visuals = NovelTranslationSheetVisuals(context)
		val root = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			background = visuals.panelBackground()
			setPadding(dp(14), dp(12), dp(14), dp(14))
		}
		val scroll = ScrollView(context).apply {
			isFillViewport = false
			addView(root, matchWidth())
		}
		visuals.applyInsetPadding(scroll)

		val header = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = android.view.Gravity.CENTER_VERTICAL
		}
		header.addView(visuals.icon(R.drawable.ic_translate_noirero))
		val heading = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
		heading.addView(visuals.label(context.getString(R.string.novel_translation_title), 21f), matchWidth())
		heading.addView(visuals.label(context.getString(R.string.novel_translation_sheet_subtitle), 12f, subdued = true), matchWidth())
		header.addView(heading, weighted())
		root.addView(header, matchWidth().apply { bottomMargin = dp(10) })

		val viewCard = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			background = visuals.cardBackground()
			setPadding(dp(9), dp(8), dp(9), dp(8))
		}
		viewCard.addView(visuals.label(context.getString(R.string.novel_translation_current_view_label), 13f))
		val segmented = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			background = visuals.segmentBackground()
			setPadding(dp(3), dp(3), dp(3), dp(3))
		}
		val modeButtons = linkedMapOf<NovelTranslationDisplayMode, MaterialButton>()
		val options = listOf(
			NovelTranslationDisplayMode.ORIGINAL to R.string.novel_translation_state_original,
			NovelTranslationDisplayMode.TRANSLATED_ONLY to R.string.novel_translation_segment_translated,
			NovelTranslationDisplayMode.BILINGUAL to R.string.novel_translation_state_bilingual,
		)
		options.forEach { (mode, labelRes) ->
			val button = MaterialButton(context).apply { setText(labelRes); isAllCaps = false }
			segmented.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
				marginStart = dp(2)
				marginEnd = dp(2)
			})
			modeButtons[mode] = button
		}
		viewCard.addView(segmented, matchWidth().apply { topMargin = dp(5) })
		var currentMode = state.displayMode
		var selectedMode = currentMode
		fun updateCurrentState() {
			// Segmented control owns the view choice; avoid a second status row that
			// increases sheet height or contradicts the selection.
			modeButtons.forEach { (mode, button) ->
				visuals.styleButton(button, if (selectedMode == mode) {
					NovelTranslationSheetVisuals.ButtonKind.SEGMENT_SELECTED
				} else NovelTranslationSheetVisuals.ButtonKind.SEGMENT_IDLE)
				button.isSelected = selectedMode == mode
			}
		}
		root.addView(viewCard, matchWidth().apply { bottomMargin = dp(10) })
		updateCurrentState()

		root.addView(visuals.label(context.getString(R.string.novel_translation_language_pair), 14f),
			matchWidth().apply { bottomMargin = dp(4) })
		val languageRow = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = android.view.Gravity.CENTER_VERTICAL
		}
		val sourceButton = MaterialButton(context).apply { isAllCaps = false }
		val targetButton = MaterialButton(context).apply { isAllCaps = false }
		visuals.styleButton(sourceButton, NovelTranslationSheetVisuals.ButtonKind.SELECTOR)
		visuals.styleButton(targetButton, NovelTranslationSheetVisuals.ButtonKind.SELECTOR)
		val arrow = visuals.label("→", 19f).apply { gravity = android.view.Gravity.CENTER }
		languageRow.addView(sourceButton, weighted())
		languageRow.addView(arrow, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
		languageRow.addView(targetButton, weighted())
		root.addView(languageRow, matchWidth().apply { bottomMargin = dp(10) })

		fun refreshLanguageButtons() {
			sourceButton.text = languageLabel(settings.sourceLanguage, source = true)
			targetButton.text = languageLabel(settings.targetLanguage, source = false)
		}
		refreshLanguageButtons()
		sourceButton.setOnClickListener {
			chooseLanguage(source = true) {
				settings.sourceLanguage = it
				refreshLanguageButtons()
			}
		}
		targetButton.setOnClickListener {
			chooseLanguage(source = false) {
				settings.targetLanguage = it
				refreshLanguageButtons()
			}
		}

		val autoModes = NovelAutoTranslationMode.entries
		val autoTranslateSelector = autoMangaId?.let { id ->
			Spinner(context).apply {
				adapter = compactSpinnerAdapter(listOf(
					context.getString(R.string.novel_translation_auto_off),
					context.getString(R.string.novel_translation_auto_chapter),
					context.getString(R.string.novel_translation_auto_page),
				), visuals.onSurface)
				setSelection(autoModes.indexOf(settings.autoTranslateMode(id)))
				minimumHeight = dp(48)
				contentDescription = context.getString(R.string.novel_translation_auto_translate_this_novel)
			}
		}

		val manualScopeSelector = if (!selectionInvocation) Spinner(context).apply {
			adapter = compactSpinnerAdapter(listOf(
				context.getString(if (pagedReader) R.string.novel_translation_scope_page else R.string.novel_translation_scope_visible),
				context.getString(R.string.novel_translation_scope_chapter),
			), visuals.onSurface)
			setSelection(if (settings.manualScope == NovelTranslationScope.PAGE) 0 else 1)
			minimumHeight = dp(48)
		} else null

		val services = NovelTranslationService.entries
		val serviceSpinner = Spinner(context).apply {
			adapter = compactSpinnerAdapter(listOf(
				context.getString(R.string.novel_translation_online),
				context.getString(R.string.novel_translation_ai),
			), visuals.onSurface)
			setSelection(services.indexOf(currentService()).coerceAtLeast(0))
			minimumHeight = dp(48)
		}
		val contextSwitch = MaterialSwitch(context).apply {
			contentDescription = context.getString(R.string.novel_translation_context_aware)
			isChecked = settings.contextAware
		}
		val cards = ArrayList<View>(4)
		if (autoTranslateSelector != null) cards.add(visuals.selectorCard(
			context.getString(R.string.novel_translation_auto_compact),
			R.drawable.ic_auto_fix, autoTranslateSelector,
		))
		if (manualScopeSelector != null) cards.add(visuals.selectorCard(
			context.getString(R.string.novel_translation_scope),
			R.drawable.ic_book_page, manualScopeSelector,
		))
		cards.add(visuals.toggleCard(
			context.getString(R.string.novel_translation_context_aware),
			context.getString(R.string.novel_translation_context_compact_summary),
			R.drawable.ic_auto_stories, contextSwitch,
		))
		cards.add(visuals.selectorCard(
			context.getString(R.string.novel_translation_service),
			R.drawable.ic_language, serviceSpinner,
		))

		root.addView(visuals.optionsGrid(cards), matchWidth().apply { bottomMargin = dp(4) })
		if (autoTranslateSelector != null && !pagedReader) {
			root.addView(visuals.label(context.getString(R.string.novel_translation_auto_page_requires_paged),
				11f, subdued = true), matchWidth().apply { bottomMargin = dp(5) })
		}

		val advancedButton = visuals.card(
			context.getString(R.string.novel_translation_advanced_settings),
			context.getString(R.string.novel_translation_advanced_summary),
			R.drawable.ic_settings,
			control = visuals.icon(R.drawable.ic_chevron_right),
		).apply {
			isClickable = true
			isFocusable = true
			minimumHeight = dp(58)
		}
		root.addView(advancedButton, matchWidth().apply { bottomMargin = dp(6) })

		fun selectedService(): NovelTranslationService =
			selectedServiceFromPosition(serviceSpinner.selectedItemPosition)

		fun selectedEngine(): NovelTranslationEngineKind = when (selectedService()) {
			NovelTranslationService.ONLINE -> NovelTranslationEngineKind.ONLINE
			NovelTranslationService.AI -> NovelTranslationEngineKind.AI
		}

		fun applySelectedService() {
			settings.engine = selectedEngine()
		}

		var updatingAutoSelector = false
		fun updateAutoUi() {
			val id = autoMangaId ?: return
			if (settings.isAutoTranslateEnabled(id) && !settings.isAutoTranslateActive(id)) {
				settings.setAutoTranslateEnabled(id, false)
				onAutoTranslateChanged(false)
			}
			updatingAutoSelector = true
			autoTranslateSelector?.setSelection(autoModes.indexOf(settings.autoTranslateMode(id)))
			updatingAutoSelector = false
		}

		fun updateServiceUi() {
			applySelectedService()
			val remoteAi = selectedService() == NovelTranslationService.AI
			contextSwitch.isEnabled = remoteAi
			contextSwitch.alpha = if (remoteAi) 1f else 0.5f
			advancedButton.isEnabled = remoteAi
			advancedButton.alpha = if (remoteAi) 1f else 0.6f
			updateAutoUi()
		}
		serviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
			override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
				updateServiceUi()
			}
			override fun onNothingSelected(parent: AdapterView<*>?) = Unit
		}
		updateServiceUi()

		autoTranslateSelector?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
			override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
				if (updatingAutoSelector) return
				val novelId = autoMangaId ?: return
				val mode = autoModes[position.coerceIn(autoModes.indices)]
				if (mode == settings.autoTranslateMode(novelId)) return
				if (mode == NovelAutoTranslationMode.PAGE && !pagedReader) {
					updateAutoUi()
					Toast.makeText(context, R.string.novel_translation_auto_page_requires_paged, Toast.LENGTH_LONG).show()
					return
				}
				applySelectedService()
				if (mode != NovelAutoTranslationMode.OFF && selectedService() == NovelTranslationService.AI && !isAiConfigured()) {
					updateAutoUi()
					Toast.makeText(context, R.string.novel_translation_auto_requires_ai_setup, Toast.LENGTH_LONG).show()
					showAiSettings(continueToTranslate = false)
					return
				}
				settings.setAutoTranslateMode(novelId, mode)
				if (mode != NovelAutoTranslationMode.OFF && selectedService() == NovelTranslationService.AI) {
					Toast.makeText(context, context.getString(
						R.string.novel_translation_auto_ai_note, settings.provider.displayName,
					), Toast.LENGTH_LONG).show()
				}
				onAutoTranslateChanged(mode != NovelAutoTranslationMode.OFF)
			}
			override fun onNothingSelected(parent: AdapterView<*>?) = Unit
		}

		contextSwitch.setOnCheckedChangeListener { _, checked -> settings.contextAware = checked }

		advancedButton.setOnClickListener {
			autoMangaId?.let { id ->
				val wasEnabled = settings.isAutoTranslateEnabled(id)
				settings.setAutoTranslateEnabled(id, false)
				if (wasEnabled) onAutoTranslateChanged(false)
				updateAutoUi()
			}
			showAiSettings(continueToTranslate = false, onDismiss = {
				// Advanced settings writes the same preference used by the compact toggle.
				contextSwitch.isChecked = settings.contextAware
				updateServiceUi()
			})
		}

		val primary = MaterialButton(context).apply {
			setIconResource(R.drawable.ic_auto_fix)
		}
		val toggleOriginal = MaterialButton(context).apply {
			setIconResource(R.drawable.ic_eye)
			visibility = if (projectionActionsEnabled) View.VISIBLE else View.GONE
		}
		visuals.styleButton(primary, NovelTranslationSheetVisuals.ButtonKind.PRIMARY)
		visuals.styleButton(toggleOriginal, NovelTranslationSheetVisuals.ButtonKind.SECONDARY)
		root.addView(primary, matchWidth().apply { topMargin = dp(2) })
		root.addView(toggleOriginal, matchWidth().apply { topMargin = dp(7) })

		val dialog = BottomSheetDialog(context)
		fun desiredMode(): NovelTranslationDisplayMode =
			NovelTranslationSheetModePolicy.translatedMode(selectedMode, settings.bilingual)
		fun desiredScope(): NovelTranslationScope =
			if (manualScopeSelector?.selectedItemPosition == 0) NovelTranslationScope.PAGE else NovelTranslationScope.CHAPTER

		fun refreshPrimaryAction() {
			val visibleOnly = desiredScope() == NovelTranslationScope.PAGE
			primary.setText(
				when {
					selectionInvocation -> R.string.novel_translation_translate
					visibleOnly -> R.string.novel_translation_translate_page
					else -> R.string.novel_translation_translate_chapter
				},
			)
			toggleOriginal.setText(
				if (currentMode == NovelTranslationDisplayMode.ORIGINAL) {
					R.string.novel_translation_show_translation
				} else {
					R.string.novel_translation_show_original
				},
			)
		}
		manualScopeSelector?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
			override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
				settings.manualScope = desiredScope()
				refreshPrimaryAction()
			}
			override fun onNothingSelected(parent: AdapterView<*>?) = Unit
		}
		refreshPrimaryAction()

		modeButtons.forEach { (mode, button) ->
			button.setOnClickListener {
				selectedMode = mode
				if (mode != NovelTranslationDisplayMode.ORIGINAL) {
					settings.bilingual = mode == NovelTranslationDisplayMode.BILINGUAL
				}
				if (projectionActionsEnabled) {
					if (mode == NovelTranslationDisplayMode.ORIGINAL) onRestoreOriginal()
					else onShowTranslation(mode)
					currentMode = mode
				}
				updateCurrentState()
				refreshPrimaryAction()
			}
		}

		fun translateNow() {
			if (!selectionInvocation) settings.manualScope = desiredScope()
			applySelectedService()
			val engine = selectedEngine()
			val displayMode = desiredMode()
			if (selectedService() == NovelTranslationService.AI && !isAiConfigured()) {
				showAiSettings(continueToTranslate = true, pendingDisplayMode = displayMode)
				return
			}
			dispatchTranslation(engine, displayMode)
			dialog.dismiss()
		}

		primary.setOnClickListener { translateNow() }
		toggleOriginal.setOnClickListener {
			if (projectionActionsEnabled) {
				if (currentMode == NovelTranslationDisplayMode.ORIGINAL) onShowTranslation(desiredMode())
				else onRestoreOriginal()
				dialog.dismiss()
			}
		}

		dialog.setContentView(scroll)
		dialog.setOnShowListener { dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
		dialog.show()
	}

	/**
	 * The selection ActionMode is still active while its Translate callback opens this sheet.
	 * Projection actions belong to the chapter-level sheet: disabling them here prevents a selected
	 * range in another visible chapter from accidentally toggling the chapter at currentLocator().
	 */
	private fun isActiveTextSelectionInvocation(): Boolean {
		val focused = fragment.view?.findFocus() as? TextView ?: return false
		if (focused.getTag(R.id.tag_epub_selectable_text) != true) return false
		val start = focused.selectionStart
		val end = focused.selectionEnd
		return start >= 0 && end >= 0 && start != end
	}

	private fun selectedServiceFromPosition(position: Int): NovelTranslationService =
		NovelTranslationService.entries[position.coerceIn(NovelTranslationService.entries.indices)]

	private fun currentService(): NovelTranslationService =
		if (settings.engine == NovelTranslationEngineKind.ONLINE) NovelTranslationService.ONLINE else NovelTranslationService.AI

	private fun dispatchTranslation(engine: NovelTranslationEngineKind, displayMode: NovelTranslationDisplayMode) {
		val source = settings.sourceLanguage
		val target = settings.targetLanguage
		if (source != NovelTranslationSettings.LANGUAGE_AUTO && source.equals(target, true)) {
			Toast.makeText(context, R.string.novel_translation_invalid_pair, Toast.LENGTH_SHORT).show()
			return
		}
		settings.engine = engine
		settings.bilingual = displayMode == NovelTranslationDisplayMode.BILINGUAL
		onTranslate(
			NovelTranslationSelection(
				engine = engine,
				sourceLanguage = source,
				targetLanguage = target,
				style = settings.style,
				contextAware = engine == NovelTranslationEngineKind.AI && settings.contextAware,
				displayMode = displayMode,
				scope = if (isActiveTextSelectionInvocation()) NovelTranslationScope.CHAPTER else settings.manualScope,
			),
		)
	}

	private fun isAiConfigured(): Boolean {
		val provider = settings.provider
		return secrets.has(provider) &&
			(provider.protocol == NovelAiProtocol.DEEPL || settings.model(provider).isNotBlank()) &&
			settings.resolvedBaseUrl(provider).isNotBlank()
	}

	private fun languageLabel(code: String, source: Boolean): String {
		val choices = if (source) NovelTranslationLanguages.sourceChoices() else NovelTranslationLanguages.targetChoices()
		val known = choices.firstOrNull { it.code.equals(code, true) }
		return known?.let { "${it.name} (${it.code})" } ?: code
	}

	private fun chooseLanguage(source: Boolean, onSelected: (String) -> Unit) {
		val saved = if (source) settings.sourceLanguage else settings.targetLanguage
		NovelTranslationLanguagePicker(
			context = context,
			source = source,
			selectedCode = saved,
			onSelected = onSelected,
			onCustomLanguage = { showCustomLanguage(source, onSelected) },
		).show()
	}

	private fun showCustomLanguage(source: Boolean, onSelected: (String) -> Unit) {
		val field = TextInputLayout(context).apply {
			hint = context.getString(R.string.novel_translation_language_code)
			setPadding(dp(20), 0, dp(20), 0)
		}
		val input = TextInputEditText(context).apply {
			setSingleLine()
			setText(
				if (source) settings.sourceLanguage.takeUnless { it == NovelTranslationSettings.LANGUAGE_AUTO }.orEmpty()
				else settings.targetLanguage,
			)
		}
		field.addView(input)
		MaterialAlertDialogBuilder(context)
			.setTitle(if (source) R.string.novel_translation_choose_source else R.string.novel_translation_choose_target)
			.setView(field)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				input.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(onSelected)
			}
			.show()
	}

	private fun showAiSettings(
		continueToTranslate: Boolean,
		onDismiss: () -> Unit = {},
		pendingDisplayMode: NovelTranslationDisplayMode = if (settings.bilingual) {
			NovelTranslationDisplayMode.BILINGUAL
		} else {
			NovelTranslationDisplayMode.TRANSLATED_ONLY
		},
	) {
		val root = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(20), dp(8), dp(20), dp(8))
		}
		val scroll = ScrollView(context).apply {
			isFillViewport = true
			addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		}
		val providers = NovelAiProvider.entries
		val initialProvider = settings.provider
		val providerSpinner = Spinner(context).apply {
			adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, providers.map { it.displayName })
			setSelection(providers.indexOf(initialProvider).coerceAtLeast(0))
		}
		root.addLabeledView(R.string.novel_translation_provider, providerSpinner)

		val keyField = TextInputLayout(context).apply { hint = context.getString(R.string.novel_translation_api_key) }
		val keyInput = TextInputEditText(context).apply {
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
			setSingleLine()
		}
		keyField.addView(keyInput)
		root.addView(keyField, matchWidth())
		root.addView(TextView(context).apply {
			setText(R.string.novel_translation_api_key_hint)
			setPadding(0, 0, 0, dp(8))
		})

		val modelField = TextInputLayout(context).apply { hint = context.getString(R.string.novel_translation_model) }
		val modelInput = TextInputEditText(context).apply { setSingleLine() }
		modelField.addView(modelInput)
		root.addView(modelField, matchWidth())

		val loadModels = MaterialButton(context).apply { setText(R.string.novel_translation_model_discover) }
		root.addView(loadModels, matchWidth())

		val baseField = TextInputLayout(context).apply { hint = context.getString(R.string.novel_translation_base_url) }
		val baseInput = TextInputEditText(context).apply { setSingleLine() }
		baseField.addView(baseInput)
		root.addView(baseField, matchWidth())
		root.addView(TextView(context).apply { setText(R.string.novel_translation_base_url_hint) })

		val styles = NovelTranslationStyle.entries
		val styleSpinner = Spinner(context).apply {
			adapter = ArrayAdapter(
				context,
				android.R.layout.simple_spinner_dropdown_item,
				listOf(
					context.getString(R.string.novel_translation_style_natural),
					context.getString(R.string.novel_translation_style_literal),
					context.getString(R.string.novel_translation_style_novel),
				),
			)
			setSelection(styles.indexOf(settings.style).coerceAtLeast(0))
		}
		root.addLabeledView(R.string.novel_translation_style, styleSpinner)

		val contextSwitch = MaterialSwitch(context).apply {
			setText(R.string.novel_translation_context_aware)
			isChecked = settings.contextAware
		}
		root.addView(contextSwitch, matchWidth())
		root.addView(TextView(context).apply {
			setText(R.string.novel_translation_context_aware_summary)
			setPadding(0, 0, 0, dp(8))
		})

		val deeplSwitch = MaterialSwitch(context).apply {
			setText(R.string.novel_translation_deepl_free)
			isChecked = settings.deeplFreeApi
		}
		root.addView(deeplSwitch, matchWidth())
		root.addView(TextView(context).apply {
			setText(R.string.novel_translation_privacy_note)
			setPadding(0, dp(8), 0, 0)
		})

		fun selectedProvider(): NovelAiProvider =
			providers[providerSpinner.selectedItemPosition.coerceIn(providers.indices)]

		fun applyProviderUi(provider: NovelAiProvider) {
			modelInput.setText(settings.model(provider))
			baseInput.setText(settings.baseUrlOverride(provider))
			keyInput.text?.clear()
			keyInput.hint = if (secrets.has(provider)) "••••••••" else null
			val usesModel = provider.protocol != NovelAiProtocol.DEEPL
			modelField.isEnabled = usesModel
			modelInput.isEnabled = usesModel
			loadModels.isEnabled = usesModel && provider.supportsModelDiscovery
			styleSpinner.isEnabled = usesModel
			contextSwitch.isEnabled = usesModel
			deeplSwitch.isEnabled = provider == NovelAiProvider.DEEPL
		}

		providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
			override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
				applyProviderUi(providers[position.coerceIn(providers.indices)])
			}
			override fun onNothingSelected(parent: AdapterView<*>?) = Unit
		}
		applyProviderUi(initialProvider)

		fun saveFields(): NovelAiProvider {
			val provider = selectedProvider()
			settings.provider = provider
			settings.setModel(provider, modelInput.text?.toString().orEmpty())
			settings.setBaseUrlOverride(provider, baseInput.text?.toString().orEmpty())
			if (provider.protocol != NovelAiProtocol.DEEPL) {
				settings.style = styles[styleSpinner.selectedItemPosition.coerceIn(styles.indices)]
				settings.contextAware = contextSwitch.isChecked
			}
			settings.deeplFreeApi = deeplSwitch.isChecked
			keyInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { secrets.put(provider, it) }
			return provider
		}

		loadModels.setOnClickListener {
			val provider = saveFields()
			if (!provider.supportsModelDiscovery) {
				Toast.makeText(context, R.string.novel_translation_model_empty, Toast.LENGTH_LONG).show()
				return@setOnClickListener
			}
			if (!secrets.has(provider)) {
				Toast.makeText(context, R.string.novel_translation_ai_setup_required, Toast.LENGTH_LONG).show()
				return@setOnClickListener
			}
			if (settings.resolvedBaseUrl(provider).isBlank()) {
				Toast.makeText(context, R.string.novel_translation_base_url_required, Toast.LENGTH_LONG).show()
				return@setOnClickListener
			}
			providerSpinner.isEnabled = false
			loadModels.isEnabled = false
			loadModels.setText(R.string.novel_translation_model_loading)
			fragment.viewLifecycleOwner.lifecycleScope.launch {
				val result = runCatching { NovelAiTranslationEngine(httpClient, settings, secrets).listModels() }
				providerSpinner.isEnabled = true
				loadModels.setText(R.string.novel_translation_model_discover)
				loadModels.isEnabled = provider.supportsModelDiscovery
				val models = result.getOrNull().orEmpty()
				if (models.isEmpty()) {
					Toast.makeText(
						context,
						result.exceptionOrNull()?.localizedMessage ?: context.getString(R.string.novel_translation_model_empty),
						Toast.LENGTH_LONG,
					).show()
				} else {
					MaterialAlertDialogBuilder(context)
						.setTitle(R.string.novel_translation_model)
						.setItems(models.toTypedArray()) { _, which -> modelInput.setText(models[which]) }
						.setNegativeButton(android.R.string.cancel, null)
						.show()
				}
			}
		}

		val dialog = MaterialAlertDialogBuilder(context)
			.setTitle(R.string.novel_translation_ai_setup)
			.setView(scroll)
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.novel_translation_clear_key, null)
			.setPositiveButton(R.string.novel_translation_save, null)
			.create()
		dialog.setOnDismissListener { onDismiss() }
		dialog.setOnShowListener {
			dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
				val provider = selectedProvider()
				secrets.clear(provider)
				keyInput.text?.clear()
				keyInput.hint = null
				Toast.makeText(context, R.string.novel_translation_key_cleared, Toast.LENGTH_SHORT).show()
			}
			dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				val provider = saveFields()
				if (!secrets.has(provider)) {
					Toast.makeText(context, R.string.novel_translation_ai_setup_required, Toast.LENGTH_LONG).show()
					return@setOnClickListener
				}
				if (settings.resolvedBaseUrl(provider).isBlank()) {
					Toast.makeText(context, R.string.novel_translation_base_url_required, Toast.LENGTH_LONG).show()
					return@setOnClickListener
				}
				if (provider.protocol != NovelAiProtocol.DEEPL && settings.model(provider).isBlank()) {
					Toast.makeText(context, R.string.novel_translation_ai_setup_required, Toast.LENGTH_LONG).show()
					return@setOnClickListener
				}
				dialog.dismiss()
				if (continueToTranslate) dispatchTranslation(NovelTranslationEngineKind.AI, pendingDisplayMode)
			}
		}
		dialog.show()
	}

	private fun LinearLayout.addLabeledView(labelRes: Int, view: View) {
		addView(TextView(context).apply { setText(labelRes); setPadding(0, dp(8), 0, dp(4)) }, matchWidth())
		addView(view, matchWidth())
	}

	private fun compactSpinnerAdapter(labels: List<String>, foreground: Int): ArrayAdapter<String> =
		object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, labels) {
			override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
				super.getView(position, convertView, parent).also { view ->
					(view as? TextView)?.apply {
						setSingleLine(false)
						maxLines = 3
						ellipsize = null
						textSize = 12f
						setTextColor(foreground)
					}
				}
		}.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

	private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
	private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
	private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
