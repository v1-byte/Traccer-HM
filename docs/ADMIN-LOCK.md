# Adm Lock — Catatan Fitur & Rencana

> Status: **Fase 1 dan 3 sudah ada kodenya** (`server/license-server/`, `admin-lock/`); Fase 2 (APK user membaca lisensi) belum · Dibuat 2026-11-19 · Target: HM Tracker v1.5.1
> Lihat juga: [ARSITEKTUR-3-APK.md](ARSITEKTUR-3-APK.md) · [ADMIN-HM.md](ADMIN-HM.md)

## 1. Tujuan

Adm Lock adalah alat admin untuk **mengunci / membuka fitur APK user dari jarak jauh**, supaya paket **Basic / Premium / Platinum / VIP** benar-benar dikontrol admin, bukan dipilih sendiri oleh pengguna.

## 2. Kondisi sekarang di repo (fakta dari kode)

| Hal | Kondisi |
|---|---|
| Paket | `Tier.kt`: BASIC, PREMIUM, PLATINUM. 10 fitur dikunci lewat enum `Feature` (tiap fitur punya paket minimum). |
| Cara ganti paket | `ui/Settings.kt` punya pilihan paket (`Seg`) yang **bisa dipilih siapa pun**. Label di layar sendiri menyebut ini "demo". |
| Tanda tangan paket | `tierSig()` = HMAC dengan kunci Keystore **perangkat sendiri**. Hanya mencegah ubah data di DB/backup. **Bukan lisensi**: begitu user memilih Platinum, tanda tangan dibuat otomatis. |
| Terikat ke | **Perangkat**, bukan akun. `AppVm.login()` sengaja mempertahankan `tier` saat ganti akun. |
| Login | OTP email/WhatsApp lewat `server/otp-server` (`OtpApi.kt`). Identitas user = email atau nomor (`ident`). |
| Server lisensi | **Belum ada.** |

Kesimpulan: kunci paket sekarang **belum aman**. Adm Lock harus menggantikan pemilih paket lokal dengan lisensi dari server.

## 3. Fitur Adm Lock

| # | Fitur | Fungsi | Prioritas |
|---|---|---|---|
| 1 | Kelola paket per akun | Admin set paket (Basic/Premium/Platinum/VIP) untuk satu `ident` | Wajib |
| 2 | Masa berlaku | Tanggal kedaluwarsa; lewat tanggal otomatis turun ke Basic | Wajib |
| 3 | Status akun | `ACTIVE`, `SUSPENDED` (fitur berbayar dicabut), `BLOCKED` (APK terkunci) | Wajib |
| 4 | Override per fitur | Tambah / cabut satu fitur di luar paket (mis. beri CALC_EXPORT saja) | Tinggi |
| 5 | Kode aktivasi (voucher) | Admin buat kode: paket + durasi + batas pakai; user tukar di APK | Tinggi |
| 6 | Batas perangkat | Maks. N perangkat per akun; admin bisa cabut perangkat tertentu | Sedang |
| 7 | Daftar user & perangkat | Cari akun, lihat paket, terakhir aktif, versi app | Sedang |
| 8 | Log admin | Siapa mengubah apa, kapan (tidak bisa dihapus) | Tinggi |
| 9 | Pesan ke user | Teks di layar terkunci / banner (mis. "Paket habis, hubungi admin") | Rendah |
| 10 | Versi minimum | Paksa update bila versi APK terlalu lama | Rendah |

### Tingkat penguncian

1. **Turun ke Basic**: paket habis / dicabut. Semua fitur inti tetap jalan (sesuai janji README: Basic tidak kehilangan fitur lama).
2. **Kunci penuh (`BLOCKED`)**: APK menampilkan layar terkunci. Disarankan layar ini **tetap mengizinkan ekspor backup**, karena isinya data & bukti audit milik user.

## 4. Cara kerja

