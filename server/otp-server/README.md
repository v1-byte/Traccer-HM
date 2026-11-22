# OTP server HM Tracker

Server kecil (Node 18+, tanpa dependensi) yang membuat, mengirim, dan memverifikasi OTP. Kode OTP tidak pernah dikirim ke aplikasi; aplikasi hanya menerima `requestId`.

## API
| Endpoint | Body | Balasan |
|---|---|---|
| `POST /otp/send` | `{"channel":"email"\|"wa","ident":"..."}` | `{"ok":true,"requestId":"..."}` |
| `POST /otp/verify` | `{"requestId":"...","code":"123456"}` | `{"ok":true}` |

Galat: status 4xx/5xx dengan `{"ok":false,"error":"pesan"}`. Batas: OTP berlaku 5 menit, 5 percobaan, jeda kirim ulang 1 menit, maksimal 5 permintaan per jam per email/nomor, kode sekali pakai.

## Menjalankan
```
DEV_LOG_CODE=1 node server.js          # uji lokal: kode dicetak ke log
DELIVERY_WEBHOOK_URL=https://... node server.js   # produksi
```
`DELIVERY_WEBHOOK_URL` harus mengirim pesan nyata (Resend, SendGrid, Fonnte, Twilio, dll.) dan menerima `{"channel","ident","message"}`. Simpan kunci API di server, bukan di APK.

## Menyambungkan aplikasi
Build dengan URL HTTPS server (tanpa slash akhir):
```
./gradlew assembleRelease -PHM_OTP_API_URL=https://otp.contoh.com
```
atau env `HM_OTP_API_URL`. Tanpa nilai ini aplikasi kembali ke mode demo. Android memblokir HTTP polos, jadi server harus di belakang HTTPS.

Catatan: penyimpanan in-memory cukup untuk satu instance; gunakan Redis jika lebih.
