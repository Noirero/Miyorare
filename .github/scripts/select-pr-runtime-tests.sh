#!/usr/bin/env bash
set -euo pipefail

# Select the smallest Android runtime suite that covers the PR diff.
# Input paths are read from stdin. Use --full for the repository-wide runtime suite.

FULL=false
SELF_TEST=false
CLASSIFY=false
for arg in "$@"; do
  case "$arg" in
    --full) FULL=true ;;
    --self-test) SELF_TEST=true ;;
    --classify) CLASSIFY=true ;;
    *) echo "Unknown argument: $arg" >&2; exit 64 ;;
  esac
done

CLASSES=()
RUN_RUNTIME_ISOLATION=false
RUN_MIGRATION_PERSISTENCE=false
REQUIRE_FULL=false
MAPPED=false

add_class() {
  local candidate="$1"
  local existing
  for existing in "${CLASSES[@]:-}"; do
    [[ "$existing" == "$candidate" ]] && return 0
  done
  CLASSES+=("$candidate")
}

add_sync_suite() {
  add_class org.koitharu.kotatsu.sync.data.GoogleDriveAuthRecoveryTest
  add_class org.koitharu.kotatsu.sync.data.SyncSettingsAuthStateTest
  add_class org.koitharu.kotatsu.sync.ui.BrowserDriveAuthCallbackRoutingTest
  MAPPED=true
}

add_tracker_suite() {
  add_class org.koitharu.kotatsu.details.ui.TrackerPeopleSectionTest
  add_class org.koitharu.kotatsu.scrobbling.common.data.TrackerDetailsSessionTest
  add_class org.koitharu.kotatsu.details.ui.TrackerRecommendationSectionTest
  add_class org.koitharu.kotatsu.details.domain.TrackerRecommendationMappingTest
  add_class org.koitharu.kotatsu.details.domain.FavouriteReleasePredictionsTest
  add_class org.koitharu.kotatsu.details.ui.prediction.ReleasePredictionHeaderTest
  add_class org.koitharu.kotatsu.details.ui.prediction.ChapterReleasePredictionSheetTest
  add_class org.koitharu.kotatsu.details.domain.DetailsLiveLocalStateTest
  MAPPED=true
}

add_mangaupdates_suite() {
  add_class org.koitharu.kotatsu.scrobbling.mangaupdates.data.MangaUpdatesRepositoryTest
  add_class org.koitharu.kotatsu.scrobbling.mangaupdates.data.MangaUpdatesSessionStoreTest
  add_class org.koitharu.kotatsu.scrobbling.mangaupdates.ui.MangaUpdatesAuthActivityTest
  MAPPED=true
}

add_migration_suite() {
  add_class org.koitharu.kotatsu.alternatives.domain.LosslessMigrationTransferRegressionTest
  add_class org.koitharu.kotatsu.alternatives.domain.LosslessMigrationCopyRegressionTest
  add_class org.koitharu.kotatsu.alternatives.domain.ProfileMigrationPersistenceRegressionTest
  add_class org.koitharu.kotatsu.alternatives.domain.MigrationNonRoomDurabilityRegressionTest
  add_class org.koitharu.kotatsu.settings.sources.migration.MigrationBatchControlsTest
  add_class org.koitharu.kotatsu.alternatives.domain.MigrationBatchVisibilityTest
  RUN_MIGRATION_PERSISTENCE=true
  MAPPED=true
}

add_local_download_suite() {
  add_class org.koitharu.kotatsu.local.data.FastDownloadedNovelResolutionTest
  add_class org.koitharu.kotatsu.download.domain.maintenance.DownloadMaintenanceRegressionTest
  add_class org.koitharu.kotatsu.download.domain.maintenance.DownloadIndexCoverSafetyTest
  add_class org.koitharu.kotatsu.settings.DownloadMaintenanceSettingsTest
  add_class org.koitharu.kotatsu.favourites.domain.DownloadedLocalStateTest
  add_class org.koitharu.kotatsu.local.data.LocalChapterDeleteStateTest
  add_class org.koitharu.kotatsu.details.domain.DetailsLiveLocalStateTest
  MAPPED=true
}

