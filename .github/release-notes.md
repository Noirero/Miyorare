## Changelog 3

- Extension Mihon kini memiliki profil jaringan Adaptive, Standard, Aggressive, dan Custom. Mode Custom mendukung connection timeout 5–60 detik, retry aman, delay retry, temporary-error backoff, serta override profil per host/source. Waktu tunggu retry kini responsif terhadap pembatalan dan `Retry-After` server dibatasi agar request tidak menahan worker tanpa batas.
- Source yang kompatibel dengan Mihon kini dapat dibuka melalui WebView internal dari alur source untuk menyelesaikan login, memperbarui cookie/sesi, atau melewati challenge browser. WebView autentikasi source berjalan tanpa pemblokiran ad-block agar script/XHR login tidak terputus.
- Related Manga pada Detail kini menggunakan grup multi-keyword yang dibatasi dengan carousel horizontal dan aksi Lihat Semua, sehingga rekomendasi lebih terarah tanpa membuat query terkait berjalan berlebihan. Pencarian fallback yang sama juga tidak lagi dijalankan ganda pada tampilan Related penuh.
- Pemuatan Related ditunda sampai data utama Detail selesai dan ketersediaan chapter diprioritaskan, sehingga informasi utama dan tombol baca tidak perlu menunggu rekomendasi terkait.
- Disukai dan Didownload kini memiliki mode pemuatan Paged dan Berkelanjutan. Paged memakai batch adaptif konservatif, sedangkan Berkelanjutan memakai batch adaptif lebih besar untuk scroll jarak jauh tanpa memuat seluruh library ke RAM sekaligus.
- Pagination library besar dioptimalkan agar scrolling dan pemuatan metadata tetap responsif pada koleksi yang sangat banyak.
- Tombol download chapter kembali menjadi satu ketuk langsung ke alur download normal. Menu “Mulai sekarang” dihapus karena tidak memiliki prioritas queue nyata dan hanya menambah satu langkah.
- Queue download dan notifikasi kini memberikan feedback state yang lebih jelas saat Jeda, Lanjutkan, dan Batal sedang diproses. Waktu tunggu empty-state dipangkas, retry hydration worker dikurangi, dan pemeriksaan privasi notifikasi memakai cache singkat untuk mengurangi query database berulang.
- Detail notifikasi untuk download Private kini dapat diatur melalui toggle tersendiri agar informasi sensitif tidak harus ditampilkan pada notifikasi.
- Sejumlah masalah preference key, state kategori, dan fallback Related diperbaiki untuk meningkatkan stabilitas pada pembukaan ulang layar dan kondisi data kosong.

## Changelog 2

