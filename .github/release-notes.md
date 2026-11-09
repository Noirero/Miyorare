## Miyorare v1.4.22

This update improves chapter management and EPUB novel reading. Select and update multiple chapters at once, open new text readers in left-to-right page mode by default, and choose when automatic translation should run. Bilingual reading also gains clearer paragraph-by-paragraph presentation.

### ✨ What's New

- **Bulk Chapter Read/Unread** — Long-press chapters in the modern Details screen to enter multi-select, then mark selected chapters as read or unread. The existing Manage Chapters selection menu now supports the same batch actions.
- **Invert Chapter Selection** — Quickly select the chapters not currently selected, within the visible/selectable chapter scope. Select All and an explicit exit action remain available.
- **Automatic Translation on Page Open** — Choose **Off**, **On chapter open**, or **On page open** for each novel. In paged EPUB reading, page-open mode translates only the original text within the opened page rather than automatically requesting unvisited pages.

### 🚀 Improvements

- **Left-to-Right Novel Default** — New text readers default to paged left-to-right navigation when no reading-mode preference has been saved. Existing per-book or global Scroll/RTL preferences are retained, and these modes remain selectable.
- **Clearer Bilingual Paragraphs** — Original and translated paragraphs appear together. The translation uses the normal Reader text style and a subtle accent-coloured side marker, while the original is visually subdued.
- **More Predictable Chapter Actions** — Bulk updates use the existing persisted chapter read-state pipeline. Marking manga chapters unread also clears their corresponding saved page-resume position without fabricating a Reader visit.

### 🐞 Fixes

- **Page Translation and Reflow** — Prevent pagination recalculation from being mistaken for a newly opened page, reducing unnecessary automatic translation requests.
- **Rapid Page Navigation** — Allow subsequent page-open requests to proceed correctly after an earlier translation request is in flight.

**Note:** Automatic page translation applies to paged EPUB modes, not Scroll mode. Translation may still consume provider requests, and previously cached coarse translations may not form complete bilingual paragraph pairs without retranslation.

<details>
<summary><strong>🇮🇩 Bahasa Indonesia</strong></summary>

Pembaruan ini meningkatkan pengelolaan chapter dan pengalaman membaca novel EPUB. Kamu dapat memilih dan memperbarui banyak chapter sekaligus, menggunakan navigasi halaman kiri-ke-kanan sebagai bawaan pembaca teks baru, serta menentukan kapan penerjemahan otomatis dijalankan. Tampilan bilingual juga menjadi lebih jelas per paragraf.

### ✨ Yang Baru

- **Read/Unread Banyak Chapter Sekaligus** — Tekan lama chapter pada halaman Details modern untuk masuk ke mode multi-select, lalu tandai chapter terpilih sebagai sudah dibaca atau belum dibaca. Menu pemilihan **Manage Chapters** juga mendukung aksi massal yang sama.
- **Balik Pilihan Chapter (Invert Selection)** — Pilih chapter yang sebelumnya belum terpilih dalam cakupan chapter yang bisa dipilih, tanpa mengubah status membacanya. Select All dan tombol keluar tetap tersedia.
- **Terjemahan Otomatis Saat Halaman Dibuka** — Pilih **Off**, **On chapter open**, atau **On page open** untuk tiap novel. Pada EPUB dengan mode halaman, terjemahan otomatis hanya memproses teks asli di halaman yang dibuka, bukan meminta terjemahan halaman yang belum dikunjungi.

### 🚀 Peningkatan

- **Default Novel Kiri-ke-Kanan (LTR)** — Pembaca teks baru menggunakan mode halaman LTR apabila belum ada preferensi mode baca yang tersimpan. Preferensi Scroll/RTL global atau per buku yang sudah ada tetap dihormati dan tetap dapat dipilih.
- **Paragraf Bilingual Lebih Jelas** — Paragraf asli dan terjemahan ditampilkan berpasangan. Terjemahan memakai gaya teks Reader normal dengan penanda aksen tipis di samping, sementara teks asli dibuat lebih lembut.
- **Aksi Chapter Lebih Konsisten** — Perubahan status banyak chapter menggunakan sistem Read/Unread tersimpan yang sama. Menandai chapter manga belum dibaca juga menghapus posisi resume halaman terkait, tanpa membuat kunjungan Reader palsu.

### 🐞 Perbaikan

- **Terjemahan Halaman dan Reflow** — Mencegah perhitungan ulang tata letak halaman dianggap sebagai halaman baru yang dibuka sehingga mengurangi permintaan terjemahan otomatis yang tidak perlu.
- **Navigasi Halaman Cepat** — Memastikan permintaan terjemahan untuk halaman berikutnya tetap bisa diproses setelah permintaan sebelumnya sedang berjalan.

**Catatan:** Terjemahan otomatis per halaman berlaku pada mode EPUB berpaginasi, bukan Scroll. Penerjemahan dapat menggunakan request provider, dan hasil terjemahan lama yang tersimpan sebagai segmen besar mungkin memerlukan penerjemahan ulang agar terbentuk pasangan paragraf bilingual yang lengkap.

</details>
