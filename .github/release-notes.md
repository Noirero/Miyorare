## Catatan penting sebelum rilis

Versi ini masih dikembangkan secara bertahap. Beberapa bagian berikut belum selesai atau belum diperiksa secara menyeluruh:

1. Tema Modern belum diterapkan secara merata pada seluruh bagian aplikasi.
2. Masalah delay atau keterlambatan respons belum diperiksa secara menyeluruh.
3. Pengembangan dan perbaikan fitur Novel belum menjadi fokus pada tahap ini.
4. Tata letak tulisan pada halaman detail manga belum dirapikan.
5. Loading saat membuka halaman detail manga belum dihilangkan atau dioptimalkan sepenuhnya.
6. Pada Unduhan → kategori → Pilih semua, pilihan belum mencakup seluruh manga.
7. Menu login Google belum diperbaiki.
8. Dua mode Disukai belum ditambahkan.

Seluruh bagian tersebut akan diperiksa dan diperbaiki secara bertahap pada pembaruan berikutnya.

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
