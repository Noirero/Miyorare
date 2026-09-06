<p align="center">
  <img src="assets/app-icon-dark.svg" alt="Miyorare dark mode app icon" width="112" />
</p>

<h1 align="center">Miyorare</h1>

<p align="center">
  A lightweight, modern comic and novel reader for Android with a beautiful Material 3 Expressive design. Supports Mihon and LNReader extensions.
</p>

<p align="center">
  <a href="https://github.com/Noirero/Miyorare/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/Noirero/Miyorare?style=for-the-badge&logo=github&label=latest"></a>
  <a href="https://discord.com/channels/1435615296202477581/1435650246163169382/1435651186140119091"><img alt="Discord online users" src="https://img.shields.io/discord/1435615296202477581?style=for-the-badge&logo=discord&logoColor=white&label=discord&color=5865F2"></a>
  <a href="https://github.com/Noirero/Miyorare/releases"><img alt="Total downloads" src="https://img.shields.io/github/downloads/Noirero/Miyorare/total?style=for-the-badge&logo=github&label=downloads"></a>
  <a href="LICENSE"><img alt="GPLv3 license" src="https://img.shields.io/github/license/Noirero/Miyorare?style=for-the-badge"></a>
</p>

<p align="center">
  <a href="https://developer.android.com/"><img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white"></a>
  <a href="https://kotlinlang.org/"><img alt="Kotlin" src="https://img.shields.io/github/languages/top/Noirero/Miyorare?style=for-the-badge&logo=kotlin&logoColor=white"></a>
  <a href="https://developer.android.com/compose"><img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white"></a>
  <a href="https://m3.material.io/"><img alt="Material 3 Expressive" src="https://img.shields.io/badge/Material%203-Expressive-6750A4?style=for-the-badge&logo=materialdesign&logoColor=white"></a>
</p>

<p align="center">
  <a href="https://github.com/Noirero/Miyorare/releases/latest"><strong>Download APK</strong></a>
  |
  <a href="https://drop-sauce.app/guide/"><strong>Upstream Guide</strong></a>
  |
  <a href="https://discord.com/channels/1435615296202477581/1435650246163169382/1435651186140119091"><strong>Discord</strong></a>
  |
  <a href="https://github.com/Noirero/Miyorare/issues"><strong>Issues</strong></a>
</p>

---

## About

Miyorare is a free and open-source comic and novel reader for Android, built to feel quick, clean, and comfortable to use with a broad feature set.

| Channel | App name | Android application ID |
| --- | --- | --- |
| Final (`main`) | Miyorare | `org.noirero.miyorare` |
| Beta (`beta`) | Miyorare Beta | `org.noirero.miyorare.beta` |

⭐Please give the repo a star if you like the project. It helps more people find it.🌟

## Keamanan & Play Protect

Miyorare Beta dapat memunculkan peringatan Play Protect karena memiliki fitur tingkat lanjut seperti pemasangan extension APK, akses penyimpanan, pemeriksaan aplikasi terpasang, dan dukungan Shizuku.

**Peringatan tersebut tidak otomatis berarti Miyorare adalah malware.**

APK resmi Miyorare yang dibuat melalui GitHub Actions telah diperiksa dan pada APK yang diaudit **tidak ditemukan indikasi umum malware atau tanda bahwa file telah disusupi setelah proses signing**. APK resmi juga menggunakan tanda tangan digital sehingga perubahan pada file setelah ditandatangani dapat terdeteksi.

Pada manifest yang diperiksa tidak ditemukan izin SMS, Accessibility Service untuk mengambil alih perangkat, maupun izin overlay `SYSTEM_ALERT_WINDOW` yang sering disalahgunakan untuk tampilan login palsu.

Namun, Miyorare mendukung extension pihak ketiga. Keamanan sebuah extension tetap bergantung pada sumber dan pembuat extension tersebut. Gunakan hanya repository dan extension yang Anda percaya.

