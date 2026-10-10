# Tracker recommendations in Details

Recommendations use the existing supplemental provider capabilities, persisted associations and lifecycle controller. B1 normalized targets/results and B2 people behavior remain authoritative. Core Details and Chapters load independently; section visibility starts bounded first-page reads, provider failures remain separate, and retry addresses one provider. No metadata tables or migration are added.

| Provider | Characters | Staff | Recommendations |
| --- | --- | --- | --- |
| AniList | Supported | Supported | Per-title recommendations, rating order |
| Kitsu | Supported | Supported | Unsupported |
| MyAnimeList | Unsupported | Official author names/roles, no fabricated portrait | Per-title recommendations |
| Shikimori | Supported | Supported | Similar manga, explicitly labeled separately |
| MangaUpdates | Unsupported | Authors/artists, optional public author portraits | Per-title recommendations |
| MangaBaka | Unverified/excluded | Unverified/excluded | Unsupported |

Provider target, real title, optional cover and provider URL survive normalization. Each provider/kind retains its own heading; related titles and genre discovery remain separate. Pages and cards are bounded, and Show more only expands already loaded data. There is no fuzzy association, automatic Library entry or tracking write.

A tap reverses the selected provider's persisted target association. One usable genuine manga opens existing Details; several require explicit user selection; none opens existing title-seeded search. Candidate identity and source URL come from the manga repository, never a parser Manga synthesized from tracker metadata. Private-only/unusable candidates are excluded, and an overfull candidate set falls back to search. Provider pages are a separate HTTPS action without source headers. Selection rechecks current mapping, account generation and privacy.

## People parity review

KEEP: B1 provider capability/result models, stable association authority, scoped clients and account epochs; B2 sequential provider reads, cancellation, late-result rejection, independent retry, partial data and bounded UI. These protections are retained together with all existing tests. No B1/B2 component was replaced.

ADAPT: preserve optional person URLs, preferred names, larger portrait/cover fallbacks, and AniList's role/favorites character order and rating recommendation order. Production supplemental reads use an I/O dispatcher so legacy body parsing cannot block the UI. Official MAL authors retain actual roles without website scraping or invented character data. Kitsu's explicit relationship parsing remains stronger than a general work-information request. Supplemental data remains suppressed in incognito, private-only and on-device contexts.

The behavior review traced provider parsing through normalized identity, attribution and UI navigation. Direct source copying, a second catalog/entity framework, website scraping, and automatic remote-library import were unnecessary. Existing privacy, cancellation, provider isolation and test infrastructure remain in place.

## Validation boundaries

Focused JVM fixtures cover success, unsupported/empty providers, isolated errors, provenance, demand coalescing, cancellation, stale contexts and resolution choices. Android fixtures cover real recommendation UI, persisted reverse mappings, genuine source identity and absence of Library/tracking side effects. These are deterministic checks; authenticated provider accounts and physical devices are not claimed. Exact-head CI evidence is recorded in PR #563 after execution.
