# Model Integritas Jejak Audit

Tujuan: memberi bukti offline bahwa foto meter dan angka HM tidak diubah setelah dicatat.

## Struktur entri

Setiap foto menghasilkan satu entri `Audit` dengan field: nomor urut `n`, waktu `t`, `unit`, bacaan live, bacaan foto, angka final `value`, flag `edited`, selisih jam `skewMs`, kondisi `gps`, peringatan `warn`, SHA-256 file foto `sha`, sumber `src` (`cam` / `gal` / `imp`), dan tanda tangan entri sebelumnya `prev`.

## Tanda tangan

```
sig = HMAC-SHA256( kunci Keystore "hmt_audit_hmac", canonical(entri) )
canonical = n, t, unit, live, photo, value, edited, skewMs, gps, warn, sha, src, prev
            digabung dengan pemisah U+001F
```

- Kunci dibuat di Android Keystore dan tidak dapat diekspor dari perangkat.
- `prev` berisi `sig` entri bertanda tangan sebelumnya, sehingga entri membentuk **rantai**.
- **Urutan dan pemisah `canonical()` tidak boleh diubah.** Mengubahnya membuat semua tanda tangan lama gagal. Perubahan format memerlukan versi baru dan migrasi.

## Hasil verifikasi (`Trust`)

| Status | Arti |
|---|---|
| `VERIFIED` | Tanda tangan, rantai, foto, dan HM cocok |
| `UNSIGNED` | Entri lama (sebelum v1.3), hasil impor backup, atau kunci perangkat tidak tersedia; **bukan** dianggap dimanipulasi |
| `FILE_MISSING` | Foto sudah tidak ada (dihapus atau dipangkas); bukan manipulasi |
| `HM_CHANGED` | Angka HM di riwayat berbeda dari saat foto diambil |
| `FILE_CHANGED` | SHA-256 file berbeda dari yang tersimpan |
| `CHAIN_BROKEN` | Ada entri sebelumnya yang dihapus atau diubah |
| `RECORD_EDITED` | Isi entri tidak cocok dengan tanda tangannya |

Status `HM_CHANGED` dan yang setelahnya dianggap *buruk* (`Verdict.bad`). Urutan penilaian di `verifyAudits()`: catatan diubah → rantai putus → foto berubah → HM berubah → foto hilang → tidak bertanda tangan → kunci tidak tersedia → terverifikasi.

Pemeriksaan penuh dijalankan dari **Atur → Periksa integritas jejak audit**; hasil per foto tampil di Detail Foto.

## Batas yang disengaja

Tidak terdeteksi secara offline:

- instal ulang aplikasi atau *reset semua data* (kunci dan jejak ikut hilang),
- penghapusan entri **paling akhir** (tidak ada entri sesudahnya yang menunjuk ke sana),
- perangkat yang di-root penuh.

Mitigasi yang direncanakan: jangkar hash rantai di server. Foto dari galeri ditandai `src = gal` karena tidak melewati pemeriksaan kamera dan GPS.

## Pertanyaan terbuka

Kunci Keystore tidak ikut Auto Backup. Jika basis data dipulihkan ke perangkat lain dan kunci baru dibuat, tanda tangan lama tidak akan cocok. Perilaku ini perlu diverifikasi di perangkat nyata dan diputuskan penanganannya (lihat Roadmap di README).

## Kode Ref di watermark

Template Detail Card mencetak `Ref XXXX-XXXX`: 8 karakter pertama dari `HMAC-SHA256(kunci Keystore, "REF" U+001F n U+001F t U+001F unit U+001F value U+001F prev)`. Kode ini mengikat nomor urut, waktu, unit, angka, dan rantai, serta tidak bisa dibuat ulang tanpa perangkat asal. **Atur → Cek kode Ref foto** mencarinya di jejak audit perangkat ini.

Batas yang disengaja: foto tidak bisa memuat hash dirinya sendiri karena hash file dihitung setelah watermark digambar. Keutuhan piksel foto diperiksa lewat `sha` di jejak audit (Detail Foto), bukan dari kode Ref. Kode Ref hanya bisa dicocokkan di perangkat yang membuat foto, karena kuncinya tidak bisa diekspor.
