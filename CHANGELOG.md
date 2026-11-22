# Changelog

Format mengikuti [Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/) dan versi mengikuti [Semantic Versioning](https://semver.org/lang/id/).

## [Belum dirilis]

### Farm tractor: pilihan merek dan profil meter
- Kategori **Farm tractor** kini menyediakan pilihan John Deere, Kubota, Massey Ferguson, New Holland, Case IH, Fendt, Yanmar, Mahindra, Deutz-Fahr, Iseki, Kioti, serta **Traktor lainnya**.
- Ditambahkan profil OCR HM untuk merek-merek tersebut dan profil umum farm tractor, dengan istilah panel `HOUR`, `HOURS`, `HR`, `ENGINE`, dan `HM` sebagai prior ringan. Angka tetap harus dicocokkan dengan meter asli dan sampel foto unit.
- Tes katalog dan inferensi profil farm tractor ditambahkan.

### Foto contoh docs/samples dikompres
- Empat foto anotasi dikompres ulang tanpa mengubah dimensi atau rasio (720×960 dan 1200×1600): 660 KB → 310 KB. EXIF (termasuk tag lokasi GPS) dibuang. Dataset hasil bangun (`docs/samples/dataset/images`) tidak disentuh karena itu data latih.

### Latih kamera: bantuan AI gratis (opsional) + latih di Colab
- **Bantuan AI** (`AiAssist.kt`, kartu *4 · Bantuan AI gratis* di Latih Kamera): tombol **Tebak angka dengan AI** di layar Sampel latih mengirim potongan area angka ke Google Gemini (REST `generateContent`) dengan kunci Google AI Studio milik pengguna dan mengisi kolom angka. Mati secara bawaan; tebakan tidak pernah disimpan otomatis (pengguna tetap mencocokkan dengan meter asli), tidak dipakai di alur catat HM, dan hanya potongan angka yang dikirim (tanpa watermark/lokasi/nama unit). Jawaban hanya diterima bila berbentuk angka meter (digit + maks. satu desimal). Galat 400/401/403/404/429 dan tanpa internet diberi pesan jelas.
- Kunci disimpan di prefs `hm_ai` yang dikecualikan dari backup (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`, diaktifkan di manifest). `Field` punya parameter `secret` untuk menyamarkan kunci.
- **Latih tanpa komputer**: `tools/train_colab.ipynb` menjalankan `train_digits.py` di Google Colab gratis. Panduan di `docs/TRAINING.md`.
- Tes baru: `AiAssistTest`. Belum dikompilasi atau dicoba di HP: jalankan `./gradlew testDebugUnitTest assembleDebug`, lalu uji dengan kunci sungguhan (cek nama model dan jatah di AI Studio).

### Tombol premium + status watermark "READY"
- **Watermark**: label status hijau di semua template kini tertulis **READY** (sebelumnya LANCAR). `PERINGATAN` dan `WAJIB SERVIS` tidak berubah. Lebar kapsul dihitung dari teksnya, jadi tata letak tetap rapi. Teks "Lancar" di tab Status aplikasi, laporan teks, dan tutorial tidak diubah.
- **Tombol utama dan sekunder** (`ui/Components.kt`): `PrimaryBtn` kini bergradasi dengan pantulan cahaya di separuh atas, garis kilau di tepi, dan cahaya warna di bawahnya; `OutBtn` bergaya kaca (gradasi tipis + tepi terang). Semua tombol punya efek tekan yang mengecil sedikit lalu memantul kembali, ditambah getar halus (`press(haptic = false)` untuk tombol kamera yang sudah punya getar sendiri).
- **Komponen baru**: `GhostBtn` (kapsul berlatar tipis untuk "+ Unit", "← Atur"), `DialogBtn` (tombol AlertDialog: berisi warna untuk aksi utama/berbahaya, kapsul tipis untuk batal), serta pembantu `sheenBrush` dan `Modifier.gloss`.
- **Toggle, Seg, Pill, Sheet**: Toggle bergradasi aksen dengan cahaya dan pegas, Seg terpilih menyala dengan cahaya, Pill bergradasi bertepi tipis, Bottom Sheet bersudut 24 dp dengan pegangan geser.
- **Tombol yang tadinya polos** kini ikut bergaya dan beranimasi tekan: tombol masuk di Login (`GoldBtn`) dan tab WhatsApp/Email, "Ganti nomor / email", "Lewati" di Tutorial, hapus unit/pembacaan ("✕" / "Hapus?"), titik warna aksen, kartu template watermark, baris konfirmasi di Sheets, serta chip kamera (zoom, EV, izin GPS, SegChip, CamSeg). Dialog di Login, Atur, dan Latih Kamera memakai `DialogBtn`.
- Belum dikompilasi atau dicoba di HP: jalankan `./gradlew assembleDebug`, lalu cek tampilan di mode gelap dan terang.

### Tutorial awal otomatis + 8 template watermark baru
- **Tutorial penggunaan** (`ui/Tutorial.kt`): tampil sekali setelah instal, 7 langkah yang berjalan sendiri (bar progres, jari peraga yang mengetuk tombol kamera dan tombol simpan, kurung `[ ]` auto detektor, pratinjau template watermark, gauge status servis). Tombol **Lewati** selalu tampil; **Lanjut/Kembali** dan tombol Back HP juga berfungsi. Bendera "sudah tampil" disimpan di prefs terpisah (`hmt1onb`) sehingga tidak ikut terhapus oleh Reset data atau ganti akun. Bisa diputar ulang dari **Atur > Tutorial Penggunaan**.
- **8 template watermark baru**: Kapsul (`capsule`), Tiket (`ticket`), Gauge Ring (`ring`), Dashboard (`grid`), Cap Tanggal (`stamp`), Banner Status (`banner`), Glass Light (`glass`), Poster (`poster`). Semuanya digambar oleh `drawWatermark` yang sama dengan kamera dan foto jadi, jadi pratinjau, overlay kamera, dan hasil foto selalu sama.
- Daftar template dipindah ke satu tempat, `WM_TEMPLATES` di `Watermark.kt` (id, nama, deskripsi, `premium`). Template baru ikut paket Premium seperti Minimal/Bold/Top Strip/Detail; ubah `premium = false` pada entri terkait untuk membukanya di Basic. Kartu template yang terkunci kini diberi label PREMIUM.
- Tes baru: `WatermarkTemplatesTest`.

### Dataset: foto ODO Dt 14 (28481) + contoh gangguan
- `docs/samples/dataset/` (format ekspor aplikasi) dibangun dari `dataset_spec.json` oleh `tools/make_dataset.py` (`build`, `check`, `--zip`): 1 sampel target `28481` (ODO/KM, LCD 7-segmen, potongan tanpa coretan) dan 8 contoh gangguan (angka speedometer, tanggal, logo).

### Meter drum QUARTZ: potongan tepat di batas drum, koma, dan drum 1/10 terbaca terpisah
- **Potongan tidak lagi memotong angka merah**: pada foto acuan 00991, pemotong lama memakai tengah gugus merah dan jatuh di dalam drum 1/10 (x≈367 dari batas drum sebenarnya x≈355) karena hanya sebagian angka merah yang lolos `isRedPx`. `findDrumCut` kini mundur melewati latar PUTIH drum 1/10 sampai celah gelap (`drumLeftEdge`); hasil stabil di ukuran bingkai berbeda (x≈358-359 pada tiga ukuran uji, dihitung dengan cermin Python dari logika ini pada foto yang coretannya dihapus). Bila kontras kecil atau tidak ada latar putih, aturan lama dipakai.
- **Koma ditentukan tata letak, bukan OCR**: `drumDigitsOnly` membuang semua titik/koma dari bacaan drum yang dihitung (kamera live, foto final, semua lintasan). Sebelumnya `0099.1` lolos `normalizeCandidate` sebagai desimal 99,1.
- **Drum 1/10 dibaca terpisah** (`drumTenthCrop`, `pickTenth`): satu digit dari empat lintasan OCR, dipakai hanya bila minimal dua lintasan sepakat tanpa seri dan bacaannya tepat satu digit. Tampil di Preview sebagai info (`Drum 1/10 jam terbaca 2 (≈ 12 menit)`). Tidak ditambahkan ke HM; `composeDrumValue(..., includeTenth = true)` sudah tersedia bila nanti ingin `991.2`.
- **Dataset zona**: `docs/samples/drum_zones.json` + `tools/drum_zones.py` (`add`, `report`) menyimpan kotak zona merah/hijau/kuning dan rasio tata letak dari foto acuan bercoretan.
- File baru `DrumLayout.kt`, `DrumLayoutTest`; tes baru di `DrumSplitTest`. `FinalRead` punya kolom `tenth`. Belum dikompilasi atau dicoba di HP: jalankan `./gradlew testDebugUnitTest`, lalu uji dengan foto drum tanpa coretan.

### Kamera: bingkai baca manual (lebih akurat, fokus satu titik)
- **Bingkai baca manual** (Pro → *Bingkai baca* → *Bingkai manual*): bingkai di layar yang bisa digeser (seret isinya) dan diubah **lebar dan tingginya** (tarik lingkaran di keempat sudut, sudut seberang tetap diam), atau diatur lewat dua penggeser. Pratinjau cepat: *Baris panjang* (meter drum 6 angka/odometer), *Kotak LCD*, *Tengah*. Posisi dan ukuran disimpan dan dipakai lagi saat kamera dibuka; chip *BINGKAI MANUAL* di atas baris zoom menandakan mode aktif dan sekali ketuk mematikannya.
- **Hanya area bingkai yang dibaca**: OCR langsung dan area baca foto memakai bingkai itu (`rectToFrame`, setara `boxToFrame` untuk kotak sembarang), tanpa pencarian angka di seluruh frame, jadi tulisan lain di sekitar meter (label, nomor seri, stempel) tidak ikut terbaca.
- **Autofokus dan eksposur satu titik**: fokus + eksposur + white balance dikunci di titik tengah bingkai dengan area ukur kecil (`ReadFrame.focusSize`), dipicu 0,6 dtk setelah bingkai berhenti digeser, diulang bila foto mulai buram (paling cepat tiap 4 dtk), dan ketuk di dalam bingkai = fokus ulang. Kunci AF/AE tetap dihormati.
- Model `ReadFrame` (geometri murni) di `CameraAssist.kt`; ikon baru `frame`; tes baru `ReadFrameTest`. Belum dikompilasi atau dicoba di HP: jalankan `./gradlew testDebugUnitTest`, lalu coba menggeser dan menarik sudut bingkai di beberapa ukuran layar.

### Watermark: rincian pemakaian dan estimasi BBM
- **HM awal → akhir** di Pro/Detail Card: baris baru `HM 1.234,5 (1 Mei) → 1.250,0 (4 Mei)` dari pembacaan sebelumnya ke pembacaan foto, ditambah *rata-rata per hari* bila jedanya 20 jam atau lebih (untuk jeda lebih pendek rata-rata harian tidak ditampilkan karena menyesatkan). Satuan km memakai label `ODO`. Baris selisih `+15,5 jam sejak …` yang sudah ada tetap tampil.
- **Estimasi BBM** (baris kuning): `Estimasi BBM ≈ 120 L ±18,0 L (102–138 L)  ·  8,0 L/jam`. Rumus: jam terpakai × konsumsi (satuan jam, L/jam) atau km terpakai ÷ konsumsi (satuan km, km/L); toleransi ± dalam persen (bawaan 15%, 0–50%). Ini perkiraan dari konsumsi rata-rata, bukan hasil ukur tangki. Baris hanya muncul bila konsumsi unit diisi.
- **Data unit baru**: `fuelRate` (konsumsi) dan `fuelTolPct` (toleransi) disimpan di SQLite (skema DB 4 → 5, kolom `fuel_rate`/`fuel_tol`, migrasi otomatis) dan di backup JSON; data lama tetap terbaca dengan nilai bawaan.
- **Atur**: kartu *Konsumsi BBM* (konsumsi + toleransi, dengan contoh hasil dari 2 pembacaan terakhir) dan kotak centang *Estimasi BBM* di isi watermark; kotak *Rincian* kini menyebut HM awal → akhir.
- Logika murni di `Watermark.kt` (`usagePeriod`, `fuelEstimate`, `wmUsageLines`, `wmFuelLines`, `wmCardLines`); tes baru `WatermarkFuelTest`. Belum dikompilasi atau dicoba di HP: jalankan `./gradlew testDebugUnitTest` dan cek tinggi kartu di foto lanskap.

### Kamera: tombol bergaya profesional
- **Satu bahasa desain tombol** (`ui/Camera.kt`, token `CamUi`): bahan kaca dan tepi bergradien yang sama di semua tombol; ukuran sentuh minimum 48 dp (tombol atas 42 → 48 dp, tombol Galeri/Atur 52 → 54 dp, tombol tutup panel 34 → 40 dp, segmen zoom min. 44×36 dp, segmen panel min. 40 dp).
- **Tombol rana baru** (`ShutterButton`): bagian dalam mengecil dan cincin luar sedikit menyusut saat ditekan (rasa tombol fisik); cincin tetap berwarna status bacaan; hitung mundur dan tanda *A* jepret otomatis tetap di tengah.
- **Status aktif lebih jelas**: tombol rel kanan memakai lingkar aksen + latar tipis (tetap terbaca di bawah matahari); tombol panel Pro punya titik aksen saat aktif dan lencana paket kuning pada fitur terkunci.
- **Lencana jumlah mode aktif di tombol Pro** (AF/AE terkunci, jepret oto, timer, EV, kilat foto) sehingga pengaturan yang masih menyala terlihat walau panel tertutup (`activeModeCount` di `CameraAssist.kt`).
- **Getar halus tiap ketukan tombol** lewat `LocalCamBuzz`, mengikuti opsi *Getar*; tombol rana sengaja tidak ditambah getar karena alur jepret sudah punya umpan balik sendiri.
- **Aksesibilitas**: semua tombol punya deskripsi dan peran tombol (`camTap`) untuk pembaca layar, termasuk status aktif/terkunci.
- **Panel Pro**: pegangan di atas, judul bagian dengan garis pemisah, tombol *Atur ulang* bergaya kapsul.
- **Cincin kemajuan jepret otomatis**: saat *Jepret oto* aktif dan angka valid, busur putih terisi searah jarum jam di tombol rana selama angka stabil (1 dtk) dan HP tenang, jadi pengguna tahu kapan foto akan diambil (`AutoShootGate.progress`). Nol saat goyang, bacaan berubah, atau jeda setelah menembak.
- **Zoom halus** di panel Pro (bagian *Zoom*): penggeser berskala log dari zoom minimum sampai maksimum lensa dengan magnet dan getar di 1×/2×/4×; ketuk nilai zoom = kembali 1×. Kotak pelacak angka dan penahan zoom otomatis memakai jalur yang sama dengan pratinjau zoom (`zoomToFrac`, `zoomFromFrac`, `zoomSnap`, `fmtZoom`).
- Getar ganda saat memilih pratinjau zoom dihapus (getar kini hanya dari tombolnya).
- Tes baru `ZoomAndProgressTest`.
- Tes baru `activeModeCountOnlyCountsModesThatChangeThePhoto`. Belum dikompilasi atau dicoba di HP: jalankan `./gradlew testDebugUnitTest` lalu cek tampilan di beberapa ukuran layar.

### Kamera: menu dan fitur lebih profesional
- **Menu dirapikan** (`ui/Camera.kt`): pintasan harian (senter, kilat foto, timer, kisi, histogram) jadi satu kapsul kaca di kanan dengan ikon + status; semua pengaturan lain ada di panel **Pro** bersusun (*Asisten · Tampilan · Eksposur · Pemotretan*), bisa digulir, dengan tombol *Atur ulang*. Fitur paket lebih tinggi kini terlihat (redup + label Premium/Platinum, ketukan menjelaskan paketnya), bukan disembunyikan.
- **Histogram kecerahan langsung** dengan penanda piksel terbakar, dan **saran eksposur** (chip kuning \"Terlalu terang · turunkan eksposur · ketuk\") yang hanya tampil bila bertahan 2 siklus agar tidak berkedip. Berguna untuk LCD/LED yang silau.
- **Penggeser EV di samping cincin fokus** (seret naik/turun, magnet di 0) dan penggeser EV horizontal di panel; **tekan lama layar = kunci AF/AE** di titik itu (Platinum).
- **Chip mode aktif** di atas baris zoom (AF/AE terkunci, jepret oto, timer, EV, zoom oto, kilat); ketuk chip = matikan. Tombol rana menampilkan hitung mundur dan tanda *A* saat jepret otomatis.
- **Level** menampilkan garis acuan tetap dan derajat kemiringan; **kisi** punya tiga mode (mati, 3×3, 3×3 + silang tengah); opsi **Getar** (haptic) baru.
- **Pengaturan kamera diingat** antar-sesi (kisi, histogram, level, suara, getar, timer, kilat, zoom oto). Jepret otomatis sengaja tidak disimpan.
- Logika murni baru di `CameraAssist.kt` (`lumaHistogram`, `exposureAdvice`, `clampEv`, `nextGrid`, `nextTimer`, `rollLabel`, `CamPrefs`) dengan tes di `CameraAssistTest`. Perilaku di kamera asli tetap perlu dicoba di beberapa HP.

### Meter drum mekanik: hanya drum yang dihitung dibaca
- **Pemotong drum 1/10 tersambung** (`DrumSplit.kt` sebelumnya ada tetapi belum dipanggil): pada mode display *HM drum mekanik* strip angka dipotong tepat sebelum drum merah/oranye sebelum OCR di kamera live (`Camera.kt`), foto final (`readFinalDetailed(drum = true)`), penyimpanan sampel latih (`Sheets.kt`), dan *Latih ulang & uji* (`Trainer.kt`). Foto contoh QUARTZ Jd 08: `01058` terbaca, bukan `010586` atau `105.8`.
- **Profil drum tanpa prior desimal** (`decimalLikely = false`, 3-6 digit): digit terakhir tidak lagi otomatis dianggap desimal 1/10.
- **Pendeteksi lebih tahan coretan**: kolom merah harus minimal 30% dari kolom merah terkuat, sehingga garis merah tipis (spidol, goresan, pantulan) tidak menggeser potongan.
- Tes baru `DrumSplitTest` dan `drumProfileHasNoDecimalPriorSoIntegerDrumsStayIntegers`. Teks bantuan kamera dan Pusat latih diubah: label sampel drum = digit gelap saja (mis. `01058`).
- Contoh foto dan label di `docs/samples/`.

### Penyimpanan lebih sederhana + Bagikan
- **Simpan = otomatis masuk HP**: setelah angka dicek dan dikonfirmasi di layar Preview, foto ber-watermark selalu disalin ke Pictures/HM Tracker (tanpa pengaturan terpisah; toggle \"Simpan otomatis ke galeri\" dihapus, kolom `gal` di backup lama diabaikan). Toast memberi tahu bila penyalinan ke galeri HP gagal.
- **Bagikan**: tombol *Simpan & bagikan* di layar Preview dan *Bagikan* di Detail Foto membuka menu berbagi Android (WhatsApp, email, Drive, dll). File dikirim apa adanya lewat `FileProvider` (`Share.kt`, `res/xml/file_paths.xml`; hanya folder `photos/` yang terbuka), jadi SHA-256 di jejak audit tetap cocok.
- Tombol lama *Simpan ke galeri* di Detail Foto menjadi *Simpan salinan ke galeri* (untuk foto lama yang belum ada di HP).

### Watermark: lebih lengkap, bisa diperkecil, kartu bisa transparan
- **Ukuran bebas 35–120%** (slider + pilihan cepat Kecil 55 / Sedang 80 / Besar 100), menggantikan tiga ukuran tetap. Detail Card tidak lagi dipaksa minimal ukuran sedang. Data/backup lama (`wmSize`) dimigrasi otomatis ke persen (`wmScaleOf`).
- **Kepekatan kartu 10–100%** (`wmAlpha`, default 88%) berlaku untuk semua template. Di bawah 65% teks diberi bayangan tipis agar tetap terbaca di atas foto terang.
- **Pratinjau langsung** di Atur → Template Watermark memakai `drawWatermark` yang sama dengan kamera dan foto jadi (data contoh bila unit belum diisi).
- **Pro Card dan Detail Card memakai satu penggambar kartu** (`infoCard`): lebar kartu mengikuti isi, dan Pro kini memuat sisa, persen bar, selisih/laju/perkiraan servis, serta baris bukti + kode Ref. Tiap bagian bisa dimatikan (`WmShow`: HM, SMR, bar, rincian, bukti, GPS); tinggi kartu mengikuti lewat `cardHeightUnits`.
- Tes baru: `cardHeightSkipsSwitchedOffSections`, `scaleMigratesFromOldSizePresets`, `watermarkSettingsRoundTripAndLegacyMigration`.

### Ditambahkan / diperbaiki (pengaturan & login)
- Interval positif yang valid pada layar Atur langsung tersimpan ke unit aktif; tile interval di Status dan kalkulasi servis mengikuti nilai yang sama. Input kosong/tidak valid tidak menimpa nilai tersimpan.
- Login email menyediakan pemilih akun sistem: alamat terpilih mengisi kolom email tanpa membaca daftar akun secara diam-diam atau meminta `GET_ACCOUNTS`. Pemilih ini hanya mengisi alamat, bukan autentikasi.
- `versionName` dinaikkan ke `1.5.1` dan `versionCode` ke `6`; rincian fitur, batasan login, serta hasil CI dicatat di [docs/FEATURES-v1.5.1.md](docs/FEATURES-v1.5.1.md).

## [1.5.0] - 2026-11-19

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

[Belum dirilis]: ../../compare/v1.5.1...HEAD
[1.5.1]: ../../compare/v1.5.0...v1.5.1
[1.5.0]: ../../compare/v1.4.0...v1.5.0
[1.4.0]: ../../releases/tag/v1.4.0
[1.3.0]: ../../releases/tag/v1.3.0
[1.2.0]: ../../releases/tag/v1.2.0
[1.1.0]: ../../releases/tag/v1.1.0
[1.0.0]: ../../releases/tag/v1.0.0
