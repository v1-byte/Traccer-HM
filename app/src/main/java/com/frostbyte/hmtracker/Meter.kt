package com.frostbyte.hmtracker

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Jam tepercaya. Setelah ada fix GPS asli, waktu dihitung dari waktu GPS + elapsedRealtime,
 * sehingga mengubah jam HP sesudahnya tidak memengaruhi waktu pembacaan.
 */
object TrustedClock {
    @Volatile private var anchorGps = 0L
    @Volatile private var anchorElapsed = 0L
    @Volatile private var ok = false

    val synced: Boolean get() = ok

    fun now(): Long = if (ok) anchorGps + (SystemClock.elapsedRealtime() - anchorElapsed) else System.currentTimeMillis()

    fun sync(gpsMs: Long) {
        anchorGps = gpsMs; anchorElapsed = SystemClock.elapsedRealtime(); ok = true
    }

    /** Selisih jam HP terhadap waktu GPS dalam ms (0 bila belum ada fix GPS). */
    fun skewMs(): Long = if (ok) abs(now() - System.currentTimeMillis()) else 0L
}

// ===== OCR =====

suspend fun TextRecognizer.read(bm: Bitmap): Text? = suspendCancellableCoroutine { c ->
    process(InputImage.fromBitmap(bm, 0))
        .addOnSuccessListener { if (c.isActive) c.resume(it) }
        .addOnFailureListener { if (c.isActive) c.resume(null) }
}

/** Perbaiki salah baca huruf-mirip-angka pada elemen yang memang berisi angka; null bila bukan angka meter. */
internal fun fixDigits(s: String): String? {
    if (s.none { it.isDigit() }) return null
    val mapped = buildString {
        for (ch in s) append(
            when (ch) {
                'O', 'o', 'D', 'Q' -> '0'
                'I', 'l', '|' -> '1'
                'Z', 'z' -> '2'
                'S' -> '5'
                'G', 'b' -> '6'
                'B' -> '8'
                'g', 'q' -> '9'
                else -> ch
            }
        )
    }
    val kept = mapped.filter { it.isDigit() || it == '.' || it == ',' }
    if (kept.count { it.isDigit() } * 2 < mapped.length) return null
    return kept
}

/** Semua digit hasil OCR (huruf mirip angka diperbaiki lewat [fixDigits]), tanpa titik/koma: dipakai membaca satu digit drum 1/10. */
internal fun digitsOf(t: Text?): String =
    t?.textBlocks.orEmpty().flatMap { b -> b.lines.flatMap { l -> l.elements.mapNotNull { fixDigits(it.text) } } }
        .joinToString("") { it.filter { ch -> ch.isDigit() } }

/**
 * Kumpulkan kandidat angka dari hasil OCR memakai kotak per elemen: elemen bertetangga digabung
 * hanya bila jaraknya kecil, sehingga dua angka terpisah tidak menyatu seperti saat semua spasi dibuang.
 * Hasil diurutkan dari yang paling banyak digitnya.
 */
fun candidatesOf(t: Text?): List<String> {
    if (t == null) return emptyList()
    val raw = ArrayList<String>()
    for (b in t.textBlocks) for (ln in b.lines) {
        val nums = ln.elements.mapNotNull { e -> fixDigits(e.text)?.let { it to e.boundingBox } }
        if (nums.isEmpty()) continue
        var cur = StringBuilder(); var prev: Rect? = null
        for ((s, bx) in nums) {
            if (cur.isNotEmpty() && prev != null && bx != null && bx.left - prev.right > bx.height() * 0.9) {
                raw += cur.toString(); cur = StringBuilder()
            }
            cur.append(s); prev = bx ?: prev
        }
        if (cur.isNotEmpty()) raw += cur.toString()
    }
    return raw.mapNotNull { normalizeCandidate(it) }.distinct().sortedByDescending { c -> c.count { it.isDigit() } }
}

fun bestCandidate(t: Text?): String = candidatesOf(t).firstOrNull() ?: ""

// ===== pencarian angka otomatis di seluruh frame =====

/** Elemen OCR tanpa tipe Android agar pemilihan angka bisa dites di JVM. */
data class OcrTok(val s: String, val l: Int, val t: Int, val r: Int, val b: Int)

