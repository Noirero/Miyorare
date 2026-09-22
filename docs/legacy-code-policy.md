# Legacy Code Policy

Miyorare keeps legacy code only when it protects a currently supported compatibility contract.
Git history is the archive; obsolete runtime paths are not retained "just in case".

## Rules

1. **Replace, do not stack.**
   A proven new implementation replaces the old execution path. Do not keep old and new paths active
   together with delayed repair or fallback-to-new behavior.

2. **Keep compatibility only for real persisted contracts.**
   Legacy code may remain when it is required for existing user databases, downloaded files,
   supported Android versions, extension/runtime ABI compatibility, or bounded failure recovery.

3. **Keep legacy work out of hot paths.**
   Compatibility must not add broad filesystem scans, full-library reconciliation, repeated database
   work, or other unbounded work to startup, scrolling, Details first render, navigation, or Reader
   foreground work.

4. **Prefer migrate-once then remove old state.**
   When old persisted state can be converted safely, migrate it once, persist the canonical form,
   delete the old state/key, and use only the new implementation afterward.

5. **Every compatibility path needs a removal criterion.**
   Its code comment or test should identify what historical data/platform/runtime contract it protects
   and when it becomes safe to delete.

6. **Beta/development hooks do not ship as permanent production paths.**
   Staging identities, diagnostic overrides, one-shot CI triggers, and similar testing mechanisms
   must be removed before promotion to Main or be compile-time isolated to non-production builds.

7. **Delete dead code.**
   Code with no production caller, no persisted-data/platform/ABI compatibility role, and no required
   build/test/tooling role should be removed rather than retained for possible future use.

## Review checklist

Before keeping a legacy or fallback path, reviewers should be able to answer all of these:

- What real user data, supported platform, or runtime contract would break if it were removed?
- Is the compatibility path bounded and outside performance-critical execution?
- Is there a regression test for the compatibility contract?
- Is there a clear future condition under which the code can be removed?

If those questions have no concrete answers, remove the path.
