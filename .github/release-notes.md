## Miyorare v1.4.15

This release expands Miyorare's discovery ecosystem, strengthens persistent cover handling, and introduces a major new iteration of source migration. Migration improvements in this release are still **Beta / not yet stable**, while Discovery gains MangaBaka and tracker-account integration.

### ✨ What's New

- **MangaBaka in Discovery** — MangaBaka joins the Discovery catalog ecosystem with dedicated catalog support.
- **MangaBaka Personal Home** — Discovery can provide a personalized MangaBaka home experience with dedicated browsing and “See all” screens.
- **Tracker catalog accounts** — Discovery now includes account infrastructure and UI for supported tracker catalogs.
- **Manual migration search (Beta)** — source migration now provides a dedicated manual search flow when automatic candidate matching is not enough. This migration functionality is still under active development and should be considered **Beta / not yet stable**.
- **Broader migration search controls (Beta)** — migration can search through explicit scopes and use global search integration to find replacement titles more flexibly. The expanded migration flow is **not yet considered stable**.
- **Persistent cover storage** — Miyorare now has dedicated persistent storage for manga covers instead of relying solely on ordinary image-cache behavior.

### 🚀 Improvements

- **Migration search & matching (Beta)** — candidate discovery and matching have been expanded to work with the new search scopes and manual/global search paths. These migration improvements remain **Beta / not yet stable**.
- **Lossless migration copy (Beta)** — migration has a dedicated copy path designed to preserve related user/library data while moving a title to its replacement source. This functionality is still **Beta** and may receive further changes.
- **Batch migration controls (Beta)** — batch migration controls, scheduling, background work, and execution policies have been substantially expanded, but the overall migration system remains **not yet stable**.
- **Migration session preferences (Beta)** — migration can retain additional session/search choices instead of treating each operation as completely isolated. This remains part of the **Beta migration system**.
- **Discovery catalog data** — AniList, Kitsu, and MyAnimeList providers received additional catalog data and supplemental information handling.
- **Tracker details & relations** — tracker-backed details now have richer relation-reading infrastructure and expanded people information.
- **MangaBaka tracker details** — MangaBaka is integrated more deeply into tracker-backed title information.
- **Persistent cover restoration** — stored covers are integrated with Miyorare's image loading and restoration path for more durable artwork availability.
- **Discovery snapshots** — catalog snapshot handling has been improved for mixed and supplemental catalog data.

### 🐞 Fixes

- **Migration (Beta)** — improved preservation of library and user data when copying entries during source migration.
- **Migration (Beta)** — improved candidate matching for direct searches and broader search scopes.
- **Migration (Beta)** — improved behavior when automatic matching cannot confidently find a replacement.
- **Migration (Beta)** — improved batch migration state, work scope, and execution handling.
- Improved image disk-cache behavior alongside the new persistent cover storage path.
- Improved cover restoration behavior when normal cached artwork is no longer available.
- Improved tracker relation reading so relation data remains isolated and handled consistently.
- Improved Discovery catalog snapshot and supplemental-data handling.
- Added regression coverage for **Beta migration**, persistent covers, MangaBaka Discovery, tracker accounts, tracker relations, and Discovery catalog behavior.

> **Migration status:** The migration features described in this release are still **Beta / not yet stable**. They are under active development and may receive further behavioral, UI, and compatibility changes in future releases.

<details>
<summary><strong>🇮🇩 Bahasa Indonesia</strong></summary>

Rilis ini memperluas ekosistem Discovery Miyorare, memperkuat penanganan persistent cover, dan menghadirkan iterasi besar baru untuk source migration. Peningkatan migration pada rilis ini masih berstatus **Beta / belum stabil**, sementara Discovery mendapat integrasi MangaBaka dan akun tracker.

### ✨ Yang Baru

