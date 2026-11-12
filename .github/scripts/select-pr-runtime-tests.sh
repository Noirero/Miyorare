#!/usr/bin/env bash
set -euo pipefail

# Select the smallest Android runtime suite that covers the PR diff.
# Input paths are read from stdin. Use --full for the repository-wide runtime suite.

FULL=false
SELF_TEST=false
CLASSIFY=false
POLICY=false
for arg in "$@"; do
  case "$arg" in
    --full) FULL=true ;;
    --self-test) SELF_TEST=true ;;
    --classify) CLASSIFY=true ;;
    --policy) POLICY=true ;;
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

add_reader_custom_tint_suite() {
  add_class org.koitharu.kotatsu.reader.domain.ReaderCustomTintRuntimeTest
  MAPPED=true
}

add_migration_suite() {
  add_class org.koitharu.kotatsu.alternatives.domain.LosslessMigrationTransferRegressionTest
  add_class org.koitharu.kotatsu.alternatives.domain.SimpleMigrationTransferRegressionTest
  add_class org.koitharu.kotatsu.alternatives.domain.LosslessMigrationCopyRegressionTest
  add_class org.koitharu.kotatsu.alternatives.domain.ProfileMigrationPersistenceRegressionTest
  add_class org.koitharu.kotatsu.alternatives.domain.MigrationNonRoomDurabilityRegressionTest
  add_class org.koitharu.kotatsu.settings.sources.migration.MigrationBatchControlsTest
  add_class org.koitharu.kotatsu.settings.sources.migration.MigrationFlowScreenTest
  add_class org.koitharu.kotatsu.settings.sources.migration.MigrationLandingScreenTest
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
  add_class org.koitharu.kotatsu.discovery.DiscoverySearchBarOwnershipRuntimeTest
  MAPPED=true
}

add_database_suite() {
  add_class org.koitharu.kotatsu.core.db.ChapterPersistenceRegressionTest
  MAPPED=true
}

add_history_visual_suite() {
  # History-specific real View/locale/theme runtime acceptance.
  if [[ -f app/src/androidTest/kotlin/org/koitharu/kotatsu/history/ui/HistoryAccordionVisualRuntimeTest.kt ]]; then
    add_class org.koitharu.kotatsu.history.ui.HistoryAccordionVisualRuntimeTest
  fi
}

