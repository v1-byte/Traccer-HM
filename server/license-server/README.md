# Server lisensi + Adm Lock

Server kecil (Node 18+, tanpa dependensi) yang menerbitkan **lisensi bertanda tangan** untuk APK user dan menyediakan API untuk APK **Adm Lock** (`admin-lock/`). Rancangan: [docs/ADMIN-LOCK.md](../../docs/ADMIN-LOCK.md).

## Menjalankan
```
ADMIN_EMAILS=anda@contoh.com DEV_LOG_CODE=1 node server.js     # uji lokal: OTP admin dicetak ke log
ADMIN_EMAILS=anda@contoh.com DELIVERY_WEBHOOK_URL=https://... node server.js   # produksi
node --test test.js                                              # 9 tes
```
Saat pertama jalan server membuat kunci ECDSA P-256 di `data/license-private.pem` dan **mencetak kunci publik** (base64 X.509). Kunci publik itulah yang nanti ditanam di APK user (`HM_LICENSE_PUBKEY`). Kunci privat jangan pernah keluar dari server; bisa juga lewat env `LICENSE_PRIVATE_KEY_PEM`. Pasang di belakang HTTPS.

| Env | Fungsi |
|---|---|
| `ADMIN_EMAILS` | Daftar email admin (koma). Hanya email ini yang bisa masuk. |
| `DELIVERY_WEBHOOK_URL` / `_AUTH` | Pengirim OTP admin, kontrak sama dengan `otp-server`. |
| `LICENSE_TTL_DAYS` | Batas pemakaian offline lisensi (default 7). |
| `MAX_DEVICES` | Maks. perangkat per akun (default 2). |
| `DATA_DIR`, `PORT`, `TRUST_PROXY` | Lokasi data, port (8081), pakai `X-Forwarded-For`. |

## API
APK user: `POST /license/check {ident, deviceId, appVersion}` · `POST /license/redeem {ident, deviceId, code}` · `GET /license/pubkey`.
Balasan lisensi: `{ok, license:{payload:"<JSON string>", sig:"<base64 DER>"}}`; verifikasi `SHA256withECDSA` atas byte `payload` persis seperti diterima. Isi payload: `ident, tier, status, features[], deviceId, iat, exp, validUntil, msg, minVersionCode`.

Admin (header `Authorization: Bearer <token>`): `POST /admin/login/send|verify`, `GET /admin/me|tiers|config|log|users|vouchers`, `PATCH /admin/config`, `PATCH /admin/users/:ident` (juga membuat akun), `POST /admin/users/:ident/block|unblock`, `GET /admin/users/:ident/devices`, `DELETE /admin/users/:ident/devices/:deviceId`, `POST /admin/vouchers`, `POST /admin/vouchers/:code/revoke`. `PATCH` juga diterima sebagai `POST` + `X-HTTP-Method-Override: PATCH`.

Isi paket diatur di konstanta `TIERS` pada `server.js` (VIP sementara sama dengan Platinum).

## Batasan yang perlu diketahui
- **`/license/check` belum terikat login OTP.** Siapa pun yang tahu `ident` bisa meminta lisensi akun itu dan, sebelum batas perangkat penuh, mendaftarkan perangkatnya. Dampak: isi lisensi tidak rahasia, tapi slot perangkat bisa dihabiskan pihak lain. Perbaikan di Fase 2: server menerbitkan token sesi setelah OTP user terverifikasi, dan `/license/check` mewajibkannya.
- Penyimpanan berkas JSON, satu proses. Untuk skala lebih besar pindah ke SQLite/Postgres.
- Peran admin baru satu (semua email di `ADMIN_EMAILS`); peran `owner` / `lock-admin` / `hm-admin` belum dibedakan.
