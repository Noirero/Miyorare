Miyorare tetap berpegang pada **Kestabilan · Kecepatan · Kelancaran**.

Rilis berikutnya memusatkan perubahan Beta setelah v1.3.7 pada pengelolaan chapter di Details, Details dari Jelajah/Extension, pembukaan EPUB dari luar aplikasi, Favourites untuk library besar, pemulihan download setelah cold start, Reader Local/CBZ, serta pengurangan pekerjaan database dan storage yang tidak diperlukan.

### ✨ Baru

- **Filter / Sort / Display chapter di Details** — daftar chapter kini dapat difilter berdasarkan Downloaded, Unread, Bookmarked, New, serta Branch atau Scanlator ketika tersedia.
- **Sorting chapter yang lebih lengkap** — chapter dapat diurutkan berdasarkan urutan source, nomor chapter, tanggal upload, atau alfabet, termasuk pilihan arah urutan.
- **Pilihan tampilan chapter** — pengguna dapat memilih judul source atau nomor chapter serta tampilan List atau Grid.
- **Default chapter terpisah** — pengaturan default Details dipisahkan antara Manga dan Novel serta antara ruang Normal dan Private. Pilihan Branch tetap khusus untuk judul yang sedang dibuka.
- **Open with untuk EPUB** — file EPUB dari luar Miyorare kini dapat dibuka melalui Android Open with, dipreview terlebih dahulu, lalu dipilih antara Import & Read atau Import only.
- **Pengelolaan EPUB yang sudah pernah diimport** — ketika file sudah tersedia di library, Miyorare dapat menawarkan Read, Open details, atau Re-import dengan penggantian file yang lebih aman.

### ⚡ Ditingkatkan

- **Details dari Jelajah/Extension kini lebih cepat dibuka dan dibuka ulang** — jalur Jelajah → Extension → daftar manga/novel → Details kini menggunakan snapshot Details terbaru yang tersimpan secara terbatas, sehingga data yang baru dilihat dapat digunakan kembali tanpa mengulang pekerjaan berat yang tidak diperlukan.
- **Initial render Extension Details dibuat non-blocking** — metadata ringan dari daftar dapat tampil lebih dahulu, sementara refresh source dan enrichment Local/download berjalan tanpa menahan snapshot pertama.
- **Extension Details lebih hemat untuk daftar chapter besar** — Miyorare mengecek keadaan cache terlebih dahulu dan hanya mematerialisasi daftar chapter penuh ketika snapshot persisten memang tersedia.
- **Refresh Extension Details lebih konsisten dengan Room** — hasil source yang berhasil dipersist terlebih dahulu sebelum dipublikasikan, lalu layar Details mengikuti snapshot Room yang sudah committed. Reopen dan process recreation dapat menggunakan jalur snapshot durable yang sama.
- **Akses Filter · Urutkan · Tampilan dibuat lebih jelas** — kontrol chapter options di header Details kini menggunakan label teks yang eksplisit, bukan hanya ikon, dan tetap terbaca pada layar yang lebih sempit.
- **Details menampilkan cached chapter lebih cepat** — chapter yang sudah tersimpan di Room dapat muncul sebelum enrichment Local/download selesai.
- **Update chapter di Details lebih efisien** — Details kini mengamati chapter melalui Room Flow per manga, sehingga perubahan chapter manga lain tidak ikut memicu pemrosesan ulang daftar yang sedang dibuka.
- **Navigation cache Details lebih ringan** — manga remote tidak lagi menyimpan salinan daftar chapter lengkap di cache navigasi; Room tetap menjadi sumber durable, sementara percepatan khusus Local tetap dipertahankan.
- **Favourites cold start lebih ringan** — pembukaan awal Favourites memakai snapshot Local yang sudah tersimpan dan tidak langsung melakukan full filesystem scan.
- **Downloaded shelf lebih cepat tersedia** — tampilan awal tidak lagi harus menunggu rebuild penuh Local index; perbaikan index tetap dapat berlangsung di background.
- **Pagination Favourites untuk library besar diperkuat** — daftar dengan ribuan manga dapat terus memuat setelah halaman awal dan tetap menggunakan window bertahap agar penggunaan memori terkontrol.
- **Reader lebih memprioritaskan chapter yang sudah didownload** — exact local/download lookup dilakukan lebih awal agar pembacaan offline tidak tertahan oleh source remote yang lambat atau tidak tersedia.
- **Konteks Normal/Private diteruskan ke Reader** — pembukaan dari Details, Chapters, Pages, maupun Bookmarks membawa FavouriteSpace yang aktif sehingga download dicari dari ruang yang sesuai.
- **Local CBZ lebih cepat dibuka** — flat sidecar-free CBZ menggunakan fast path berbasis ZipFile, archive reuse, dan page cache untuk mengurangi pembacaan ZIP berulang.
- **Resolusi download dipusatkan** — pencarian salinan Local/download, ownership, legacy recovery, serta pemisahan Normal/Private kini ditangani melalui resolver khusus agar Details lebih sederhana dan konsisten.

### 🐞 Diperbaiki