- **MangaBaka di Discovery** — MangaBaka bergabung dengan ekosistem katalog Discovery melalui dukungan katalog khusus.
- **MangaBaka Personal Home** — Discovery dapat menyediakan pengalaman MangaBaka personal dengan browsing khusus dan layar “See all”.
- **Akun tracker catalog** — Discovery sekarang memiliki infrastructure dan UI akun untuk tracker catalog yang didukung.
- **Manual migration search (Beta)** — source migration sekarang memiliki alur pencarian manual khusus ketika automatic candidate matching tidak mencukupi. Fungsi migration ini masih dalam pengembangan aktif dan berstatus **Beta / belum stabil**.
- **Kontrol pencarian migration lebih luas (Beta)** — migration dapat menggunakan search scope khusus dan integrasi global search untuk menemukan judul pengganti secara lebih fleksibel. Alur migration yang diperluas ini **belum dianggap stabil**.
- **Persistent cover storage** — Miyorare sekarang memiliki penyimpanan persisten khusus untuk cover manga dan tidak hanya bergantung pada image cache biasa.

### 🚀 Peningkatan

- **Migration search & matching (Beta)** — candidate discovery dan matching diperluas agar bekerja dengan search scope baru serta jalur manual/global search. Peningkatan migration ini masih **Beta / belum stabil**.
- **Lossless migration copy (Beta)** — migration memiliki jalur copy khusus yang dirancang untuk mempertahankan data user/library terkait saat judul dipindahkan ke source pengganti. Fungsi ini masih **Beta** dan dapat berubah lagi.
- **Batch migration controls (Beta)** — kontrol batch migration, scheduling, background work, dan execution policy diperluas secara signifikan, tetapi keseluruhan sistem migration masih **belum stabil**.
- **Migration session preferences (Beta)** — migration dapat mempertahankan pilihan session/search tambahan sehingga setiap operasi tidak selalu dimulai sepenuhnya dari awal. Ini masih menjadi bagian dari **sistem migration Beta**.
- **Data Discovery catalog** — provider AniList, Kitsu, dan MyAnimeList mendapat tambahan data katalog dan supplemental information handling.
- **Tracker details & relations** — detail berbasis tracker sekarang memiliki infrastructure pembacaan relation yang lebih kaya serta informasi people yang diperluas.
- **MangaBaka tracker details** — MangaBaka terintegrasi lebih dalam dengan informasi judul berbasis tracker.
- **Persistent cover restoration** — cover tersimpan diintegrasikan dengan image loading dan restoration path Miyorare agar artwork tersedia lebih tahan lama.
- **Discovery snapshots** — penanganan catalog snapshot ditingkatkan untuk mixed dan supplemental catalog data.

### 🐞 Perbaikan

- **Migration (Beta)** — meningkatkan pemeliharaan data library dan user ketika entry disalin selama source migration.
- **Migration (Beta)** — meningkatkan candidate matching untuk direct search dan search scope yang lebih luas.
- **Migration (Beta)** — meningkatkan perilaku ketika automatic matching tidak dapat menemukan pengganti dengan cukup yakin.
- **Migration (Beta)** — meningkatkan batch migration state, work scope, dan execution handling.
- Meningkatkan image disk-cache behavior bersama persistent cover storage yang baru.
- Meningkatkan cover restoration ketika artwork dari cache biasa sudah tidak tersedia.
- Meningkatkan pembacaan tracker relations agar relation data tetap terisolasi dan ditangani secara konsisten.
- Meningkatkan penanganan Discovery catalog snapshot dan supplemental data.
- Menambahkan regression coverage untuk **migration Beta**, persistent cover, MangaBaka Discovery, tracker accounts, tracker relations, dan perilaku Discovery catalog.

> **Status Migration:** Fitur-fitur migration yang dijelaskan pada rilis ini masih berstatus **Beta / belum stabil**. Sistem tersebut masih dalam pengembangan aktif dan dapat menerima perubahan behavior, UI, maupun compatibility pada rilis berikutnya.

</details>
