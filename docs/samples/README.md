# Sampel acuan: drum mekanik QUARTZ "ENGINE HOURS 1/10"

| Berkas | Angka benar (label) | Mode display | Catatan |
|---|---|---|---|
| `drum-quartz-01058_annotated.jpg` | `01058` | HM drum mekanik | Unit Jd 08, 20 Nov 2026 06:28. Lingkaran putih = drum yang dihitung. Lingkaran merah = drum 1/10 jam (angka `6`), tidak wajib. |
| `drum-quartz-00991_annotated.jpg` | `00991` (drum 1/10 = `2`) | HM drum mekanik | Unit Jd 08, 12 Nov 2026 19:41. Merah = drum yang dihitung, hijau = penanda `1/10` (posisi koma), kuning = drum 1/10 jam (latar putih, angka merah). Kotak zona di `drum_zones.json`. `_clean_approx.jpg` = coretan dihapus dengan inpaint (pendekatan untuk uji, bukan foto asli). |
| `odo-lcd-28481_annotated.jpg` | `28481` | ODO / KM digital | Unit Dt 14 (dump truck), 20 Nov 2026 20:19. LCD 7-segmen miring, 5 digit tanpa desimal; teks `ODO` di kiri LCD bukan angka. Lingkaran merah di luar LCD = area baca. Sampel + contoh gangguan sudah dipotong di `dataset/`. |

Aturan dari foto ini:

1. HM = lima drum gelap di kiri: **01058** (nilai 1058).
2. Drum oranye/merah 1/10 jam dan drum terang sesudahnya **tidak dihitung** dan tidak ditulis di label.
3. Teks lain pada foto (tanggal, kompas `172° S`, koordinat, `Index number: 436`, nomor seri `V3-08687-22402`, tulisan `QUARTZ`) adalah gangguan, bukan HM.

**Foto ini adalah acuan, bukan sampel piksel.** Coretan lingkaran putih/merah digambar di atas foto; garis merah itu memengaruhi pendeteksi drum merah. Untuk melatih kamera, ambil foto yang sama tanpa coretan lewat *Atur > Latih Kamera > Ambil sampel dengan kamera* (mode display *HM drum mekanik*), ketik `01058`, simpan. Variasikan cahaya dan sudut, minimal 10 sampel per jenis drum.

## Tiga zona (foto 00991)

| Warna coretan | Zona | Arti | Dibaca sebagai |
|---|---|---|---|
| Merah | `counted` | Drum gelap yang dihitung | Digit saja: `00991` |
| Hijau | `marker` | Penanda `1/10`; koma ada tepat sesudah drum gelap terakhir | Tata letak, bukan OCR |
| Kuning | `tenth` | Drum 1/10 jam, latar putih + angka merah | Satu digit 0-9 (1 digit = 6 menit): `2` = 12 menit |

Aturan tambahan: titik atau koma yang terbaca OCR pada drum yang dihitung dibuang (celah antar drum bisa terbaca `0099.1`, yang lolos sebagai desimal 99,1). Drum 1/10 tidak dijumlahkan ke HM: HM foto ini `991`, bukan `991,2`.

## Menambah sampel zona

```
python tools/drum_zones.py add FOTO.jpg --counted 00991 --tenth 2 --unit "Jd 08"
python tools/drum_zones.py report
```
Coretan harus tiga warna terpisah (merah, kuning, hijau) di foto yang sama. Foto untuk melatih kamera tetap harus tanpa coretan (lihat catatan di atas).

## Dataset siap latih (`dataset/`)

Format sama dengan ekspor aplikasi (Atur > Latih Kamera > Ekspor): `images/`, `labels.csv`, `dataset_manifest.json`. Anotasi sumbernya `dataset_spec.json` (foto + kotak + label); bangun ulang dengan:

```
python tools/make_dataset.py check            # label cocok aturan aplikasi, potongan target tanpa coretan merah
python tools/make_dataset.py build --zip hm_dataset_samples.zip
python tools/train_digits.py hm_dataset_samples.zip --out hm_digits_model.zip
```

Isi saat ini (foto Dt 14): 1 sampel target `28481` (ODO/KM, LCD, potongan bersih) dan 8 contoh gangguan dari foto yang sama (angka skala speedometer 140/120/100/80/60/40, stempel tanggal, logo). Gangguan berlabel kosong, jadi dilewati pelatih angka dan dipakai sebagai contoh negatif. Foto drum 00991 dan 01058 belum masuk `dataset/` karena coretan merah menutupi pikselnya; ambil ulang tanpa coretan.

Satu sampel belum cukup untuk melatih model (butuh sekitar 300+, idealnya 1000+); paket ini adalah awal dan contoh format.

## Catatan ukuran foto

Foto contoh di folder ini sudah dikompres ulang: dimensi piksel dan rasio aspek tidak berubah, JPEG progresif 4:2:0, kualitas dipilih serendah mungkin yang masih mirip aslinya (PSNR >= 37 dB, SSIM >= 0,93), dan metadata EXIF (termasuk tag lokasi GPS, nama kamera, dan keterangan) dibuang. Total foto turun dari sekitar 660 KB menjadi sekitar 310 KB. Koordinat zona di `drum_zones.json` dan `dataset_spec.json` tetap berlaku karena ukuran gambarnya sama. Foto asli ada di zip sebelumnya.
