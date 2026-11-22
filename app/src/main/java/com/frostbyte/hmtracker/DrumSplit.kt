package com.frostbyte.hmtracker

import android.graphics.Bitmap

/*
 * Meter drum mekanik (mis. "QUARTZ ... ENGINE HOURS 1/10").
 * Hanya drum gelap di kiri yang dihitung sebagai HM (mis. 01058). Drum 1/10 jam (angka merah/oranye) dan drum terang
 * sesudahnya BUKAN bagian wajib. Bila ikut terbaca, "01058" berubah jadi "010586" (nilai salah 10x) atau, karena prior
 * desimal, jadi 105.8. Karena itu strip drum dipotong tepat sebelum drum merah sebelum OCR dijalankan.
 * Bagian penentu (findDrumCut) logika murni tanpa Android supaya bisa dites di JVM.
 *
 * Tiga zona pada meter QUARTZ (lihat docs/samples/README.md): (1) drum gelap yang dihitung (tanda merah di foto acuan),
 * (2) penanda "1/10" di bawah drum 1/10 (tanda hijau), (3) drum 1/10 jam: latar PUTIH dengan angka merah (tanda kuning).
 * Batas antara zona 1 dan 3 adalah batas drum, bukan tengah angka merah: angka merah sering tipis dan hanya sebagian
 * yang lolos [isRedPx], jadi potongan dimundurkan melewati latar putih drum 1/10 sampai celah gelap ([drumLeftEdge]).
 */

/** [cut] = batas kanan drum yang dihitung (piksel pada lebar gambar yang dianalisis). */
data class DrumCut(val cut: Int, val tenthLeft: Int, val tenthRight: Int)

/** Piksel merah/oranye khas angka drum 1/10 (ARGB int). Oranye (230,90,50) dan merah murni lolos; coklat dan warna kulit tidak. */
internal fun isRedPx(p: Int): Boolean {
    val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
    return r >= 130 && r - g >= 45 && r - b >= 60 && g >= b - 10
}

/**
 * Cari drum merah pada strip drum [w] x [h] dan kembalikan batas potong, atau null bila tidak ada drum merah yang
 * meyakinkan (lalu strip dibaca utuh seperti biasa). Syarat gugus merah: lebar 2%-35% strip, tinggi >= 30% strip,
 * mulai di sebelah kanan 35% strip; gugus paling kanan dipakai. Celah antar bagian angka (mis. lubang pada "6") dijembatani.
 */
fun findDrumCut(px: IntArray, w: Int, h: Int): DrumCut? {
    if (w < 40 || h < 8 || px.size < w * h) return null
    val cnt = IntArray(w)
    val top = IntArray(w) { Int.MAX_VALUE }; val bot = IntArray(w) { -1 }
    for (x in 0 until w) {
        var n = 0
        for (y in 0 until h) if (isRedPx(px[y * w + x])) { n++; if (y < top[x]) top[x] = y; if (y > bot[x]) bot[x] = y }
        cnt[x] = n
    }
    // Kolom dihitung merah bila cukup tinggi DAN minimal 30% dari kolom merah terkuat: angka drum 1/10 tebal dan tinggi,
    // sedangkan garis tipis merah (lingkaran spidol, goresan, pantulan lampu) hanya beberapa piksel per kolom.
    val minCnt = maxOf(2, h / 25, (cnt.max() * 30 + 99) / 100)
    val col = BooleanArray(w) { cnt[it] >= minCnt }
    val gap = maxOf(2, w / 60)
    var best: IntArray? = null   // [start, end]
    var x = 0
    while (x < w) {
        if (!col[x]) { x++; continue }
        val s = x; var e = x; var miss = 0
        x++
        while (x < w && miss <= gap) { if (col[x]) { e = x; miss = 0 } else miss++; x++ }
        var yMin = Int.MAX_VALUE; var yMax = -1
        for (i in s..e) if (col[i]) { yMin = minOf(yMin, top[i]); yMax = maxOf(yMax, bot[i]) }
        val cw = e - s + 1
        val ok = cw >= w / 50 && cw <= w * 35 / 100 && (yMax - yMin + 1) >= h * 30 / 100 && s >= w * 35 / 100
        if (ok) best = intArrayOf(s, e, yMin, yMax)
    }
    val b = best ?: return null
    val cw = b[1] - b[0] + 1
    val pad = maxOf(2, cw / 2)
    var cut = b[0] - pad
    // drum 1/10 berlatar putih: mundur sampai tepi kiri drum itu, supaya angka merah tidak terpotong dan latar putih tidak ikut dibaca
    val left = drumLeftEdge(px, w, h, b[0], cw, b[2], b[3])
    if (left < b[0]) cut = left - maxOf(2, w / 100)
    if (cut < w * 30 / 100) return null
    return DrumCut(cut, cut, minOf(w, b[1] + pad))
}

