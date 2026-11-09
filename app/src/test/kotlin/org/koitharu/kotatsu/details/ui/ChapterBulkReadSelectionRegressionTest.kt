package org.koitharu.kotatsu.details.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the shared reader settings and chapter mutation boundaries. Runtime UI remains CI-gated. */
class ChapterBulkReadSelectionRegressionTest {

    @Test
    fun `novel LTR default leaves explicitly saved global and per-book modes intact`() {
        val prefs = source("core/prefs/AppSettings.kt")
        val book = source("reader/ui/epub/EpubBookSettingsStore.kt")
        assertTrue(prefs.contains("""prefs.getString(KEY_EPUB_READING_MODE, "paged_ltr") ?: "paged_ltr" """.trim()))
        assertTrue(prefs.contains("set(value) = prefs.edit { putString(KEY_EPUB_READING_MODE, value) }"))
        assertTrue(book.contains("prefs.getString(key(\"reading_mode\"), global.epubReadingMode)"))
        assertTrue(book.contains("putString(key(\"reading_mode\"), global.epubReadingMode)"))
    }

    @Test
    fun `single swipe and bulk actions share persisted read override and manga reset policy`() {
        val vm = source("details/ui/pager/ChaptersPagesViewModel.kt")
        val progress = source("details/data/ChapterProgressRepository.kt")
        assertTrue(vm.contains("setChaptersReadState(setOf(chapterId), isRead)"))
        assertTrue(vm.contains("chapters.value.filter { it.chapter.id in chapterIds && it.isUnread == isRead }"))
        assertTrue(vm.contains("settings.setChapterReadOverrides(mangaId, settings.getChapterReadOverrides(mangaId) + updates)"))
        assertTrue(vm.contains("chapterReadOverrides.update { it + updates }"))
        assertTrue(vm.contains("if (!isRead && getMangaOrNull()?.isNovelContent != true)"))
        assertTrue(vm.contains("chapterProgressRepository.requestManualUnreadResets("))
        assertTrue(progress.contains("settings.markChapterPageResetPending(mangaId, chapterId, space.dbValue)"))
        assertTrue(progress.contains("processLifecycleScope.launch(Dispatchers.IO)"))
        assertTrue(progress.contains("database.withTransaction"))
        val batch = vm.substringAfter("fun setChaptersReadState(").substringBefore("fun toggleChapterReadState(")
        assertFalse("Marking read must not invent a Reader history cursor", batch.contains("historyRepository.addOrUpdate("))
    }

    @Test
    fun `inactive multi-select cannot shadow the rating editor selected semantics`() {
        val row = source("details/ui/DetailsChapterComponents.kt")
            .substringAfter("internal fun InlineChapterCard(")
        assertTrue(row.contains(".then(if (isSelectionMode) Modifier.semantics { selected = isSelected } else Modifier)"))
        assertTrue(row.contains("modifier = Modifier.semantics {"))
        assertTrue(row.contains("selected = personal.isRated"))
        assertTrue(row.contains("onClick = onPersonalClick"))
    }

    @Test
    fun `both details and manager selection use the same bulk status operation`() {
        val screen = source("details/ui/DetailsExpressiveScreen.kt")
        val activity = source("details/ui/DetailsExpressiveActivity.kt")
        val manager = source("details/ui/pager/chapters/ChaptersSelectionCallback.kt")
        val menu = resource("menu/mode_chapters.xml")
        val swipe = source("details/ui/ChapterReadSwipeCard.kt")
        assertTrue(screen.contains("onLongClick = { selectedChapterIds = selectedChapterIds + chapter.chapter.id }"))
        assertTrue(screen.contains("if (selectedChapterIds.isEmpty()) actions.onChapterClick(chapter)"))
        assertTrue(screen.contains("onChaptersMarkRead(selectedChapterIds)"))
        assertTrue(screen.contains("onChaptersMarkUnread(selectedChapterIds)"))
        assertTrue(activity.contains("onChaptersMarkRead = { ids -> viewModel.setChaptersReadState(ids, true) }"))
        assertTrue(activity.contains("onChaptersMarkUnread = { ids -> viewModel.setChaptersReadState(ids, false) }"))
        assertTrue(manager.contains("viewModel.setChaptersReadState(ids, item.itemId == R.id.action_mark_read)"))
        assertTrue(menu.contains("@+id/action_mark_read"))
        assertTrue(menu.contains("@+id/action_mark_unread"))
        assertTrue(swipe.contains("if (!selectionActive) detectHorizontalDragGestures("))
    }

    private fun source(relativePath: String): String = sequenceOf(
        File("src/main/kotlin/org/koitharu/kotatsu", relativePath),
        File("app/src/main/kotlin/org/koitharu/kotatsu", relativePath),
    ).first(File::isFile).readText()

    private fun resource(relativePath: String): String = sequenceOf(
        File("src/main/res", relativePath),
        File("app/src/main/res", relativePath),
    ).first(File::isFile).readText()
}
