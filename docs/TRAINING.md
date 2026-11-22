# Sistem latih kamera

Tujuannya satu: pembacaan angka meter makin akurat dan makin lancar **untuk meter yang benar-benar kamu pakai**. Ada tiga lapis yang saling melengkapi. Semua data tinggal di HP (`filesDir/training/`), tidak dikirim ke mana pun, dan tidak ikut backup. Satu-satunya pengecualian adalah **Bantuan AI (opsional)** di bawah, yang mati secara bawaan.

| Lapis | Apa yang terjadi | Efek |
|---|---|---|
| 1. Belajar otomatis | Tiap angka yang kamu simpan dipelajari (digit yang tertukar, pola digit dan desimal per unit, lintasan baca yang benar) dan potongan fotonya disimpan sebagai sampel (maks. 300) | Koreksi digit yang pernah tertukar, titik desimal yang tepat untuk meter drum, lintasan OCR yang tidak berguna dilewati |
| 2. Latih manual | Jepret meter apa saja, ketik angka yang benar. Bobot 3x, tidak dibuang otomatis | Mempercepat belajar pada meter yang sulit (LCD miring, drum, silau) |
| 3. Model sendiri | Ekspor sampel, latih di komputer, pasang `hm_digits_model.zip` | Pembacaan live jauh lebih cepat dan khusus untuk meter kamu |

## Alur

1. **Atur > Latih Kamera > Buka pusat latih.**
2. Biarkan *Belajar otomatis* hidup. Pakai aplikasi seperti biasa; tiap pembacaan yang disimpan menambah sampel.
3. Untuk meter yang sering salah baca, tambah sampel manual (*Ambil sampel dengan kamera*). Setelah jepret, periksa pratinjau potongan (harus berisi angka meter), ketik angka yang benar, simpan. Kamera tetap terbuka untuk sampel berikutnya. Di kamera mode latih tampil lencana **MODE LATIH** dan jumlah sampel; tombol *Selesai* kembali ke Pusat latih. Bacaan live tidak diperiksa terhadap riwayat/interval unit, dan watermark serta peringatan servis disembunyikan. Variasikan jarak, sudut, dan cahaya agar model belajar kondisi nyata.
4. Setelah 10+ sampel, tekan **Latih ulang & uji**. Statistik dibangun ulang dari semua sampel dan akurasi diukur secara berurutan: tiap sampel diuji dengan pelajaran dari sampel sebelumnya saja, sehingga angkanya jujur (tidak menguji dengan jawaban sendiri).
5. Bila sampel sudah 300+, **Ekspor dataset (zip)** dan latih model (lihat bawah).

### Dataset dan provenance sumber

Ekspor dataset sekarang berisi `labels.csv` dan `dataset_manifest.json`. Setiap foto lapangan
ditandai `source_kind=field_photo`; `source_id` hanya menunjuk manual yang menjadi rujukan istilah
panel, bukan menyatakan bahwa foto tersebut disalin dari manual. Kamus rujukan mencakup FUSO,
CAT 307E2, CAT D3K2/D4K2/D5K2, Komatsu, Hitachi, dan Volvo CE. Skema menyimpan `semantic_label`,
`target_status`, `region_type`, mode display, model/konteks unit, serta glare/blur/occlusion.
Detail URL dan aturan anotasi ada di [OCR_DATASET_SPEC.md](OCR_DATASET_SPEC.md).

### Menulis angka benar

Tulis persis angka di meter, dengan titik hanya bila ada satu digit desimal: `113775` (odometer), `1041.5` (LCD HM dengan satu digit desimal). Format lain ditolak.

**Meter drum mekanik (mis. QUARTZ "ENGINE HOURS 1/10")**: tulis HANYA drum gelap di kiri yang dihitung sebagai HM, contoh `01058`. Drum merah/oranye 1/10 jam dan drum terang sesudahnya tidak wajib dan tidak ditulis. Pada foto contoh (unit Jd 08, 20 Nov 2026) angka benarnya `01058`, bukan `010586` dan bukan `105.8`. Lihat [samples/README.md](samples/README.md).

Cara kerjanya: pada mode display **HM drum mekanik** strip angka dipotong tepat sebelum drum merah (`DrumSplit.kt`: `findDrumCut`, `splitDrumCrop`, `drumCounted`) sebelum OCR, baik di kamera live, foto final, sampel latih, maupun *Latih ulang & uji*. Gambar sampel yang tersimpan hanya berisi drum yang dihitung, jadi cocok dengan angka benarnya. Bila tidak ada drum merah yang meyakinkan, strip dibaca utuh seperti biasa. Profil drum tidak lagi memakai prior desimal, jadi `01058` tidak berubah menjadi `105.8`.

