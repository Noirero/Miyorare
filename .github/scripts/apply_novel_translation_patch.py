from pathlib import Path

path = Path("app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/epub/EpubReaderFragment.kt")
text = path.read_text(encoding="utf-8")

import_anchor = "import org.koitharu.kotatsu.reader.ui.ReaderState\n"
translation_imports = """import org.koitharu.kotatsu.reader.ui.epub.translation.MiyorareOnlineTranslationEngine
import org.koitharu.kotatsu.reader.ui.epub.translation.NovelAiTranslationEngine
import org.koitharu.kotatsu.reader.ui.epub.translation.NovelTranslationDialogController
import org.koitharu.kotatsu.reader.ui.epub.translation.NovelTranslationEngine
import org.koitharu.kotatsu.reader.ui.epub.translation.NovelTranslationEngineKind
import org.koitharu.kotatsu.reader.ui.epub.translation.NovelTranslationRequest
import org.koitharu.kotatsu.reader.ui.epub.translation.NovelTranslationSecrets
import org.koitharu.kotatsu.reader.ui.epub.translation.NovelTranslationSelection
import org.koitharu.kotatsu.reader.ui.epub.translation.NovelTranslationSettings
"""
if "import org.koitharu.kotatsu.reader.ui.epub.translation.NovelTranslationDialogController" not in text:
    if import_anchor not in text:
        raise SystemExit("ReaderState import anchor not found")
    text = text.replace(import_anchor, import_anchor + translation_imports, 1)

start_marker = "\tfun showTranslationDialog() {"
end_marker = "\tprivate fun showTranslationStatusDialog("
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0 or end <= start:
    raise SystemExit("Translation method block markers not found")

new_block = r'''	fun showTranslationDialog() {
		if (chapters.isEmpty()) return
		NovelTranslationDialogController(
			fragment = this,
			httpClient = httpClient,
			onRestoreOriginal = ::restoreOriginalTranslation,
			onTranslate = ::translateCurrentChapter,
		).show()
	}

	private fun translateCurrentChapter(selection: NovelTranslationSelection) {
		val locator = currentLocator()
		val chapter = chapters.getOrNull(locator.chapter) ?: return
		val original = translationOriginals[chapter.id] ?: chapter.content ?: return
		translationOriginals.putIfAbsent(chapter.id, SpannedString(original))
		cancelActiveTranslation(incrementGeneration = false)
		val generation = ++translationGeneration
		val engine: NovelTranslationEngine = when (selection.engine) {
			NovelTranslationEngineKind.ONLINE -> MiyorareOnlineTranslationEngine(httpClient)
			NovelTranslationEngineKind.AI -> NovelAiTranslationEngine(
				httpClient = httpClient,
				settings = NovelTranslationSettings(requireContext()),
				secrets = NovelTranslationSecrets(requireContext()),
			)
		}
		setChapterLoading(true)
		showTranslationStatusDialog(generation, selection.sourceLanguage, selection.targetLanguage)
		val chunks = splitTranslationText(original)
		updateTranslationStatus(getString(R.string.epub_translate_translating_progress, 0, chunks.size))
		translationJob = viewLifecycleOwner.lifecycleScope.launch {
			val translated = runCatching {
				withContext(Dispatchers.IO) {
					val result = ArrayList<EpubTranslatedChunk>(chunks.size)
					chunks.forEachIndexed { index, chunk ->
						if (generation != translationGeneration) return@withContext null
						val translatedText = if (chunk.text.isBlank()) {
							chunk.text
						} else {
							engine.translate(
								NovelTranslationRequest(
									text = chunk.text,
									sourceLanguage = selection.sourceLanguage,
									targetLanguage = selection.targetLanguage,
									style = selection.style,
									contextAware = selection.contextAware,
									beforeContext = if (selection.contextAware) chunks.getOrNull(index - 1)?.text.orEmpty() else "",
									afterContext = if (selection.contextAware) chunks.getOrNull(index + 1)?.text.orEmpty() else "",
								),
							)
						}
						result += EpubTranslatedChunk(chunk, translatedText)
						withContext(Dispatchers.Main) {
							if (generation == translationGeneration && isAdded) {
								updateTranslationStatus(getString(R.string.epub_translate_translating_progress, index + 1, chunks.size))
							}
						}
					}
					buildTranslatedSpanned(original, result)
				}
			}.getOrElse { error ->
				if (generation == translationGeneration && isAdded) {
					finishTranslationUi()
					val detail = error.localizedMessage?.takeIf { it.isNotBlank() }
					Toast.makeText(requireContext(), detail ?: getString(R.string.epub_translate_failed), Toast.LENGTH_LONG).show()
				}
				return@launch
			}
			if (!isAdded || generation != translationGeneration || translated == null) return@launch
			finishTranslationUi()
			val beforeLength = chapter.text.length.coerceAtLeast(1)
			chapter.content = translated
			val mappedOffset = (translated.length * (locator.offset.toDouble() / beforeLength)).toInt().coerceIn(0, translated.length)
			refreshReader(Locator(locator.chapter, mappedOffset))
			Toast.makeText(requireContext(), R.string.epub_translate_done, Toast.LENGTH_SHORT).show()
		}
	}

'''
text = text[:start] + new_block + text[end:]

old_constants = '''\t\tprivate const val ONLINE_TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single"\n\t\tprivate val TRANSLATION_PAIRS = listOf(\n\t\t\t"en" to "id", "ja" to "id", "ja" to "en", "ko" to "id", "ko" to "en", "zh-CN" to "id", "zh-CN" to "en",\n\t\t)\n'''
if old_constants in text:
    text = text.replace(old_constants, "", 1)

path.write_text(text, encoding="utf-8")
print("Novel translation reader integration patch applied")
