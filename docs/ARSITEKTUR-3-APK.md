# Arsitektur 3 APK + Server

> Status: **rencana** · Dibuat 2026-11-19

## 1. Isi repo hari ini

| Bagian | Lokasi | Status |
|---|---|---|
| APK user (HM Tracker v1.5.1) | `app/` | **Sudah berjalan** |
| Server OTP | `server/otp-server/` | Ada (Node, tanpa dependensi) |
| APK Admin HM | — | **Belum ada** |
| Adm Lock | `admin-lock/` | **Ada (v0.1.0)**, belum diuji di perangkat |
| Server lisensi | `server/license-server/` | **Ada** (sinkronisasi belum) |

## 2. Rencana 3 komponen

| # | Komponen | Peran | Catatan |
|---|---|---|---|
| 1 | **APK user** | Catat HM, kamera OCR, watermark, audit | Sudah ada; perlu klien lisensi & sinkronisasi |
| 2 | **Admin HM** | Pantau armada, bacaan, bukti | Detail: [ADMIN-HM.md](ADMIN-HM.md) |
| 3 | **Adm Lock** | Kunci / buka paket Basic, Premium, Platinum, VIP | Detail: [ADMIN-LOCK.md](ADMIN-LOCK.md) |

Ketiganya berbicara lewat **satu server** (login admin, lisensi, sinkronisasi), tidak langsung satu sama lain.

```
 APK user ───────┐
                 ├──►  Server (OTP · lisensi · sinkronisasi)  ◄──── Adm Lock
 (offline-first) │                    │                         ◄──── Admin HM
                 └────────────────────┘
```

## 3. Saran susunan repo

Satu repo besar (monorepo) agar kontrak API dan versi tetap sinkron:

```
Traccer-HM/
├── app/                    # APK user (sudah ada)
├── admin-hm/               # Admin HM (baru)
├── admin-lock/             # Adm Lock (baru)
├── server/
│   ├── otp-server/         # sudah ada
│   └── license-server/     # baru: lisensi + (nanti) sinkronisasi
└── docs/
```

Alternatif: repo terpisah per aplikasi. Monorepo lebih mudah selama masih satu pengembang; pisahkan kalau tiap aplikasi punya jadwal rilis sendiri.

## 4. Urutan pengerjaan

1. **Adm Lock** (server lisensi → klien di APK user → panel admin). Dikerjakan pertama.
2. **Sinkronisasi** data HM ke server.
3. **Admin HM**.

Alasan urutan: Adm Lock memasang fondasi bersama (login admin, server, tanda tangan), dan sinkronisasi membutuhkan lisensi aktif untuk memutuskan siapa boleh mengirim data.
