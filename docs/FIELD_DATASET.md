# Intake dataset foto lapangan

Dataset OCR hanya boleh berisi foto yang memang boleh dipakai untuk pelatihan. Prior merek di aplikasi bukan pengganti foto meter nyata. Untuk memperbesar dataset farm tractor, ambil foto dari unit yang Anda miliki atau dari operator/dealer yang memberi izin tertulis.

## Target awal farm tractor

Usahakan minimal 20–30 foto per keluarga merek, dengan variasi:

- John Deere dan Kubota sebagai prioritas pertama.
- Massey Ferguson, New Holland, Case IH, Fendt, Yanmar, Mahindra, Deutz-Fahr, Iseki, dan Kioti.
- Kondisi terang, redup, silau, kotor, miring, sebagian tertutup, dan jarak kamera berbeda.
- Foto panel penuh dan crop area angka. Sertakan contoh negatif seperti RPM, suhu, tanggal, trip, lampu, dan nomor seri.

Angka target harus dibaca dan dikonfirmasi manusia dari meter asli. Jangan menebak label dari nama merek. Jika angka atau kondisi tidak jelas, beri `target_status: unreadable` atau `negative`, bukan label perkiraan.

## Provenance wajib

Setiap foto pada `docs/samples/dataset_spec.json` harus menyimpan `source_id`, `machine_type`, `brand_profile`, `unit`, `display_mode`, `light_condition`, dan `camera_angle`. Untuk foto baru, tambahkan juga informasi internal berikut di catatan sumber sebelum commit:

- izin pemakaian atau pemilik foto;
- model unit dan tahun bila diketahui;
- negara/pasar;
- operator atau ID sumber yang tidak memuat data pribadi;
- tanggal pengambilan;
- apakah foto asli, crop, atau hasil anotasi.

Jangan commit wajah, nomor telepon, alamat, koordinat GPS, plat nomor, atau dokumen kerja. Hapus EXIF GPS sebelum commit.

## Validasi sebelum commit

```bash
python3 tools/validate_field_dataset.py \
  --spec docs/samples/dataset_spec.json \
  --samples-dir docs/samples

python3 tools/make_dataset.py check
python3 tools/make_dataset.py build --zip /tmp/hm-dataset.zip
```

Validator memeriksa keberadaan foto, ukuran kotak anotasi, metadata penting, dan format label target. Ia tidak menyatakan bahwa OCR pasti benar; verifikasi manusia tetap wajib.

## Format anotasi singkat

```json
{
  "photo": "farm-tractor-john-deere-001.jpg",
  "machine_type": "Farm tractor",
  "brand_profile": "john_deere",
  "unit": "jam",
  "display_mode": "hm_lcd",
  "source_id": "owned_field_photo_jd_001",
  "model_context": "John Deere 5E · engine hour meter",
  "light_condition": "daylight",
  "camera_angle": "front",
  "items": [
    {
      "box": [120, 80, 460, 190],
      "label": "1234.5",
      "target_status": "target",
      "region_type": "numeric_readout",
      "semantic_label": "Hour Meter / HM",
      "notes": "angka dikonfirmasi pada panel asli"
    }
  ]
}
```

Foto dari marketplace, katalog, atau hasil pencarian web tidak dimasukkan sebagai foto lapangan tanpa lisensi yang jelas. Manual pabrikan hanya dipakai untuk istilah panel dan struktur, bukan untuk membuat angka HM.
