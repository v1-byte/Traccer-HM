# HM Tracker

[![Lisensi: MIT](https://img.shields.io/badge/lisensi-MIT-blue.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose&logoColor=white)

Aplikasi Android native untuk mencatat **hour meter (HM) / odometer** unit alat berat dan kendaraan, menghitung sisa waktu menuju servis, dan menyimpan **bukti foto berwatermark yang dapat diaudit**. Angka meter dibaca otomatis dari kamera (OCR on-device, tanpa internet), divalidasi, lalu dicatat dalam rantai audit bertanda tangan.

> Port native dari versi web `hm-tracker/index.html` dengan tampilan yang dipertahankan (warna, tata letak, ikon garis, 5 template watermark, tema gelap/terang, 8 warna aksen).

## Fitur

- **Paket Basic / Premium / Platinum** (*Atur → Paket Layanan*): Basic memuat semua fitur inti. Premium menambah penunjuk level kamera, prediksi servis, ekspor CSV, template watermark lengkap, dan putar foto. Platinum menambah kunci AF/AE, kontras foto, tren pemakaian, perbandingan unit, dan laporan siap bagikan. Aktivasi paket masih lokal (demo) dan terikat tanda tangan kunci perangkat (backup tidak membawa paket). Lihat [CHANGELOG.md](CHANGELOG.md).
- **Status servis**: gauge busur 270°, sisa jam/km, estimasi tanggal, status lancar / peringatan / wajib servis.
- **Interval otomatis (1.5.1)**: angka interval valid pada *Atur → HM / KM & Interval* langsung disimpan untuk unit aktif dan tile interval di Beranda ikut berubah. Detail: [catatan fitur 1.5.1](docs/FEATURES-v1.5.1.md).
- **Pilih email perangkat (1.5.1)**: layar login email dapat membuka pemilih akun sistem Android, mengisi alamat pilihan, dan tetap menyediakan kolom manual sebagai fallback. Ini hanya mengisi alamat, bukan autentikasi Google.
- **Kamera pembaca meter**: angka meter **dicari otomatis di seluruh frame** (ML Kit), ditandai kotak `[ ]` yang mengikuti angka, lalu kamera **memfokuskan dan mengukur eksposur ke angka itu**. Ketuk layar untuk memilih angka lain atau fokus manual. Penguncian angka (muncul di 4 dari 6 frame terakhir), zoom cubit, senter, penolakan foto buram/gelap. Bila tidak ada angka ditemukan, dipakai kotak bidik tetap.
- **Pengenalan HM**: angka HM dibedakan dari nomor seri, RPM, suhu, jam/tanggal, dan odometer lewat **label di dekat angka** (`HM`, `HOUR(S)`, `SMR`, `JAM` untuk HM; `KM`, `ODO` untuk odometer; `RPM`, `SN`, `x100`, `%`, ... diturunkan skornya). Angka berlabel HM menang atas angka lain yang lebih besar atau lebih dekat ke tengah. Bila label di dekat angka tidak cocok dengan unit (mis. odometer saat unit memakai HM), kotak berubah kuning dan memberi tahu.
- **Fokus otomatis profesional**: titik fokus kecil tepat di angka; fokus dipicu hanya saat perlu (angka baru atau bergeser, foto mulai buram, fokus gagal, penyegaran berkala), tidak berburu terus-menerus. Hasil fokus ditampilkan (titik kuning = mencari, hijau = terkunci, merah = gagal), fokus gagal diulang dengan jeda makin panjang, kotak dihaluskan dan tidak loncat antar angka. **Zoom otomatis** memperbesar angka yang terlalu kecil (maks. 4x, hanya bila angka tetap utuh di frame); cubit atau ketuk menahannya 10 dtk, dan tombol *Zoom oto* mematikannya.
- **OCR lebih akurat**: pembacaan foto memakai 5 lintasan (polaritas otomatis/terbalik, tanpa olah, skala besar, hitam-putih ambang lokal) lalu mayoritas **per digit**; kunci live juga memakai mayoritas per digit antar frame dan mencoba polaritas terbalik bila frame kosong. Digit yang sering tertukar (0/8, 3/8, 5/6, 1/7, ...) dikoreksi otomatis **hanya bila** bacaan asli mustahil menurut riwayat unit dan tepat satu tafsiran yang masuk akal; hasil koreksi selalu ditandai peringatan agar dicocokkan dengan meter asli.
- **Sistem latih kamera**: aplikasi belajar dari koreksi kamu, bisa dilatih manual dengan foto meter + angka benar, dan bisa memakai model AI sendiri (TFLite) yang dilatih dari sampelnya. Semua data tinggal di HP. Lihat [docs/TRAINING.md](docs/TRAINING.md).
- **Dataset berprovenance**: ekspor training menyertakan `labels.csv` berisi mode ODO/HM/drum, label semantik, kondisi foto, model/konteks unit, `source_id`, dan `dataset_manifest.json` yang merujuk manual FUSO, CAT, Komatsu, Hitachi, dan Volvo. Sumber manual dipakai untuk terminology panel; angka tetap berasal dari foto unit nyata. Lihat [docs/OCR_DATASET_SPEC.md](docs/OCR_DATASET_SPEC.md).
- **Profil panel meter**: prior OCR ringan untuk Mitsubishi, LV/light vehicle, pickup, double cabin, bus, dump truck, truk tangki, CAT excavator/dozer, Komatsu, Hitachi, Volvo CE, John Deere, Kubota, excavator, dan dozer. Profil membantu membedakan odometer KM dari HM/engine-hour dan gangguan seperti trip, RPM, tanggal, serta nomor seri, tetapi tidak menggantikan sampel foto unit nyata.
- **Validasi hitungan**: HM tidak boleh turun; kenaikan dibandingkan waktu nyata (maks. 1,1× jam atau 150 km/jam); konfirmasi manual wajib sebelum simpan.
- **Watermark** 5 template (isi unit, operator, lokasi dapat diatur), foto otomatis masuk `Pictures/HM Tracker`.
- **Audit bertanda tangan**: HMAC-SHA256 dengan kunci Android Keystore, dirantai antar entri. Perubahan data, penghapusan entri, atau penggantian foto terdeteksi. Lihat [docs/AUDIT.md](docs/AUDIT.md).
- **Penyimpanan SQLite** dengan migrasi otomatis, pemangkasan foto (200 terbaru) tanpa menghapus jejak audit.
- **Backup / pulihkan JSON** (`schema: 2`), kompatibel dengan backup versi web.
- Tema gelap/terang, 8 warna aksen, ukuran teks, riwayat garis waktu, galeri dengan lencana HM.

## Prasyarat

| Kebutuhan | Versi |
|---|---|
| JDK | 17 |
| Gradle | 8.9 (lihat [catatan wrapper](#gradle-wrapper)) |
| Android Studio | Koala (2024.1) atau lebih baru |
| Android SDK | compileSdk 35, minSdk 24 |

## Memulai

```bash
git clone <url-repo> HMTracker
cd HMTracker
```

**Android Studio**: *Open* folder ini → tunggu Sync → *Run*.

**Terminal**:

```bash
gradle assembleDebug          # APK debug -> app/build/outputs/apk/debug/
gradle testDebugUnitTest      # tes unit JVM
gradle lintDebug              # Android Lint -> app/build/reports/
```

### Gradle wrapper

Repo ini menyertakan `gradle-wrapper.properties` tetapi **belum** `gradlew`, `gradlew.bat`, dan `gradle-wrapper.jar`. Buat sekali lalu commit:

```bash
gradle wrapper --gradle-version 8.9
git add gradlew gradlew.bat gradle/wrapper
git commit -m "build: tambahkan Gradle wrapper"
```

Setelah itu ganti `gradle ...` menjadi `./gradlew ...` di README dan workflow.

## Struktur proyek

```
.
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/java/com/frostbyte/hmtracker/
│       │   ├── MainActivity.kt   # entri tunggal + navigasi
│       │   ├── AppVm.kt          # ViewModel status aplikasi
│       │   ├── Data.kt           # model, hitungan HM, SQLite, backup JSON
│       │   ├── Validation.kt     # aturan validasi & kualitas foto
│       │   ├── Meter.kt          # OCR, pemilihan angka meter, voting frame, jam tepercaya
│       │   ├── HmFinder.kt       # label HM/KM, kotak pelacak stabil, kebijakan fokus, zoom otomatis
│       │   ├── Watermark.kt      # 5 template watermark
│       │   ├── Integrity.kt      # tanda tangan & verifikasi rantai audit
│       │   └── ui/               # layar Compose (Home, Camera, Gallery, ...)
│       ├── main/res/
│       └── test/java/...         # tes unit JVM
├── docs/                         # arsitektur, model audit, panduan rilis
├── gradle/libs.versions.toml     # katalog versi dependensi
└── .github/                      # CI, rilis, template issue/PR, Dependabot
```

Penjelasan lengkap: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Pengujian

Tes unit mencakup hitungan HM & status servis, validasi bacaan (turun/naik tak wajar, selisih jam), normalisasi OCR, kualitas foto, geometri kotak bidik, pengenalan HM lewat label, kebijakan fokus, zoom otomatis, backup bolak-balik, dan seluruh skenario integritas audit.

```bash
gradle testDebugUnitTest
```

Ambang kualitas foto (`SHARP_BLOCK`, `SHARP_WARN`, `CONTRAST_WARN`, `GLARE_WARN` di `Validation.kt`) dikalibrasi dari gambar sintetis; sesuaikan dengan foto meter asli unit Anda.

## Rilis

Rilis dibuat otomatis saat tag versi didorong. Ringkasnya:

1. Naikkan `versionCode`/`versionName` di `app/build.gradle.kts`, perbarui `CHANGELOG.md`.
2. `git tag v1.5.1 && git push origin v1.5.1`.
3. Workflow *Release* menjalankan tes, membangun APK bertanda tangan, dan mengunggah APK + `mapping.txt` + `SHA256SUMS.txt` ke GitHub Releases.

Detail dan cara membuat keystore: [docs/RELEASING.md](docs/RELEASING.md).

## Batasan yang diketahui
- **Login email dan OTP WhatsApp masih mode demo.** Pemilih akun hanya mengisi email dan tidak memverifikasi identitas. Kode OTP WhatsApp dibuat di perangkat dan ditampilkan di notifikasi; ini **bukan** batas keamanan. Tambahkan autentikasi backend sebelum dipakai produksi.
- Belum ada model khusus untuk **meter drum mekanik** (angka berputar); konfirmasi manual tetap diperlukan.
- Pengenalan HM bergantung pada **label yang ikut terbaca** di dekat angka. Meter tanpa label tercetak tetap dipilih lewat ukuran dan posisi seperti sebelumnya; ketuk angka bila yang terpilih salah. Layar LCD 7-segmen kadang kurang terbaca oleh ML Kit.
- Stempel dari aplikasi kamera lain (koordinat, tanggal, jam) dan watermark HM Tracker sendiri pada foto yang diimpor bisa menyerupai angka meter. Pemilihan memakai label, ukuran, dan posisi, tetapi tetap periksa angka di layar konfirmasi.
- Logika fokus/zoom/label diuji sebagai fungsi murni di JVM, tetapi perilaku fokus pada kamera asli (`Camera.kt`) perlu dicoba langsung di beberapa HP.
- Instal ulang aplikasi, reset semua data, atau penghapusan entri audit paling akhir **tidak terdeteksi secara offline**; perlu jangkar hash di server.
- Kode Ref di watermark hanya bisa dicocokkan di perangkat pembuat foto, dan tidak memuat hash foto itu sendiri (lihat [docs/AUDIT.md](docs/AUDIT.md)). Tidak ada QR.
- Paket Premium/Platinum belum punya lisensi/pembayaran sungguhan; tanda tangan paket hanya mencegah ubah-data.
- Rilis tanpa keystore memakai kunci debug dan tidak boleh dipublikasikan.

## Roadmap

Kandidat, belum menjadi komitmen:

- [ ] Kalibrasi pencarian angka otomatis (`pickMeter`: jumlah digit minimum, rasio, bobot jarak, faktor label di `HmFinder.kt`) dan ambang zoom otomatis (`autoZoomFor`) dengan foto meter asli. Tambahkan daftar label sesuai merek unit Anda (`HM_STRONG`, `NOISE_WORDS`).
- [ ] Tambahkan Gradle wrapper dan pin ke `./gradlew` di CI.
- [ ] Bersihkan temuan Android Lint pertama, buat baseline, lalu aktifkan `abortOnError`.
- [ ] Tambahkan ktlint/detekt.
- [ ] Verifikasi perilaku Auto Backup (`allowBackup="true"`) terhadap kunci Keystore: kunci tidak ikut dipulihkan, sehingga entri bertanda tangan di perangkat baru berpotensi tampil sebagai "Catatan diubah". Putuskan: nonaktifkan backup basis data atau tangani status ini.
- [ ] Backend OTP WhatsApp sungguhan.
- [ ] Jangkar hash audit di server.
- [ ] Tes instrumentasi / UI Compose.
- [ ] Model untuk meter drum mekanik.

## Berkontribusi

Lihat [CONTRIBUTING.md](CONTRIBUTING.md) dan [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Laporan keamanan: [SECURITY.md](SECURITY.md). Riwayat perubahan: [CHANGELOG.md](CHANGELOG.md).

## Lisensi

[MIT](LICENSE) © 2026 Frostbyte
