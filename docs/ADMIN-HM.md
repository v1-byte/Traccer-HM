# Admin HM — Catatan Fitur & Rencana

> Status: **rencana, belum ada kode** · Dibuat 2026-11-19 · Target: HM Tracker v1.5.1
> Lihat juga: [ARSITEKTUR-3-APK.md](ARSITEKTUR-3-APK.md) · [ADMIN-LOCK.md](ADMIN-LOCK.md)
> Dikerjakan **setelah** Adm Lock.

## 1. Tujuan

Admin HM adalah aplikasi untuk **memantau seluruh unit dan catatan HM** yang dikirim dari APK user: sisa jam servis, bukti foto, dan status keaslian data, dalam satu tampilan armada.

## 2. Kondisi sekarang di repo (fakta dari kode)

| Hal | Kondisi |
|---|---|
| Penyimpanan data | **Semua lokal di HP** (SQLite). Tidak ada sinkronisasi ke server. |
| Data per unit | `UnitData` dengan `readings` (waktu, HM, penanda servis), interval, peringatan |
| Bukti | Foto berwatermark di `Pictures/HM Tracker` + rantai audit HMAC (`Integrity.kt`) |
| Status audit | `Trust`: VERIFIED, UNSIGNED, FILE_MISSING, HM_CHANGED, FILE_CHANGED, CHAIN_BROKEN, RECORD_EDITED |
| Server | Hanya `otp-server` (OTP). Roadmap README sudah mencatat "Jangkar hash audit di server" sebagai belum dikerjakan. |

**Konsekuensi penting:** Admin HM tidak punya data apa pun untuk ditampilkan sampai APK user mengirim data ke server. Fitur sinkronisasi adalah **prasyarat**, bukan tambahan.

## 3. Fitur Admin HM

| # | Fitur | Fungsi | Prioritas |
|---|---|---|---|
| 1 | Dashboard armada | Semua unit: HM terakhir, sisa jam/km, status lancar / peringatan / wajib servis | Wajib |
| 2 | Detail unit | Riwayat bacaan (garis waktu), foto bukti, lokasi GPS, siapa yang mencatat | Wajib |
| 3 | Status keaslian | Tampilkan hasil verifikasi audit per bacaan (VERIFIED / diubah / rantai putus) | Wajib |
| 4 | Peringatan | Servis jatuh tempo, HM turun atau naik tidak wajar, unit lama tidak dicatat | Tinggi |
| 5 | Kelola unit & operator | Tambah unit, tetapkan operator, atur interval servis dari admin | Tinggi |
| 6 | Laporan | Ekspor CSV/PDF per unit atau per periode | Tinggi |
| 7 | Peta | Posisi terakhir tiap unit dari GPS di watermark | Sedang |
| 8 | Pencarian & filter | Per unit, operator, lokasi, status servis | Sedang |
| 9 | Koreksi terkontrol | Admin mengoreksi bacaan salah, **dengan alasan wajib** dan jejak log | Sedang |
| 10 | Notifikasi | Push/WhatsApp saat unit wajib servis | Rendah |

## 4. Prasyarat: sinkronisasi dari APK user

Yang perlu ditambahkan di sisi APK user dan server:

- Kirim tiap bacaan baru: `unitId`, `t`, `value`, `svc`, rekaman audit (`Audit` termasuk `sig`, `sha`, `prev`), GPS, `ident`, `deviceId`.
- Foto: kirim **hash SHA-256** dulu (cukup untuk jangkar audit); upload file foto sebagai tahap berikutnya.
- Antrean offline: bacaan yang dicatat tanpa sinyal dikirim ulang saat online.
- **Jangkar hash audit di server**: server menyimpan hash terakhir rantai tiap akun. Dengan ini instal ulang atau penghapusan entri terakhir bisa terdeteksi (celah yang diakui di README).

### Endpoint (usulan)

| Endpoint | Fungsi |
|---|---|
| `POST /sync/readings` | Kirim batch bacaan + rekaman audit |
| `POST /sync/photo` | Upload foto (tahap lanjut) |
| `GET /admin/fleet` | Ringkasan armada |
| `GET /admin/units/:id` | Detail unit + riwayat |
| `GET /admin/alerts` | Daftar peringatan |
| `POST /admin/units/:id/correction` | Koreksi bacaan (wajib `reason`) |

### Catatan verifikasi

Tanda tangan audit memakai kunci Keystore **di HP**; server tidak bisa memverifikasinya. Jadi sisi server hanya bisa menjamin: urutan rantai tidak terputus (lewat `prev`), hash foto cocok, dan tidak ada bacaan yang hilang di tengah. Itu sudah cukup untuk mendeteksi manipulasi riwayat; keaslian pembacaan angka dari meter tetap bergantung pada foto.

## 5. Hubungan dengan Adm Lock

- Berbagi **server dan sistem login admin** yang sama (peran: `owner`, `lock-admin`, `hm-admin`).
- Data HM hanya boleh disinkronkan bila lisensi akun aktif (satu cek dari `/license/check`).
- Fitur Platinum (laporan, tren, perbandingan unit) bisa dipindah logikanya ke server agar tidak bisa dibuka lewat APK yang dimodifikasi.

## 6. Privasi & keamanan

- Data lokasi dan foto bersifat sensitif: batasi admin hanya ke akun/armada miliknya.
- Beri tahu user di APK bahwa data dikirim ke server, dan sediakan opsi mematikan sinkronisasi untuk paket Basic jika itu keputusan produk.
- Koreksi admin tidak boleh menimpa data asli: simpan sebagai entri baru bertanda "dikoreksi admin".
- Semua tindakan admin masuk log.

## 7. Fase pengerjaan

- [ ] **Fase 0**: keputusan di bagian 8
- [ ] **Fase 1**: model data server + `POST /sync/readings` + jangkar hash
- [ ] **Fase 2**: APK user mengirim bacaan (antrean offline)
- [ ] **Fase 3**: dashboard armada + detail unit + status keaslian
- [ ] **Fase 4**: peringatan + laporan CSV/PDF
- [ ] **Fase 5**: peta, koreksi terkontrol, notifikasi

## 8. Pertanyaan terbuka

1. Siapa penggunanya: satu perusahaan dengan banyak operator, atau banyak pelanggan terpisah (multi-tenant)?
2. Foto: cukup hash di server, atau foto asli juga diunggah?
3. Bentuk Admin HM: APK native atau panel web?
4. Apakah sinkronisasi wajib untuk semua paket, atau hanya paket tertentu?