Saat memotret sampel drum: jangan memberi coretan/lingkaran spidol merah di foto sebelum dijadikan sampel (garis merah mengacaukan pendeteksi drum merah); tandai di layar lain atau pakai salinan foto.

## Cara kerja di dalam

- `Training.kt` (logika murni, ada tes di `TrainingTest`):
  - `confusionPairs` / `withEvent`: bila bacaan mesin berbeda dari angka benar di paling banyak dua digit pada posisi sama, pasangan `dibaca>benar` dihitung. Pasangan yang pernah terjadi minimal 2 kali dipakai `learnedRepair` (dipanggil dari `resolveReading` hanya bila bacaan mustahil menurut riwayat unit dan tafsiran dengan pasangan itu masuk akal; bila beberapa cocok, harus ada yang >= 2x lebih sering). Hasil koreksi selalu ditandai peringatan.
  - `refine`: setelah 5 sampel, unit yang hampir selalu berdesimal mendapat titik sebelum digit terakhir, **hanya bila jumlah digit kandidat sama dengan yang lazim untuk unit itu** (drum desimal merah yang tak terbaca, `1041`, tidak berubah menjadi `104.1`). Unit tanpa desimal membuang titik nyasar. Kandidat dengan jumlah digit yang jarang didahulukan setelahnya oleh kandidat yang lazim.
  - `passPlan`: sesudah 30 sampel, lintasan ML Kit yang tidak pernah menang (kecuali `auto` dan `inv`) dilewati, jadi pembacaan foto lebih cepat.
  - `preferInverse`: bila polaritas terbalik jauh lebih sering benar, kamera live mencobanya lebih dulu (satu panggilan OCR lebih sedikit per frame).
- `Meter.kt` `readFinalDetailed`: seperti `readFinal` tetapi mengembalikan bacaan tiap lintasan (dipakai untuk tahu lintasan mana yang benar) dan area angka yang dipakai (dipakai untuk memotong sampel).
- `Trainer.kt`: penyimpanan profil, sampel, ekspor/impor, dan **Latih ulang & uji**.
- `DigitModel.kt` + `tools/train_digits.py`: model sendiri (lihat bawah).

### Profil merek/jenis meter

## Tahap 1: mode kamera

Pada pengaturan unit sekarang tersedia **Jenis display meter**: otomatis dari satuan,
`ODO / KM digital`, `HM digital / LCD`, dan `HM drum mekanik`. Mode eksplisit mengalahkan
tebakan satuan saat kamera memilih kandidat, sehingga ODO kendaraan tidak dicampur dengan
HM excavator dan drum mekanik mendapat prior digit desimal yang lebih sesuai. Tahap berikutnya
adalah pengumpulan dataset foto nyata dan anotasi area meter.

Di **Pengaturan > Data Unit Aktif > Profil panel meter** tersedia prior ringan untuk
Mitsubishi/kendaraan, LV/light vehicle, pickup, double cabin, bus, dump truck, truk tangki,
CAT excavator, CAT dozer, Komatsu, Hitachi, Volvo CE, John Deere, Kubota,
dozer umum, dan excavator umum. Profil berisi istilah panel
yang lazim (`HM`, `SMR`, `HOUR`, `HMR`, `ODO`, `KM`), rentang panjang angka, serta kecenderungan
adanya digit desimal. Profil tidak berisi angka HM contoh, karena angka HM adalah nilai aktual
masing-masing unit dan memasukkannya sebagai “jawaban benar” justru dapat merusak pembacaan.

Prior ini sengaja lemah: label yang terbaca, ukuran/posisi angka, riwayat unit, dan sampel foto
yang dikonfirmasi pengguna tetap lebih penting. Untuk hasil terbaik, kumpulkan minimal 10 sampel
manual per keluarga tampilan (LCD, drum mekanik, dan panel kendaraan), dengan variasi cahaya,
sudut, jarak, dan angka yang berbeda. Data katalog bersifat referensi terminologi panel; foto
meter unit yang dipakai di lapangan adalah sumber training yang valid.

Untuk LV dan dump truck yang memakai odometer, pilih satuan **KM**. Untuk engine-hour meter
yang terpisah, buat unit/profil HM terpisah atau pilih satuan **HM (jam)** agar label `HOUR/HM/SMR`
tidak bersaing dengan `ODO/KM`. Angka `TRIP`, `RPM`, speedometer, tanggal, koordinat, dan nomor
seri tetap diperlakukan sebagai gangguan dan harus diperiksa pada layar konfirmasi.