add_local_reader_suite() {
  # Reader/SAF-specific regression tests; unrelated local/download edits keep the lighter suite.
  add_class org.koitharu.kotatsu.local.library.SmartLocalCoverPipelineTest
  MAPPED=true
}

add_discovery_suite() {
  add_class org.koitharu.kotatsu.discovery.DiscoveryCatalogUiTest
  add_class org.koitharu.kotatsu.discovery.CatalogReadingBridgeTest
  add_class org.koitharu.kotatsu.discovery.DiscoveryNavigationRuntimeTest
  MAPPED=true
}

add_database_suite() {
  add_class org.koitharu.kotatsu.core.db.ChapterPersistenceRegressionTest
  MAPPED=true
}

add_history_suite() {
  add_class org.koitharu.kotatsu.history.data.PrivateHistoryIsolationRuntimeTest
  MAPPED=true
}

add_runtime_pack_suite() {
  RUN_RUNTIME_ISOLATION=true
  MAPPED=true
}

add_backup_suite() {
  add_class org.koitharu.kotatsu.backup.local.LocalBackupIdentityTest
  MAPPED=true
}

add_category_sets_data_suite() {
  add_class org.koitharu.kotatsu.core.db.CategorySetsMigrationTest
  add_class org.koitharu.kotatsu.favourites.CategorySetsRuntimeTest
  MAPPED=true
}

add_category_sets_ui_suite() {
  add_category_sets_data_suite
  add_class org.koitharu.kotatsu.favourites.ui.CategorySetsUiTest
}

add_full_suite() {
  # Preserve the pre-refactor full-runtime class order. New optional mapped tests
  # are appended only when their source exists in the exact PR head.
  add_class org.koitharu.kotatsu.sync.data.GoogleDriveAuthRecoveryTest
  add_class org.koitharu.kotatsu.sync.data.SyncSettingsAuthStateTest
  add_class org.koitharu.kotatsu.sync.ui.BrowserDriveAuthCallbackRoutingTest
  add_class org.koitharu.kotatsu.details.ui.TrackerPeopleSectionTest
  add_class org.koitharu.kotatsu.scrobbling.common.data.TrackerDetailsSessionTest
  add_class org.koitharu.kotatsu.core.db.ChapterPersistenceRegressionTest
  add_class org.koitharu.kotatsu.backup.local.LocalBackupIdentityTest
  add_class org.koitharu.kotatsu.details.ui.TrackerRecommendationSectionTest
  add_class org.koitharu.kotatsu.details.domain.TrackerRecommendationMappingTest
  add_class org.koitharu.kotatsu.scrobbling.mangaupdates.data.MangaUpdatesRepositoryTest
  add_class org.koitharu.kotatsu.scrobbling.mangaupdates.data.MangaUpdatesSessionStoreTest
  add_class org.koitharu.kotatsu.scrobbling.mangaupdates.ui.MangaUpdatesAuthActivityTest
  add_class org.koitharu.kotatsu.alternatives.domain.LosslessMigrationTransferRegressionTest
  add_class org.koitharu.kotatsu.alternatives.domain.LosslessMigrationCopyRegressionTest
  add_class org.koitharu.kotatsu.alternatives.domain.ProfileMigrationPersistenceRegressionTest
  add_class org.koitharu.kotatsu.alternatives.domain.MigrationNonRoomDurabilityRegressionTest
  add_class org.koitharu.kotatsu.settings.sources.migration.MigrationBatchControlsTest
  add_class org.koitharu.kotatsu.alternatives.domain.MigrationBatchVisibilityTest
  add_class org.koitharu.kotatsu.local.data.FastDownloadedNovelResolutionTest
  add_class org.koitharu.kotatsu.local.library.SmartLocalCoverPipelineTest
  add_class org.koitharu.kotatsu.details.domain.FavouriteReleasePredictionsTest
  add_class org.koitharu.kotatsu.details.ui.prediction.ReleasePredictionHeaderTest
  add_class org.koitharu.kotatsu.details.ui.prediction.ChapterReleasePredictionSheetTest
  add_class org.koitharu.kotatsu.download.domain.maintenance.DownloadMaintenanceRegressionTest
  add_class org.koitharu.kotatsu.download.domain.maintenance.DownloadIndexCoverSafetyTest
  add_class org.koitharu.kotatsu.settings.DownloadMaintenanceSettingsTest
  add_class org.koitharu.kotatsu.favourites.domain.DownloadedLocalStateTest
  add_class org.koitharu.kotatsu.local.data.LocalChapterDeleteStateTest
  add_class org.koitharu.kotatsu.details.domain.DetailsLiveLocalStateTest
  add_class org.koitharu.kotatsu.discovery.DiscoveryCatalogUiTest
  add_class org.koitharu.kotatsu.discovery.CatalogReadingBridgeTest
  add_class org.koitharu.kotatsu.discovery.DiscoveryNavigationRuntimeTest
  add_class org.koitharu.kotatsu.core.db.CategorySetsMigrationTest
  add_class org.koitharu.kotatsu.favourites.CategorySetsRuntimeTest
  add_class org.koitharu.kotatsu.favourites.ui.CategorySetsUiTest

  if [[ -f app/src/androidTest/kotlin/org/koitharu/kotatsu/history/data/PrivateHistoryIsolationRuntimeTest.kt ]]; then
    add_class org.koitharu.kotatsu.history.data.PrivateHistoryIsolationRuntimeTest
  fi

  RUN_RUNTIME_ISOLATION=true
  RUN_MIGRATION_PERSISTENCE=true
  MAPPED=true
}