**Unduh Miyorare hanya dari repository, GitHub Releases, atau GitHub Actions resmi `Noirero/Miyorare`. APK dari sumber lain tidak dapat dianggap identik atau aman hanya karena menggunakan nama Miyorare.**

Penjelasan lengkap mengenai alasan peringatan Play Protect, hasil verifikasi APK, hash, signature, perbedaan dengan pola malware umum, serta batasan pemeriksaan tersedia di **[SECURITY.md](SECURITY.md)**.

## Screenshots

<p align="center">
  <img src="assets/main_favorites-preview.webp" alt="Miyorare favorites screen" width="31%" />
  <img src="assets/manga_details_page-preview.webp" alt="Miyorare details screen" width="31%" />
  <img src="assets/reading_ui-preview.webp" alt="Miyorare manga reading screen" width="31%" />
  <img src="assets/novel_reading_ui-preview.webp" alt="Miyorare novel reading screen" width="31%" />
  <img src="assets/extension_page-preview.webp" alt="Miyorare extensions screen" width="31%" />
  <img src="assets/settings-preview.webp" alt="Miyorare settings screen" width="31%" />
</p>

<p align="center">
  <sub>Favorites | Details | Manga/Webtoon Reader | Novel Reader | Extensions | Settings</sub>
</p>

## Highlights
- Full novel reading support alongside manga, including offline EPUB file importing.
- Multi-source extension engine supporting LNReader JS plugins and Tsundoku APK extensions.
- Lightweight Android-first experience with a modern, polished interface.
- Rich extension support with library, reading, history, bookmarks, tracking, stats, and settings tools.
- Google Drive sync, local backup/restore, and in-app updates to keep your setup moving with you.
- Supports Kotatsu and Mihon backup restoration alongside google drive sync
- Free and open-source under the GPLv3 license.

<details>
<summary><strong>Features</strong></summary>

- Comfortable manga, webtoon and novel reading experience with configurable reader behavior, haptics, and zoom gestures.
- EPUB novel importing for offline reading.
- Extensive extension ecosystem supporting native extensions, LNReader JS plugins, and Tsundoku APK extensions.
- Reverse tracking integration with a redesigned tracking menu.
- Favorites, history, bookmarks, tracking, stats, and categories to keep your library organized.
- Google Drive sync for library, history, bookmarks, tracking, stats, settings, and covers.
- Local backup and restore system for moving or protecting your setup.
- Material 3 Expressive details page for clear and quick overview
- New onboarding/welcome flow with sync and restore setup.
- Android widgets for continue reading, favorites, and reading stats.
- PDF import support, converting PDFs into readable CBZ chapters.
- App lock with biometric or device credential support.
- Downloads for offline reading when a source supports it.
- In-app updates, with APKs also published through GitHub Releases.

</details>

<details>
<summary><strong>Recent improvements</strong></summary>

- Full novel support with offline EPUB file importing.
- LNReader JS plugin and Tsundoku APK extension support.
- Interactive zoom gestures in novel reading mode.
- Added reverse tracking and refreshed tracking menu design.
- New popup animations across app flows.
- Redesigned list options, filter menu, and progress tracking.
- Minor UI improvements, edge-case crash fixes, and release build cleanups.

</details>

## Star History

<a href="https://www.star-history.com/?repos=Noirero%2FMiyorare&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=Noirero/Miyorare&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=Noirero/Miyorare&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=Noirero/Miyorare&type=date&legend=top-left" />
 </picture>
</a>

## Install