## Model AI sendiri

Model CNN + CTC kecil (masukan 32x160 abu-abu, keluaran deret `0-9` dan `.`), hanya operasi bawaan TFLite (tanpa LSTM, tanpa Flex).

```bash
pip install tensorflow pillow numpy
python tools/train_digits.py hm_dataset.zip --out hm_digits_model.zip
```

Pasang `hm_digits_model.zip` lewat *Pasang model (zip)*. Aplikasi menguji model dimuat dulu; bila gagal, model lama tetap.

Cara dipakai di aplikasi:

- **Kamera live**: bila keyakinan >= 0,85 hasilnya langsung dipakai tanpa memanggil ML Kit (jauh lebih cepat); selain itu ML Kit tetap jalan.
- **Foto penuh**: model ikut memberi satu suara (dua bila yakin >= 0,8) pada pemungutan suara lintasan, tidak pernah menjadi satu-satunya penentu.

Pra-olah gambar di `prep()` (Python) dan `prepForModel` (Kotlin) harus sama. Bila mengubah salah satu, ubah keduanya dan latih ulang. Augmentasi di skrip (polaritas acak, rotasi kecil, kecerahan, kontras, blur, derau) sengaja menutup kasus meter gelap/terang dan silau; pada drum dengan silau, rata-rata kecerahan potongan bisa membuat pembalikan otomatis meleset, itu sebabnya polaritas diacak saat melatih.

### Batasan yang perlu diketahui

- Sampel sedikit (< 300) menghasilkan model yang belum akurat; skrip memperingatkan dan menolak di bawah 60.
- Angka **Model sendiri** pada laporan uji bisa terlalu optimistis bila sampelnya ikut melatih model. Uji dengan sampel baru yang tidak diekspor.
- Digit drum yang sedang bergulir (setengah angka) memang sulit untuk OCR apa pun; sampel manual dari meter drum membantu, tetapi bacaan tetap wajib dicocokkan dengan meter asli (konfirmasi tetap diminta sebelum simpan).

## Latih model tanpa komputer (Colab gratis)

Langkah 3 tidak harus memakai komputer. `tools/train_colab.ipynb` menjalankan `tools/train_digits.py` di Google Colab (gratis, GPU T4 opsional).

1. Aplikasi: **Atur > Latih Kamera > Ekspor dataset (zip)**, simpan `hm_dataset.zip`.
2. Buka `tools/train_colab.ipynb` di [colab.research.google.com](https://colab.research.google.com) (Upload), lalu **Runtime > Run all**.
3. Pilih `hm_dataset.zip` dan `tools/train_digits.py` saat diminta, tunggu selesai, unduh `hm_digits_model.zip`.
4. Aplikasi: **Pasang model (zip)**.

Dataset foto meter diunggah ke Colab milikmu sendiri; jangan pakai akun bersama bila fotonya sensitif. Versi TensorFlow di Colab bisa berbeda dari komputer pengembang; bila skrip galat, kirim pesannya.

## Lapis 4: bantuan AI gratis (opsional)

Untuk mengurangi mengetik saat mengambil sampel manual: di **Atur > Latih Kamera > 4 · Bantuan AI**, nyalakan lalu tempel kunci API dari [Google AI Studio](https://aistudio.google.com/apikey). Di layar *Sampel latih* muncul tombol **Tebak angka dengan AI**.

- **Aturan keselamatan**: tebakan hanya mengisi kolom angka. Kamu tetap harus mencocokkannya dengan meter asli lalu menekan *Simpan sampel latih*; angka salah yang lolos akan merusak model, jadi tebakan AI tidak pernah disimpan otomatis dan tidak dipakai di alur catat HM biasa.
- **Privasi**: yang dikirim hanya potongan gambar area angka (bukan foto penuh, tanpa watermark, lokasi, atau nama unit). Mati secara bawaan. Kunci disimpan di prefs `hm_ai` yang dikecualikan dari backup.
- **Gratis**: memakai jatah gratis Google AI Studio milikmu sendiri. Batas jatah, nama model, dan syarat (termasuk pemakaian data di paket gratis) ditentukan Google dan bisa berubah; cek di AI Studio. Nama model bisa diganti di kolom *Model* bila Google mengubahnya (galat 404).
- Kode: `AiAssist.kt` (REST `generateContent`, kunci lewat header), tombol di `ReviewSheet` (`ui/Sheets.kt`), kartu pengaturan di `ui/Training.kt`, tes `AiAssistTest`.
