# Panduan Kontribusi

Terima kasih sudah ingin berkontribusi pada HM Tracker. Dengan berpartisipasi Anda setuju mengikuti [Kode Etik](CODE_OF_CONDUCT.md).

## Menyiapkan lingkungan

1. JDK 17, Android Studio Koala+, Android SDK 35.
2. Fork dan clone repo, lalu *Open* di Android Studio atau jalankan `gradle assembleDebug`.
3. Pastikan tes lulus sebelum mulai: `gradle testDebugUnitTest`.

## Alur kerja

1. Buat branch dari `main`: `feat/nama-singkat`, `fix/nama-singkat`, `docs/...`, `refactor/...`.
2. Buat perubahan kecil dan terfokus; satu PR = satu tujuan.
3. Jalankan `gradle testDebugUnitTest lintDebug`.
4. Perbarui `CHANGELOG.md` pada bagian **[Belum dirilis]**.
5. Buka Pull Request dan isi templatnya.

### Pesan commit

Gunakan [Conventional Commits](https://www.conventionalcommits.org/id/v1.0.0/):

```
feat(kamera): kunci angka setelah 4 dari 6 frame
fix(audit): jangan menandai entri impor sebagai diubah
docs: perjelas langkah rilis
```

Tipe umum: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `ci`, `chore`.

## Gaya kode

- Kotlin resmi (`kotlin.code.style=official`), aturan dasar di `.editorconfig`.
- Komentar dan teks UI berbahasa Indonesia, konsisten dengan kode yang ada.
- Logika murni (hitungan, validasi, integritas) ditulis sebagai fungsi tanpa ketergantungan Android agar dapat dites di JVM.

## Aturan yang tidak boleh dilanggar

- **Jangan mengubah urutan/pemisah field di `canonical()`** (`Integrity.kt`). Tanda tangan audit lama akan menjadi tidak valid. Perubahan wajib disertai versi format dan migrasi.
- **Jangan menghapus baris audit.** Pemangkasan hanya berlaku untuk foto.
- Perubahan skema DB: naikkan versi `Db`, tulis migrasi, tambahkan tes. Perubahan format backup: naikkan `SCHEMA` dan pertahankan pembacaan format lama.
- Jangan commit keystore, kata sandi, foto meter asli, atau data pribadi.

## Menambah tes

Tes ada di `app/src/test/java/com/frostbyte/hmtracker/`. Perbaikan bug sebaiknya diawali tes yang gagal. Perubahan ambang kualitas foto perlu dasar data (foto meter nyata atau gambar sintetis) di deskripsi PR.

## Melaporkan bug & usulan

Gunakan [issue](../../issues/new/choose). Untuk celah keamanan, ikuti [SECURITY.md](SECURITY.md), bukan issue publik.
