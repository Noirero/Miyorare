package org.koitharu.kotatsu.reader.ui.epub.translation

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.R

/** Selection returned to the reader. */
data class NovelTranslationSelection(
	val engine: NovelTranslationEngineKind,
	val sourceLanguage: String,
	val targetLanguage: String,
	val style: NovelTranslationStyle,
	val contextAware: Boolean,
)

/**
 * UI-only controller for the Novel translator. It is created only when the user taps Translate, so
 * the optional AI feature adds no startup or idle Reader work.
 */
class NovelTranslationDialogController(
	private val fragment: Fragment,
	private val httpClient: OkHttpClient,
	private val onRestoreOriginal: () -> Unit,
	private val onTranslate: (NovelTranslationSelection) -> Unit,
) {

	private val context get() = fragment.requireContext()
	private val settings by lazy { NovelTranslationSettings(context) }
	private val secrets by lazy { NovelTranslationSecrets(context) }

	fun show() {
		val labels = arrayOf(
			context.getString(R.string.novel_translation_restore_original),
			"${context.getString(R.string.novel_translation_online)}\n${context.getString(R.string.novel_translation_online_summary)}",
			"${context.getString(R.string.novel_translation_ai)}\n${context.getString(R.string.novel_translation_ai_summary)}",
			context.getString(R.string.novel_translation_configure),
		)
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.novel_translation_engine)
			.setItems(labels) { _, which ->
				when (which) {
					0 -> onRestoreOriginal()
					1 -> chooseLanguages(NovelTranslationEngineKind.ONLINE)
					2 -> {
						val provider = settings.provider
						if (!secrets.has(provider) ||
							(provider.protocol != NovelAiProtocol.DEEPL && settings.model.isBlank()) ||
							settings.resolvedBaseUrl(provider).isBlank()
						) {
							showAiSettings(continueToTranslate = true)
						} else {
							chooseLanguages(NovelTranslationEngineKind.AI)
						}
					}
					3 -> showAiSettings(continueToTranslate = false)
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun chooseLanguages(engine: NovelTranslationEngineKind) {
		val sources = NovelTranslationLanguages.sourceChoices()
		val labels = sources.map { "${it.name} (${it.code})" }.toMutableList().apply {
			add(context.getString(R.string.novel_translation_custom_language))
		}.toTypedArray()
		val saved = settings.sourceLanguage
		val checked = sources.indexOfFirst { it.code.equals(saved, true) }.coerceAtLeast(0)
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.novel_translation_choose_source)
			.setSingleChoiceItems(labels, checked) { dialog, which ->
				dialog.dismiss()
				if (which == sources.size) {
					showCustomLanguage(source = true, engine = engine)
				} else {
					settings.sourceLanguage = sources[which].code
					chooseTarget(engine, sources[which].code)
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun chooseTarget(engine: NovelTranslationEngineKind, source: String) {
		val targets = NovelTranslationLanguages.targetChoices()
		val labels = targets.map { "${it.name} (${it.code})" }.toMutableList().apply {
			add(context.getString(R.string.novel_translation_custom_language))
		}.toTypedArray()
		val saved = settings.targetLanguage
		val checked = targets.indexOfFirst { it.code.equals(saved, true) }.coerceAtLeast(0)
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.novel_translation_choose_target)
			.setSingleChoiceItems(labels, checked) { dialog, which ->
				dialog.dismiss()
				if (which == targets.size) {
					showCustomLanguage(source = false, engine = engine, selectedSource = source)
				} else {
					finishLanguageSelection(engine, source, targets[which].code)
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun showCustomLanguage(
		source: Boolean,
		engine: NovelTranslationEngineKind,
		selectedSource: String = settings.sourceLanguage,
	) {
		val field = TextInputLayout(context).apply {
			hint = context.getString(R.string.novel_translation_language_code)
			setPadding(dp(20), 0, dp(20), 0)
		}
		val input = TextInputEditText(context).apply {
			setSingleLine()
			setText(if (source) settings.sourceLanguage.takeUnless { it == NovelTranslationSettings.LANGUAGE_AUTO }.orEmpty() else settings.targetLanguage)
		}
		field.addView(input)
		MaterialAlertDialogBuilder(context)
			.setTitle(if (source) R.string.novel_translation_choose_source else R.string.novel_translation_choose_target)
			.setView(field)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				val code = input.text?.toString()?.trim().orEmpty()
				if (code.isBlank()) return@setPositiveButton
				if (source) {
					settings.sourceLanguage = code
					chooseTarget(engine, code)
				} else {
					finishLanguageSelection(engine, selectedSource, code)
				}
			}
			.show()
	}

	private fun finishLanguageSelection(engine: NovelTranslationEngineKind, source: String, target: String) {
		if (source != NovelTranslationSettings.LANGUAGE_AUTO && source.equals(target, true)) {
			Toast.makeText(context, R.string.novel_translation_invalid_pair, Toast.LENGTH_SHORT).show()
			return
		}
		settings.engine = engine
		settings.sourceLanguage = source
		settings.targetLanguage = target
		onTranslate(
			NovelTranslationSelection(
				engine = engine,
				sourceLanguage = source,
				targetLanguage = target,
				style = settings.style,
				contextAware = engine == NovelTranslationEngineKind.AI && settings.contextAware,
			),
		)
	}

	private fun showAiSettings(continueToTranslate: Boolean) {
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

		val loadModels = Button(context).apply { setText(R.string.novel_translation_model_discover) }
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
					Toast.makeText(context, result.exceptionOrNull()?.localizedMessage ?: context.getString(R.string.novel_translation_model_empty), Toast.LENGTH_LONG).show()
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
				if (continueToTranslate) chooseLanguages(NovelTranslationEngineKind.AI)
			}
		}
		dialog.show()
	}

	private fun LinearLayout.addLabeledView(labelRes: Int, view: View) {
		addView(TextView(context).apply { setText(labelRes); setPadding(0, dp(8), 0, dp(4)) }, matchWidth())
		addView(view, matchWidth())
	}

	private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
	private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