/** Angka meter yang ditemukan beserta kotaknya (piksel frame), skor pencarian, dan label di dekatnya (HM/KM/lainnya) bila ada. */
data class MeterHit(val text: String, val l: Int, val t: Int, val r: Int, val b: Int, val score: Double = 0.0, val tag: LabelTag? = null)

/** Elemen OCR per baris (hanya yang punya kotak). */
fun tokensOf(t: Text?): List<List<OcrTok>> {
    if (t == null) return emptyList()
    val out = ArrayList<List<OcrTok>>()
    for (b in t.textBlocks) for (ln in b.lines) {
        val toks = ln.elements.mapNotNull { e -> e.boundingBox?.let { OcrTok(e.text, it.left, it.top, it.right, it.bottom) } }
        if (toks.isNotEmpty()) out += toks
    }
    return out
}

/**
 * Pilih angka yang paling mirip angka meter pada frame [fw] x [fh]: elemen bertetangga digabung (aturan sama dengan
 * [candidatesOf]; titik desimal yang terpisah ikut disambung), minimal [minDigits] digit, bentuk memanjang, dan tinggi
 * tidak terlalu kecil (min. 1% tinggi frame; angka yang kecil tetap kalah skor dari yang besar, lalu zoom otomatis
 * memperbesarnya). Jam/tanggal ("12:30") dan pengali ("x100") dilewati.
 *
 * Skor = digit x tinggi relatif x (1 - 0,6 x jarak dari titik pilihan [prefX],[prefY]; bawaan tengah frame atau titik
 * yang diketuk) x faktor panjang digit (5-7 digit ideal) x faktor label. Label di dekat angka menentukan jenisnya:
 * "HM"/"HOUR"/"SMR"/"JAM" menaikkan skor bila yang dicari [want] = HM, "KM"/"ODO" bila KM; label lain (RPM, SN, suhu,
 * tekanan, ...) menurunkannya, jadi HM tidak tertukar dengan nomor seri atau angka RPM. Tanpa [want], label HM/KM
 * hanya memberi bonus kecil. Null bila tidak ada yang layak.
 */
fun pickMeter(
    lines: List<List<OcrTok>>, fw: Int, fh: Int,
    prefX: Float = 0.5f, prefY: Float = 0.5f, minDigits: Int = 3, want: MeterKind? = null,
    profile: MeterProfile? = null,
): MeterHit? {
    if (fw <= 0 || fh <= 0) return null
    val labels = collectLabels(lines)
    var best: MeterHit? = null
    fun consider(s: String, l: Int, t: Int, r: Int, b: Int, glued: LabelTag?) {
        val c = normalizeCandidate(s) ?: return
        val digits = c.count { it.isDigit() }
        val w = r - l; val h = b - t
        if (digits < minDigits || h <= 0 || w < h * 1.2f || h < fh * 0.01f) return
        val dx = (l + r) / 2f / fw - prefX; val dy = (t + b) / 2f / fh - prefY
        val dist = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtMost(1f)
        val tag = glued ?: nearbyLabel(labels, l, t, r, b)
        var score = digits * (h.toDouble() / fh) * (1.0 - 0.6 * dist) * lenFactor(digits) * labelFactor(tag, want)
        // Prior ringan dari tipe panel; label HM/KM tetap lebih kuat daripada profil.
        profile?.let { p ->
            if (digits in p.typicalDigits) score *= 1.10
            else score *= 0.88
            if (p.decimalLikely == c.contains('.')) score *= 1.04 else score *= 0.98
        }
        if (c.contains('.')) score *= 1.15   // digit desimal terbaca: ciri khas HM
        if (score > (best?.score ?: 0.0)) best = MeterHit(c, l, t, r, b, score, tag)
    }
    for (ln in lines) {
        var sb = StringBuilder(); var l = 0; var t = 0; var r = 0; var b = 0; var prevR = 0
        var glued: LabelTag? = null
        fun flush() {
            if (sb.isNotEmpty()) consider(sb.toString(), l, t, r, b, glued)
            sb = StringBuilder(); glued = null
        }
        for (e in ln.sortedBy { it.l }) {
            val h = e.b - e.t
            if (isDateLike(e.s) || isMultiplier(e.s) || e.s.contains('%')) { flush(); continue }
            val raw = e.s.trim()
            if (raw == "." || raw == ",") {   // titik desimal yang dibaca sebagai elemen sendiri
                if (sb.isNotEmpty() && e.l - prevR <= h * 0.9) { sb.append('.'); prevR = e.r; r = maxOf(r, e.r) }
                continue
            }
            val (core, g) = splitGlued(e.s)        // "SN12345" -> angka 12345 berlabel SN (huruf label tidak ikut dikoreksi jadi angka)
            val s = fixDigits(core)
            if (s == null) { flush(); continue }   // label/teks lain memutus angka
            if (sb.isNotEmpty() && e.l - prevR > h * 0.9) flush()
            if (sb.isEmpty()) { l = e.l; t = e.t; r = e.r; b = e.b } else { t = minOf(t, e.t); b = maxOf(b, e.b); r = maxOf(r, e.r) }
            glued = glued ?: g
            sb.append(s); prevR = e.r
        }
        flush()
    }
    return best
}


