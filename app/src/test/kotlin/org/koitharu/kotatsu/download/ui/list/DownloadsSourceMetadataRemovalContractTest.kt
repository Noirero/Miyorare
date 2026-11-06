package org.koitharu.kotatsu.download.ui.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level regressions supplement Android runtime coverage for the three owner-scoped
 * Downloads changes. No dependency on application resources or a physical filesystem.
 */
class DownloadsSourceMetadataRemovalContractTest {

    @Test
    fun `source icon stays outside the moving text and uses the existing favicon pipeline`() {
        val row = resource("layout/item_download.xml")
        val delegate = source("DownloadItemAD.kt")
        assertEquals(2, Regex("<org\\.koitharu\\.kotatsu\\.core\\.ui\\.image\\.FaviconView").findAll(row).count())
        assertTrue(delegate.contains("icon.setImageAsync(source)"))
        assertTrue(delegate.contains("primaryMarquee?.bind(showPrimarySource)"))
        assertTrue(delegate.contains("secondaryMarquee?.bind(showSecondarySource)"))
        assertTrue(delegate.contains("icon.setImageResource(R.drawable.ic_manga_source)"))
        assertFalse(delegate.contains("binding.imageViewCover.setImageAsync(source"))
    }

    @Test
    fun `marquee reverses with a readable hold and stops on detach or lifecycle stop`() {
        val marquee = source("DownloadMetadataMarquee.kt")
        assertTrue(marquee.contains("Keyframe.ofInt(atEnd, overflow)"))
        assertTrue(marquee.contains("Keyframe.ofInt(1f, 0)"))
        assertTrue(marquee.contains("val holdMs = 800L"))
        assertTrue(marquee.contains("override fun onViewDetachedFromWindow(v: View) = stop()"))
        assertTrue(marquee.contains("override fun onStop(owner: LifecycleOwner) = stop()"))
        assertTrue(marquee.contains("if (overflow < 2)"))
    }

    @Test
    fun `physical deletion runs before history removal and stays in active favourite space`() {
        val viewModel = source("DownloadsViewModel.kt")
        val removal = viewModel.substringAfter("fun remove(ids: Set<Long>, deleteFiles: Boolean = false)")
            .substringBefore("fun removeCompleted()")
        assertTrue(removal.contains("it.workState.isFinished && it.uiAction == null"))
        assertTrue(removal.contains("deleteLocalManga(mangaIds, favouriteSpace)"))
        assertTrue(removal.indexOf("deleteLocalManga(mangaIds, favouriteSpace)") < removal.indexOf("workScheduler.delete(workIds)"))
        assertFalse(removal.contains("deleteLocalManga(mangaIds)"))
        val activity = source("DownloadsActivity.kt")
        assertTrue(activity.contains("showRemovalChoices(controller.snapshot(), mode)"))
        assertTrue(activity.contains("viewModel.remove(selectedIds)"))
        assertTrue(activity.contains("viewModel.remove(selectedIds, deleteFiles = true)"))
    }

    private fun source(name: String): String = path("src/main/kotlin/org/koitharu/kotatsu/download/ui/list/$name")

    private fun resource(name: String): String = path("src/main/res/$name")

    private fun path(relative: String): String = (
        sequenceOf(File(relative), File("app/$relative")).firstOrNull(File::isFile)
            ?: error("Missing production source: $relative")
        ).readText()
}
