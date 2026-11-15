package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class CbzPageCacheKeyTest {
	@get:Rule val folder = TemporaryFolder()

	@Test fun samePathReplacementDoesNotReuseOldPage() {
		val file = File(folder.root, "Chapter 3.cbz")
		val displaced = File(folder.root, "Chapter 3 (other manga).cbz")
		val url = "zip://${file.absolutePath}#1.webp"
		val cache = mutableMapOf<String, ByteArray>()
		val oldPage = byteArrayOf(1, 2, 3)
		val newPage = byteArrayOf(4, 5, 6)

		fun load(): Pair<String, ByteArray> = ZipFile(file).use { zip ->
			val entry = requireNotNull(zip.getEntry("1.webp"))
			val key = cbzPageCacheKey(url, entry)
			key to cache.getOrPut(key) { zip.getInputStream(entry).use { it.readBytes() } }
		}

		file.writeCbz(oldPage)
		val oldLength = file.length()
		val (oldKey, first) = load()
		assertArrayEquals(oldPage, first)
		assertEquals(oldKey, load().first)

		java.nio.file.Files.move(file.toPath(), displaced.toPath())
		file.writeCbz(newPage)
		assertEquals(oldLength, file.length())
		val (newKey, second) = load()
		assertNotEquals(oldKey, newKey)
		assertArrayEquals(newPage, second)
		assertArrayEquals(oldPage, cache.getValue(oldKey))
	}

	private fun File.writeCbz(bytes: ByteArray) {
		ZipOutputStream(outputStream()).use { zip ->
			val entry = ZipEntry("1.webp").apply {
				method = ZipEntry.STORED
				size = bytes.size.toLong()
				crc = CRC32().apply { update(bytes) }.value
				time = 1_700_000_000_000L
			}
			zip.putNextEntry(entry)
			zip.write(bytes)
			zip.closeEntry()
		}
	}
}
