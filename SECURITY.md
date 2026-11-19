# Kebijakan Keamanan

## Versi yang didukung

Hanya rilis terbaru pada cabang `main` yang menerima perbaikan keamanan.

## Melaporkan celah

**Jangan** membuka issue publik. Gunakan *Security → Report a vulnerability* (GitHub private vulnerability reporting) di repo ini. Sertakan versi aplikasi, perangkat/versi Android, langkah reproduksi, dan dampaknya. Kami akan mengonfirmasi laporan secepatnya dan mengabari perkembangan perbaikan.

## Cakupan & model ancaman

Aplikasi ini mengutamakan **bukti yang dapat diaudit secara offline**. Hal-hal berikut perlu diketahui sebelum menilai suatu temuan sebagai celah:

- **Login OTP mode demo**: OTP dibuat dan ditampilkan di perangkat. Ini sengaja (placeholder) dan bukan batas keamanan sampai backend OTP dipasang.
- **Jejak audit** dilindungi HMAC-SHA256 berkunci Android Keystore dan dirantai (lihat [docs/AUDIT.md](docs/AUDIT.md)). Ia mendeteksi perubahan/penghapusan di tengah rantai, penggantian foto, dan perubahan HM riwayat. Ia **tidak** dapat mendeteksi instal ulang, reset seluruh data, penghapusan entri paling akhir, atau perangkat yang di-root penuh tanpa jangkar di server.
- Foto dari **galeri** tidak melewati pemeriksaan kamera/GPS dan ditandai demikian.
- Entri sebelum v1.3 dan hasil impor backup berstatus *tidak bertanda tangan*, bukan dianggap dimanipulasi.

## Praktik untuk pengelola

- Keystore rilis dan sandi hanya disimpan di GitHub Secrets / penyimpanan rahasia; `.gitignore` memblokir `*.jks`, `*.keystore`, `keystore.properties`.
- Dependensi dipantau Dependabot.
- APK rilis dipublikasikan bersama `SHA256SUMS.txt`.
