# Panduan Rilis

Rilis dibuat oleh workflow [`release.yml`](../.github/workflows/release.yml) saat tag `vX.Y.Z` didorong.

## 1. Membuat keystore (sekali saja)

```bash
keytool -genkeypair -v -keystore hmtracker-release.jks -alias hmtracker \
  -keyalg RSA -keysize 4096 -validity 10000
```

**Cadangkan file `.jks` dan kata sandinya di tempat aman.** Jika hilang, pembaruan tidak bisa menimpa instalasi yang ada. Jangan pernah commit file ini (sudah diblokir `.gitignore`).

## 2. Mengisi secret GitHub

*Settings → Secrets and variables → Actions → New repository secret*:

| Secret | Isi |
|---|---|
| `HM_KEYSTORE_BASE64` | `base64 -w0 hmtracker-release.jks` |
| `HM_KEYSTORE_PASSWORD` | kata sandi keystore |
| `HM_KEY_ALIAS` | alias kunci (mis. `hmtracker`) |
| `HM_KEY_PASSWORD` | kata sandi kunci |

## 3. Menerbitkan versi

1. Di `app/build.gradle.kts` naikkan `versionCode` (bilangan bulat, selalu naik) dan `versionName` (SemVer).
2. Pindahkan isi **[Belum dirilis]** di `CHANGELOG.md` ke bagian versi baru.
3. Commit ke `main`, lalu:

```bash
git tag v1.4.0
git push origin v1.4.0
```

Workflow `release.yml` akan:

1. memastikan tag sama dengan `versionName`,
2. menolak lanjut bila ada secret yang kosong (agar tidak jatuh ke kunci debug),
3. menjalankan tes unit,
4. membangun APK rilis bertanda tangan,
5. menerbitkan GitHub Release berisi APK, `mapping-<tag>.txt`, dan `SHA256SUMS.txt`.

Simpan `mapping.txt` untuk membaca stack trace dari build yang diperkecil R8.

## Build rilis lokal

```bash
export HM_KEYSTORE_PATH=/jalur/ke/hmtracker-release.jks
export HM_KEYSTORE_PASSWORD=...
export HM_KEY_ALIAS=hmtracker
export HM_KEY_PASSWORD=...
./gradlew :app:assembleRelease --no-daemon
```

Tanpa variabel ini, build rilis memakai kunci debug dan **tidak boleh dipublikasikan**.

## Pipeline otomatis

CI memakai `./gradlew` dari wrapper yang dipin ke Gradle 8.9. Push ke `main` menjalankan tes, lint, build debug, tes server, dan validasi provenance dataset. Push tag `vX.Y.Z` menjalankan tes ulang, build APK bertanda tangan, verifikasi `apksigner`, pembuatan checksum, upload artefak Actions, dan GitHub Release. Keempat secret signing harus sudah diisi sebelum tag didorong.

Untuk menjalankan ulang rilis dari Actions tanpa mendorong tag baru, gunakan **Actions → Release → Run workflow** dan masukkan tag yang sudah ada.

## Verifikasi APK

```bash
sha256sum -c SHA256SUMS.txt
apksigner verify --print-certs HMTracker-v1.4.0.apk
```

## Rilis Adm Lock (jalur terpisah)

Satu repo, dua jalur rilis yang tidak saling memicu:

| | HM Tracker (user) | Adm Lock (admin) |
|---|---|---|
| Tag | `v1.5.1` | `admin-v0.1.0` |
| Workflow | `release.yml` | `admin-lock-release.yml` |
| Modul / versi | `app/` | `admin-lock/build.gradle.kts` |
| File | `HMTracker-vX.apk` | `AdmLock-vX.apk` |
| Unduh | GitHub Release (publik) | Artefak Actions `AdmLock-vX` (akses repo saja); Release hanya jika repo privat |

Secret penandatanganan sama. Tag `admin-v…` tidak cocok dengan pola `v*.*.*`, jadi tidak memicu rilis user, dan sebaliknya. Bisa juga dijalankan manual dari tab Actions (*Adm Lock Release → Run workflow*).