- Cover di kategori Disukai kini mulai dimuat sebelum terlihat saat menggulir library besar, dengan antrean terbatas agar scrolling tetap responsif.
- Judul dari Disukai kini menyiapkan chapter tersimpan sebelum dibuka, sehingga tombol Baca/Lanjut dan daftar bab dapat muncul langsung sementara pembaruan extension berjalan di belakang.
- Filter extension di Disukai kini tetap aktif dan tetap tercentang saat berpindah kategori, dengan pilihan terpisah untuk Manga dan Novel.
- Extension yang meminta “Open WebView to refresh token” kini menawarkan tindakan untuk membuka WebView dan memuat ulang chapter setelah pengguna kembali, bukan hanya menampilkan tombol Tutup.
- Badge jumlah kategori Disukai kembali muncul dan kini dihitung langsung oleh database agar tampil jauh lebih cepat, termasuk pada library besar.
- Pemilihan kategori melalui “Favoritkan ini” kini baru diterapkan lewat tombol Oke dan dialog ditutup setelah penyimpanan berhasil, sehingga hasilnya jelas dan tidak terpotong oleh penutupan dialog.
- Item chapter terakhir memperoleh ruang aman di atas tepi layar/navigation bar agar tetap terlihat penuh dan mudah ditekan.
- Halaman manga potret otomatis mengisi lebar layar saat reader diputar ke lanskap, sehingga gambar tidak lagi mengecil di tengah layar dan tetap dapat digeser vertikal.
- Downloader kini dapat menjalankan beberapa Manga/Novel dari source yang sama secara paralel hingga batas performa yang dipilih; Jeda dan Batal juga merespons sejak worker masih antre sehingga tidak perlu menunggu download lain selesai lebih dulu.
- Filter konten Jelajah kini tampil langsung sebagai kontrol Semua / SFW / NSFW di atas daftar source, langsung memperbarui source yang ditampilkan, dan klasifikasi manual source Mihon disimpan per exact source ID.
- Filter Source di Jelajah kini memiliki ON/OFF semua yang benar-benar independen untuk Languages dan Individual sources; mematikan bahasa tidak mengubah pilihan source individual, dan perubahan dikumpulkan lokal lalu diterapkan sekali saat menekan Oke agar toggle tetap responsif.
- Tombol collapse pada panel Bab/Chapter kembali berfungsi untuk menutup panel tanpa membuat scroll atau swipe daftar chapter menyeret sheet.
- Disukai kini menampilkan Manga/Novel lebih cepat saat pertama dibuka, termasuk saat informasi unread, progress, dan continue reading diaktifkan.
- Jelajah kini tidak lagi mengukur dan memuat seluruh daftar source sekaligus; daftar Manga/Novel tetap virtualized sehingga koleksi extension besar tidak membekukan UI atau memicu ANR saat source grid ditampilkan.
- Source Mihon yang dibuka saat extension masih dimuat kini otomatis disambungkan kembali ke source ID yang tepat setelah pemuatan selesai; pembaruan extension juga menyegarkan layar source tanpa berpindah bahasa.
- Alternatives kini menyimpan fallback ke Preferred Languages ketika mode Pinned tidak memiliki source, sehingga layar tidak kembali mencoba mode Pinned kosong setelah recreation.
- Filter bahasa pada tab Ekstensi kini mengenali semua bahasa yang benar-benar tersedia di dalam satu APK multi-source, termasuk kode regional.
- Global Search dan Alternatives menunggu pemuatan extension selesai agar tidak menampilkan hasil kosong palsu saat aplikasi baru dibuka; Alternatives juga tidak lagi menghitung dan memuat daftar source dua kali untuk satu pencarian.
- Pemeriksaan download lama kini bersifat read-only dan tidak lagi menghapus metadata atau cover hanya karena aplikasi sedang mengecek keberadaan file.
- Pemindaian folder download legacy dan dialog filter source dioptimalkan agar lebih responsif pada library atau kumpulan extension besar.
- Extension Mihon kini mempertahankan setiap source sebagai source terpisah berdasarkan ID aslinya, termasuk beberapa source dan beberapa bahasa dalam satu APK.
- Bahasa source dikenali secara dinamis, termasuk kode regional seperti `pt-BR`, pseudo-language `all` dan `other`, serta kode bahasa baru yang belum dikenal aplikasi.
- Jelajah → Sumber kini mengelompokkan source berdasarkan bahasa yang tersedia, menempatkan source yang dipin di atas, dan menyediakan filter bahasa serta kontrol aktif/nonaktif untuk setiap source.
- Pin dan status aktif source disimpan per source ID, sehingga mematikan atau mem-pin satu varian bahasa tidak memengaruhi varian lain dari extension yang sama.
- Tab Ekstensi tetap menampilkan satu entri per APK dan memperlihatkan daftar bahasa source yang tersedia; update dan uninstall tetap berlaku untuk APK extension tersebut.
- Favourite, History, Global Search, Temukan Serupa, Alternatives, Migration, backup, dan pemulihan mempertahankan source ID serta jenis konten Manga/Novel yang tepat.
- Alternatives kini mempunyai query yang dapat diedit, pencarian ulang, judul alternatif, riwayat query sesi, Stop Search, indikator scope Manga/Novel, pilihan Pinned/Preferred/All Sources, dan filter bahasa.
- Folder download source kini memakai kode bahasa stabil seperti `NHentai (EN)`, `NHentai (RU)`, `SourceABC (PT-BR)`, `ALL`, dan `OTHER` tanpa mengubah struktur judul maupun chapter di bawahnya.
- Download lama dengan folder source tanpa kode bahasa tetap ditemukan, dibaca, dilanjutkan, dan dihapus tanpa perlu mengunduh ulang atau memindahkan file.
- Pemindaian download lama tetap mengenali chapter CBZ, ZIP, EPUB, dan PDF.
- Restore backup Tachiyomi/Mihon diperbarui, tetap berjalan ketika layar ditinggalkan, menampilkan progres, dan memeriksa kembali favourite yang belum pulih.
- Repository extension dapat diberi jenis Manga, Novel, atau Anime secara eksplisit dan ditampilkan dalam kelompok yang sesuai.
- Login extension Mihon di browser internal mempertahankan header, cookie, sesi, dan permintaan POST, lalu menyegarkan data chapter setelah kembali ke aplikasi.
- Library Lokal mendukung chapter PDF, refresh manual, metadata author dari folder, dan pemuatan yang lebih ringan untuk koleksi besar.
- Favourites/Disukai mempunyai pengaturan tampilan per Manga/Novel, pilihan kartu, ukuran grid, informasi bahasa, unread, download, continue reading, serta pemuatan dan pencarian yang dioptimalkan untuk library besar.
- Filter SFW/NSFW untuk source Jelajah dapat diatur otomatis maupun manual.
- Crash dan ANR yang terjadi sebelum aplikasi tertutup dapat dipulihkan pada pembukaan berikutnya, disalin, atau diekspor sebagai berkas teks.
- Status DNS dan User-Agent aktif dapat dilihat langsung dari pengaturan jaringan.

