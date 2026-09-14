# Settings reorganization mapping

This document locks the safety rule for the Miyorare Settings cleanup:

> Move the UI location, not the underlying behavior.

Existing preference keys, defaults, storage paths, databases, workers, source identities, authentication state and backup semantics must remain unchanged unless a separate task explicitly changes them.

## Root navigation

| Previous location | New location | Safety rule |
| --- | --- | --- |
| Settings > Display | Settings > Display | Same fragment and preferences |
| Settings > Favourites | Display > Manga list | Reuse `FavouriteHeaderScrollMode.KEY_PREFERENCE` and `AppSettings.KEY_FAVOURITES_LIST_LOADING_MODE` |
| Settings > Reader settings | Settings > Reader | Same fragment and preferences |
| Settings > Extensions | Settings > Sources & extensions | Navigation/title cleanup only |
| Settings > Downloads | Settings > Downloads | Same fragment and download storage behavior |
| Settings > Check for new chapters | Settings > Chapter updates | Navigation/title cleanup only; worker scheduling is unchanged |
| Settings > Google Drive sync | Hidden from root until the feature is ready | Sync implementation/data are not deleted |
| Settings > Storage and network | Settings > Storage and network | Same fragment and network/storage preferences |
| Settings > Backup and restore | Settings > Backup and restore | Same fragment and backup/restore behavior |
| Display > Privacy | Settings > Privacy & security > App security | Reuse `KEY_PROTECT_APP`, `KEY_PROTECT_APP_TIMEOUT`, `KEY_SCREENSHOTS_POLICY` |
| Settings > Private favourites | Settings > Privacy & security > Private favourites | Existing Private favourites fragment/security store remains authoritative |
| Settings > Services | Settings > Services & tracking | Navigation/title cleanup only |
| Settings > About | Settings > About | Same fragment |

## Invariants

- Do not rename or migrate a preference key merely because its control moved.
- Do not alter download paths, local-file indexing, CBZ/ZIP/EPUB/PDF handling, or previously downloaded-file detection.
- Do not alter Normal/Private favourites isolation, category membership, restore targeting, security state, or Private backup opt-in.
- Do not alter Reader state, chapter progress, update workers, notification scheduling, source login/session data, or source aliases.
- Upgrade users must retain their existing values. Fresh-install defaults remain governed by the existing preference definitions.
- Google Drive Sync may be hidden from the root UI while unfinished; code/data must stay intact for later reactivation.

## Post-layout verification order

1. Wiring audit: every moved control still reads/writes the original key/state.
2. Regression audit: Normal/Private favourites, Reader, Downloads, Chapter updates, Extensions/Source Packs, Backup/Restore, Local, networking, and upgrade behavior.
3. Language cleanup after the structure is stable.
4. Visual polish for disabled text, clipped values, spacing and dark-theme contrast.
5. Progressive disclosure for advanced/technical controls.
6. Accessibility/responsiveness: large font, small screens, landscape, Light/Dark/AMOLED, Classic/Modern and all accent themes.
7. Final classification: SAFE / MINOR / IMPORTANT / BLOCKER before Beta is considered ready.