```
 Adm Lock (APK/panel admin)            Server lisensi               APK user
 ─────────────────────────            ───────────────             ───────────
 login admin (OTP + whitelist) ──►   cek peran admin
 set paket / kunci / voucher   ──►   simpan di DB + tulis log
                                                                  login OTP (sudah ada)
                                       ◄── POST /license/check ── ident + deviceId
                                     buat lisensi, TANDA TANGAN
                                     dengan kunci PRIVAT server ──►  simpan di perangkat
                                                                  verifikasi dengan kunci PUBLIK
                                                                  → Tier.allows() baca lisensi
```

Prinsip utama:

- **Kunci privat hanya di server.** APK hanya memegang kunci publik, jadi tidak bisa memalsukan lisensi.
- APK admin **tidak bisa mengunci APK user sendirian**. Keduanya berbicara lewat server. Tanpa server, tidak ada yang bisa dikunci dari jauh.
- Lisensi bisa dipakai **offline** sampai batas `exp` (jam diambil dari `TrustedClock` yang sudah ada).

## 5. Kontrak lisensi & API (usulan)

### Payload lisensi (JSON, ditandatangani server)

```json
{
  "ident": "628123456789",
  "tier": "platinum",
  "status": "active",
  "featuresAdd": [],
  "featuresRemove": [],
  "deviceId": "uuid-perangkat",
  "iat": 1795000000000,
  "exp": 1795604800000,
  "validUntil": 1798000000000,
  "msg": ""
}
```

- `exp` = kapan **lisensi harus diperbarui** (mis. 7 hari; ini batas offline).
- `validUntil` = kapan **paket** berakhir.
- Tanda tangan: **ECDSA P-256 (SHA256withECDSA)**. Dipilih karena didukung di semua Android sampai minSdk 24; Ed25519 baru ada di API 33.
- Server mengirim **daftar fitur aktif**, bukan hanya nama paket. Dengan begitu menambah paket baru (mis. VIP) **tidak butuh update APK**.

### Endpoint untuk APK user

| Endpoint | Fungsi |
|---|---|
| `POST /license/check` `{ident, deviceId, appVersion}` | Ambil lisensi terbaru |
| `POST /license/redeem` `{ident, deviceId, code}` | Tukar kode aktivasi |

### Endpoint admin (wajib token admin)

| Endpoint | Fungsi |
|---|---|
| `POST /admin/login` | OTP khusus email admin yang di-whitelist, balas token pendek |
| `GET /admin/users?q=` | Cari akun |
| `PATCH /admin/users/:ident` | Ubah `tier`, `status`, `validUntil`, `featuresAdd/Remove`, `msg` |
| `POST /admin/users/:ident/block` · `/unblock` | Kunci / buka penuh |
| `GET /admin/users/:ident/devices` · `DELETE .../:deviceId` | Lihat / cabut perangkat |
| `POST /admin/vouchers` · `GET /admin/vouchers` | Buat / lihat kode |
| `GET /admin/log` | Riwayat tindakan admin |

### Tabel server

```
users     (ident PK, tier, status, validUntil, featuresAdd, featuresRemove, msg, createdAt)
devices   (ident, deviceId, firstSeen, lastSeen, appVersion, revoked)
vouchers  (code PK, tier, days, maxUse, used, expiresAt)
admin_log (ts, admin, action, target, detail)
```

## 6. Perubahan yang dibutuhkan di APK user

