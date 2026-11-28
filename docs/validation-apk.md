# Miyorare Validation APK

Internal physical acceptance only. Existing Main, Beta, Amain2 Staging, debug and
Global Validation variants and mandatory PR CI are unchanged. The manual workflow
is intentionally independent of required CI and never publishes a release.

## Activation (separate authorization)

The workflow must first reach the trusted default branch (main). Do not dispatch
from candidate branches. Configure a protected GitHub environment named
validation-signing with required reviewers and main-only deployment rules.
Create and back up a dedicated long-lived testing keystore offline; never reuse
the Main/Beta key. Configure environment secrets VALIDATION_KEYSTORE_BASE64,
VALIDATION_STORE_PASSWORD, VALIDATION_KEY_ALIAS and VALIDATION_KEY_PASSWORD.
Set the environment variable VALIDATION_CERTIFICATE_SHA256 to the pinned
64-hex-character certificate digest. Confirm it is not a production certificate.
Do not put a keystore or credentials in the repository or artifacts.

## Build contract

Dispatch Validation APK from main with an open same-repository PR number and
its current full HEAD SHA, targeting Amain2. The trusted workflow checks HEAD
before build, before signing, and before upload. The build job checks out
candidate source independently and receives no signing secrets. A fail-closed
packaging overlay is applied only to disposable checkouts before
assembleDebug -PMIYORARE_VALIDATION=true. This preserves debug-only fixtures
and supports older compatible PRs without changing their HEAD. Unsupported
source anchors abort; no broad fallback. Overlay changes, workflow SHA, source
SHA, run identity, version and final signed APK digest are recorded alongside
each APK. The result is a derivative of the exact source SHA, not an unmodified
tree or production build.

The signing job only checks out trusted workflow code; it never runs Gradle or
candidate scripts. It validates the manifest before and after signing, uses
the dedicated key, checks the pinned certificate and ZIP alignment, and cleans
up temporary key files. Review the candidate and unsigned artifact provenance
before environment approval. Signed and unsigned artifacts in this public
repository are not confidential; do not include user data or credentials.
An APK can still contain arbitrary candidate code;
this is not a sandbox for malicious PRs.

Package org.noirero.miyorare.validation, label Miyorare Validation, independent
private data. The overlay removes broad storage and package installer/delete
permissions, disables automatic backup and legacy external storage, disables
Shizuku, removes shared activity deep links/callback filters, and restricts
configured Normal/Private/legacy manga roots to app-owned canonical paths.
SELF_UPDATE_SUPPORTED remains false in debug. This is not complete filesystem
or network isolation: explicit document grants, manual imports, third-party
extensions, network accounts and cloud actions need separate test data.
Never select production folders or restore production backup/account data.
External callbacks, extension installation, and broad-storage tests are not
acceptance targets of this variant. Use dedicated test accounts for any sync
testing. App-owned default folders are used for ordinary tests.

## Update sequence

Version code = 1,000,000 + 2 * workflow run_number + slot (baseline 0,
candidate 1). New dispatches allocate increasing codes; reruns retain their
original codes and must not be installed after a newer run. Rerun all jobs, not
only the signing job, because unsigned artifact names include the run attempt.
Do not rename/recreate the workflow without preserving the version sequence. Install in order,
without uninstall or clearing data. The global concurrency group prevents
parallel signing but does not promise FIFO dispatch ordering. Never install
older artifacts over newer ones. A stable certificate is necessary for updates;
database downgrade/migration incompatibility between unrelated PRs is still
possible. Use disposable test data and snapshot when needed.

For What's New PR #1065, request a pair with baseline_version=1.1.9.
The baseline is the PR merge-base source (not a historical 1.1.9 release);
the candidate retains its actual versionName and debug fixture. Install
baseline, complete onboarding, add sample collection, then install candidate
as an update. Verify popup only after upgrade, interrupted/recreated popup,
acknowledgement persistence, feature navigation and Back, and retained data.
A repeat test may need a fresh Validation-only installation or a new baseline
pair. Never uninstall Main or Beta. Actual APK upgrade proof remains distinct
from seeded emulator instrumentation.

## Validation status

Infrastructure changes trigger a separate, unsigned contract job that runs the
Python self-tests, applies the overlay to the exact PR head, builds the debug
APK and checks its merged manifest. It never accesses signing secrets or
uploads an installable APK. Manual builds verify identity before and after signing. Required compile/JVM/runtime/identity CI remains separate;
physical acceptance is tied to the source SHA plus overlay and signed artifact.
No secret creation, production signing, workflow dispatch, release, or branch
promotion is part of infrastructure implementation. Signed-pair emulator
upgrade and device acceptance require environment setup and subsequent runs.
