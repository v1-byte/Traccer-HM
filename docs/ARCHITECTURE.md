# Arsitektur

Aplikasi satu modul (`:app`), satu `Activity`, UI sepenuhnya Jetpack Compose (Material 3).

## Lapisan

| Lapisan | Berkas | Tanggung jawab |
|---|---|---|
| UI | `MainActivity.kt`, `ui/*` | Layar Compose: Login, Home, Camera, Gallery, Sheets, Settings; tema dan komponen bersama (`Theme.kt`, `Components.kt`, `Icons.kt`) |
| State | `AppVm.kt` | `AndroidViewModel` memegang data aplikasi, daftar foto, tab aktif, status kamera/review, toast |
| Domain | `Data.kt` (model, `calc`), `Validation.kt` | Model (`UnitData`, `Reading`, `Audit`, `Calc`), hitungan sisa servis, aturan validasi bacaan, penilaian kualitas foto, geometri kotak bidik |
| Persepsi | `Meter.kt`, `HmFinder.kt` | ML Kit OCR, normalisasi kandidat angka, pemilihan angka meter (`pickMeter`), `Voter` (angka dikunci bila muncul di 4 dari 6 frame), `TrustedClock`; label HM/KM, kotak pelacak stabil, `FocusPolicy`, zoom otomatis |
| Keluaran | `Watermark.kt` | Menggambar 5 template watermark ke `Canvas` |
| Integritas | `Integrity.kt` | `KeystoreSigner`, `canonical()`, `verifyAudits()`, `Trust`/`Verdict` |
| Penyimpanan | `Data.kt` (`Store`, `Db`) | SQLite `hmt.db`, backup/pulihkan JSON, simpan ke `Pictures/HM Tracker` lewat MediaStore |

Fungsi-fungsi domain, validasi, dan integritas sengaja dibuat murni (menerima `Signer`, lambda `fileSha`/`hmOf`) agar dapat dites di JVM tanpa perangkat.

## Alur pengambilan foto

1. **Kamera** (CameraX): preview, analisis, dan foto dipaksa rasio 4:3 yang sama sehingga kotak `[ ]` identik di ketiganya.
2. **Analisis live** (~0,5 dtk, 1280x960):
   - *Cari*: OCR seluruh frame (tiap 3 siklus, saat belum ada angka terlacak, atau 1 dtk setelah zoom otomatis) → `tokensOf` → `pickMeter` memilih angka paling mirip meter (min. 3 digit, memanjang, cukup tinggi). Skor = digit x tinggi x kedekatan ke tengah/titik yang diketuk x faktor panjang digit x **faktor label**: label `HM`/`HOUR`/`SMR`/`JAM` di dekat angka menaikkan skor bila unit memakai HM (`KM`/`ODO` bila KM), label lain (`RPM`, `SN`, ...) menurunkannya. Hasilnya digabung ke kotak lama oleh `mergeTrack` (dihaluskan; pindah angka hanya bila skor ≥ 1,4x) menjadi `NumTrack` (kotak normal 0..1, membawa label).
   - *Baca*: area terlacak (diperlebar) dipotong, diolah `prepForOcr`, dibaca → `bestCandidate` → `Voter`. Dua kali berturut-turut tak terbaca → pelacakan dilepas.
   - *Fokus*: `FocusPolicy` dievaluasi tiap 300 ms dan hanya memicu `startFocusAndMetering` (AF + AE + AWB, area ukur sebesar angka) bila angka baru/bergeser, ketajaman area angka turun, fokus gagal (jeda makin panjang), atau penyegaran 8 dtk. Hasil `FocusMeteringResult` menentukan `AfState` (mencari/terkunci/gagal) yang ditampilkan di layar. Ketukan manual diutamakan 4 dtk.
   - *Zoom*: `autoZoomFor` memperbesar angka yang terlalu kecil (maks. 4x) selama angka tetap utuh di frame, lalu `remapForZoom` menghitung ulang kotak. Cubit/ketukan manual menahannya 10 dtk.
   - Tanpa angka ditemukan, jatuh ke kotak `[ ]` tetap (`boxToFrame`).
3. **Jepret**: area baca mengikuti kotak terlacak (koordinat normal berlaku di foto karena rasio sama). Foto resolusi penuh (2560 px) dijalankan ulang melalui OCR (3 lintasan: polaritas otomatis, terbalik, mentah), kualitas dinilai (`judgeQuality`), foto yang sangat buram/gelap ditolak.
4. **Validasi** (`checkReading`/`resolveReading`/`chooseFinal`): HM tidak boleh turun; kenaikan dibandingkan waktu nyata dari `TrustedClock` (GPS + `elapsedRealtime`).
5. **Review wajib**: pengguna dapat mengedit angka dan harus mencentang kecocokan dengan meter.
6. **Simpan**: foto berwatermark (dan foto asli tanpa watermark) disimpan, bacaan dicatat, dan audit ditandatangani serta dirantai (lihat [AUDIT.md](AUDIT.md)).

## Penyimpanan

- Basis data `hmt.db` (versi 2) dengan tabel `unit`, `reading`, `photo`, `audit`, `meta`.
- Tulis dilakukan atomik (transaksi); penulisan pengaturan/unit berjalan di executor satu thread, berurutan dan di luar main thread.
- Foto dipangkas sampai `MAX_PHOTOS = 200` terbaru; baris `audit` tidak pernah dihapus.
- Migrasi otomatis dari `SharedPreferences` (data v1.0/1.1) ke SQLite, dan dari DB v1 ke v2 (kolom `n`, `prev`, `sig`, `src`).

## Backup

Format JSON dengan `schema: 2` (konstanta `SCHEMA`), memuat unit, bacaan, dan seluruh jejak audit.

- Backup web lama (tanpa `schema`) tetap dibaca.
- Backup dengan `schema` lebih baru ditolak.
- Backup tanpa unit ditolak.
- Audit hasil impor berstatus *tidak bertanda tangan* karena kunci perangkat lain tidak dapat diperiksa di sini.

## Build & dependensi

- Versi dependensi dan plugin terpusat di `gradle/libs.versions.toml`.
- Rilis: R8 (`isMinifyEnabled`, `isShrinkResources`) dengan `mapping.txt` untuk membaca stack trace.
- ABI: `arm64-v8a`, `armeabi-v7a`.
