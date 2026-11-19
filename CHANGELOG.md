# Changelog

Format mengikuti [Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/) dan versi mengikuti [Semantic Versioning](https://semver.org/lang/id/).

## [Belum dirilis]

### Diperbaiki / ditambah (Detail Card, bukti, paket)
- **Pratinjau = foto jadi**: layar review kini memakai satu waktu foto (`shotT`) untuk pratinjau, validasi, dan simpan. Sebelumnya pratinjau memanggil `TrustedClock.now()` sendiri, sehingga jam, selisih, laju, dan kode Ref di foto tersimpan bisa berbeda dari yang terlihat saat review. `projM` menerima waktu eksplisit.
- **Kode Ref bertanda tangan** (`proofCode`, `Integrity.kt`): 8 karakter heks dari HMAC kunci Keystore atas nomor urut, waktu, unit, angka, dan tanda tangan sebelumnya. Dicetak di Detail Card dan di Detail Foto. Menu *Atur → Cek kode Ref foto* mencocokkan kode dari foto yang dibagikan ke jejak audit di perangkat dan menampilkan status integritasnya. Bila kunci tidak tersedia, dipakai kode waktu lama.
- **Baris bukti Detail Card** (`WmEvidence`, `wmEvidenceLines`): sumber waktu (GPS/HP) dan selisih jam HP bila ≥ 2 menit; asal angka; sumber foto (kamera / GALERI tanpa cek kamera dan GPS / impor); "GPS tidak tersedia" bila GPS diminta tetapi tidak ada; jumlah peringatan validasi. Baris yang perlu perhatian berwarna kuning.
- **Keterbacaan**: Detail Card tidak lagi digambar lebih kecil dari ukuran sedang meski ukuran watermark "kecil"; teks panjang mengecil paling jauh 80% lalu dipotong; teks kecil dinaikkan ke 8,5–9 pt. Tinggi kartu dihitung `detailHeightUnits` (dites), bukan angka ajaib.
- **Paket terikat perangkat**: paket ditandatangani kunci Keystore (`tierSig`, `verifiedTier`). Mengubah `tier` di backup atau basis data tanpa tanda tangan yang cocok mengembalikan ke Basic, dan pemulihan backup tidak pernah memberi paket. Ini menutup celah ubah-data, bukan lisensi: pembayaran/lisensi sungguhan tetap butuh server atau Play Billing.
- Tes baru: `proofCode`, `normRef`, `wmEvidenceLines`, `srcNoteOf`, `detailHeightUnits`, dan penandatanganan paket.
- Perubahan perilaku: pengguna yang sebelumnya mengaktifkan Premium/Platinum (demo) sebelum versi ini kembali ke Basic sekali, karena belum punya tanda tangan paket; aktifkan ulang di *Atur → Paket Layanan*.

### Ditambahkan
- **Paket layanan Basic / Premium / Platinum** (`Tier.kt`): fitur lama tetap ada di Basic; fitur baru dikunci per paket. Pilih paket di *Atur → Paket Layanan*. Aktivasi masih lokal (demo), pembayaran belum tersambung. Paket disimpan di backup (`tier`).
- **Kamera (Premium/Platinum)**: penunjuk *Level* (garis tengah ikut kemiringan HP, hijau bila lurus; Premium) dan *Kunci AF/AE* (fokus & eksposur dikunci pada angka, tidak dipicu ulang otomatis; Platinum).
- **Edit foto saat menyimpan** (`Edit.kt`): putar 90° (Premium), atur kontras (Platinum). Diterapkan hanya pada foto ber-watermark; OCR dan foto asli tetap memakai foto mentah sehingga jejak audit tidak berubah.
- **Hitungan & analitik** (`Analytics.kt`): laju pemakaian harian, prediksi tanggal servis dengan tingkat keyakinan (Premium); tren naik/turun dan perbandingan semua unit (Platinum); ekspor CSV semua riwayat (Premium); laporan teks siap bagikan (Platinum). Kartu *Analitik* di Beranda.
- Template watermark Minimal, Bold, dan Top Strip kini di paket Premium (Basic memakai Pro Card dan Classic).
- Tes unit `TierTest`, `AnalyticsTest`, `EditTest`.
- **Kamera lebih profesional** (`ui/Camera.kt`, `CameraAssist.kt`):
  - *Peredup di luar kotak* `[ ]` supaya perhatian tertuju ke angka meter; kisi sepertiga opsional (chip *Kisi*).
  - *Cincin rana berwarna* mengikuti status bacaan (putih = belum ada, hijau = terkunci & valid, kuning = periksa/goyang, merah = ditolak), getar halus sekali saat angka terkunci valid.
  - *Peringatan goyang* (`MotionGate`, akselerometer dengan histeresis, berhenti saat aplikasi di latar): "Tahan HP lebih stabil".
  - *Zoom cepat* 1×/2×/4× sesuai kemampuan lensa (kotak pelacak dihitung ulang, menahan zoom otomatis 10 dtk).
  - *Umpan balik jepret*: kilat layar singkat dan bunyi rana; *tombol volume* ikut memotret saat kamera terbuka.
  - Layar tetap menyala selama kamera terbuka; cincin fokus beranimasi dan berwarna sesuai status fokus; ketuk nilai EV untuk mengembalikan ke 0; baris alat dapat digulir sehingga tidak meluap di layar sempit.
  - Watermark digambar di atas peredup sehingga pratinjau tetap sama dengan hasil foto. Tes `CameraAssistTest`.
  - *Kamera mode latih* (`cm == "train"`): lencana **MODE LATIH · N sampel** dan penghitung sampel (total · manual) di kamera, tombol *Selesai* kembali ke Pusat latih; bacaan live diterima apa adanya (`trainReading`, tanpa pemeriksaan riwayat/interval unit sehingga meter apa pun bisa dilatih), tanpa watermark, peringatan servis, `StopSheet`, maupun peringatan \"Ini KM, bukan HM\"; petunjuk \"Variasikan jarak, sudut & cahaya\".
- **Kamera: penyempurnaan lanjutan** (`ui/Camera.kt`, `CameraAssist.kt`):
  - *Jepret otomatis* (chip **Jepret otomatis**, `AutoShootGate`): memotret sendiri bila bacaan valid dengan nilai yang sama selama 1 dtk dan HP tidak goyang; jeda 3 dtk setelah menembak agar tidak beruntun. Memakai alur `snap()` yang sama (watermark, review, cek kualitas tidak berubah).
  - *Bar ketajaman* (`sharpFraction`) merah/kuning/hijau mengikuti ambang `SHARP_BLOCK`/`SHARP_WARN`.
  - *Petunjuk situasional*: silau (miringkan HP), layar berkontras rendah/berpendar seperti LCD oranye (kunci AF/AE), dan drum mekanik (tegak lurus kaca agar desimal 1/10 terbaca).
  - *Area baca menghindari stempel*: kotak tanggal/jam/koordinat di dekat angka (mis. stempel kamera GPS) dikenali dari OCR penuh dan dipangkas dari area baca, baik di pratinjau maupun saat jepret (`trimRoiAwayFromStamps`; kotak angka tidak pernah dipotong).
  - Tes `AutoShootGateTest` dan `TrimRoiTest`.
- **Watermark: template Detail Card** (`Watermark.kt`, paket Premium): waktu + zona (WIB/WITA/WIT), tanggal, status, unit, operator, tiga angka utama (HM/ODO saat ini, Terpakai, Sisa), selisih dari pembacaan sebelumnya beserta lama waktunya, laju pemakaian, perkiraan tanggal servis, SMR dan Next Maintenance, bar interval berpersen, lokasi/GPS, dan baris bukti: sumber waktu (GPS atau HP), asal angka (OCR terverifikasi / OCR / diedit manual) dan kode `Ref` dari waktu foto. Teks panjang mengecil lalu dipotong otomatis. Tes `WatermarkDetailTest`.
- Watermark Pro Card kini menulis **ODO SAAT INI** untuk unit km (sebelumnya selalu "HM SAAT INI"); Classic menulis "ODO".
- **Akurasi hitungan & penyesuaian HM** (`Validation.kt`, `Data.kt`, `Analytics.kt`, `ui/Sheets.kt`):
  - *Tafsiran lain dari bacaan* di lembar review: ketuk untuk memakai titik desimal yang digeser satu digit atau penukaran satu digit mirip (0/8, 5/6, 1/7, ...) yang lolos pemeriksaan riwayat (`alternativesOf`), masing-masing dengan keterangan lolos/tidak lolos. Juga rincian selisih terhadap pembacaan terakhir beserta batas wajarnya (`describeRise`).
  - *Deteksi lonjakan di atas pemakaian biasa* (`rateAnomaly`): kenaikan yang masih di bawah batas fisik tetapi lebih dari 3x pemakaian biasa unit (laju 4 pembacaan terakhir) + kelonggaran 12 jam / 300 km, setelah lewat 1 hari, kini diberi peringatan dan ikut memicu koreksi digit otomatis.
  - *Petunjuk desimal pembacaan pertama*: untuk profil yang lazim berdesimal (drum 1/10 jam, LCD HM) dan titik tidak terbaca, digit terakhir dianggap desimal ("10410" menjadi 1041.0) dengan catatan agar dicocokkan dengan meter asli.
  - Tes `HmAccuracyTest`.
- **Sistem latih kamera** (`Training.kt`, `Trainer.kt`, `DigitModel.kt`, `ui/Training.kt`, `tools/train_digits.py`, `docs/TRAINING.md`), tiga lapis yang digabung:
  - *Belajar otomatis*: tiap bacaan yang disimpan dipelajari (digit yang tertukar, pola digit/desimal per unit, lintasan OCR yang benar) dan potongan area angkanya disimpan sebagai sampel (maks. 300). Hasilnya dipakai untuk koreksi digit yang pernah tertukar (`learnedRepair`), pemasangan titik desimal sesuai pola unit (`refine`, tidak bila jumlah digit tidak lazim sehingga drum desimal merah yang terlewat tidak menjadi `104.1`), melewati lintasan yang tak pernah menang (`passPlan`), dan mencoba polaritas terbalik lebih dulu di kamera live (`preferInverse`).
  - *Latih manual*: mode kamera `train` menyimpan foto + angka benar sebagai sampel bobot 3x; pratinjau potongan memastikan yang disimpan memang angka meter. **Latih ulang & uji** membangun ulang statistik dan mengukur akurasi secara berurutan.
  - *Model sendiri*: ekspor dataset zip, latih CNN+CTC dengan `tools/train_digits.py`, pasang `hm_digits_model.zip`; dipakai di kamera live (jalur cepat bila yakin >= 0,85) dan sebagai suara tambahan pada pembacaan foto.
- `readFinalDetailed` (bacaan per lintasan + area angka); `resolveReading` dan `chooseFinal` menerima pasangan salah-baca hasil belajar.
- Dependensi `org.tensorflow:tensorflow-lite` 2.14.0; tes `TrainingTest`.
- Kamera mencari angka meter otomatis di seluruh frame (`pickMeter`), kotak `[ ]` mengikuti angka, serta fokus dan eksposur otomatis ke angka itu. Ketukan memilih angka yang dimaksud (10 detik) dan fokus manual diutamakan 4 detik. Tanpa angka ditemukan, kotak tetap dipakai seperti sebelumnya. Area baca saat jepret mengikuti angka yang dilacak.
- Struktur repo profesional: katalog versi Gradle (`gradle/libs.versions.toml`), workflow CI (tes, lint, build) dan rilis, Dependabot, template issue/PR.
- Dokumentasi: README, CONTRIBUTING, CODE_OF_CONDUCT, SECURITY, `docs/ARCHITECTURE.md`, `docs/AUDIT.md`, `docs/RELEASING.md`.
- `.editorconfig`, `.gitattributes`, `.gitignore` yang diperluas, lisensi MIT.
- Konfigurasi Android Lint dengan laporan HTML/XML.

- **Pengenalan HM** (`HmFinder.kt`): `pickMeter` membaca label di dekat angka (`HM`, `HOUR(S)`, `SMR`, `JAM`, `KM`, `ODO`, `RPM`, `SN`, `x100`, `%`, ...), baik di kiri/kanan maupun di atas/bawah angka atau menempel pada angka (`SN12345`, `1234.5H`). Label yang cocok dengan unit menaikkan skor, label lain (RPM, nomor seri, suhu, tekanan) menurunkannya; jam/tanggal (`12:30`) dan pengali (`x100`) dilewati; titik desimal yang terpisah disambung; angka 5-7 digit dan berdesimal diutamakan. Kotak kuning "Ini KM, bukan HM" bila label tidak cocok dengan unit.
- **Pengendali fokus** (`FocusPolicy`): fokus dipicu hanya bila angka baru/bergeser, foto buram, fokus gagal (jeda makin panjang, berhenti setelah 4 kali sampai angka bergeser), atau penyegaran 8 dtk; jeda minimum 0,9 dtk dan tidak saat ketukan manual. Area ukur dipersempit ke angka, ditambah white balance, hasil fokus dibaca dari `FocusMeteringResult` dan ditampilkan (titik kuning/hijau/merah, petunjuk saat gagal), kamera tanpa autofokus dilewati.
- **Zoom otomatis** (`autoZoomFor`): angka yang tingginya di bawah 5,5% frame diperbesar bertahap (maks. 1,6x per langkah, 4x total) hanya bila angka tetap utuh di frame; angka kebesaran diperkecil. Koordinat kotak dihitung ulang setelah zoom. Cubit atau ketukan menahannya 10 dtk; tombol *Zoom oto* mematikannya.
- **Kotak pelacak stabil** (`mergeTrack`): gerakan kotak dihaluskan dan pindah ke angka lain hanya bila skornya minimal 1,4x; satu pencarian yang meleset tidak lagi menghapus kotak.
- Tes unit `HmFinderTest`, termasuk 5 kasus dari foto meter asli (odometer truk dengan dial dan stempel `22.22.44`, odometer di bawah `km/h`, HM ekskavator `9127 0h` dengan koordinat UTM, drum `ENGINE HOURS`, LCD kecil).
- **Foto dari galeri** kini memilih angka meter dengan `pickMeter` (label, ukuran, posisi) lalu membacanya ulang tiga lintasan, bukan lagi angka dengan digit terbanyak yang sering berupa koordinat atau stempel waktu (`readFinal(..., want)`).

### Diubah (hitungan HM)
- **Ambang status per satuan** (`warnLevels`): unit jam tetap kuning < 20 dan merah < 10. Unit km memakai 5% dan 2% interval (minimal 20 dan 10 km), sebab ambang tetap 10/20 km baru memperingatkan setelah hampir jatuh tempo pada interval 10.000 km. Teks di Pengaturan dan `StopSheet` mengikuti ambang ini.
- **Perkiraan tanggal servis** di Beranda memakai laju terbaru (4 pembacaan terakhir) seperti prediksi Analitik, bukan rata-rata sejak servis yang menyamarkan lonjakan pemakaian.
- **Prediksi Analitik** menghitung jatuh tempo dari waktu pembacaan terakhir (sisa interval dihitung pada saat itu), tidak dari "sekarang" yang menggeser prediksi bila pembacaan sudah lama. Ditambah *perkiraan HM sekarang* di laporan.
- Selisih pemakaian dan sisa dibulatkan 2 desimal sehingga derau desimal biner tidak tampil (mis. 0.30000000000000426).
- Unit **km** tidak lagi menafsirkan digit berlebih sebagai desimal (odometer bulat); sebelumnya "500000" bisa diam-diam menjadi 50000.0.

### Diubah
- Tinggi minimum angka turun dari 2% ke 1% tinggi frame (angka kecil tetap kalah skor dari yang besar). Sebelumnya angka kecil tidak pernah dilacak sehingga zoom otomatis tidak pernah aktif.
- `km/h` tidak lagi dianggap label angka lain (odometer berada tepat di bawahnya, sebelumnya kena penalti RPM); jam bertitik (`22.22.44`, `6.15.01`) dan pecahan `1/10` dilewati; label di atas/bawah angka dicari sampai 2,2x tinggi angka.
- `pickMeter` menerima parameter `want` (HM/KM), `MeterHit` memuat `score` dan `tag`, `NumTrack` memuat `tag`; semua bawaan lama tetap kompatibel.
- Elemen non-angka (label) kini memutus penggabungan angka, dan huruf label yang menempel tidak lagi "dikoreksi" menjadi angka (`SN12345` tadinya `512345`).
- `qualityOf` dipecah menjadi `statsOf` + `judgeQuality` agar ketajaman terukur bisa dipakai pengendali fokus.
- Dependensi dan plugin Gradle dipindahkan ke version catalog (tanpa perubahan versi).
- Workflow `build-apk.yml` digantikan `ci.yml` dan `release.yml`.

## [1.4.0] - Tampilan baru
### Ditambahkan
- Gauge busur 270° bergradien dengan cahaya berdenyut, penanda ujung, skala garis, angka sisa yang menghitung naik; kartu status berwarna sesuai kondisi.
- Navigasi melayang dengan tombol kamera berdenyut, efek tekan di semua tombol, blok Beranda muncul bertahap.
- Grafik batang animasi, riwayat garis waktu, kartu bergradien, galeri dengan lencana HM, splash berpendar.
- Tema warna lebih dalam (`cardHi`, `track`) dan helper animasi (`press`, `glow`, `Reveal`, `AnimNum`).

## [1.3.0] - Audit bertanda tangan
### Ditambahkan
- Audit ditandatangani HMAC-SHA256 dengan kunci Android Keystore, dirantai dengan entri sebelumnya.
- Deteksi: catatan diubah, catatan dihapus/disisipkan, foto diganti, HM riwayat diubah. Menu *Atur → Periksa integritas jejak audit*.
- Penanda "Sumber: galeri" untuk foto yang tidak melewati pemeriksaan kamera/GPS.
### Diubah
- Skema DB naik ke versi 2 (kolom `n`, `prev`, `sig`, `src`) dengan migrasi otomatis.

## [1.2.0] - Penyimpanan & rilis
### Ditambahkan
- Penyimpanan SQLite (`hmt.db`: unit, reading, photo, audit, meta) dengan migrasi otomatis dari SharedPreferences.
- Backup JSON `schema: 2` memuat seluruh jejak audit; backup web lama tetap bisa dipulihkan, backup dari versi lebih baru ditolak.
- Penandatanganan rilis lewat secret GitHub dan `mapping.txt`.
### Diubah
- Tulis atomik (transaksi) di thread terpisah; foto dipangkas sampai 200 terbaru, jejak audit tidak pernah dihapus.

## [1.1.0] - Kamera profesional
### Ditambahkan
- Analisis live 1280x960 tiap ~0,5 dtk dengan penguncian angka; preview, analisis, dan foto dipaksa rasio 4:3.
- OCR ulang pada foto resolusi penuh (3 lintasan); perbaikan salah baca O/I/l/S/B; titik desimal otomatis bila satu-satunya tafsiran masuk akal.
- Validasi hitungan, layar konfirmasi wajib, jam GPS + `elapsedRealtime`, jejak audit per foto dengan SHA-256.
- Kontrol kamera: fokus/eksposur ketuk, zoom, senter, kompensasi eksposur, foto 2560 px, penolakan foto buram/gelap.

## [1.0.0] - Rilis awal
### Ditambahkan
- Port native Kotlin + Jetpack Compose dari versi web: login, status, galeri, pengaturan, kamera dengan watermark 5 template, tema gelap/terang, 8 warna aksen.

[Belum dirilis]: ../../compare/v1.4.0...HEAD
[1.4.0]: ../../releases/tag/v1.4.0
[1.3.0]: ../../releases/tag/v1.3.0
[1.2.0]: ../../releases/tag/v1.2.0
[1.1.0]: ../../releases/tag/v1.1.0
[1.0.0]: ../../releases/tag/v1.0.0
