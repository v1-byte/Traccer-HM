# Spesifikasi dataset OCR berbasis sumber nyata

Tahap 2 menggunakan manual operator yang dapat ditelusuri sebagai **rujukan istilah dan struktur panel**. Manual tidak dipakai untuk mengarang angka HM. Angka pada sampel tetap berasal dari foto unit nyata yang dikonfirmasi pengguna.

## Sumber rujukan

- **FUSO FE/FG 2015/2017**: membedakan `ODO`, `TRIP`, `TRIP A`, dan `TRIP B`, serta area multi-information display, warning, transmission, dan odometer/trip. [Manual 2017](https://mitfuso.com/files/Owners-Manual-FUSO-2017-FEFG.pdf) dan [Manual 2015](https://mitfuso.com/files/Owners-Manual-FUSO-2015-FEFG.pdf).
- **CAT 307E2**: menggunakan istilah `Service Hour Meter`, `Monitoring System`, `Fuel Level Gauge`, `Coolant Temperature Gauge`, `Action Lamp`, dan `Action Alarm`. Sumber: [Operation and Maintenance Manual 307E2](https://westcountyequipment.com/files/307E2.pdf) dan [Cat Publications](https://catpublications.com/).
- **CAT D3K2/D4K2/D5K2**: mendokumentasikan `Compact Instrument Cluster`, `LCD Screen`, `Odometer`, `Total Distance Traveled`, `Service Hour Meter`, serta engine speed/rpm. Sumber: [Manual SEBU8498-04](https://www.gawest.com/assets/uploads/CAT-D5K.pdf) dan [Cat service manuals](https://www.cat.com/en_US/support/maintenance/service-manuals.html).
- **Komatsu PC210LC-11/PC138US**: mendokumentasikan liquid-crystal machine monitor, `service meter/clock`, meter display, message display, gauge, camera image display, dan function switches. Sumber: [PC210LC-11 manual](https://www.fairfaxhire.co.uk/wp-content/uploads/2025/01/KOMATSU-PC210LC-11-OPS-MANUAL.pdf) dan [PC138US manual](https://s564272a96de90d1a.jimcontent.com/download/version/1710767173/module/11466610473/name/Komatsu%20PC138US%20Operation%20%26%20Maintenance%20Manual.pdf).
- **Hitachi ZXU13US-5B**: mendokumentasikan `Multi Function Monitor`, `Hour Meter`, `Clock`, `Sub Meter`, `Fuel Gauge`, `Coolant Temperature Gauge`, `Basic Screen`, dan `Starting Screen`. Sumber: [Operator's Manual](https://www.completehire.com.au/media/downloadable/pdfs/product-information/Complete-Hire-Sydney-Hitachi-ZXU13us-5B-13T-Excavator-Operators-Manual.pdf).
- **Volvo CE EC140E**: mendokumentasikan IC screen, engine speed graph/segmented bar graph, numeric rpm, dan `HOURS 1/10`. Sumber: [Operator's Manual](https://irp.cdn-website.com/28d45882/files/uploaded/EC140EL%20O.pdf) dan [Volvo CE product archive](https://www.volvoce.com/global/en/products-and-services/past-products/).

Beberapa sumber adalah salinan manual pada repositori dealer atau document CDN. Karena itu, setiap sampel menyimpan `source_id`, model/konteks, dan status `field_photo`; source ID hanya menunjukkan rujukan terminology, bukan asal piksel foto.

## Skema anotasi

Ekspor dataset sekarang menghasilkan `labels.csv` dan `dataset_manifest.json`. Setiap baris menyimpan:

- `exact_text` dan `label`: teks/angka persis yang terlihat dan label yang dikonfirmasi.
- `semantic_label`: misalnya `ODO/KM`, `Hour Meter / HM`, atau `Service Hour Meter / drum`.
- `target_status`: `target`, `negative`, `non_text`, `unreadable`, atau `unknown`.
- `region_type`: `numeric_readout`, `text`, `gauge`, `graph/bar`, `icon`, `lamp`, `warning`, `button/control`, `menu`, atau `camera_image`.
- `machine_type`, `brand_profile`, `display_mode`, `unit`, dan `model_context`.
- `source_kind`: foto lapangan, galeri, atau sumber lain; foto yang disimpan dari kamera aplikasi ditandai `field_photo`.
- `source_id`: rujukan manual yang terminology-nya relevan, bila tersedia.
- `glare`, `blur`, `occlusion`, `crop`, `brightness`, dan `notes` untuk kondisi gambar.

## Aturan pengambilan foto

Foto harus menyertakan panel penuh bila memungkinkan, bukan hanya angka. Untuk FUSO, simpan mode `ODO`, `TRIP`, `TRIP A`, atau `TRIP B` yang sedang tampil. Untuk CAT, pisahkan `Service Hour Meter`, gauge, lampu, dan `CHECK FLTR/FLUID INFO`. Untuk Komatsu, ambil contoh standard screen dan meter-only screen bila tersedia. Untuk Hitachi, catat Basic Screen, Starting Screen, alarm, atau menu. Untuk Volvo, pisahkan angka engine speed dari segmented bar graph dan simpan satuannya.

Jangan memaksa OCR pada simbol, logo, lampu, pointer, gauge graphic, camera view, atau teks yang tertutup. Simpan sebagai `non_text` atau `unreadable`. Istilah negatif bersifat khusus terhadap target yang dipilih; `TRIP` bukan selalu “salah”, tetapi menjadi negatif ketika targetnya ODO total.

Teknologi display hanya diisi jika ditetapkan oleh manual model tersebut atau terlihat jelas pada foto. Jangan mengubah semua panel menjadi LCD, 7-segment, analog, atau drum hanya berdasarkan merek.
