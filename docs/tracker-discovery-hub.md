# Tracker discovery Home

Home is a public tracker catalogue. Favorites remains the saved-content surface; Explore and ordinary global search remain source discovery. A catalogue work is identified by provider, Manga/Novel kind, and the complete remote ID. It is never a parser `Manga`.

## Navigation

The five roots are `[Favorites, slot 1, Home, slot 2, slot 3]`. The preference normalizer keeps the first three distinct, available, non-fixed entries from an old configuration. It removes unknown values, duplicates, Favorites, Home and Reader Journey from editable slots, then fills gaps using Feed, History, Explore and other available roots. A malformed stored preference type recovers to defaults. The settings editor replaces/reorders exactly three slots.

The existing 48dp header gear opens a utility sheet containing the existing Reader Journey and Settings routes. This preserves space for the bottom navigation's existing Continue control. Opening/dismissing the sheet does not select a root. Favorites still temporarily uses this control as Back during inline search; closing/hiding that search restores Main's utility action, while other/private hosts keep their existing action. Root navigation retains Fragment instances and view state; Home reselection scrolls to the top without reloading its provider.

## Data and lifecycle

`TrackerCatalogProvider` is read-only and separate from scrobbling writes. `CatalogHttp` derives its client from the existing base transport, retaining DNS, proxy and TLS infrastructure. The public child has no session cookies/authenticator, strips Authorization/Cookie, refuses unexpected origins and redirects, and omits source-browser and credential logging interceptors. It shares the existing cancellation-through-body-consumption helper. Requests are limited to three concurrently, 40 seconds, and 2MB per response. Provider retry/reset headers govern cooldowns; no permanent requests-per-minute claim is encoded.

Home sends card fields only. AniList batches its eight All-filter rails in one GraphQL request. Other providers use bounded public collection requests. Spotlight reuses a truthful loaded rail. Optional detail metadata can be partial without discarding the work. Search, section browsing and detail retain provider identity, real media kind and real pagination. Provider failures never select another provider.

The repository coalesces identical Home loads and cancels underlying work when the last subscriber leaves. Supervised work isolates failures. Request generations reject late provider/filter results; cache writes are serialized per request key and checked against the latest generation. Failed sections retain their own last-good rows. A successful, genuinely empty collection can replace old rows; transport failures cannot do so.

Disposable card snapshots live in `cacheDir/tracker-discovery-v1`, outside database and backup formats. Policy is 15 minutes fresh, at most 24 hours readable, 18 provider/filter/adult snapshots, eight rails and twelve cards per rail. Partial refreshes do not renew old data indefinitely. Corrupt/expired snapshots are ignored and removed best-effort. Detail memory is bounded to 64 entries and has no disk persistence. Image bytes use the existing Coil cache.

Global NSFW policy applies server-side where supported and client-side everywhere. Known adult records are excluded when disabled; an unknown rating cannot supply a visible cover. The public client does not consult account credentials or private Library state.

## Provider capabilities

| Provider | Manga / Novel | Home | Search / detail | Paging | Authentication | Deliberate omissions |
| --- | --- | --- | --- | --- | --- | --- |
| AniList | `MANGA` type, with `NOVEL` distinguished by format | Recent, Trending, Popular, Top rated, separately per kind | Public GraphQL; staff, synopsis, genres, recommendations when supplied | Real `hasNextPage`, bounded to 100 pages | None | No personalization or tracker writes |
| MyAnimeList | Real `media_type`, including `novel` and `light_novel` | Top ranked (`manga` / `novels`), Popular (`bypopularity`), Most favorited (`favorite`) | Public v2; explicit author-name and recommendation-node field projections | Provider next-page presence; own validated offset, never arbitrary next URLs | Existing public-read Client-ID; no user login/token | No fabricated Trending; kind-filtered global rankings can yield sparse/empty Novel pages |
| Kitsu | Real manga resource `subtype`, including `novel` | Genuine Trending collection, Popular by user count, Top rated by average rating | Public JSON:API; categories/staff when available | Collection links; Trending is one unpaged collection and may contain no Novel records | None | No invented recommendations or Trending continuation |

MangaUpdates, Shikimori and MangaBaka discovery are outside the approved MVP rollout. Existing tracking integrations are untouched.

## Reading bridge

