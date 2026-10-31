package org.koitharu.kotatsu.download.domain.maintenance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadMaintenanceAbsenceTest {
	@get:Rule val temp = TemporaryFolder()

	@Test fun missingContainerInsideReadableRootIsStale() {
		val root = temp.newFolder("downloads")
		assertTrue(DownloadMaintenanceAbsence.isConfirmedMissing(
			File(root, "deleted").path, emptySet(), listOf(root),
		))
	}

	@Test fun existingCopyIsNeverRemoved() {
		val root = temp.newFolder("downloads")
		val copy = File(root, "chapter.cbz").apply { writeText("chapter") }
		assertFalse(DownloadMaintenanceAbsence.isConfirmedMissing(copy.path, emptySet(), listOf(root)))
	}

	@Test fun scannedCopyIsNotRemovedEvenIfMovedAfterScan() {
		val root = temp.newFolder("downloads")
		val path = File(root, "moved").canonicalPath
		assertFalse(DownloadMaintenanceAbsence.isConfirmedMissing(path, setOf(path), listOf(root)))
	}

	@Test fun inaccessibleDestinationIsNotTreatedAsEmpty() {
		val unavailable = File(temp.root, "unmounted")
		assertFalse(DownloadMaintenanceAbsence.isConfirmedMissing(
			File(unavailable, "manga").path, emptySet(), listOf(unavailable),
		))
	}

	@Test fun unrelatedPathIsNotReconciled() {
		val root = temp.newFolder("downloads")
		val other = temp.newFolder("external")
		assertFalse(DownloadMaintenanceAbsence.isConfirmedMissing(
			File(other, "missing").path, emptySet(), listOf(root),
		))
	}
}
