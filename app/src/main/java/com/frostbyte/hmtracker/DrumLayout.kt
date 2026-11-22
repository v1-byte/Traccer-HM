package com.frostbyte.hmtracker

/*
 * Logika murni (tanpa Android) untuk meter drum mekanik QUARTZ "ENGINE HOURS 1/10".
 *
 * Tiga zona, sesuai foto acuan docs/samples/drum-quartz-00991_annotated.jpg:
 *  - MERAH  : drum gelap yang dihitung sebagai HM (mis. 00991). Hanya digit; drum tidak punya titik tercetak.
 *  - HIJAU  : penanda "1/10". Posisi koma = tepat sesudah drum gelap terakhir, jadi koma ditentukan oleh TATA LETAK,
 *             bukan oleh OCR. Titik/koma yang "terbaca" OCR pada drum yang dihitung adalah gangguan (celah antar drum,
 *             pantulan) dan dibuang.
 *  - KUNING : drum 1/10 jam (latar putih, angka merah) = satu digit 0-9; 1 digit = 6 menit. Dibaca terpisah dan
 *             TIDAK dijumlahkan ke HM (HM = 991, bukan 991,2) kecuali pemanggil meminta lewat [composeDrumValue].
 */

/** Hasil baca meter drum: [counted] hanya digit (mis. "00991"), [tenth] digit drum 1/10 (0-9) atau null bila tidak yakin. */
data class DrumRead(val counted: String, val tenth: Int?)

/**
 * Drum yang dihitung tidak punya titik desimal. Kandidat OCR seperti "0099.1" (celah antar drum terbaca titik) akan
 * lolos [normalizeCandidate] sebagai desimal 99,1 (salah 10x), jadi semua titik/koma dibuang sebelum kandidat dipakai.
 */
fun drumDigitsOnly(s: String): String = s.filter { it.isDigit() }

/**
 * Pilih digit drum 1/10 dari bacaan beberapa lintasan OCR ([reads]: teks digit tiap lintasan). Hanya bacaan satu digit yang
 * dihitung (dua digit berarti drum sebelah ikut terbaca) dan digit harus disepakati minimal [need] lintasan tanpa seri;
 * selain itu null, lebih baik tidak menampilkan angka daripada salah.
 */
fun pickTenth(reads: List<String>, need: Int = 2): Int? {
    val votes = reads.map { drumDigitsOnly(it) }.filter { it.length == 1 }.groupingBy { it }.eachCount()
    val top = votes.values.maxOrNull() ?: return null
    if (top < need || votes.values.count { it == top } > 1) return null
    return votes.entries.first { it.value == top }.key.toInt()
}

/** 1 digit drum 1/10 = 0,1 jam = 6 menit. */
fun tenthMinutes(d: Int): Int = d.coerceIn(0, 9) * 6

/** Teks info untuk layar Preview. */
fun tenthInfo(d: Int): String = "Drum 1/10 jam terbaca $d (≈ ${tenthMinutes(d)} menit). Tidak ditambahkan ke HM (HM = drum gelap saja)."

/**
 * Nilai HM drum sebagai teks. Bawaan: hanya drum yang dihitung ("00991"). Bila [includeTenth] dan digit 1/10 ada, koma
 * dipasang tepat sesudah drum terakhir: "00991" + 2 -> "991.2" (nol di depan dibuang karena sudah berupa angka desimal).
 */
fun composeDrumValue(counted: String, tenth: Int?, includeTenth: Boolean = false): String {
    val d = drumDigitsOnly(counted)
    if (!includeTenth || tenth == null || d.isEmpty()) return d
    val whole = d.trimStart('0').ifEmpty { "0" }
    return "$whole.${tenth.coerceIn(0, 9)}"
}