## Changelog 1

- Miyorare Beta memakai identitas aplikasi terpisah agar dapat dipasang berdampingan dengan Miyorare Final.
- Download Manga dan Novel memakai struktur folder source → judul → chapter yang mudah dibaca tanpa kembali ke struktur download Kotatsu.
- Chapter Manga disimpan sebagai CBZ terpisah dan chapter Novel sebagai EPUB terpisah, dengan dukungan file lokal CBZ, ZIP, EPUB, dan PDF.
- Penghapusan chapter individual hanya menghapus file chapter yang dipilih, termasuk chapter bernama duplikat dan chapter Novel yang membutuhkan identitas tepat.
- Download yang sudah ada dapat dikenali berdasarkan folder dan nama file, termasuk file tanpa metadata indeks serta nama halaman angka sederhana.
- Downloader mendukung beberapa halaman dan source paralel, melanjutkan unduhan gambar, menampilkan progres chapter, dan menyediakan pengaturan batas performa.
- Disukai dipisahkan antara Manga dan Novel beserta kategori masing-masing, pencarian lokal, badge jumlah, kategori virtual Lokal, dan pin kategori Lokal.
- Notes per judul, penyuntingan metadata, dan rendering deskripsi Markdown dipertahankan pada halaman detail.
- Global Search dan Alternatives mempertahankan varian source yang berbeda, mendukung filter bahasa dan scope, serta memprioritaskan source yang dipin.
- Browser internal tersedia dari hasil pencarian dan Alternatives untuk membuka website source serta melakukan login.
- Pilihan DNS over HTTPS, custom DNS, dan User-Agent tersedia dari pengaturan jaringan.
- Terjemahan EPUB online mempertahankan susunan paragraf dan format teks serta menyediakan pilihan bahasa.

---

## Miyorare — Reading Gets Bigger 📚

Miyorare terus berkembang, tetapi satu prinsip tetap dijaga: **Kestabilan · Kecepatan · Kelancaran.**

Karena pembaruan Main kali ini berdekatan dengan rilis sebelumnya, catatan berikut **digabungkan** agar perubahan sebelumnya tetap tercantum dan tambahan terbaru tidak terpisah menjadi changelog yang terlalu pendek.

### 📖 Novel & EPUB

- Novel kini dapat menyimpan profil pengaturan reader khusus per judul, atau tetap mengikuti pengaturan Novel global.
- Dukungan Novel/EPUB diperluas, termasuk download EPUB, highlight dengan catatan, dan penanganan reader yang lebih tepat untuk source Novel.
- Status pemuatan chapter dibuat lebih jelas saat data masih diproses.
- Memperbaiki crash pada dialog highlight/catatan EPUB sehingga input highlight dapat dibuka dengan lebih aman.