add_history_suite() {
  add_class org.koitharu.kotatsu.history.data.PrivateHistoryIsolationRuntimeTest
  add_history_visual_suite
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

add_onboarding_suite() {
  add_class org.koitharu.kotatsu.main.ui.welcome.OnboardingAppearanceIntegrationTest
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
  add_class org.koitharu.kotatsu.alternatives.domain.SimpleMigrationTransferRegressionTest
  add_class org.koitharu.kotatsu.alternatives.domain.LosslessMigrationCopyRegressionTest
  add_class org.koitharu.kotatsu.alternatives.domain.ProfileMigrationPersistenceRegressionTest
  add_class org.koitharu.kotatsu.alternatives.domain.MigrationNonRoomDurabilityRegressionTest
  add_class org.koitharu.kotatsu.settings.sources.migration.MigrationBatchControlsTest
  add_class org.koitharu.kotatsu.settings.sources.migration.MigrationFlowScreenTest
  add_class org.koitharu.kotatsu.settings.sources.migration.MigrationLandingScreenTest
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
  add_class org.koitharu.kotatsu.discovery.DiscoverySearchBarOwnershipRuntimeTest
  add_class org.koitharu.kotatsu.core.db.CategorySetsMigrationTest
  add_class org.koitharu.kotatsu.favourites.CategorySetsRuntimeTest
  add_class org.koitharu.kotatsu.favourites.ui.CategorySetsUiTest
  if [[ -f app/src/androidTest/kotlin/org/koitharu/kotatsu/reader/domain/ReaderCustomTintRuntimeTest.kt ]]; then
    add_reader_custom_tint_suite
  fi

  if [[ -f app/src/androidTest/kotlin/org/koitharu/kotatsu/main/ui/welcome/OnboardingAppearanceIntegrationTest.kt ]]; then
    add_onboarding_suite
  fi

  if [[ -f app/src/androidTest/kotlin/org/koitharu/kotatsu/history/data/PrivateHistoryIsolationRuntimeTest.kt ]]; then
    add_class org.koitharu.kotatsu.history.data.PrivateHistoryIsolationRuntimeTest
  fi

  add_history_visual_suite

  # Execute scoped View-palette regressions in full Android 15 runs when present.
  if [[ -f app/src/androidTest/kotlin/org/koitharu/kotatsu/core/ui/dialog/ModernConfirmationDialogColorsTest.kt ]]; then
    add_class org.koitharu.kotatsu.core.ui.dialog.ModernConfirmationDialogColorsTest
  fi
  if [[ -f app/src/androidTest/kotlin/org/koitharu/kotatsu/reader/ui/config/ModernReaderSheetChromeProbeTest.kt ]]; then
    add_class org.koitharu.kotatsu.reader.ui.config.ModernReaderSheetChromeProbeTest
  fi

  # Enforce launcher widget palette compatibility on Android 15 when the test exists.
  if [[ -f app/src/androidTest/kotlin/org/koitharu/kotatsu/widget/common/ModernWidgetThemeRuntimeTest.kt ]]; then
    add_class org.koitharu.kotatsu.widget.common.ModernWidgetThemeRuntimeTest
  fi

  RUN_RUNTIME_ISOLATION=true
  RUN_MIGRATION_PERSISTENCE=true
  MAPPED=true
}

plan_path() {
  local path="$1"
  case "$path" in
    app/src/main/kotlin/org/koitharu/kotatsu/main/ui/welcome/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/main/ui/welcome/*)
      add_onboarding_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/local/library/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/local/library/*|app/src/main/kotlin/org/koitharu/kotatsu/local/data/input/LocalPdfCache.kt)
      add_local_download_suite
      add_local_reader_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/details/ui/pager/pages/MangaPageFetcher.kt)
      add_tracker_suite
      add_local_reader_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/reader/domain/PageLoader.kt|app/src/main/kotlin/org/koitharu/kotatsu/picker/ui/page/PagePickerViewModel.kt)
      add_local_reader_suite ;;

    app/src/main/kotlin/org/koitharu/kotatsu/reader/domain/ReaderColorFilter.kt|app/src/main/kotlin/org/koitharu/kotatsu/reader/domain/ReaderCustomTint.kt|app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/colorfilter/*|app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/pager/*|app/src/androidTest/kotlin/org/koitharu/kotatsu/reader/domain/ReaderCustomTintRuntimeTest.kt)
      add_reader_custom_tint_suite ;;

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

# Conservative classification: only documentation-only diffs skip Android runtime.
classify_paths() {
  local path
  local count=0
  local has_runtime=false
  local full=false
  for path in "$@"; do
    [[ -z "$path" ]] && continue
    ((count+=1))
    case "$path" in
      extensions/miyorare-sources/*|.github/workflows/miyorare-source-pack-check.yml|.github/workflows/miyorare-global-source-pack-check.yml|.github/workflows/miyorare-multi-upstream-check.yml)
        # These inputs build/verify standalone Source Pack shards, not the Android app.
        # Dedicated PR checks remain authoritative. Mixed app/CI changes and
        # explicit runtime labels still follow the conservative policy below.
        ;;
      README.md|README.*.md|CHANGELOG.md|CONTRIBUTING.md|LICENSE|LICENSE.*|NOTICE|.editorconfig|.gitignore|docs/*.md|docs/*.txt|docs/*.rst|*.md)
        # Markdown outside the root or docs/ can be build/runtime input.
        if [[ "$path" == */* && "$path" != docs/* ]]; then
          has_runtime=true; full=true
        fi
        ;;
      docs/*) has_runtime=true; full=true ;;
      *)
        has_runtime=true
        MAPPED=false
        REQUIRE_FULL=false
        plan_path "$path"
        if [[ "$MAPPED" != true || "$REQUIRE_FULL" == true ]]; then
          full=true
        fi
        ;;
    esac
  done
  if ((count==0)); then
    printf 'run_runtime=true\nfull=true\n'
  elif [[ "$has_runtime" == false ]]; then
    printf 'run_runtime=false\nfull=false\n'
  elif [[ "$full" == true ]]; then
    printf 'run_runtime=true\nfull=true\n'
  else
    printf 'run_runtime=true\nfull=false\n'
  fi
}

# PR event policy is based on current labels and draft state, not the event's
# changed-label payload. Therefore labeled/unlabeled/synchronize/reopened
# resolve identically for the same PR state.
resolve_policy() {
  local run="$1" full="$2" draft="$3" required="$4" force_full="$5" mapped="$6" user_issue="$7" theme="$8" owner="${9:-false}"
  local v
  for v in "$run" "$full" "$draft" "$required" "$force_full" "$mapped" "$user_issue" "$theme" "$owner"; do
    [[ "$v" == true || "$v" == false ]] || return 2
  done
  if [[ "$user_issue" == true || "$theme" == true ]]; then
    required=true
  fi
  if [[ "$force_full" == true ]]; then
    run=true; full=true
  elif [[ "$required" == true ]]; then
    run=true
    [[ "$mapped" == true ]] || full=true
  elif [[ "$owner" == true ]]; then
    # Owner-only PRs use quick feedback even if paths would require full runtime.
    # Mandatory user/theme/explicit runtime labels above always take precedence.
    run=false; full=false
  elif [[ "$draft" == true ]]; then
    run=false; full=false
  fi
  if [[ "$draft" == true && "$required" == false && "$force_full" == false ]]; then
    run=false; full=false
  elif [[ "$full" == true ]]; then
    run=true
  fi
  printf 'run_runtime=%s\nfull=%s\n' "$run" "$full"
}

policy_self_test() {
  local got expected
  policy_check() {
    got="$(resolve_policy "$1" "$2" "$3" "$4" "$5" "$6" "${9:-false}" "${10:-false}" "${11:-false}")"
    expected="$(printf 'run_runtime=%s\nfull=%s' "$7" "$8")"
    [[ "$got" == "$expected" ]] || { echo "Policy mismatch: $got != $expected" >&2; return 1; }
  }
  # File changes, including docs-only, mapped, and unknown paths.
  policy_check false false false false false false false false
  policy_check true false false false false true true false
  policy_check true true false false false false true true
  policy_check true true true false false false false false
  # Label additions/removals, including priority of full over required.
  policy_check false false false true false false true true
  policy_check true false false true false true true false
  policy_check false false false false true false true true
  policy_check true false false true true true true true
  policy_check false false false false false false false false
  # Owner-only PRs always remain fast for both draft and ready PRs.
  policy_check true true false false false false false false false false true
  policy_check true false true false false true false false false false true
  policy_check false false false false false false false false false false true
  # Explicit heavy labels override owner fast mode, even on draft PRs.
  policy_check true false false true false true true false false false true
  policy_check false false true false true false true true false false true
  policy_check true true false false false false true true true false true
  policy_check false false true false false false true true false true true
  # Explicit label precedence on draft and ready PRs, including reversions.
  # Args: path_run path_full draft required full_override mapped expected_run expected_full user theme owner
  policy_check false false true false false true true false false true true
  policy_check false false true false false false true true true false true
  policy_check false false true true false true true false false false true
  policy_check false false true false true true true true false false true
  policy_check true true false false false false false false false false true
  policy_check true false false false false true true false false false false
  # Documentation-only PRs must run Android Runtime for either mandatory label.
  policy_check false false false false false false true true true false
  policy_check false false false false false false true true false true
  policy_check false false true false false false true true true false
  policy_check false false true false false false true true false true
  # Draft and ready_for_review event transitions.
  policy_check true false true false false true false false
  policy_check true false false false false true true false
  policy_check false false true true false false true true
  policy_check false false true false true false true true
  # Same PR state on opened, synchronize, reopened, labeled, unlabeled:
  # the resolver has no event-dependent branch.
  if resolve_policy invalid false false false false false false false >/dev/null 2>&1; then return 1; fi
  echo 'Runtime label/event policy self-test: PASS'
}

classify_self_test() {
  local result
  result="$(classify_paths README.md docs/guide.md)"
  [[ "$result" == "$(printf 'run_runtime=false\nfull=false')" ]]
  # A: Source Pack manifest/tooling/overlays stay non-Android.
  local path
  for path in extensions/miyorare-sources/multi-upstream.json \
              extensions/miyorare-sources/packs.json \
              extensions/miyorare-sources/tools/verify_multi_upstream.py \
              extensions/miyorare-sources/overlays/gekkoushi/all/Foo.kt; do
    result="$(classify_paths "$path")"
    [[ "$result" == "$(printf 'run_runtime=false\nfull=false')" ]]
  done
  result="$(classify_paths extensions/miyorare-sources/packs.json extensions/miyorare-sources/tools/prepare_pack.py)"
  [[ "$result" == "$(printf 'run_runtime=false\nfull=false')" ]]
  # B: Only standalone Source Pack workflow definitions are exempt.
  for path in .github/workflows/miyorare-source-pack-check.yml \
              .github/workflows/miyorare-global-source-pack-check.yml \
              .github/workflows/miyorare-multi-upstream-check.yml; do
    result="$(classify_paths "$path")"
    [[ "$result" == "$(printf 'run_runtime=false\nfull=false')" ]]
  done
  result="$(classify_paths .github/workflows/source-pack-contract.yml)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=true')" ]]
  # C: Mixed app/runtime changes win regardless of order.
  result="$(classify_paths extensions/miyorare-sources/multi-upstream.json app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryDao.kt)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=false')" ]]
  result="$(classify_paths app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryDao.kt .github/workflows/miyorare-multi-upstream-check.yml)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=false')" ]]
  result="$(classify_paths extensions/miyorare-sources/packs.json app/src/main/assets/important.txt)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=true')" ]]
  # D: Heavy labels override no-runtime Source Pack classification.
  result="$(resolve_policy false false false true false false false false false)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=true')" ]]
  result="$(resolve_policy false false false false true false false false true)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=true')" ]]
  result="$(resolve_policy false false false false false false true false false)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=true')" ]]
  result="$(resolve_policy false false false false false false false true false)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=true')" ]]
  # E: Central runtime decision machinery stays fail-conservative.
  result="$(classify_paths extensions/miyorare-sources/multi-upstream.json .github/scripts/select-pr-runtime-tests.sh)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=true')" ]]
  result="$(classify_paths .github/workflows/pr-compile-unit-check.yml extensions/miyorare-sources/packs.json)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=true')" ]]
  result="$(classify_paths extensions/miyorare-sources-unknown/packs.json)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=true')" ]]
  result="$(classify_paths app/src/main/assets/important.txt)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=true')" ]]
  result="$(classify_paths .github/ISSUE_TEMPLATE/config.md)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=true')" ]]
  result="$(classify_paths app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryDao.kt)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=false')" ]]
  result="$(classify_paths app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryDao.kt app/src/main/kotlin/unknown/Unknown.kt)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=true')" ]]
  result="$(classify_paths .github/workflows/pr-compile-unit-check.yml)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=true')" ]]
  result="$(classify_paths)"
  [[ "$result" == "$(printf 'run_runtime=true\nfull=true')" ]]
  echo 'Runtime risk classifier self-test: PASS'
}

self_test() {
  local out
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/history/data/HistoryDao.kt')"
  grep -q '^mode=targeted$' <<<"$out"
  grep -q 'PrivateHistoryIsolationRuntimeTest' <<<"$out"
  if [[ -f app/src/androidTest/kotlin/org/koitharu/kotatsu/history/ui/HistoryAccordionVisualRuntimeTest.kt ]]; then
    grep -q 'HistoryAccordionVisualRuntimeTest' <<<"$out"
  fi

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/local/library/LocalContentReader.kt')"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/local/data/LocalStorageCache.kt')"
  grep -q 'FastDownloadedNovelResolutionTest' <<<"$out"
  ! grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/reader/domain/PageLoader.kt')"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/main/ui/welcome/OnboardingScreen.kt')"
  grep -q '^mode=targeted$' <<<"$out"
  grep -q 'OnboardingAppearanceIntegrationTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/androidTest/kotlin/org/koitharu/kotatsu/main/ui/welcome/OnboardingAppearanceIntegrationTest.kt')"
  grep -q 'OnboardingAppearanceIntegrationTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/discovery/data/CatalogJson.kt')"
  grep -q 'DiscoveryCatalogUiTest' <<<"$out"
  grep -q 'DiscoveryNavigationRuntimeTest' <<<"$out"
  grep -q 'DiscoverySearchBarOwnershipRuntimeTest' <<<"$out"
  ! grep -q 'CategorySetsUiTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/backup/local/data/LocalBackupRepository.kt')"
  grep -q 'LocalBackupIdentityTest' <<<"$out"
  ! grep -q 'CategorySetsUiTest' <<<"$out"

  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/alternatives/domain/Foo.kt')"
  grep -q '^run_migration_persistence=true$' <<<"$out"
  grep -q 'SimpleMigrationTransferRegressionTest' <<<"$out"
  out="$(plan_from_paths false 'app/src/main/kotlin/org/koitharu/kotatsu/reader/domain/ReaderCustomTint.kt')"
  grep -q 'ReaderCustomTintRuntimeTest' <<<"$out"

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
  grep -q 'SimpleMigrationTransferRegressionTest' <<<"$out"
  grep -q 'SmartLocalCoverPipelineTest' <<<"$out"
  if [[ -f app/src/androidTest/kotlin/org/koitharu/kotatsu/history/ui/HistoryAccordionVisualRuntimeTest.kt ]]; then
    grep -q 'HistoryAccordionVisualRuntimeTest' <<<"$out"
  fi
  grep -q '^run_runtime_isolation=true$' <<<"$out"
  grep -q '^run_migration_persistence=true$' <<<"$out"

  classify_self_test
  policy_self_test
  echo 'Runtime selector self-test: PASS'
}

if [[ "$SELF_TEST" == true ]]; then
  self_test
  exit 0
fi

if [[ "$POLICY" == true ]]; then
  : "${DRAFT:?}" "${LABEL_RUNTIME:?}" "${LABEL_FULL:?}" "${TARGETED_AVAILABLE:?}" "${LABEL_USER_ISSUE:?}" "${LABEL_THEME:?}" "${LABEL_OWNER:?}"
  input="$(cat)"
  run="$(sed -n 's/^run_runtime=//p' <<< "$input")"
  full="$(sed -n 's/^full=//p' <<< "$input")"
  resolve_policy "$run" "$full" "$DRAFT" "$LABEL_RUNTIME" "$LABEL_FULL" "$TARGETED_AVAILABLE" "$LABEL_USER_ISSUE" "$LABEL_THEME" "$LABEL_OWNER"
  exit 0
fi
mapfile -t PATHS
if [[ "$CLASSIFY" == true ]]; then
  classify_paths "${PATHS[@]}"
  exit 0
fi
plan_from_paths "$FULL" "${PATHS[@]}"