Only metadata detail performs a cheap reverse lookup of exact persisted tracker associations. It checks provider, media kind, enabled source/local identity, global adult policy and private-only membership. A single usable exact mapping can open ordinary Details. Several mappings require a chooser.

Otherwise the reading CTA calls the existing Alternatives source engine with title and kind, without constructing a parser work. The extraction retains existing language/pinned/history ordering, concurrency, detail limits and cross-source ID exclusion behavior for the original Alternatives callers. Search results, even a single fuzzy candidate, require explicit choice. All-source retry and typed global-source-search fallback are explicit. The latter carries Manga/Novel scope in the intent without changing the global preference.

Choosing a genuine source result opens the existing parser Details route. The choice is retained only in that detail ViewModel; no fuzzy permanent association, tracker entry or Favorite is created. Selection is rechecked when opening and when delivering a queued navigation event after lifecycle suspension. Closing the chooser, replacing a request, or changing adult policy cancels/rejects stale work.

## Contract and terms review (2026-10-24)

Authoritative sources reviewed before enabling integrations:

- [AniList documentation](https://docs.anilist.co/), [current terms](https://docs.anilist.co/guide/terms-of-use), and the official [documentation source](https://github.com/AniList/docs). The implementation is a complementary reader integration, avoids catalogue hoarding/backups, and uses bounded disposable snapshots. The terms contain non-commercial/commercial thresholds and naming requirements; this change introduces no monetization or provider branding in the app identity.
- [MyAnimeList v2 reference](https://myanimelist.net/apiconfig/references/api/v2) and [API agreement](https://myanimelist.net/static/apiagreement.html). Public reads reuse the configured application Client-ID and do not reuse OAuth sessions. Attribution and provider links are visible. No user-generated/account data is cached. The agreement's release/substantial-functionality notification obligations remain a release-owner responsibility before publication; this PR does not publish a release or send external messages.
- Official [Kitsu JSON:API documentation](https://hummingbird-me.github.io/api-docs/), [API source](https://github.com/hummingbird-me/api-docs), and [website terms](https://kitsu.app/terms). The current deployed site's `render-markdown("terms")` loads the terms embedded in its vendor asset (version 1.0, December 1, 2016); the same text is available in the official [website source](https://github.com/hummingbird-me/kitsu-web/blob/df0f97cca3bc579002b50660922a81421ec6c3a1/markdown/terms.md). The docs explicitly support third-party public reads. The terms distinguish software licensing from content/brand rights, prohibit scraping/disruption, and refer to API-specific terms available on request. No unpublished terms or separate commercial permission are claimed here. This integration uses the documented API, identifies Kitsu as the data source, uses no copied brand assets, and does not mirror the catalogue.

No external project's implementation code/assets were copied. API responses remain provider data with provider attribution, rather than Miyorare-owned catalogue content.

Live engineering probes: MAL public rankings/detail returned HTTP 200, including projected author names and recommendation kind fields; Kitsu Trending returned HTTP 200. AniList GraphQL and some Kitsu filtered requests returned HTTP 403 from this execution environment. Fixture validation does not establish live success for those paths, authenticated behavior, or a physical device. No account credentials were used.

## Validation and regression boundaries

Focused JVM tests cover navigation migration; wire-level provider requests/parsing/kinds/paging; origin, credential and body-cancellation boundaries; cache expiry/corruption/isolation; genuine empty versus failure; partial rows; request coalescing; final-subscriber cancellation; and provider/filter latest-wins behavior. The pre-existing Alternatives contract assertion follows the extracted loop and still checks source plus ID exclusion, rather than weakening it to ID-only matching.

Android runtime tests cover real MainActivity retained navigation, scroll/reselection/recreation, gear/Journey/Settings, metadata activity navigation with genuine parser identity, typed source-search fallback, exact Room mapping/private boundaries without writes, actual provider-adapter/repository/ViewModel reading flows, and Compose loading/error/rate-limit/stale/partial/search/detail/chooser states. Existing runtime selections are retained and the new classes are appended to the existing CI workflow.

Exact-head CI evidence belongs in the PR description after execution. Existing Favorites/private isolation, Explore, Reader/Details, downloads/Smart Local, tracker synchronization, database schema, backup and Journey content were not redesigned. Their integration boundaries are included in the final diff/post-CI review.