1. Open the [latest GitHub release](https://github.com/Noirero/Miyorare/releases/latest).
2. Download the newest `Miyorare` APK.
3. Install it on a compatible Android device.
4. Add your preferred source or extension repository, then start reading.

Android may ask you to allow installs from your browser or file manager. That is normal for APKs downloaded outside the Play Store.

## FAQ

### Does Miyorare include manga or novels?
> No. Miyorare does not include built-in content. Sources are provided through external libraries, JS plugins, or repositories added by users.

### Is Miyorare free?
> Yes. Miyorare is free and open source under the GPLv3 license.

### How do updates work?
> Miyorare supports in-app updates, and release APKs are also published on GitHub. You can update from inside the app or install the latest APK from the Releases page.

### Can I contribute?
> Yes. Pull requests for patches, fixes, and new features are welcome.

## Project structure

```plaintext
app/src/main/
├── kotlin/org/koitharu/kotatsu/
│   ├── core/          # Shared database, network, parser, preferences, UI, and utility code
│   ├── main/          # App entry points, main activity, and app-level screens
│   ├── reader/        # Manga and novel reader UI and reading behavior
│   ├── details/       # Manga and novel details, chapters, metadata, and related services
│   ├── explore/       # Browse and discovery screens
│   ├── search/        # Search screens and search flows (with Manga/Novel toggle)
│   ├── favourites/    # Favorites and library-facing flows
│   ├── history/       # Reading history and progress
│   ├── download/      # Offline downloads and download queue
│   ├── extensions/    # Extension browsing, JS plugins, and APK extension management
│   ├── lnreader/      # LNReader JS plugin integration
│   ├── mihon/         # Mihon & Tsundoku APK extension integration
│   ├── backup/        # Local backup and restore
│   ├── sync/          # Sync data, domain, UI, and workers
│   ├── tracker/       # Tracking integrations and reverse tracking
│   ├── widget/        # Android home screen widgets
│   └── settings/      # Settings screens and preferences
└── res/
    ├── drawable*/     # Icons, backgrounds, and app artwork
    ├── layout*/       # XML screens, widgets, and reusable layouts
    ├── mipmap*/       # Launcher icons
    ├── values*/       # Strings, colors, themes, and translations
    └── xml/           # Android XML configuration
```

## Contribute

You can send a Pull Request for your patches, fixes, or new features here.

1. Fork the repository.
2. Create a focused branch for your change.
3. Build locally with `./gradlew :app:assembleDebug`.
4. Open a Pull Request with a short explanation of what changed.

Small fixes are welcome. Clear screenshots or short screen recordings are extra helpful for UI changes.

## Credits

Miyorare is derived from [DropSauce](https://github.com/HuzaifaKhalid1311/DropSauce) and exists because of the work already done by the open-source Android manga reader community.

Special thanks to the original [Kotatsu](https://github.com/KotatsuApp/Kotatsu) developers, [LNReader](https://github.com/LNReader/lnreader) developers, and the [Mihon](https://github.com/mihonapp/mihon) developers/community for the ideas, code, source ecosystem, and long-running maintenance work that helped shape projects like this.

## Fingerprint sertifikat APK Beta resmi yang diaudit

Fingerprint berikut berlaku untuk APK Beta pada audit yang didokumentasikan di [SECURITY.md](SECURITY.md). Build/channel lain harus diverifikasi secara terpisah apabila menggunakan sertifikat yang berbeda.

<div align="left">

SHA1:

```plaintext
73:AD:95:2A:3D:D7:52:40:66:5B:EE:66:8B:21:DD:29:50:D9:4E:8F
```

SHA256:

```plaintext
A4:41:D2:4E:96:19:AC:85:53:F1:D4:71:81:C1:93:F7:13:D8:49:4E:41:44:78:A1:3D:1E:40:FE:55:62:F4:65
```

</div>

## License

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)

<div align="left">

All programs from Miyorare™ project are free, open-source programs under the GPL license. You may copy, distribute, and modify the software as long as you keep track of changes/dates in the source files. Any modifications to the software, including code licensed under the GPL (via a compiler), must also be provided under the GPL license.

</div>

## Disclaimer

<div align="left">

The developer(s) of this application do not have any affiliation with the content providers available. If there is any content, it is provided by external libraries added or imported by users; the application itself does not include any built-in content.

</div>