plan_path() {
  local path="$1"
  case "$path" in
    app/src/main/kotlin/org/koitharu/kotatsu/local/library/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/local/library/*|app/src/main/kotlin/org/koitharu/kotatsu/local/data/input/LocalPdfCache.kt)
      add_local_download_suite
      add_local_reader_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/details/ui/pager/pages/MangaPageFetcher.kt)
      add_tracker_suite
      add_local_reader_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/reader/domain/PageLoader.kt|app/src/main/kotlin/org/koitharu/kotatsu/picker/ui/page/PagePickerViewModel.kt)
      add_local_reader_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/history/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/history/*)
      add_history_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/sync/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/sync/*)
      add_sync_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/scrobbling/mangaupdates/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/scrobbling/mangaupdates/*)
      add_mangaupdates_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/scrobbling/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/scrobbling/*|app/src/main/kotlin/org/koitharu/kotatsu/details/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/details/*)
      add_tracker_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/alternatives/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/alternatives/*|app/src/main/kotlin/org/koitharu/kotatsu/settings/sources/migration/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/settings/sources/migration/*)
      add_migration_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/download/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/download/*|app/src/main/kotlin/org/koitharu/kotatsu/local/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/local/*|app/src/main/kotlin/org/koitharu/kotatsu/settings/DownloadMaintenanceSettings*|app/src/androidTest/kotlin/org/koitharu/kotatsu/settings/DownloadMaintenanceSettings*)
      add_local_download_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/discovery/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/discovery/*)
      add_discovery_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/sets/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/favourites/ui/CategorySetsUiTest.kt|app/src/androidTest/kotlin/org/koitharu/kotatsu/favourites/CategorySetsRuntimeTest.kt|app/src/main/kotlin/org/koitharu/kotatsu/favourites/data/CategorySet*|app/src/main/kotlin/org/koitharu/kotatsu/core/db/migrations/CategorySetsSchema.kt|app/src/main/kotlin/org/koitharu/kotatsu/core/db/migrations/Migration49To50.kt)
      add_category_sets_ui_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/backup/local/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/backup/local/*)
      add_backup_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/core/db/DatabasePrePopulateCallback.kt)
      add_database_suite
      add_category_sets_data_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/core/db/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/core/db/*)
      add_database_suite ;;

    kotatsu-runtime/*|kotatsu-runtime-fixture/*|app/src/main/kotlin/org/koitharu/kotatsu/tsuki/runtime/*)
      add_runtime_pack_suite ;;

    build.gradle*|settings.gradle*|gradle/*|gradle.properties|buildSrc/*|.github/workflows/pr-compile-unit-check.yml|.github/scripts/select-pr-runtime-tests.sh)
      REQUIRE_FULL=true ;;

    *) ;;
  esac
}

emit_plan() {
  local mode="$1"
  local joined=""
  if ((${#CLASSES[@]})); then
    local IFS=,
    joined="${CLASSES[*]}"
  fi
  printf 'mode=%s\n' "$mode"
  printf 'classes=%s\n' "$joined"
  printf 'run_runtime_isolation=%s\n' "$RUN_RUNTIME_ISOLATION"
  printf 'run_migration_persistence=%s\n' "$RUN_MIGRATION_PERSISTENCE"
}

plan_from_paths() {
  local full="$1"; shift
  CLASSES=()
  RUN_RUNTIME_ISOLATION=false
  RUN_MIGRATION_PERSISTENCE=false
  REQUIRE_FULL=false
  MAPPED=false

  if [[ "$full" == true ]]; then
    add_full_suite
    emit_plan full
    return 0
  fi

  local path
  for path in "$@"; do
    [[ -n "$path" ]] && plan_path "$path"
  done

  if [[ "$REQUIRE_FULL" == true ]]; then
    echo "Runtime selection touched shared CI/build infrastructure; use ci:runtime-full." >&2
    return 2
  fi
  if [[ "$MAPPED" != true ]]; then
    echo "ci:runtime-required has no targeted runtime mapping for this diff; add a mapping or use ci:runtime-full." >&2
    return 3
  fi
  emit_plan targeted
}

# Conservative PR-level gate. Proven non-runtime changes can skip the emulator;
# all other changes run a mapped suite or the full suite.
classify_paths() {
  local path
  local runtime=false
  local full=false
  local mapped=false
  local count=0
  for path in "$@"; do
    [[ -z "$path" ]] && continue
    ((count+=1))
    case "$path" in
      *.md|*.txt|*.rst|LICENSE|NOTICE|.editorconfig|.gitignore)
        ;;
      *)
        runtime=true
        plan_path "$path"
        ;;
    esac
  done
  if (( count == 0 )); then
    # Empty or unavailable diff must not silently skip required validation.
    printf 'run_runtime=true\nfull=true\n'
    return 0
  fi
  if [[ "$runtime" == false ]]; then
    printf 'run_runtime=false\nfull=false\n'
  elif [[ "$REQUIRE_FULL" == true || "$MAPPED" != true ]]; then
    printf 'run_runtime=true\nfull=true\n'
  else
    # Mixed mapped/unmapped changes cannot be assumed to have targeted coverage.
    # Only mapped source families and known non-runtime metadata qualify.
    for path in "$@"; do
      case "$path" in
        *.md|*.txt|*.rst|LICENSE|NOTICE|.editorconfig|.gitignore) ;;
        *)
          CLASSES=()
          MAPPED=false
          REQUIRE_FULL=false
          plan_path "$path"
          if [[ "$REQUIRE_FULL" == true || "$MAPPED" != true ]]; then
            full=true
            break
          fi
          ;;
      esac
    done
    if [[ "$full" == true ]]; then
      printf 'run_runtime=true\nfull=true\n'
    else
      printf 'run_runtime=true\nfull=false\n'
    fi
  fi
}

classify_self_test() {
  local result
  result="$(classify_paths README.md docs/guide.md)"
  [[ "$result" ==   local out
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryDao.kt')"
  grep -q '^mode=targeted$' <<<"$out"
  grep -q 'PrivateHistoryIsolationRuntimeTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/local/library/LocalContentReader.kt')"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/local/data/LocalStorageCache.kt')"
  grep -q 'FastDownloadedNovelResolutionTest' <<<"$out"
  ! grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/reader/domain/PageLoader.kt')"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/discovery/data/CatalogJson.kt')"
  grep -q 'DiscoveryCatalogUiTest' <<<"$out"
  grep -q 'DiscoveryNavigationRuntimeTest' <<<"$out"
  ! grep -q 'CategorySetsUiTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt')"
  grep -q 'LocalBackupIdentityTest' <<<"$out"
  ! grep -q 'CategorySetsUiTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/alternatives/domain/Foo.kt')"
  grep -q '^run_migration_persistence=true$' <<<"$out"

  out="$(plan_from_paths false 'kotatsu-runtime/src/main/kotlin/example/Foo.kt')"
  grep -q '^run_runtime_isolation=true$' <<<"$out"

  if plan_from_paths false 'gradle/libs.versions.toml' >/dev/null 2>&1; then
    echo 'Expected shared build infrastructure to require full runtime.' >&2
    return 1
  fi
  if plan_from_paths false 'README.md' >/dev/null 2>&1; then
    echo 'Expected an unmapped runtime request to fail closed.' >&2
    return 1
  fi

  out="$(plan_from_paths true 'README.md')"
  grep -q '^mode=full$' <<<"$out"
  grep -q 'CategorySetsUiTest' <<<"$out"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  grep -q '^run_runtime_isolation=true$' <<<"$out"
  grep -q '^run_migration_persistence=true$' <<<"$out"

  classify_self_test
  echo 'Runtime selector self-test: PASS'
}

if [[ "$SELF_TEST" == true ]]; then
  self_test
  exit 0
fi

mapfile -t PATHS
if [[ "$CLASSIFY" == true ]]; then
  classify_paths "${PATHS[@]}"
  exit 0
fi
plan_from_paths "$FULL" "${PATHS[@]}"
run_runtime=false\nfull=false' ]]
  result="$(classify_paths app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryDao.kt)"
  [[ "$result" ==   local out
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryDao.kt')"
  grep -q '^mode=targeted$' <<<"$out"
  grep -q 'PrivateHistoryIsolationRuntimeTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/local/library/LocalContentReader.kt')"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/local/data/LocalStorageCache.kt')"
  grep -q 'FastDownloadedNovelResolutionTest' <<<"$out"
  ! grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/reader/domain/PageLoader.kt')"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/discovery/data/CatalogJson.kt')"
  grep -q 'DiscoveryCatalogUiTest' <<<"$out"
  grep -q 'DiscoveryNavigationRuntimeTest' <<<"$out"
  ! grep -q 'CategorySetsUiTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt')"
  grep -q 'LocalBackupIdentityTest' <<<"$out"
  ! grep -q 'CategorySetsUiTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/alternatives/domain/Foo.kt')"
  grep -q '^run_migration_persistence=true$' <<<"$out"

  out="$(plan_from_paths false 'kotatsu-runtime/src/main/kotlin/example/Foo.kt')"
  grep -q '^run_runtime_isolation=true$' <<<"$out"

  if plan_from_paths false 'gradle/libs.versions.toml' >/dev/null 2>&1; then
    echo 'Expected shared build infrastructure to require full runtime.' >&2
    return 1
  fi
  if plan_from_paths false 'README.md' >/dev/null 2>&1; then
    echo 'Expected an unmapped runtime request to fail closed.' >&2
    return 1
  fi

  out="$(plan_from_paths true 'README.md')"
  grep -q '^mode=full$' <<<"$out"
  grep -q 'CategorySetsUiTest' <<<"$out"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  grep -q '^run_runtime_isolation=true$' <<<"$out"
  grep -q '^run_migration_persistence=true$' <<<"$out"

  echo 'Runtime selector self-test: PASS'
}

if [[ "$SELF_TEST" == true ]]; then
  self_test
  exit 0
fi

mapfile -t PATHS
plan_from_paths "$FULL" "${PATHS[@]}"
run_runtime=true\nfull=false' ]]
  result="$(classify_paths app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryDao.kt app/src/main/kotlin/unknown/Unknown.kt)"
  [[ "$result" ==   local out
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryDao.kt')"
  grep -q '^mode=targeted$' <<<"$out"
  grep -q 'PrivateHistoryIsolationRuntimeTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/local/library/LocalContentReader.kt')"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/local/data/LocalStorageCache.kt')"
  grep -q 'FastDownloadedNovelResolutionTest' <<<"$out"
  ! grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/reader/domain/PageLoader.kt')"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/discovery/data/CatalogJson.kt')"
  grep -q 'DiscoveryCatalogUiTest' <<<"$out"
  grep -q 'DiscoveryNavigationRuntimeTest' <<<"$out"
  ! grep -q 'CategorySetsUiTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt')"
  grep -q 'LocalBackupIdentityTest' <<<"$out"
  ! grep -q 'CategorySetsUiTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/alternatives/domain/Foo.kt')"
  grep -q '^run_migration_persistence=true$' <<<"$out"

  out="$(plan_from_paths false 'kotatsu-runtime/src/main/kotlin/example/Foo.kt')"
  grep -q '^run_runtime_isolation=true$' <<<"$out"

  if plan_from_paths false 'gradle/libs.versions.toml' >/dev/null 2>&1; then
    echo 'Expected shared build infrastructure to require full runtime.' >&2
    return 1
  fi
  if plan_from_paths false 'README.md' >/dev/null 2>&1; then
    echo 'Expected an unmapped runtime request to fail closed.' >&2
    return 1
  fi

  out="$(plan_from_paths true 'README.md')"
  grep -q '^mode=full$' <<<"$out"
  grep -q 'CategorySetsUiTest' <<<"$out"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  grep -q '^run_runtime_isolation=true$' <<<"$out"
  grep -q '^run_migration_persistence=true$' <<<"$out"

  echo 'Runtime selector self-test: PASS'
}

if [[ "$SELF_TEST" == true ]]; then
  self_test
  exit 0
fi

mapfile -t PATHS
plan_from_paths "$FULL" "${PATHS[@]}"
run_runtime=true\nfull=true' ]]
  result="$(classify_paths .github/workflows/pr-compile-unit-check.yml)"
  [[ "$result" ==   local out
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryDao.kt')"
  grep -q '^mode=targeted$' <<<"$out"
  grep -q 'PrivateHistoryIsolationRuntimeTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/local/library/LocalContentReader.kt')"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/local/data/LocalStorageCache.kt')"
  grep -q 'FastDownloadedNovelResolutionTest' <<<"$out"
  ! grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/reader/domain/PageLoader.kt')"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/discovery/data/CatalogJson.kt')"
  grep -q 'DiscoveryCatalogUiTest' <<<"$out"
  grep -q 'DiscoveryNavigationRuntimeTest' <<<"$out"
  ! grep -q 'CategorySetsUiTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt')"
  grep -q 'LocalBackupIdentityTest' <<<"$out"
  ! grep -q 'CategorySetsUiTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/alternatives/domain/Foo.kt')"
  grep -q '^run_migration_persistence=true$' <<<"$out"

  out="$(plan_from_paths false 'kotatsu-runtime/src/main/kotlin/example/Foo.kt')"
  grep -q '^run_runtime_isolation=true$' <<<"$out"

  if plan_from_paths false 'gradle/libs.versions.toml' >/dev/null 2>&1; then
    echo 'Expected shared build infrastructure to require full runtime.' >&2
    return 1
  fi
  if plan_from_paths false 'README.md' >/dev/null 2>&1; then
    echo 'Expected an unmapped runtime request to fail closed.' >&2
    return 1
  fi

  out="$(plan_from_paths true 'README.md')"
  grep -q '^mode=full$' <<<"$out"
  grep -q 'CategorySetsUiTest' <<<"$out"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  grep -q '^run_runtime_isolation=true$' <<<"$out"
  grep -q '^run_migration_persistence=true$' <<<"$out"

  echo 'Runtime selector self-test: PASS'
}

if [[ "$SELF_TEST" == true ]]; then
  self_test
  exit 0
fi

mapfile -t PATHS
plan_from_paths "$FULL" "${PATHS[@]}"
run_runtime=true\nfull=true' ]]
  result="$(classify_paths)"
  [[ "$result" ==   local out
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryDao.kt')"
  grep -q '^mode=targeted$' <<<"$out"
  grep -q 'PrivateHistoryIsolationRuntimeTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/local/library/LocalContentReader.kt')"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/local/data/LocalStorageCache.kt')"
  grep -q 'FastDownloadedNovelResolutionTest' <<<"$out"
  ! grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/reader/domain/PageLoader.kt')"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/discovery/data/CatalogJson.kt')"
  grep -q 'DiscoveryCatalogUiTest' <<<"$out"
  grep -q 'DiscoveryNavigationRuntimeTest' <<<"$out"
  ! grep -q 'CategorySetsUiTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt')"
  grep -q 'LocalBackupIdentityTest' <<<"$out"
  ! grep -q 'CategorySetsUiTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/alternatives/domain/Foo.kt')"
  grep -q '^run_migration_persistence=true$' <<<"$out"

  out="$(plan_from_paths false 'kotatsu-runtime/src/main/kotlin/example/Foo.kt')"
  grep -q '^run_runtime_isolation=true$' <<<"$out"

  if plan_from_paths false 'gradle/libs.versions.toml' >/dev/null 2>&1; then
    echo 'Expected shared build infrastructure to require full runtime.' >&2
    return 1
  fi
  if plan_from_paths false 'README.md' >/dev/null 2>&1; then
    echo 'Expected an unmapped runtime request to fail closed.' >&2
    return 1
  fi

  out="$(plan_from_paths true 'README.md')"
  grep -q '^mode=full$' <<<"$out"
  grep -q 'CategorySetsUiTest' <<<"$out"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  grep -q '^run_runtime_isolation=true$' <<<"$out"
  grep -q '^run_migration_persistence=true$' <<<"$out"

  echo 'Runtime selector self-test: PASS'
}

if [[ "$SELF_TEST" == true ]]; then
  self_test
  exit 0
fi

mapfile -t PATHS
plan_from_paths "$FULL" "${PATHS[@]}"
run_runtime=true\nfull=true' ]]
  echo 'Runtime risk classifier self-test: PASS'
}

self_test() {
  local out
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryDao.kt')"
  grep -q '^mode=targeted$' <<<"$out"
  grep -q 'PrivateHistoryIsolationRuntimeTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/local/library/LocalContentReader.kt')"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/local/data/LocalStorageCache.kt')"
  grep -q 'FastDownloadedNovelResolutionTest' <<<"$out"
  ! grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/reader/domain/PageLoader.kt')"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/discovery/data/CatalogJson.kt')"
  grep -q 'DiscoveryCatalogUiTest' <<<"$out"
  grep -q 'DiscoveryNavigationRuntimeTest' <<<"$out"
  ! grep -q 'CategorySetsUiTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt')"
  grep -q 'LocalBackupIdentityTest' <<<"$out"
  ! grep -q 'CategorySetsUiTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/alternatives/domain/Foo.kt')"
  grep -q '^run_migration_persistence=true$' <<<"$out"

  out="$(plan_from_paths false 'kotatsu-runtime/src/main/kotlin/example/Foo.kt')"
  grep -q '^run_runtime_isolation=true$' <<<"$out"

  if plan_from_paths false 'gradle/libs.versions.toml' >/dev/null 2>&1; then
    echo 'Expected shared build infrastructure to require full runtime.' >&2
    return 1
  fi
  if plan_from_paths false 'README.md' >/dev/null 2>&1; then
    echo 'Expected an unmapped runtime request to fail closed.' >&2
    return 1
  fi

  out="$(plan_from_paths true 'README.md')"
  grep -q '^mode=full$' <<<"$out"
  grep -q 'CategorySetsUiTest' <<<"$out"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  grep -q '^run_runtime_isolation=true$' <<<"$out"
  grep -q '^run_migration_persistence=true$' <<<"$out"

  echo 'Runtime selector self-test: PASS'
}

if [[ "$SELF_TEST" == true ]]; then
  self_test
  exit 0
fi

mapfile -t PATHS
plan_from_paths "$FULL" "${PATHS[@]}"
