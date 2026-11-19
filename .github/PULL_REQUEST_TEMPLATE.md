## Ringkasan
<!-- Apa yang berubah dan mengapa? Tautkan issue: Closes #123 -->

## Jenis perubahan
- [ ] Perbaikan bug
- [ ] Fitur baru
- [ ] Refaktor / pembersihan
- [ ] Dokumentasi / CI

## Daftar periksa
- [ ] `gradle testDebugUnitTest` lulus
- [ ] Logika baru disertai tes (validasi HM, hitungan servis, integritas audit)
- [ ] Tidak mengubah urutan/pemisah di `canonical()` (merusak tanda tangan audit lama), atau sudah ada migrasi
- [ ] Jika skema DB atau backup berubah: `SCHEMA`/versi DB dinaikkan + migrasi + tes
- [ ] `CHANGELOG.md` diperbarui di bagian **[Belum dirilis]**
- [ ] Tidak ada kunci, keystore, atau data pribadi di commit

## Tangkapan layar / catatan uji
<!-- Untuk perubahan UI/kamera: sertakan gambar dan perangkat/versi Android yang dicoba. -->
