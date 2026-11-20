# Adm Lock (APK admin)

APK terpisah (`com.frostbyte.hmtracker.admin`) untuk mengunci / membuka paket HM Tracker dari jarak jauh. Berbicara ke `server/license-server`, **tidak langsung ke APK user**. Rancangan: [docs/ADMIN-LOCK.md](../docs/ADMIN-LOCK.md).

## Fitur
Masuk lewat OTP email (daftar putih admin) · cari / tambah akun · ubah paket (Basic, Premium, Platinum, VIP) · masa berlaku · status `active / suspended / blocked` · override per fitur (ikut paket / tambah / cabut) · pesan ke pengguna · lihat & cabut perangkat · buat & cabut kode aktivasi (voucher) · log admin · versi APK minimum.

## Build
```
./gradlew :admin-lock:assembleDebug
./gradlew :admin-lock:assembleRelease -PHM_LICENSE_API_URL=https://lisensi.contoh.com
```
`HM_LICENSE_API_URL` opsional: alamat server juga bisa diisi di layar masuk dan diingat.

## Rilis & unduh (terpisah dari HM Tracker)
Tag `admin-vX.Y.Z` (harus sama dengan `versionName` di `admin-lock/build.gradle.kts`) memicu `.github/workflows/admin-lock-release.yml`. APK ada di **Actions → run terkait → Artifacts → `AdmLock-vX`** (butuh akses repo). GitHub Release dibuat hanya jika repo privat. Rilis user memakai tag `vX.Y.Z` dan tidak terpengaruh. Lihat `docs/RELEASING.md`.

## Keamanan
- Tidak ada password admin di APK. Token sesi hanya di memori (tutup app = masuk lagi).
- `FLAG_SECURE` (tanpa tangkapan layar), `allowBackup=false`, HTTPS wajib.
- **Jangan publikasikan APK ini.** Rilis publik (`release.yml`) hanya membangun `:app`. Bagikan APK admin langsung ke admin saja. Yang melindungi akun tetap server (OTP + daftar putih), bukan kerahasiaan APK.