| File | Perubahan |
|---|---|
| `LicenseApi.kt` (baru) | Klien `/license/check` & `/redeem`, gaya sama seperti `OtpApi.kt` |
| `Tier.kt` | Ganti `tierSig`/`verifiedTier` (HMAC lokal) dengan verifikasi tanda tangan server; `allows()` membaca daftar fitur dari lisensi |
| `ui/Settings.kt` | **Hapus pemilih paket.** Ganti dengan: status paket, masa berlaku, tombol "Masukkan kode aktivasi" |
| `AppVm.kt` | Cek lisensi saat start, setelah login, dan saat app dibuka kembali. `login()` tidak lagi mempertahankan `tier` per perangkat |
| `Data.kt` | Simpan lisensi di tabel `meta` (kunci `license`) |
| `MainActivity.kt` | Layar `LockedScreen` bila `status = blocked` |
| `app/build.gradle.kts` | `BuildConfig.LICENSE_API_URL` dan `LICENSE_PUBKEY` (pola sama dengan `HM_OTP_API_URL`) |
| `docs/` + tes | Tes unit: lisensi valid, kedaluwarsa, tanda tangan dirusak, mundurkan jam, perangkat dicabut |

Dampak perilaku: paket pindah dari **terikat perangkat** menjadi **terikat akun** (+ batas perangkat). Backup tetap tidak membawa paket.

## 7. Keamanan

- Admin login wajib lewat OTP + daftar email admin; **jangan** menaruh password admin di APK.
- Semua endpoint admin: HTTPS, rate limit, token berumur pendek, dicatat di `admin_log`.
- Kode voucher: acak panjang (≥ 12 karakter), sekali pakai atau berbatas, bisa dicabut.
- Jangan simpan kunci privat di repo atau CI log; taruh di environment server.
- Batas yang jujur: pada HP yang di-root atau dengan alat hooking, pengecekan di sisi APK **tetap bisa dilewati**. Server hanya bisa menjamin lisensi asli; untuk fitur yang benar-benar mahal, taruh logikanya di server (mis. laporan Platinum). Play Integrity bisa ditambah belakangan.
- Waktu perangkat diubah mundur: pakai `TrustedClock`, dan tolak lisensi bila `iat` lebih baru dari jam perangkat secara tidak wajar.

## 8. Fase pengerjaan

- [x] **Fase 0**: keputusan di bagian 9 (lihat jawaban di bawah)
- [x] **Fase 1**: server lisensi minimal (`/license/check`, tanda tangan ECDSA, tabel `users`) di `server/license-server/`
- [ ] **Fase 2**: APK user membaca lisensi, hapus pemilih paket, layar terkunci, tes unit
- [x] **Fase 3**: panel Adm Lock (daftar user, ubah paket, kunci/buka, log) sebagai APK `admin-lock/`
- [~] **Fase 4**: voucher + batas perangkat sudah ada di server dan APK admin; sisi tukar kode di APK user ikut Fase 2
- [ ] **Fase 5**: hardening (rate limit, rotasi kunci, versi minimum, Play Integrity)

## 9. Pertanyaan terbuka (perlu diputuskan sebelum Fase 1)

1. Bentuk Adm Lock: **APK native** atau **panel web** dulu? (Web lebih cepat dibuat; APK bisa menyusul memakai API yang sama.)
2. Tempat server: tambah di `server/` (Node, seperti `otp-server`) atau layanan lain yang sudah dipakai?
3. Kunci penuh (`BLOCKED`): boleh memblokir total, atau cukup turun ke Basic?
4. Nama & daftar paket: tetap Basic/Premium/Platinum, atau tambah **VIP**? Fitur mana masuk paket mana?
5. Pembayaran: manual (admin set sendiri / voucher) dulu, atau langsung terhubung payment?

### Keputusan yang dipakai saat membangun (ubah bila tidak cocok)
1. Bentuk: **APK native terpisah** (`admin-lock/`, permintaan pemilik proyek).
2. Server: **Node tanpa dependensi** di `server/license-server/`, gaya sama dengan `otp-server`.
3. `BLOCKED`: **boleh kunci penuh**; `SUSPENDED` = turun ke Basic. Layar terkunci di APK user (Fase 2) sebaiknya tetap mengizinkan ekspor backup.
4. Paket: **Basic / Premium / Platinum / VIP**; VIP sementara sama dengan Platinum (konstanta `TIERS` di server).
5. Pembayaran: **manual** (admin set paket atau voucher) dulu.
