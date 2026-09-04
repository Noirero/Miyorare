# Contributing to Miyorare

Thank you for contributing to Miyorare.

## Development flow

- `main` is the stable branch.
- `beta` is the active development branch.
- New features and fixes should be tested in `beta` before becoming part of a stable release.

## Before making changes

1. Check the current implementation and related call paths.
2. Keep changes focused and avoid unrelated refactoring.
3. Consider compatibility with existing user data.

## Important compatibility rules

- Preserve existing Manga and Novel reading support.
- Preserve local library compatibility.
- Preserve CBZ, ZIP, EPUB, and PDF handling.
- Avoid changing download folder structures without a migration plan.

## Commits

Prefer small, clear commits describing one logical change.

Examples:

- `Fix chapter list loading delay`
- `Improve local library detection`
- `Update reader UI`

## Testing

Before submitting changes:

- Verify affected features manually.
- Check that existing features still work.
- Include screenshots or logs for UI bugs when useful.

## Releases

Beta APK builds are created only when explicitly requested. Do not trigger release builds automatically for normal development work.
