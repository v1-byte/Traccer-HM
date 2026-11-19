# Catatan implementasi dari audit kekurangan

## Yang dikerjakan

- Mode **Simpan contoh gangguan** ditambahkan ke lembar review pada mode Latih Kamera. Contoh dapat diberi kategori RPM, trip, speedometer, fuel gauge, temperature, serial number, date, coordinates, warning light, atau icon/grafik.
- Contoh gangguan disimpan sebagai `targetStatus=negative`, dengan label angka kosong. Data ini masuk ke ekspor dataset dan tidak ikut `Latih ulang & uji` maupun statistik bacaan angka.
- Metadata kondisi pengambilan foto ditambahkan: model kendaraan, tahun unit, negara/pasar, kondisi cahaya, tingkat silau, sudut kamera, dan status display. Metadata lama tetap dapat dibaca; kolom baru bersifat opsional.
- Metadata baru ikut disertakan di JSON sampel dan kolom tambahan CSV ekspor.

## Batasan yang diketahui

- Contoh gangguan menggunakan ROI kamera bila tersedia; bila tidak tersedia, seluruh foto dipakai. UI penggeseran/resize kotak manual dan klasifikasi area label versus negatif yang dapat digambar belum dibuat.
- Form metadata tambahan saat ini muncul pada mode contoh gangguan; form lengkap untuk sampel angka target masih perlu diperluas.
- ZIP sumber tidak berisi foto lapangan nyata. Perubahan ini tidak menambahkan atau mengklaim foto LV, dump truck, truk tangki, pickup, excavator, dozer, meter drum, LCD 7-segment, maupun kondisi debu/silau.
- Tidak ada detector area meter, koreksi perspektif, segmentasi digit, evaluasi lintas unit, atau sumber manual resmi baru yang ditambahkan.
- Build Android belum bisa diverifikasi karena mesin tidak memiliki Android SDK. Gradle 8.9 tersedia, tetapi proses berhenti saat mencari `ANDROID_HOME` atau `local.properties` dengan `sdk.dir`.

## Cara menggunakan fitur

Masuk ke **Latih Kamera**, ambil gambar atau pilih foto, lalu pada lembar review pilih **Simpan gangguan**, pilih kategori, lengkapi metadata yang diketahui, dan tekan **Simpan contoh gangguan**. Ekspor dataset dari Pusat Latih untuk mendapat gambar dan metadata tersebut di dalam ZIP.

## Perubahan Detail Card (bukti, waktu, paket)

Lihat CHANGELOG bagian "Diperbaiki / ditambah (Detail Card, bukti, paket)". **Belum dikompilasi dan belum dijalankan**: lingkungan tempat perubahan ini ditulis tidak punya Kotlin/Android SDK. Jalankan `gradle testDebugUnitTest` dan coba di HP nyata (tampilan kartu, kode Ref pratinjau = foto jadi, Cek kode Ref di Atur) sebelum rilis.
