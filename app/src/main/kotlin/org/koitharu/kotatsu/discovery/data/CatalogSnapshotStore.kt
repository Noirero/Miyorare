package org.koitharu.kotatsu.discovery.data

import kotlinx.serialization.json.*
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.data.array
import org.koitharu.kotatsu.scrobbling.common.data.obj
import org.koitharu.kotatsu.scrobbling.common.data.text
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface CatalogSnapshotStore {
	fun read(key: CatalogHomeRequest, now: Long): TrackerCatalogHome?
	fun write(key: CatalogHomeRequest, home: TrackerCatalogHome): Boolean
}

/** A bounded, disposable card cache under cacheDir, never a database/catalogue mirror or backup. */
class FileCatalogSnapshotStore(private val directory: File) : CatalogSnapshotStore {
	private fun file(key: CatalogHomeRequest) = File(directory, "${key.service.name}_${key.filter.name}_${key.allowAdult}.json")
	@Synchronized override fun read(key: CatalogHomeRequest, now: Long): TrackerCatalogHome? {
		val file = file(key)
		if (!file.isFile) return null
		return try {
			if (file.length() > 512_000) invalidCatalog()
			val root = Json.parseToJsonElement(file.readText()).jsonObject
			check(root.catalogInt("version") == 1 && root.text("service") == key.service.name && root.text("filter") == key.filter.name && root.catalogBoolean("adult") == key.allowAdult)
			val saved = (root["savedAt"] as? JsonPrimitive)?.longOrNull ?: invalidCatalog()
			if (!CatalogCachePolicy.isReadable(saved, now)) { file.delete(); return null }
			val rows = root.array("sections") ?: invalidCatalog()
			check(rows.size <= CatalogCachePolicy.MAX_SECTIONS)
			val sections = rows.map { value ->
				val row = value.jsonObject
				val kind = CatalogMediaKind.valueOf(row.text("kind") ?: invalidCatalog())
				check(kind in key.filter.kinds)
				val sectionKey = CatalogSectionKey(key.service, kind, CatalogSectionType.valueOf(row.text("type") ?: invalidCatalog()))
				val items = row.array("items") ?: invalidCatalog()
				check(items.size <= CatalogCachePolicy.HOME_PAGE_SIZE)
				TrackerCatalogSection(sectionKey, items.mapNotNull { entry ->
					val item = entry.jsonObject
					TrackerCatalogItem(TrackerCatalogKey(key.service, kind, item.catalogId()), item.text("title") ?: invalidCatalog(),
						catalogUrl(item.text("cover")), catalogUrl(item.text("url")), item.catalogDouble("score"), item.catalogBoolean("adult")).forPolicy(key.allowAdult)
				}.distinctBy { it.key }, row.catalogInt("next")?.takeIf { it in 2..CatalogCachePolicy.MAX_PAGE })
			}
			check(sections.distinctBy { it.key }.size == sections.size)
			TrackerCatalogHome(sections, saved)
		} catch (_: Exception) {
			// Corruption/old schema is disposable and must never prevent a fresh public fetch.
			file.delete()
			null
		}
	}

	@Synchronized override fun write(key: CatalogHomeRequest, home: TrackerCatalogHome): Boolean {
		var temp: File? = null
		return try {
			directory.mkdirs()
			val target = file(key)
			temp = File.createTempFile("catalog-", ".tmp", directory)
			temp.writeText(buildJsonObject {
				put("version", 1); put("service", key.service.name); put("filter", key.filter.name); put("adult", key.allowAdult); put("savedAt", home.savedAt)
				put("sections", buildJsonArray { for (section in home.sections.filter { it.failure == null || it.items.isNotEmpty() }.take(CatalogCachePolicy.MAX_SECTIONS)) {
					add(buildJsonObject {
						put("kind", section.key.kind.name); put("type", section.key.type.name)
						section.nextPage?.let { put("next", it) }
						put("items", buildJsonArray { for (item in section.items.take(CatalogCachePolicy.HOME_PAGE_SIZE)) add(buildJsonObject {
							put("id", item.key.remoteId); put("title", item.title)
							item.coverUrl?.let { put("cover", it) }; item.providerUrl?.let { put("url", it) }; item.score?.let { put("score", it) }; item.adult?.let { put("adult", it) }
						}) })
					})
				} })
			}.toString())
			Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
			directory.listFiles { file -> file.extension == "json" }?.sortedByDescending { it.lastModified() }
				?.drop(CatalogCachePolicy.MAX_SNAPSHOTS)?.forEach { it.delete() }
			true
		} catch (_: Exception) { false } finally { temp?.delete() }
	}
}
