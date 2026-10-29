## Miyorare v1.4.16

This release strengthens Miyorare's global source infrastructure, expands MyAnimeList-powered Discovery details, and improves privacy isolation for reading history. Source Migration also receives additional refinements while remaining **Beta / not yet stable**.

### ✨ What's New

- **Official Global Source settings** — Miyorare now provides dedicated settings and controls for supported Official Global Sources.
- **Global Source network controls** — dedicated network policy infrastructure gives Miyorare more control over how Official Global Sources communicate and recover from network problems.
- **MyAnimeList extended details** — MyAnimeList-backed Discovery titles can now expose richer supplemental information through the tracker details experience.
- **Private History isolation** — reading history now has dedicated isolation behavior for manga associated with Private Favourites, keeping private activity separated from normal History surfaces.

### 🚀 Improvements

- **Official Global Source resilience** — global sources gain dedicated resilience handling for more reliable behavior during network and source failures.
- **Tsuki source runtime** — the plugin runtime and source configuration infrastructure have been expanded to support the new Official Global Source controls.
- **MyAnimeList catalog details** — MAL catalog data now includes additional supplemental information and richer details presentation.
- **Tracker details presentation** — Discovery's supplemental details UI has been substantially expanded to present richer tracker-backed information.
- **AniList, Kitsu & MAL catalog reliability** — catalog providers received additional request, parsing, and data-handling improvements.
- **MAL catalog fallback behavior** — MyAnimeList Discovery has additional fallback and timeout handling when supplemental or home data cannot be retrieved normally.
- **Private History handling** — History storage, repository queries, reader updates, and the History widget now coordinate with Private Favourites isolation.
- **Migration manual search (Beta)** — manual migration search received additional source-selection and follow-up behavior. Migration remains **Beta / not yet stable**.
- **Migration candidate search (Beta)** — migration search received further matching and source-picker refinements while the overall migration system remains under active development.

### 🐞 Fixes

- Fixed History behavior that could allow reading activity associated with Private Favourites to appear on normal History surfaces.
- Improved History persistence and query behavior to preserve Private/Normal isolation consistently.
- Improved History widget behavior so private reading activity follows the same isolation rules.
- Improved MyAnimeList catalog detail loading and supplemental-data fallback behavior.
- Improved timeout handling for MyAnimeList Discovery home/catalog requests.
- Improved AniList and Kitsu catalog request and parsing behavior.
- Improved Official Global Source recovery and network-policy behavior.
- **Migration (Beta)** — improved manual search follow-up and source selection behavior.
- Added database migration support for the updated History privacy model.
- Added regression coverage for Private History isolation, MAL details and fallback behavior, Official Global Source resilience, and **Beta migration** behavior.

> **Migration status:** Migration functionality remains **Beta / not yet stable**. It is still under active development and may receive further behavior, UI, and compatibility changes in future releases.

<details>
<summary><strong>🇮🇩 Bahasa Indonesia</strong></summary>

Rilis ini memperkuat infrastructure global source Miyorare, memperluas detail Discovery berbasis MyAnimeList, dan meningkatkan isolasi privasi untuk riwayat membaca. Source Migration juga mendapat penyempurnaan tambahan, tetapi masih berstatus **Beta / belum stabil**.

### ✨ Yang Baru

- **Pengaturan Official Global Source** — Miyorare sekarang menyediakan pengaturan dan kontrol khusus untuk Official Global Sources yang didukung.
- **Kontrol jaringan Global Source** — network policy infrastructure khusus memberikan kontrol yang lebih baik terhadap komunikasi dan pemulihan Official Global Source ketika terjadi masalah jaringan.
- **Detail MyAnimeList yang lebih lengkap** — judul Discovery berbasis MyAnimeList sekarang dapat menampilkan supplemental information yang lebih kaya melalui pengalaman tracker details.
- **Isolasi Private History** — riwayat membaca sekarang memiliki mekanisme isolasi khusus untuk manga yang terkait dengan Private Favourites sehingga aktivitas private tetap terpisah dari History normal.

### 🚀 Peningkatan

- **Reliability Official Global Source** — global source mendapat resilience handling khusus agar lebih andal ketika terjadi masalah jaringan atau source.
- **Tsuki source runtime** — plugin runtime dan source configuration infrastructure diperluas untuk mendukung kontrol Official Global Source yang baru.
- **Detail katalog MyAnimeList** — data katalog MAL sekarang memiliki supplemental information tambahan dan tampilan detail yang lebih kaya.
- **Tampilan tracker details** — supplemental details UI di Discovery diperluas secara substansial untuk menampilkan informasi berbasis tracker yang lebih lengkap.
- **Reliability katalog AniList, Kitsu & MAL** — provider katalog mendapat peningkatan tambahan pada request, parsing, dan data handling.
- **Fallback katalog MAL** — MyAnimeList Discovery mendapat fallback dan timeout handling tambahan ketika supplemental atau home data tidak dapat diperoleh secara normal.
- **Penanganan Private History** — penyimpanan History, repository query, update dari reader, dan History widget sekarang terkoordinasi dengan isolasi Private Favourites.
- **Migration manual search (Beta)** — manual migration search mendapat peningkatan pada source selection dan follow-up behavior. Migration tetap **Beta / belum stabil**.
- **Migration candidate search (Beta)** — migration search mendapat penyempurnaan tambahan pada matching dan source picker sementara keseluruhan sistem masih dalam pengembangan aktif.

### 🐞 Perbaikan

- Memperbaiki History yang dapat membuat aktivitas membaca terkait Private Favourites muncul pada History normal.
- Meningkatkan persistence dan query History agar isolasi Private/Normal tetap konsisten.
- Meningkatkan History widget agar aktivitas membaca private mengikuti aturan isolasi yang sama.
- Meningkatkan pemuatan detail katalog MyAnimeList dan fallback supplemental data.
- Meningkatkan timeout handling untuk request MyAnimeList Discovery home/catalog.
- Meningkatkan request dan parsing katalog AniList serta Kitsu.
- Meningkatkan recovery dan network-policy behavior untuk Official Global Source.
- **Migration (Beta)** — meningkatkan manual search follow-up dan source selection behavior.
- Menambahkan database migration untuk mendukung model privasi History yang diperbarui.
- Menambahkan regression coverage untuk Private History isolation, MAL details dan fallback, Official Global Source resilience, serta behavior **migration Beta**.

> **Status Migration:** Fungsi Migration masih berstatus **Beta / belum stabil**. Sistem tersebut masih dalam pengembangan aktif dan dapat menerima perubahan behavior, UI, maupun compatibility pada rilis berikutnya.

</details>