// ===== persiapan gambar =====

fun cropSafe(bm: Bitmap, r: Roi): Bitmap {
    val sx = r.x.coerceIn(0, bm.width - 2); val sy = r.y.coerceIn(0, bm.height - 2)
    val sw = r.w.coerceIn(2, bm.width - sx); val sh = r.h.coerceIn(2, bm.height - sy)
    return Bitmap.createBitmap(bm, sx, sy, sw, sh)
}

/**
 * Skala ke tinggi [targetH], jadikan abu-abu, regangkan kontras (persentil 2-98 agar tahan noda/silau),
 * lalu balik polaritas bila [invert] true (angka terang di latar gelap -> gelap di latar terang).
 * [invert] null = otomatis dari rata-rata kecerahan.
 */
fun prepForOcr(src: Bitmap, targetH: Int, invert: Boolean?): Bitmap {
    val oh = targetH.coerceAtLeast(16)
    val ow = (oh * src.width.toFloat() / src.height).toInt().coerceIn(16, 2400)
    val bm = Bitmap.createScaledBitmap(src, ow, oh, true)
    val px = IntArray(ow * oh); bm.getPixels(px, 0, ow, 0, 0, ow, oh)
    val gr = IntArray(px.size); val hist = IntArray(256); var sum = 0L
    for (i in px.indices) {
        val p = px[i]
        val y = (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
        gr[i] = y; hist[y]++; sum += y
    }
    val total = px.size
    var acc = 0; var lo = 0; var hi = 255
    for (v in 0..255) { acc += hist[v]; if (acc >= total * 0.02) { lo = v; break } }
    acc = 0
    for (v in 255 downTo 0) { acc += hist[v]; if (acc >= total * 0.02) { hi = v; break } }
    val inv = invert ?: (sum.toDouble() / total < 110)
    val rg = maxOf(1, hi - lo)
    for (i in px.indices) {
        var q = ((gr[i] - lo) * 255 / rg).coerceIn(0, 255)
        if (inv) q = 255 - q
        px[i] = (255 shl 24) or (q shl 16) or (q shl 8) or q
    }
    return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
}

/**
 * Ubah gambar abu-abu hasil [prepForOcr] (angka gelap di latar terang) menjadi hitam-putih dengan ambang lokal
 * (Bradley): tiap piksel dibandingkan dengan rata-rata lingkungannya, jadi tetap bersih walau pencahayaan tidak rata
 * (bayangan, silau sebagian, layar LCD berpendar). Ambang global akan gagal pada kasus seperti itu.
 */
fun adaptiveBinarize(gray: Bitmap, t: Double = 0.12): Bitmap {
    val w = gray.width; val h = gray.height
    val px = IntArray(w * h); gray.getPixels(px, 0, w, 0, 0, w, h)
    val stride = w + 1
    val integral = IntArray(stride * (h + 1))
    for (y in 0 until h) {
        var row = 0
        for (x in 0 until w) {
            row += px[y * w + x] and 255
            integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
        }
    }
    val s = maxOf(12, h / 2)
    val out = IntArray(w * h)
    for (y in 0 until h) {
        val y1 = maxOf(y - s, 0); val y2 = minOf(y + s, h - 1)
        for (x in 0 until w) {
            val x1 = maxOf(x - s, 0); val x2 = minOf(x + s, w - 1)
            val count = (x2 - x1 + 1) * (y2 - y1 + 1)
            val sum = integral[(y2 + 1) * stride + x2 + 1] - integral[y1 * stride + x2 + 1] -
                integral[(y2 + 1) * stride + x1] + integral[y1 * stride + x1]
            val v = px[y * w + x] and 255
            val c = if (v.toLong() * count < sum * (1.0 - t)) 0 else 255
            out[y * w + x] = (255 shl 24) or (c shl 16) or (c shl 8) or c
        }
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}

/** Statistik kualitas (ketajaman, kecerahan, kontras, silau) dari potongan area angka. */
fun statsOf(crop: Bitmap): FrameStats {
    val w = 320; val h = (w * crop.height.toFloat() / crop.width).toInt().coerceIn(8, 240)
    val s = Bitmap.createScaledBitmap(crop, w, h, true)
    val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
    val g = IntArray(px.size) { i ->
        val p = px[i]; (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
    }
    return frameStats(g, w, h)
}

/** Nilai kualitas dari potongan area angka; null bila baik. */
fun qualityOf(crop: Bitmap): Issue? = judgeQuality(statsOf(crop))

/** Hasil baca foto penuh: [ranked] kandidat terurut, [passes] bacaan tiap lintasan (nama -> kandidat), [area] kotak angka yang dipakai (null bila tak ditemukan). */
class FinalRead(val ranked: List<String>, val passes: List<Pair<String, List<String>>>, val area: Roi?, val tenth: Int? = null)

/**
 * OCR final pada foto beresolusi tinggi. Area [roi] (sudah diperlebar) dibaca dalam tiga lintasan
 * (polaritas otomatis, polaritas terbalik, tanpa pengolahan); kandidat diurutkan menurut jumlah lintasan
 * yang sepakat, lalu jumlah digit.
 *
 * Tanpa [roi] (foto dari galeri) seluruh foto dibaca dahulu dan angka meter dipilih dengan [pickMeter] (label HM/KM,
 * ukuran, posisi; [want] = jenis meter unit), bukan sekadar angka terpanjang, karena foto sering memuat stempel waktu,
 * koordinat, dan angka lain. Area angka terpilih lalu dibaca ulang dengan tiga lintasan dan hasil pencarian ikut
 * dihitung sebagai satu suara. Bila tak ada yang terpilih, dipakai daftar kandidat seluruh foto seperti sebelumnya.
 *
 * Versi terperinci: sama seperti di atas tetapi mengembalikan juga bacaan tiap lintasan (dipakai sistem latih untuk tahu lintasan mana yang benar).
 * [plan] = lintasan ML Kit yang dijalankan (lihat [passPlan]); [model] = model sendiri (opsional) yang ikut memberi suara.
 * [drum] = meter drum mekanik: drum 1/10 jam (merah) dipotong dulu, hanya drum yang dihitung yang dibaca (lihat [splitDrumCrop]).
 */
suspend fun readFinalDetailed(
    rc: TextRecognizer, frame: Bitmap, roi: Roi?, want: MeterKind? = null,
    plan: Set<String> = ALL_PASSES.toSet(), model: DigitModel? = null, profile: MeterProfile? = null,
    drum: Boolean = false,
): FinalRead {
    var area = roi
    var seed: String? = null
    var whole: Text? = null
    var tenth: Int? = null
    if (area == null) {
        val s = if (maxOf(frame.width, frame.height) > 1600) {
            val k = 1600f / maxOf(frame.width, frame.height)
            Bitmap.createScaledBitmap(frame, (frame.width * k).toInt(), (frame.height * k).toInt(), true)
        } else frame
        whole = rc.read(s)
        val hit = pickMeter(tokensOf(whole), s.width, s.height, want = want, profile = profile)
        if (hit != null) {
            area = trackToRoi(trackOf(hit.l, hit.t, hit.r, hit.b, s.width, s.height), frame.width, frame.height, 0.3f, 0.8f)
            seed = hit.text
        }
    }
    val passes = ArrayList<Pair<String, List<String>>>()
    if (area != null) {
        val crop0 = cropSafe(frame, area)
        // meter drum mekanik: hanya drum gelap di kiri yang dihitung; drum 1/10 (merah) dipotong sebelum OCR dan
        // tebakan awal dari seluruh foto (yang mungkin memuat digit merah) tidak dipakai sebagai suara
        val dcut = if (drum) splitDrumCrop(crop0) else null
        val crop = dcut?.first ?: crop0
        if (dcut != null) seed = null
        // drum mekanik: drum yang dihitung tidak punya titik, jadi titik/koma yang terbaca OCR dibuang ("0099.1" bukan 99,1)
        fun fin(l: List<String>): List<String> = if (drum) l.map { drumDigitsOnly(it) }.filter { it.length >= 3 }.distinct() else l
        // drum 1/10 jam (zona kuning) dibaca terpisah sebagai satu digit; tidak ikut jadi suara HM
        val dtc = if (drum && dcut != null) drumTenthCrop(crop0) else null
        if (dtc != null) {
            val tr = ArrayList<String>()
            for (inv in listOf<Boolean?>(null, true, false)) tr += digitsOf(rc.read(prepForOcr(dtc, 192, inv)))
            tr += digitsOf(rc.read(dtc))
            tenth = pickTenth(tr)
        }
        val a = prepForOcr(crop, 192, null)
        if ("auto" in plan) passes += "auto" to fin(candidatesOf(rc.read(a)))
        if ("inv" in plan) {
            val inv = prepForOcr(crop, 192, true)
            passes += "inv" to fin(candidatesOf(rc.read(if (a.sameAs(inv)) prepForOcr(crop, 192, false) else inv)))
        }
        if ("raw" in plan) passes += "raw" to fin(candidatesOf(rc.read(crop)))
        // lintasan tambahan: skala lebih besar (digit tipis/kecil) dan hitam-putih ambang lokal (pencahayaan tidak rata)
        if ("x288" in plan) passes += "x288" to fin(candidatesOf(rc.read(prepForOcr(crop, 288, null))))
        if ("bin" in plan) passes += "bin" to fin(candidatesOf(rc.read(adaptiveBinarize(a))))
        // model sendiri: satu suara, dua suara bila yakin
        model?.read(crop)?.let { m ->
            normalizeCandidate(m.text)?.let { t ->
                passes += "model" to listOf(t)
                if (m.conf >= 0.8f) passes += "model" to listOf(t)
            }
        }
        seed?.let { passes += "seed" to listOf(it) }
    } else {
        passes += "whole" to candidatesOf(whole)
    }
    val votes = HashMap<String, Int>()
    passes.forEach { (_, p) -> p.forEach { votes[it] = (votes[it] ?: 0) + 1 } }
    val ranked = votes.keys.sortedWith(
        compareByDescending<String> { votes[it] ?: 0 }.thenByDescending { c -> c.count { it.isDigit() } }
    )
    // Tiap lintasan bisa salah di digit yang berbeda: ambil mayoritas per posisi digit dari kandidat teratas tiap lintasan.
    val cons = consensusOf(passes.mapNotNull { it.second.firstOrNull() }, 2)
    val out = if (cons != null) listOf(cons) + ranked.filter { it != cons } else ranked
    return FinalRead(out, passes, area, tenth)
}

suspend fun readFinal(rc: TextRecognizer, frame: Bitmap, roi: Roi?, want: MeterKind? = null, profile: MeterProfile? = null): List<String> =
    readFinalDetailed(rc, frame, roi, want, profile = profile).ranked

// ===== pemungutan suara antar frame =====

/** Angka dikunci bila muncul di [need] dari [win] frame terakhir; dilepas bila hampir hilang dari jendela. */
class Voter(private val win: Int = 6, private val need: Int = 4) {
    private val q = ArrayDeque<String>()
    var lock by mutableStateOf("")
        private set
    var last by mutableStateOf("")
        private set
    var votes by mutableIntStateOf(0)
        private set

    fun feed(v: String) {
        q.addLast(v)
        while (q.size > win) q.removeFirst()
        val best = q.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }
        votes = best?.value ?: 0
        // bila tak ada angka utuh yang cukup sering muncul, coba mayoritas per digit (frame berbeda salah di digit berbeda)
        val cons = if (best == null || best.value < need) consensusOf(q.filter { it.isNotEmpty() }, need) else null
        if (best != null && best.value >= need) lock = best.key
        else if (cons != null) { lock = cons; votes = need }
        else if (lock.isNotEmpty() && q.count { it == lock } < 2) lock = ""
        last = q.lastOrNull { it.isNotEmpty() } ?: ""
    }

    fun reset() { q.clear(); lock = ""; last = ""; votes = 0 }
}
