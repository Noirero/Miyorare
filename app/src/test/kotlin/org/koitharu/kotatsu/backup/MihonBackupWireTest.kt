package org.koitharu.kotatsu.backup

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.backup.model.MihonBackup
import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupManga
import org.koitharu.kotatsu.backup.model.MihonBackupSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MihonBackupWireTest {

	@Test
	fun `streamed top level fields remain Mihon protobuf compatible`() = runTest {
		val output = ByteArrayOutputStream()
		MihonBackupWire.writeMessage(
			output,
			MihonBackupWire.FIELD_MANGA,
			serializer<MihonBackupManga>(),
			MihonBackupManga(source = 123L, url = "/manga/1", title = "One"),
		)
		MihonBackupWire.writeMessage(
			output,
			MihonBackupWire.FIELD_CATEGORY,
			serializer<MihonBackupCategory>(),
			MihonBackupCategory(name = "Library", order = 0L, id = 7L),
		)
		MihonBackupWire.writeMessage(
			output,
			MihonBackupWire.FIELD_SOURCE,
			serializer<MihonBackupSource>(),
			MihonBackupSource(name = "Fixture", sourceId = 123L),
		)

		val decoded = ProtoBuf.decodeFromByteArray(MihonBackup.serializer(), output.toByteArray())
		assertEquals("One", decoded.backupManga.single().title)
		assertEquals("Library", decoded.backupCategories.single().name)
		assertEquals(123L, decoded.backupSources.single().sourceId)
	}

	@Test
	fun `wire walker processes fifty thousand manga messages incrementally`() = runTest {
		val output = ByteArrayOutputStream()
		repeat(50_000) { index ->
			MihonBackupWire.writeMessage(
				output,
				MihonBackupWire.FIELD_MANGA,
				serializer<MihonBackupManga>(),
				MihonBackupManga(
					source = 123L,
					url = "/manga/$index",
					title = "Manga $index",
				),
			)
		}

		var count = 0
		var lastTitle = ""
		MihonBackupWire.forEachMessage(ByteArrayInputStream(output.toByteArray())) { field, payload ->
			assertEquals(MihonBackupWire.FIELD_MANGA, field)
			val manga = MihonBackupWire.decodeMessage(payload, serializer<MihonBackupManga>())
			lastTitle = manga.title
			count++
		}

		assertEquals(50_000, count)
		assertEquals("Manga 49999", lastTitle)
		assertTrue(output.size() > 0)
	}
}
