from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Expected anchor not found in {path}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


local_repo = Path("app/src/main/kotlin/org/koitharu/kotatsu/local/data/LocalMangaRepository.kt")
replace_once(
    local_repo,
    "\t\t\tchild.isDirectory && File(child, LocalMangaOutput.SOURCE_DIR_MARKER).isFile -> child.withChildren { sourceChildren ->\n",
    "\t\t\tchild.isDirectory && child.isDownloadSourceDirectory() -> child.withChildren { sourceChildren ->\n",
)
replace_once(
    local_repo,
    "\tprivate fun File.isDownloadSourceDirectory(): Boolean {\n\t\tif (File(this, LocalMangaOutput.SOURCE_DIR_MARKER).isFile) return true\n",
    "\tprivate fun File.isDownloadSourceDirectory(): Boolean {\n\t\tif (File(this, LocalMangaOutput.SOURCE_DIR_MARKER).isFile) return true\n\t\t// Structural fallback keeps downloads from older Miyorare builds readable after marker renames.\n",
)


drive_api = Path("app/src/main/kotlin/org/koitharu/kotatsu/sync/data/GoogleDriveApi.kt")
api_text = drive_api.read_text(encoding="utf-8")
if "findMigrationCandidates" in api_text:
    raise SystemExit("Migration candidate helper already exists")
anchor = "\n\t/** Reads just the current [DriveFile.version] of a file, for a pre-upload concurrency re-check. */"
helper_lines = [
    "",
    "\t/**",
    "\t * Lists older app-owned sync files only when the canonical Miyorare file is absent.",
    "\t * Names are matched by the generic *_sync.json convention; callers still validate content",
    "\t * before adopting or deleting a candidate.",
    "\t */",
    "\tsuspend fun findMigrationCandidates(token: String): List<DriveFile> = withContext(Dispatchers.IO) {",
    "\t\tval url = \"$DRIVE_BASE/files\".toHttpUrl().newBuilder()",
    "\t\t\t.addQueryParameter(\"spaces\", \"appDataFolder\")",
    "\t\t\t.addQueryParameter(\"q\", \"trashed = false\")",
    "\t\t\t.addQueryParameter(\"fields\", \"files(id,name,modifiedTime,createdTime,version)\")",
    "\t\t\t.addQueryParameter(\"orderBy\", \"createdTime\")",
    "\t\t\t.addQueryParameter(\"pageSize\", \"100\")",
    "\t\t\t.build()",
    "\t\tval request = Request.Builder().url(url).get().authorize(token).build()",
    "\t\thttpClient.newCall(request).await().parse<FileList>()?.files.orEmpty()",
    "\t\t\t.filter { file ->",
    "\t\t\t\tfile.name != FILE_NAME && file.name?.endsWith(\"_sync.json\", ignoreCase = true) == true",
    "\t\t\t}",
    "\t}",
    "",
]
if anchor not in api_text:
    raise SystemExit("GoogleDriveApi insertion anchor not found")
drive_api.write_text(api_text.replace(anchor, "\n".join(helper_lines) + anchor, 1), encoding="utf-8")


sync_repo = Path("app/src/main/kotlin/org/koitharu/kotatsu/sync/domain/GoogleDriveSyncRepository.kt")
replace_once(
    sync_repo,
    "\t\t\tval files = api.findSyncFiles(token)\n\t\t\tval canonical = files.firstOrNull() // oldest file is the single source of truth\n",
    "\t\t\tvar files = api.findSyncFiles(token)\n"
    "\t\t\tval migratingStoredSnapshot = files.isEmpty()\n"
    "\t\t\tif (migratingStoredSnapshot) {\n"
    "\t\t\t\tfiles = api.findMigrationCandidates(token)\n"
    "\t\t\t}\n"
    "\t\t\t// Migration candidates are never overwritten in place. Valid data is merged first,\n"
    "\t\t\t// then written into a newly-created canonical Miyorare sync file.\n"
    "\t\t\tval canonical = if (migratingStoredSnapshot) null else files.firstOrNull()\n",
)
replace_once(
    sync_repo,
    "\t\t\tval unchanged = !privacyScrubbed && files.size == 1 && remote != null &&\n",
    "\t\t\tval unchanged = !migratingStoredSnapshot && !privacyScrubbed && files.size == 1 && remote != null &&\n",
)

print("Compatibility migration patch applied")
