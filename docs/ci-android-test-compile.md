# Android instrumentation compile protection — stage 1

## Problem and placement

PR #462 changed `StatsScreen()` to require `hasLoadedStats`. Three existing androidTest callers
needed updating, but full Debug JVM regression did not compile those callers. The error surfaced
in `compilePreviewAndroidTestKotlin` inside Badge/Nameplate visual validation.

| Gate | Existing role | Compile-check decision |
| --- | --- | --- |
| CI Fast | Gradle-free diff, identity, launcher and helper guards | Keep cheap; test compile routing here |
| CI Deep | Routed full Debug JVM regression on the exact PR head | Add compile-only Debug androidTest tasks to its existing invocation; route extra Preview compilation |
| Android Runtime | Policy-routed connected Debug tests on Android 15 | Preserve emulator policy and existing acceptance coverage |
| Badge/Nameplate Golden | Preview visual/runtime evidence | Preserve all current triggers and checks in stage 1 |

CI Deep avoids an additional runner, JDK/Gradle setup and separate JVM regression. Shared Android
inputs use Debug instrumentation compilation in the existing JVM invocation, reusing application
classes. Variant-only sources, build configuration, CI compile infrastructure and unknown inputs
also receive Preview compilation to protect the Beta/release source set. The actual task audit
showed that selecting `testBuildType=preview` removes `testDebugUnitTest` in this AGP configuration;
the Preview check therefore needs a separate invocation and does not repeat JVM tests.

## Task and source-set contract

CI Deep uses one invocation for JVM regression and shared Android test compilation:

```sh
./gradlew :app:testDebugUnitTest --no-daemon --stacktrace \
  :app:compileDebugAndroidTestKotlin \
  :app:compileDebugAndroidTestJavaWithJavac
```

For Preview-sensitive inputs, a second compile-only invocation uses:

```sh
./gradlew :app:compilePreviewAndroidTestKotlin \
  :app:compilePreviewAndroidTestJavaWithJavac \
  -PMIYORARE_ANDROID_TEST_BUILD_TYPE=preview --no-daemon --stacktrace
```

The existing `testBuildType` property exposes Preview instrumentation tasks; no build configuration
change is needed. Preview includes main, Preview and release Kotlin/resources. Both compiler tasks
are requested explicitly so Java errors cannot hide behind Kotlin compilation succeeding. Gradle
deduplicates shared task dependencies within the invocation, including generated classes/resources
and KSP/Hilt dependencies. Resource processing needed for compilation is expected.

The check never requests `assemble*`, `bundle*`, APK packaging, installation, `connected*AndroidTest`,
an emulator, or visual signing. The existing JVM test report artifact remains the only Deep artifact.
Compilation is source compatibility evidence, not instrumentation execution or visual acceptance.
Debug-specific runtime coverage continues in Android Runtime; existing visual gates remain intact.

## Routing

The existing `ci_deep_paths.py` remains the classifier. Its default CLI and `requires_deep()` keep
the existing full JVM routing unchanged. `--gate android-test-compile` independently controls only
the Debug compile targets; `--gate preview-android-test-compile` routes the additional Preview
targets. No labels are read or changed by compile routing.

| Change | New androidTest compilation | Existing JVM/runtime policy |
| --- | --- | --- |
| README, root Markdown/MDX, `docs/`, LICENSE, `.gitignore`, `.gitattributes` | Skip | Unchanged; existing docs-only Deep skip retained |
| CODEOWNERS, PR/issue templates, release notes, build-request marker metadata | Skip | Unchanged; no extra Gradle compile work |
| Shared application code, Android tests (Kotlin/Java), resources and manifests | Run Debug | Unchanged |
| Preview/release and other variant-specific sources | Run Debug + Preview | Unchanged |
| Gradle configuration, version catalog/wrapper, generators, CI Deep/classifier edits | Run Debug + Preview | Unchanged |
| Mixed changes, unknown inputs, empty classification | Relevant Debug/Preview; unknown/empty run both | Unchanged |
| Manual CI Deep dispatch | Run Debug + Preview | Full JVM regression retained |

Source changes trigger compilation even when no androidTest file changed. In particular, an
owner-request `StatsScreen.kt` change can skip the emulator under existing runtime policy while
still receiving compile protection. Known metadata paths are excluded only from the new work;
this stage does not narrow existing JVM or visual coverage.

## Verification

`test_ci_deep_paths.py` covers docs/metadata skips, production API changes, Kotlin/Java Android
tests, variant sources, build inputs, mixed/unknown/empty paths, CLI compatibility, manual dispatch,
exact-head workflow wiring and independence from the owner-request emulator exemption. CI Fast
and CI Deep classification both execute these cheap tests.

The [controlled Kotlin regression run](https://github.com/Noirero/Miyorare/actions/runs/37095904181)
on head `5f1ba08af3b45ee79080b6dbee45f35065532082` failed at `compileDebugAndroidTestKotlin`
with `No value passed for parameter 'hasLoadedStats'`. The temporary probe copied an existing
StatsScreen androidTest caller and omitted the new parameter, reproducing the #462 failure.
No visual workflow ran; Android Runtime skipped its emulator according to existing owner policy.
The same run audited both Debug and Preview compile task graphs: compiler dependencies were
available and no APK assembly/packaging, dexing, connected testing or installation was selected.

The [controlled Java regression run](https://github.com/Noirero/Miyorare/actions/runs/37096283526)
on head `7414143c6137fb298ba55ecc669cc3569349d79d` completed Android test Kotlin compilation
then failed at `compileDebugAndroidTestJavaWithJavac` with `cannot find symbol` for an intentionally
missing Java API reference. This verifies Java source validation independently of Kotlin failures.

Before merging, inspect the exact final PR head's Deep logs for Kotlin and Java compiler completion
and verify every workflow applicable to that head. Temporary probes and the one-off graph audit
are excluded from the final diff. Narrowing Badge/Nameplate triggers is explicitly reserved for a
separate stage 2 PR.
