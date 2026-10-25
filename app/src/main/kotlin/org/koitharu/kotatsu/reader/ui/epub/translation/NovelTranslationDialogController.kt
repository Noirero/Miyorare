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
	val displayMode: NovelTranslationDisplayMode = NovelTranslationDisplayMode.BILINGUAL,
)

data class NovelTranslationSheetState(
	val displayMode: NovelTranslationDisplayMode = NovelTranslationDisplayMode.ORIGINAL,
	val hasCachedTranslation: Boolean = false,
)

/**
 * UI-only controller for the Novel translator. It is created only when the user taps Translate, so
 * optional translation providers add no startup or idle Reader work.
 */
class NovelTranslationDialogController(
	private val fragment: Fragment,
	private val httpClient: OkHttpClient,
	private val state: NovelTranslationSheetState = NovelTranslationSheetState(),
	private val mangaId: Long? = null,
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
		val root = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(20), dp(12), dp(20), dp(20))
		}
		val scroll = ScrollView(context).apply {
			isFillViewport = true
			addView(root, matchWidth())
		}
		root.addView(TextView(context).apply {
			setText(R.string.novel_translation_title)
			setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
			setPadding(0, 0, 0, dp(12))
		}, matchWidth())

		var currentMode = state.displayMode
		val currentState = TextView(context)
		fun updateCurrentState() {
			val label = when (currentMode) {
				NovelTranslationDisplayMode.ORIGINAL -> R.string.novel_translation_state_original
				NovelTranslationDisplayMode.BILINGUAL -> R.string.novel_translation_state_bilingual
				NovelTranslationDisplayMode.TRANSLATED_ONLY -> R.string.novel_translation_state_translated_only
			}
			currentState.text = context.getString(R.string.novel_translation_current_state, context.getString(label))
		}
		updateCurrentState()
		root.addView(currentState, matchWidth())

		val languageRow = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = android.view.Gravity.CENTER_VERTICAL
		}
		val sourceButton = MaterialButton(context).apply { isAllCaps = false }
		val targetButton = MaterialButton(context).apply { isAllCaps = false }
		val arrow = TextView(context).apply {
			text = "→"
			gravity = android.view.Gravity.CENTER
		}
		languageRow.addView(sourceButton, weighted())
		languageRow.addView(arrow, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
		languageRow.addView(targetButton, weighted())
		root.addLabeledView(R.string.novel_translation_source, languageRow)

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

		val autoTranslateSwitch = autoMangaId?.let { id ->
			MaterialSwitch(context).apply {
				setText(R.string.novel_translation_auto_translate_this_novel)
				isChecked = settings.isAutoTranslateActive(id)
			}
		}
		autoTranslateSwitch?.let { root.addView(it, matchWidth()) }
		val autoTranslateAiNote = autoMangaId?.let {
			TextView(context).apply {
				setPadding(0, 0, 0, dp(8))
				visibility = View.GONE
			}
		}
		autoTranslateAiNote?.let { root.addView(it, matchWidth()) }

		val bilingualSwitch = MaterialSwitch(context).apply {
			setText(R.string.novel_translation_bilingual_view)
			isChecked = when (currentMode) {
				NovelTranslationDisplayMode.BILINGUAL -> true
				NovelTranslationDisplayMode.TRANSLATED_ONLY -> false
				NovelTranslationDisplayMode.ORIGINAL -> settings.bilingual
			}
		}
		root.addView(bilingualSwitch, matchWidth())

		val engines = NovelTranslationEngineKind.entries
		val serviceSpinner = Spinner(context).apply {
			adapter = ArrayAdapter(
				context,
				android.R.layout.simple_spinner_dropdown_item,
				listOf(
					context.getString(R.string.novel_translation_online),
					"${context.getString(R.string.novel_translation_ai)} — ${settings.provider.displayName}",
				),
			)
			setSelection(engines.indexOf(settings.engine).coerceAtLeast(0))
		}
		root.addLabeledView(R.string.novel_translation_service, serviceSpinner)

		val advancedButton = MaterialButton(context).apply {
			setText(R.string.novel_translation_advanced_settings)
			isAllCaps = false
		}
		root.addView(advancedButton, matchWidth())

		val privacyNote = TextView(context).apply {
			setText(R.string.novel_translation_privacy_note)
			setPadding(0, dp(4), 0, dp(8))
		}
		root.addView(privacyNote, matchWidth())

		fun selectedEngine(): NovelTranslationEngineKind =
			engines[serviceSpinner.selectedItemPosition.coerceIn(engines.indices)]

		var updatingAutoSwitch = false
		fun updateAutoUi() {
			val id = autoMangaId ?: return
			if (settings.isAutoTranslateEnabled(id) && !settings.isAutoTranslateActive(id)) {
				settings.setAutoTranslateEnabled(id, false)
			}
			updatingAutoSwitch = true
			autoTranslateSwitch?.isChecked = settings.isAutoTranslateActive(id)
			updatingAutoSwitch = false
			val ai = selectedEngine() == NovelTranslationEngineKind.AI
			autoTranslateAiNote?.visibility = if (ai) View.VISIBLE else View.GONE
			if (ai) {
				autoTranslateAiNote?.text = context.getString(
					R.string.novel_translation_auto_ai_note,
					settings.provider.displayName,
				)
			}
		}

		fun updateServiceUi() {
			val ai = selectedEngine() == NovelTranslationEngineKind.AI
			advancedButton.isEnabled = ai
			privacyNote.visibility = if (ai) View.VISIBLE else View.GONE
			updateAutoUi()
		}
		serviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
			override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
				settings.engine = engines[position.coerceIn(engines.indices)]
				updateServiceUi()
			}
			override fun onNothingSelected(parent: AdapterView<*>?) = Unit
		}
		updateServiceUi()

		autoTranslateSwitch?.setOnCheckedChangeListener { button, checked ->
			if (updatingAutoSwitch) return@setOnCheckedChangeListener
			val id = autoMangaId ?: return@setOnCheckedChangeListener
			settings.engine = selectedEngine()
			if (checked && settings.engine == NovelTranslationEngineKind.AI && !isAiConfigured()) {
				settings.setAutoTranslateEnabled(id, false)
				updatingAutoSwitch = true
				button.isChecked = false
				updatingAutoSwitch = false
				Toast.makeText(context, R.string.novel_translation_auto_requires_ai_setup, Toast.LENGTH_LONG).show()
				showAiSettings(continueToTranslate = false)
				return@setOnCheckedChangeListener
			}
			settings.setAutoTranslateEnabled(id, checked)
			onAutoTranslateChanged(checked)
		}

		advancedButton.setOnClickListener {
			autoMangaId?.let { id ->
				settings.setAutoTranslateEnabled(id, false)
				updateAutoUi()
			}
			showAiSettings(continueToTranslate = false)
		}

		val primary = MaterialButton(context).apply { isAllCaps = false }
		val translateAgain = MaterialButton(context).apply {
			setText(R.string.novel_translation_translate_again)
			isAllCaps = false
			visibility = if (projectionActionsEnabled) View.VISIBLE else View.GONE
		}
		root.addView(primary, matchWidth())
		root.addView(translateAgain, matchWidth())

		val dialog = BottomSheetDialog(context)
		fun desiredMode(): NovelTranslationDisplayMode = if (bilingualSwitch.isChecked) {
			NovelTranslationDisplayMode.BILINGUAL
		} else {
			NovelTranslationDisplayMode.TRANSLATED_ONLY
		}
		fun refreshPrimaryAction() {
			primary.setText(
				when {
					projectionActionsEnabled && currentMode != NovelTranslationDisplayMode.ORIGINAL -> R.string.novel_translation_show_original
					projectionActionsEnabled -> R.string.novel_translation_show_translation
					else -> R.string.novel_translation_translate
				},
			)
		}
		refreshPrimaryAction()

		bilingualSwitch.setOnCheckedChangeListener { _, checked ->
			settings.bilingual = checked
			if (projectionActionsEnabled && currentMode != NovelTranslationDisplayMode.ORIGINAL) {
				currentMode = if (checked) NovelTranslationDisplayMode.BILINGUAL else NovelTranslationDisplayMode.TRANSLATED_ONLY
				onShowTranslation(currentMode)
				updateCurrentState()
				refreshPrimaryAction()
			}
		}

		fun translateNow() {
			val engine = selectedEngine()
			val displayMode = desiredMode()
			if (engine == NovelTranslationEngineKind.AI && !isAiConfigured()) {
				showAiSettings(continueToTranslate = true, pendingDisplayMode = displayMode)
				return
			}
			dispatchTranslation(engine, displayMode)
			dialog.dismiss()
		}

		primary.setOnClickListener {
			when {
				projectionActionsEnabled && currentMode != NovelTranslationDisplayMode.ORIGINAL -> {
					onRestoreOriginal()
					dialog.dismiss()
				}
				projectionActionsEnabled -> {
					onShowTranslation(desiredMode())
					dialog.dismiss()
				}
				else -> translateNow()
			}
		}
		translateAgain.setOnClickListener { translateNow() }

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
		val choices = if (source) NovelTranslationLanguages.sourceChoices() else NovelTranslationLanguages.targetChoices()
		val labels = choices.map { "${it.name} (${it.code})" }.toMutableList().apply {
			add(context.getString(R.string.novel_translation_custom_language))
		}.toTypedArray()
		val saved = if (source) settings.sourceLanguage else settings.targetLanguage
		val checked = choices.indexOfFirst { it.code.equals(saved, true) }.coerceAtLeast(0)
		MaterialAlertDialogBuilder(context)
			.setTitle(if (source) R.string.novel_translation_choose_source else R.string.novel_translation_choose_target)
			.setSingleChoiceItems(labels, checked) { dialog, which ->
				dialog.dismiss()
				if (which == choices.size) showCustomLanguage(source, onSelected) else onSelected(choices[which].code)
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
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
		val providerSpinner = Spinner(context).apply {
			adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, providers.map { it.displayName })
			setSelection(providers.indexOf(settings.provider).coerceAtLeast(0))
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
		applyProviderUi(settings.provider)

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

	private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
	private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
	private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
