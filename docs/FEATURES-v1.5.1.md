# Catatan perubahan HM Tracker 1.5.1

Dokumen ini merangkum perubahan fitur yang masuk ke branch `main` pada commit `2914a6d`.

## Sinkronisasi interval otomatis

Pada layar **Atur → HM / KM & Interval**, angka target interval yang valid dan lebih besar dari nol langsung disimpan ke unit aktif. Angka yang kosong atau tidak valid tidak mengganti nilai tersimpan. Detail pengaturan lain tetap menggunakan tombol **Simpan Pengaturan**.

Beranda membaca interval dari unit aktif yang sama, sehingga tile **INTERVAL** ikut berubah saat nilai pengaturan diperbarui. Tampilan interval memakai nilai bawaan 200 bila data tersimpan tidak positif, selaras dengan fallback yang digunakan hitungan status servis.

## Memilih email dari akun perangkat

Di layar masuk melalui email, tombol **Pilih akun email di perangkat** membuka pemilih akun sistem Android. Pengguna memilih akun secara eksplisit; aplikasi tidak memindai daftar akun di latar belakang dan tidak meminta izin `GET_ACCOUNTS`. Alamat yang dipilih harus berformat email sebelum mengisi kolom. Kolom manual tetap tersedia sebagai fallback.

**Batas autentikasi:** pemilih hanya mengisi alamat email. Alur masuk email yang ada saat ini masih lokal/demo, bukan login Google terverifikasi dan bukan verifikasi ke server. Jangan mengandalkannya sebagai autentikasi produksi sebelum menambahkan backend OTP atau autentikasi identitas yang benar.

## Versi dan verifikasi

- `versionName`: `1.5.1`
- `versionCode`: `6`
- Min SDK: 24; target SDK: 35
- CI pada commit `2914a6d` lulus untuk unit test, Android Lint, dan build APK debug: [run 44514281894](https://github.com/v1-byte/Traccer-HM/actions/runs/44514281894).
- SHA-256 APK debug dari run tersebut: `cb7263fe35cc467cf2304a3cd2647f89d6d129f4d57794e0bb466b699efc5cfd`.

APK debug bukan APK produksi bertanda tangan. Untuk merilis APK produksi, workflow rilis memerlukan signing secrets GitHub Actions sesuai panduan di [RELEASING.md](RELEASING.md).