- **Memperbaiki cache Extension Details yang dapat ikut terhapus oleh pembersihan History yang tidak terkait** — routine cleanup kini mempertahankan snapshot Details terbaru dalam batas yang terkontrol, sementara Clear manga data tetap melakukan pembersihan eksplisit sesuai permintaan.
- **Memperbaiki pemulihan identitas Local setelah refresh Details** agar enrichment Local/download tetap terhubung dengan identitas manga/novel yang benar setelah source selesai diperbarui.
- **Memperbaiki kontrol Filter / Sort / Display yang sulit ditemukan** — akses chapter options kini selalu memiliki affordance teks yang terlihat ketika Details sudah memiliki chapter.
- **Memperbaiki Favourites yang dapat berhenti memuat setelah halaman awal** pada library besar akibat pagination permit yang tidak kembali aktif setelah perubahan window database.
- **Memperbaiki downloaded CBZ yang tidak langsung dikenali setelah aplikasi dibuka ulang** — sidecar-free CBZ kini dapat direkonsiliasi kembali ke identitas chapter remote tanpa menunggu broad storage scan.
- **Memperbaiki Reader offline yang dapat terus loading meskipun chapter sudah didownload** dengan memprioritaskan local chapter sebelum page loading remote.
- **Memperbaiki pemetaan chapter CBZ ke ID remote** agar Details dan Reader menggunakan identitas chapter yang konsisten.
- **Memperbaiki potensi stack overflow saat rebuild Local index** dengan menghindari rantai nested Room transaction yang dalam.
- **Memperbaiki potensi stack overflow saat mencari cover Local** dengan mengganti recursive directory traversal menjadi traversal iteratif yang stack-safe.
- **Memperbaiki state chapter kosong** — Miyorare kini dapat membedakan chapter yang belum pernah dimuat dengan source yang memang sudah berhasil dimuat tetapi memiliki 0 chapter.
- **Memperbaiki metadata cache chapter yang dapat ikut berubah oleh write manga biasa** — metadata freshness dan initialization chapter kini hanya diubah oleh jalur persistence Details yang memang berwenang.
- **Memperbaiki isolasi opsi Details dan Reader** — Filter / Sort / Display default milik Details tidak lagi dapat mengubah presentasi chapter sheet atau next/previous semantics di Reader.
- **Memperkuat filter Branch/Scanlator** agar pilihan tetap valid ketika struktur branch atau label scanlator berubah.
- **Memperbaiki sorting alfabet chapter** dengan collation yang mengikuti locale.

### 🛡️ Stabilitas & kompatibilitas

- **Retention cache Details dibuat terbatas dan aman** — snapshot terbaru dipertahankan untuk mempercepat reopen tanpa membiarkan cache tumbuh tanpa batas.
- **Targeted cache purge tetap dipertahankan** — pembersihan eksplisit tetap dapat menghapus data target yang diminta tanpa mencampur ruang Private atau mengubah semantics cleanup.
- **Reader tetap local-first untuk kondisi offline** meskipun Extension Details sekarang dapat menjalankan source dan Local/download enrichment secara paralel.
- **State chapter sekarang persisten dan eksplisit** — database membedakan Not Loaded, Loaded Empty, dan Loaded dengan chapter sehingga snapshot kosong yang valid tetap benar setelah process/database reopen.
- **Chapter cache yang dibersihkan kembali dianggap belum dimuat** sehingga data yang telah di-GC tidak salah diperlakukan sebagai snapshot source yang authoritative.
- **Normal dan Private tetap terisolasi** pada snapshot Local, ownership download, dan Reader resolution.
- **Legacy download tetap dipertahankan tanpa broad scan interaktif** — hasil migrasi dan reconnect yang sudah terverifikasi disimpan sebagai ownership/index persisten agar tidak perlu dicari ulang setiap kali.
- **Local index tetap tersedia selama rebuild** sampai pengganti siap untuk ditukar secara atomic.
- **Fallback CBZ/PDF/EPUB lama tetap tersedia** ketika konten tidak cocok dengan fast path baru.
- **Re-import EPUB dibuat lebih aman** melalui temporary-file validation dan backup swap sebelum file lama digantikan.

### 🔧 Internal

- Menambahkan regression coverage gabungan untuk **Extension Details Stage 1–4**, termasuk recent-cache retention, Room-first resolution, non-blocking first snapshot, post-refresh Local identity recovery, dan Room Flow setelah refresh.
- Menambahkan regression guard agar kontrol **Filter · Sort · Display** tidak kembali menjadi icon-only atau hilang pada Details yang sudah loaded.
- Menghapus sejumlah one-shot workflow/script yang pekerjaannya sudah selesai.
- Menghapus reconnect planner dan content matcher lama setelah jalurnya digantikan oleh ownership/resolver persisten.
- Menghapus jalur legacy Source Pack single-JAR yang sudah tidak dapat digunakan.
- Menambahkan regression coverage khusus untuk chapter persistence, Room Flow per manga, Details hot path, download resolution, navigation cache, Favourites pagination, Local stack safety, cold-open CBZ, external EPUB import, dan Chapter Options.