private fun lumOf(p: Int): Int = (299 * ((p shr 16) and 255) + 587 * ((p shr 8) and 255) + 114 * (p and 255)) / 1000

/**
 * Tepi kiri latar terang drum 1/10 yang memuat gugus merah [s]..[s]+[cw] pada baris [y0]..[y1]. Kolom dianggap terang bila
 * rata-rata luminansinya di atas 55% antara kolom gelap (persentil 15) dan kolom terang (persentil 95); celah gelap
 * lebih lebar dari w/80 menghentikan penelusuran (itu drum gelap sebelumnya). Mundur paling jauh 3x lebar gugus merah
 * (minimal w/8). Mengembalikan [s] bila tidak ada latar terang atau kontras kecil.
 */
internal fun drumLeftEdge(px: IntArray, w: Int, h: Int, s: Int, cw: Int, y0: Int, y1: Int): Int {
    val bh = y1 - y0 + 1
    if (bh < 4 || y0 < 0 || y1 >= h) return s
    val m = FloatArray(w)
    for (x in 0 until w) { var t = 0; for (y in y0..y1) t += lumOf(px[y * w + x]); m[x] = t.toFloat() / bh }
    val sorted = m.sortedArray()
    val lo = sorted[(w * 15 / 100).coerceIn(0, w - 1)]
    val hi = sorted[(w * 95 / 100).coerceIn(0, w - 1)]
    if (hi - lo < 40f) return s
    val thr = lo + 0.55f * (hi - lo)
    val gap = maxOf(2, w / 80)
    val maxBack = maxOf(cw * 3, w / 8)
    var left = s; var miss = 0; var x = s - 1
    while (x >= 0 && s - x <= maxBack) {
        if (m[x] >= thr) { left = x; miss = 0 } else { miss++; if (miss > gap) break }
        x--
    }
    return left
}

/** Analisis strip pada lebar maks. 400 px: batas potong dan lebar yang dianalisis, atau null bila tidak ada drum merah yang meyakinkan. */
private fun analyzeDrum(bm: Bitmap): Pair<DrumCut, Int>? {
    if (bm.width < 40 || bm.height < 8) return null
    val sw = minOf(bm.width, 400)
    val sh = (bm.height.toLong() * sw / bm.width).toInt().coerceAtLeast(8)
    val s = if (sw == bm.width) bm else Bitmap.createScaledBitmap(bm, sw, sh, true)
    val px = IntArray(sw * s.height); s.getPixels(px, 0, sw, 0, 0, sw, s.height)
    val dc = findDrumCut(px, sw, s.height) ?: return null
    return dc to sw
}

/**
 * Potong strip drum menjadi (drum yang dihitung, drum 1/10 dan sisanya). Null bila tidak ada drum merah yang
 * meyakinkan: pemanggil memakai strip utuh.
 */
fun splitDrumCrop(bm: Bitmap): Pair<Bitmap, Bitmap>? {
    val (dc, sw) = analyzeDrum(bm) ?: return null
    val cutPx = (dc.cut.toLong() * bm.width / sw).toInt().coerceIn(8, bm.width - 8)
    return Bitmap.createBitmap(bm, 0, 0, cutPx, bm.height) to Bitmap.createBitmap(bm, cutPx, 0, bm.width - cutPx, bm.height)
}

/**
 * Hanya drum 1/10 jam (latar putih + angka merah, zona kuning di foto acuan), untuk dibaca sebagai SATU digit terpisah dari
 * drum yang dihitung. Drum terang sesudahnya dan penanda "1/10" tidak ikut. Null bila tidak ada drum merah yang meyakinkan.
 */
fun drumTenthCrop(bm: Bitmap): Bitmap? {
    val (dc, sw) = analyzeDrum(bm) ?: return null
    val l = (dc.tenthLeft.toLong() * bm.width / sw).toInt().coerceIn(8, bm.width - 16)
    val r = (dc.tenthRight.toLong() * bm.width / sw).toInt().coerceIn(l + 8, bm.width)
    return Bitmap.createBitmap(bm, l, 0, r - l, bm.height)
}

/**
 * Strip drum yang HANYA berisi drum yang dihitung sebagai HM (mis. 01058). Drum 1/10 jam merah/oranye dan drum
 * terang sesudahnya dibuang. Bila tidak ada drum merah yang meyakinkan, strip dikembalikan apa adanya. Aman
 * dipanggil dua kali (strip yang sudah terpotong tidak punya drum merah, jadi tidak berubah).
 */
fun drumCounted(bm: Bitmap): Bitmap = splitDrumCrop(bm)?.first ?: bm