### 📦 Download berdasarkan volume

- Pilih dan download seluruh volume tanpa memilih chapter satu per satu.
- Lihat jumlah chapter yang siap didownload, sudah didownload, atau sudah berada dalam antrean.
- Hapus hasil download berdasarkan volume.
- State Jeda/Lanjut download dibuat lebih konsisten, termasuk setelah proses aplikasi dibuat ulang.

### 🌙 Tsuki / Usagi Plugin Support

- Menambahkan dukungan plugin opsional Tsuki / Usagi untuk UMA dan Gekkoushi.
- Plugin dapat di-install/update, diimpor dari JAR lokal, diaktifkan/nonaktifkan, dan source dapat dipilih satu per satu.
- Gekkoushi kini dapat dipasang melalui jalur instalasi resmi Tsuki/Usagi dan tidak lagi ditahan sebagai tahap deferred.
- Source plugin baru tetap nonaktif secara default sampai diaktifkan pengguna.
- Plugin tidak dibundel ke APK Miyorare dan hanya diunduh atas tindakan pengguna.

### 📚 Chikari

- Menambahkan dan menyempurnakan kompatibilitas Chikari untuk Novel.
- Memperbaiki penanganan instalasi yang sudah ada dan chapter/oneshot.

### 🗂️ Library Groups

- Group dapat ditempatkan pada kategori Favourites tertentu tanpa mengubah kategori manga aslinya.
- Menambahkan pencarian dan import metadata dari tracking service.
- Menambahkan linking tracking pada Group dan sinkronisasi progress berdasarkan Reading Timeline.
- Pengelolaan Group, urutan, metadata, dan navigasi kembali disempurnakan.

### ❤️ Favourites / Disukai

- Menambahkan pilihan perilaku header: **Header pinned** atau **Header scrolls away**.
- Mode header diperbaiki agar pilihan pinned/scroll-away diterapkan kembali dengan benar saat kembali ke halaman Disukai atau setelah keluar dari pencarian.
- Kontrol tampil/sembunyikan kategori dibuat lebih jelas.
- Berbagai state kategori, tampilan, dan interaksi Disukai diperbaiki.

### 🩺 Smart Reader Error Guidance

- Reader kini menampilkan tombol **Bantuan** saat halaman gagal dimuat.
- Miyorare mencoba memberi petunjuk penyebab yang paling mungkin, seperti DNS/adblock, jaringan, source/server, plugin/parser, atau compatibility layer Miyorare.
- Pesan bantuan dibuat bersifat diagnostik dan tidak mengubah DNS, routing, request, atau perilaku plugin secara otomatis.
- Untuk error tertentu, pengguna dapat diarahkan ke sumber/plugin terkait atau halaman issue Miyorare agar pelaporan lebih tepat.

### ⚙️ Settings

- Halaman Settings disusun menjadi kelompok yang lebih jelas agar fitur yang semakin banyak tetap mudah ditemukan.

### 🎨 Branding & Release

- Identitas launcher Main dikunci ke artwork Miyorare agar branding DropSauce tidak terbawa saat promotion.
- Adaptive launcher Main diperketat agar tidak kembali menggunakan asset monochrome lama yang belum diverifikasi.
- README Main dipertahankan terpisah dari README Beta saat promotion.
- Workflow build Main dipisahkan menjadi **Miyorare Main Build** yang dijalankan manual, sehingga build stable tidak otomatis berjalan hanya karena promotion/commit.

### 🔧 Stability & Performance

- Penyempurnaan pada Novel reader, chapter loading, download queue, Favourites, Library Groups, source handling, navigation, search, Explore, dan reader state.
- Perbaikan tambahan dilakukan pada lifecycle AppBar/Header Disukai dan jalur error Reader tanpa menambah pemeriksaan jaringan di background.
- Berbagai perbaikan internal dilakukan untuk menjaga Miyorare tetap stabil, cepat, dan lancar saat fitur baru ditambahkan.
