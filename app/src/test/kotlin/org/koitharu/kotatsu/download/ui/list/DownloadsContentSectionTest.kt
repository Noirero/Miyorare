package org.koitharu.kotatsu.download.ui.list

import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsContentSectionTest {

	@Test
	fun `active section partitions grouped rows and bulk work without mutating other domain`() {
		val vm = source("download/ui/list/DownloadsViewModel.kt")
		val activity = source("download/ui/list/DownloadsActivity.kt")
		val layout = resource("layout/activity_downloads.xml")
		assertTrue(vm.contains("val visibleWorks = combine(works, selectedSectionState)"))
		assertTrue(vm.contains("rows?.filter { row -> row.manga?.matchesMangaNovelSection(section) == true }"))
		assertTrue(vm.contains("it.isInActiveSection() && it.canPause"))
		assertTrue(vm.contains("it.isInActiveSection() && it.canResume"))
		assertTrue(vm.contains("it.isInActiveSection() && it.canCancel"))
		assertTrue(vm.contains("it.isInActiveSection() && it.workState.isFinished"))
		assertTrue(vm.contains("val items = visibleWorks.transformLatest"))
		assertTrue(vm.contains("fun allIds(): Set<Long> = visibleWorks.value"))
		assertTrue(activity.contains("selectionController.clear()"))
		assertTrue(layout.contains("view_manga_novel_toggle"))
	}

	private fun source(path: String): String = sequenceOf(
		java.io.File("src/main/kotlin/org/koitharu/kotatsu", path),
		java.io.File("app/src/main/kotlin/org/koitharu/kotatsu", path),
	).first(java.io.File::isFile).readText()

	private fun resource(path: String): String = sequenceOf(
		java.io.File("src/main/res", path),
		java.io.File("app/src/main/res", path),
	).first(java.io.File::isFile).readText()
}
