package com.frostbyte.hmtracker

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Logika murni (tanpa Android) untuk memvalidasi angka HM hasil OCR, menilai kualitas foto,
 * dan memetakan kotak detektor ke koordinat foto. Dipisah supaya bisa diuji dengan unit test.
 */

// ===== aturan kewajaran angka =====

enum class Sev { INFO, WARN, BLOCK }
data class Issue(val sev: Sev, val msg: String)

const val MAX_METER = 9_999_999.0

/** Kenaikan HM maksimum yang masuk akal dalam [elapsedMs]. Jam = 1,1x waktu nyata (+0,5 toleransi bulat); km = 150 km/jam (+5). */
fun maxRise(u: UnitData, elapsedMs: Long): Double {
    val hours = max(0.0, elapsedMs / 3_600_000.0)
    return if (u.sat == "km") hours * 150.0 + 5.0 else hours * 1.1 + 0.5
}

fun fmtElapsed(ms: Long): String {
    val min = ms / 60_000.0
    return when {
        min < 90 -> "${max(1, min.roundToInt())} menit"
        min < 48 * 60 -> String.format(ID, "%.1f jam", min / 60)
        else -> "${(min / 1440).roundToInt()} hari"
    }
}

/**
 * Periksa satu angka terhadap riwayat unit.
 * BLOCK = tidak boleh disimpan, WARN = boleh disimpan setelah dikonfirmasi, INFO = hanya informasi.
 */
fun checkReading(u: UnitData, x: Double, now: Long, init: Boolean, skewMs: Long): List<Issue> {
    val out = ArrayList<Issue>()
    if (x.isNaN() || x < 0 || x > MAX_METER) {
        out += Issue(Sev.BLOCK, "Angka tidak valid."); return out
    }
    val last = u.readings.lastOrNull()
    if (last != null) {
        val un = u.un()
        if (x < last.hm) {
            out += if (init) Issue(Sev.WARN, "Lebih kecil dari pembacaan terakhir (${f1(last.hm)} $un). Pastikan unit dan meter yang difoto benar.")
            else Issue(Sev.BLOCK, "Angka lebih kecil dari pembacaan terakhir (${f1(last.hm)} $un). HM tidak boleh turun.")
        } else {
            val rise = x - last.hm
            val elapsed = now - last.t
            if (elapsed >= 0 && rise > maxRise(u, elapsed)) {
                out += Issue(Sev.WARN, "Naik +${f1(rise)} $un, padahal baru ${fmtElapsed(elapsed)} sejak pembacaan terakhir. Kemungkinan salah baca.")
            } else if (rise == 0.0) {
                out += Issue(Sev.INFO, "Sama dengan pembacaan terakhir, tidak ada pemakaian tercatat.")
            } else if (elapsed >= 0) {
                rateAnomaly(u, rise, elapsed)?.let { out += it }
            }
        }
        if (now < last.t - 60_000) out += Issue(Sev.WARN, "Jam HP lebih awal dari pembacaan terakhir. Periksa tanggal dan jam HP.")
    }
    if (skewMs > 120_000) out += Issue(Sev.WARN, "Jam HP berbeda ${skewMs / 60_000} menit dari waktu GPS. Waktu foto memakai waktu GPS.")
    return out
}

/**
 * Kenaikan yang masih di bawah batas fisik ([maxRise]) tetapi jauh di atas pemakaian biasa unit ini (laju 4 pembacaan
 * terakhir x waktu berlalu). Menangkap salah baca digit depan atau tengah yang lolos dari batas 1,1x waktu nyata.
 * Hanya berlaku bila sudah lewat 1 hari dan laju unit diketahui; null bila tidak mencurigakan. Ambang (3x + kelonggaran
 * 12 jam / 300 km) adalah titik awal dan perlu dicocokkan dengan pola pemakaian unit di lapangan.
 */
fun rateAnomaly(u: UnitData, rise: Double, elapsedMs: Long): Issue? {
    val rate = ratePerDay(u, tail = 4) ?: return null
    val days = elapsedMs / 86_400_000.0
    if (days < 1.0) return null
    val expected = rate * days
    val slack = if (u.sat == "km") 300.0 else 12.0
    if (rise <= expected * 3.0 + slack) return null
    val un = u.un()
    return Issue(Sev.WARN, "Naik +${f1(rise)} $un dalam ${fmtElapsed(elapsedMs)}, jauh di atas pemakaian biasa unit ini (sekitar ${f1(rate)} $un/hari, perkiraan +${f1(expected)}). Periksa kemungkinan salah baca.")
}

/** Ringkasan selisih [x] terhadap pembacaan terakhir untuk ditampilkan saat review; null bila belum ada riwayat. */
fun describeRise(u: UnitData, x: Double, now: Long): String? {
    val last = u.readings.lastOrNull() ?: return null
    val un = u.un(); val d = x - last.hm
    val elapsed = now - last.t
    val head = (if (d >= 0) "+" else "−") + f1(abs(d)) + " $un sejak ${dt(last.t)}"
    return if (d >= 0 && elapsed > 0) head + " · batas wajar +" + f1(maxRise(u, elapsed)) + " $un" else head
}

data class Resolved(
    val value: Double, val text: String, val dotAdded: Boolean, val issues: List<Issue>,
    /** true bila satu digit dikoreksi otomatis agar cocok dengan riwayat unit; [repairNote] menjelaskan koreksinya. */
    val repaired: Boolean = false, val repairNote: String? = null,
) {
    val blocked get() = issues.any { it.sev == Sev.BLOCK }
    val warned get() = issues.any { it.sev >= Sev.WARN }
}

/**
 * Ubah teks mentah OCR menjadi angka. Meter sering punya digit desimal terakhir yang tidak terbaca titiknya
 * ("12345" padahal 1234.5): bila tafsiran apa adanya tidak masuk akal tetapi tafsiran bertitik masuk akal,
 * titik dipasang otomatis dan ditandai [Resolved.dotAdded].
 */
fun resolveReading(u: UnitData, raw: String, now: Long, init: Boolean, skewMs: Long = 0L, conf: Map<String, Int>? = null, decimalHint: Boolean = false): Resolved? {
    val s = raw.trim().replace(',', '.')
    val x0 = s.toDoubleOrNull() ?: return null
    val km = u.sat == "km"       // odometer bulat: digit berlebih bukan desimal, jadi tidak ditafsirkan sebagai titik
    // Pembacaan pertama belum punya riwayat untuk menentukan titik. Panel yang lazim berdesimal (drum 1/10 jam, LCD HM)
    // dan titiknya tidak terbaca: digit terakhir dianggap desimal, dengan catatan agar dicocokkan dengan meter asli.
    if (decimalHint && u.readings.isEmpty() && !km && !s.contains('.') && s.length in 4..8) {
        val v = x0 / 10.0
        val txt = String.format(java.util.Locale.US, "%.1f", v)
        return Resolved(v, txt, true, checkReading(u, v, now, init, skewMs) + Issue(Sev.INFO, "Digit terakhir dianggap desimal 1/10 ($txt). Cocokkan dengan meter asli."))
    }
    val cands = ArrayList<Pair<Double, Boolean>>()
    cands += x0 to false
    if (!s.contains('.') && s.length >= 3 && u.readings.isNotEmpty() && !km) cands += (x0 / 10.0) to true
    val scored = cands.map { (x, dot) -> Triple(x, dot, checkReading(u, x, now, init, skewMs)) }
    val clean = scored.filter { t -> t.third.none { it.sev >= Sev.WARN } }
    val pick = clean.firstOrNull() ?: scored.first()
    val text = if (pick.second) String.format(java.util.Locale.US, "%.1f", pick.first) else s
    // Bacaan tidak masuk akal (bukan sekadar jam HP yang meleset): coba perbaiki satu digit yang sering tertukar oleh OCR.
    val rawBad = pick.third.any { it.sev >= Sev.WARN } && checkReading(u, pick.first, now, init, 0L).any { it.sev >= Sev.WARN }
    if (rawBad) {
        val fix = repairByHistory(u, s, now, init) ?: conf?.let { learnedRepair(u, s, now, init, it) }
        if (fix != null) {
            val x = fix.text.toDouble()
            val n = s.substring(0, fix.pos).count { it.isDigit() } + 1
            val note = "Digit ke-$n kemungkinan salah baca (${fix.from} → ${fix.to}) dan dikoreksi agar cocok dengan riwayat unit. Cocokkan dengan meter asli."
            return Resolved(x, fix.text, false, checkReading(u, x, now, init, skewMs) + Issue(Sev.WARN, note), repaired = true, repairNote = note)
        }
    }
    return Resolved(pick.first, text, pick.second, pick.third)
}

// ===== koreksi digit yang sering tertukar =====

// Pasangan digit yang paling sering tertukar pada OCR (bentuk mirip, apalagi pada font meter 7-segmen atau dot-matrix).
private val CONFUSABLE: Map<Char, String> = run {
    val m = HashMap<Char, StringBuilder>()
    for (p in listOf("08", "06", "09", "38", "56", "35", "68", "89", "17", "49", "27")) {
        m.getOrPut(p[0]) { StringBuilder() }.append(p[1])
        m.getOrPut(p[1]) { StringBuilder() }.append(p[0])
    }
    m.mapValues { it.value.toString() }
}

class Repair(val text: String, val pos: Int, val from: Char, val to: Char)

/**
 * Cari satu digit pada [s] yang bila ditukar dengan digit mirip membuat angka masuk akal terhadap riwayat unit
 * (tidak turun, kenaikan wajar). Hanya dipakai bila bacaan asli tidak masuk akal, dan hanya bila TEPAT SATU tafsiran
 * yang cocok: kalau ada dua atau lebih, hasilnya ambigu dan tidak ditebak (null). Jam HP diabaikan di sini.
 */
fun repairByHistory(u: UnitData, s: String, now: Long, init: Boolean): Repair? {
    if (u.readings.isEmpty() || s.length !in 3..9) return null
    var found: Repair? = null
    for (i in s.indices) {
        val ch = s[i]
        val alts = CONFUSABLE[ch] ?: continue
        for (a in alts) {
            val v = s.substring(0, i) + a + s.substring(i + 1)
            val x = v.toDoubleOrNull() ?: continue
            if (checkReading(u, x, now, init, 0L).any { it.sev >= Sev.WARN }) continue
            if (found != null) return null
            found = Repair(v, i, ch, a)
        }
    }
    return found
}

/**
 * Konsensus per posisi digit dari beberapa bacaan [cands] (mis. dari beberapa lintasan OCR atau beberapa frame).
 * Bacaan dikelompokkan menurut panjang dan posisi titik; dalam satu kelompok (minimal [minAgree] bacaan) tiap posisi
 * harus punya digit mayoritas mutlak yang didukung minimal [minAgree] bacaan. Berguna bila tiap bacaan salah di digit
 * berbeda ("12845", "12345", "12355" -> "12345"). Null bila tidak ada kelompok yang sepakat.
 */
fun consensusOf(cands: List<String>, minAgree: Int = 2): String? {
    var best: String? = null; var bestSize = 0
    for (g in cands.filter { it.isNotEmpty() }.groupBy { it.length to it.indexOf('.') }.values) {
        if (g.size < minAgree || g.size <= bestSize) continue
        val sb = StringBuilder(); var ok = true
        for (i in g[0].indices) {
            val top = g.groupingBy { it[i] }.eachCount().maxByOrNull { it.value }!!
            if (top.value < minAgree || top.value * 2 <= g.size) { ok = false; break }
            sb.append(top.key)
        }
        if (ok) { best = sb.toString(); bestSize = g.size }
    }
    return best
}

/** Pilih kandidat terbaik: yang lolos pemeriksaan lebih dulu, lalu urutan asal (sudah diurutkan oleh pemanggil). */
fun chooseFinal(u: UnitData, cands: List<String>, now: Long, init: Boolean, skewMs: Long = 0L, conf: Map<String, Int>? = null, decimalHint: Boolean = false): Resolved? {
    val rs = cands.mapNotNull { resolveReading(u, it, now, init, skewMs, conf, decimalHint) }
    return rs.firstOrNull { !it.blocked && !it.warned } ?: rs.firstOrNull { !it.blocked } ?: rs.firstOrNull()
}

/** Satu tafsiran alternatif bacaan: [ok] = lolos pemeriksaan riwayat; [note] = apa yang berbeda dari bacaan OCR. */
data class Alt(val text: String, val ok: Boolean, val note: String)

/**
 * Tafsiran lain dari bacaan OCR [raws] untuk dipilih operator saat review: bacaan apa adanya, titik desimal digeser
 * satu digit (tidak untuk unit km), dan penukaran satu digit mirip (0/8, 5/6, 1/7, ...) yang membuat angka lolos
 * pemeriksaan riwayat. Yang lolos pemeriksaan diurutkan lebih dulu; angka yang sama tidak diulang.
 */
fun alternativesOf(u: UnitData, raws: List<String>, now: Long, init: Boolean, skewMs: Long = 0L, max: Int = 4): List<Alt> {
    val seen = LinkedHashMap<String, Alt>()
    val km = u.sat == "km"
    fun ok(x: Double) = checkReading(u, x, now, init, skewMs).none { it.sev >= Sev.WARN }
    fun add(text: String, note: String, onlyOk: Boolean = false) {
        val x = text.toDoubleOrNull() ?: return
        val key = String.format(java.util.Locale.US, "%.2f", x)
        if (seen.containsKey(key)) return
        val good = ok(x)
        if (onlyOk && !good) return
        seen[key] = Alt(text, good, note)
    }
    for (raw in raws) {
        val s = raw.trim().replace(',', '.')
        val x = s.toDoubleOrNull() ?: continue
        add(s, "bacaan OCR")
        if (!km) {
            if (!s.contains('.') && s.length >= 3) add(String.format(java.util.Locale.US, "%.1f", x / 10.0), "titik desimal digeser satu digit")
            else if (s.contains('.')) add(s.replace(".", ""), "tanpa titik desimal")
        }
        if (u.readings.isNotEmpty() && !s.contains('.') && !ok(x)) {
            for (i in s.indices) {
                val alts = CONFUSABLE[s[i]] ?: continue
                for (a in alts) add(s.substring(0, i) + a + s.substring(i + 1), "digit ke-${i + 1} ${s[i]} → $a", onlyOk = true)
            }
        }
    }
    return seen.values.sortedByDescending { it.ok }.take(max)
}

/** Normalisasi satu token OCR: "12345", "1234.5" atau null bila bukan angka meter (2-8 digit). */
fun normalizeCandidate(c: String): String? {
    val t = c.trim()
    if (Regex("^\\d{1,7}[.,]\\d$").matches(t)) return t.replace(',', '.')
    val d = t.filter { it.isDigit() }
    return if (d.length in 2..8) d else null
}

// ===== kualitas foto =====

data class FrameStats(val sharp: Double, val mean: Double, val std: Double, val clipHi: Double)

/** Statistik dari gambar abu-abu [g] (w x h): ketajaman (varians Laplacian ternormalisasi), rata-rata, kontras, porsi piksel silau. */
fun frameStats(g: IntArray, w: Int, h: Int): FrameStats {
    val n = g.size
    if (n == 0 || w < 3 || h < 3) return FrameStats(0.0, 0.0, 0.0, 0.0)
    var sum = 0.0; var sum2 = 0.0; var hi = 0
    for (v in g) { sum += v; sum2 += v.toDouble() * v; if (v >= 250) hi++ }
    val mean = sum / n; val variance = max(0.0, sum2 / n - mean * mean)
    var ls = 0.0; var ls2 = 0.0; var c = 0
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        val l = (-4 * g[i] + g[i - 1] + g[i + 1] + g[i - w] + g[i + w]).toDouble()
        ls += l; ls2 += l * l; c++
    }
    val lm = ls / c; val lv = max(0.0, ls2 / c - lm * lm)
    return FrameStats(lv / (variance + 1.0), mean, Math.sqrt(variance), hi.toDouble() / n)
}

// Ambang awal hasil kalibrasi gambar sintetis. Sesuaikan dengan foto meter asli di lapangan.
const val SHARP_BLOCK = 0.015
const val SHARP_WARN = 0.08
const val CONTRAST_WARN = 12.0
const val GLARE_WARN = 0.35

fun judgeQuality(s: FrameStats): Issue? = when {
    s.mean < 8 -> Issue(Sev.BLOCK, "Gambar gelap total, lensa mungkin tertutup")
    s.mean > 247 -> Issue(Sev.BLOCK, "Gambar terlalu terang (silau)")
    s.sharp < SHARP_BLOCK -> Issue(Sev.BLOCK, "Foto sangat buram")
    s.sharp < SHARP_WARN -> Issue(Sev.WARN, "Foto kurang tajam, tahan HP lebih stabil atau ketuk layar untuk fokus")
    s.clipHi > GLARE_WARN -> Issue(Sev.WARN, "Ada silau di area angka, ubah sudut atau matikan senter")
    s.std < CONTRAST_WARN -> Issue(Sev.WARN, "Kontras rendah, dekatkan HP atau nyalakan senter")
    else -> null
}

// ===== geometri kotak detektor =====

data class Roi(val x: Int, val y: Int, val w: Int, val h: Int)

/** Kotak [ ] di layar (px view): x, y, lebar, tinggi. Sama dengan yang digambar di UI. */
fun boxInView(vw: Int, vh: Int, dens: Float): FloatArray {
    val w = min(vw * 0.86f, 480f * dens)
    val hh = (w * 0.2f).roundToInt().toFloat()
    return floatArrayOf((vw - w) / 2f, vh * 0.42f - hh / 2f, w, hh)
}

/**
 * Petakan kotak view ke koordinat foto (fw x fh) untuk PreviewView FILL_CENTER.
 * Benar bila preview, analisis, dan foto memakai rasio yang sama (semuanya dipaksa 4:3).
 * [pad] memperlebar kotak (pecahan lebar/tinggi) agar toleran terhadap getaran tangan.
 */
fun boxToFrame(vw: Int, vh: Int, dens: Float, fw: Int, fh: Int, pad: Float = 0f): Roi {
    val b = boxInView(vw, vh, dens)
    val sc = max(vw.toFloat() / fw, vh.toFloat() / fh)
    val ox = (fw * sc - vw) / 2f; val oy = (fh * sc - vh) / 2f
    val px = b[2] * pad; val py = b[3] * pad
    val sx = ((b[0] - px + ox) / sc).toInt().coerceIn(0, fw - 2)
    val sy = ((b[1] - py + oy) / sc).toInt().coerceIn(0, fh - 2)
    val sw = ((b[2] + 2 * px) / sc).toInt().coerceIn(2, fw - sx)
    val sh = ((b[3] + 2 * py) / sc).toInt().coerceIn(2, fh - sy)
    return Roi(sx, sy, sw, sh)
}

// ===== pelacakan angka otomatis =====

/** Kotak angka hasil pencarian otomatis, dinormalkan (0..1) terhadap frame berukuran [fw] x [fh]; [tag] = label di dekatnya (HM/KM/lainnya) bila ada. */
data class NumTrack(val l: Float, val t: Float, val r: Float, val b: Float, val fw: Int, val fh: Int, val tag: LabelTag? = null) {
    val cx: Float get() = (l + r) / 2f
    val cy: Float get() = (t + b) / 2f
}

fun trackOf(l: Int, t: Int, r: Int, b: Int, fw: Int, fh: Int, tag: LabelTag? = null): NumTrack = NumTrack(
    (l.toFloat() / fw).coerceIn(0f, 1f), (t.toFloat() / fh).coerceIn(0f, 1f),
    (r.toFloat() / fw).coerceIn(0f, 1f), (b.toFloat() / fh).coerceIn(0f, 1f), fw, fh, tag
)

/**
 * Area baca pada bitmap [fw] x [fh] (frame analisis atau foto; rasio sama sehingga koordinat normal berlaku).
 * [padX]/[padY] memperlebar kotak sebagai pecahan lebar/tinggi kotak, agar toleran terhadap getaran tangan.
 */
fun trackToRoi(tr: NumTrack, fw: Int, fh: Int, padX: Float, padY: Float): Roi {
    val w = (tr.r - tr.l) * fw; val h = (tr.b - tr.t) * fh
    val sx = (tr.l * fw - w * padX).toInt().coerceIn(0, fw - 2)
    val sy = (tr.t * fh - h * padY).toInt().coerceIn(0, fh - 2)
    val sw = (w * (1f + 2f * padX)).toInt().coerceIn(2, fw - sx)
    val sh = (h * (1f + 2f * padY)).toInt().coerceIn(2, fh - sy)
    return Roi(sx, sy, sw, sh)
}

/**
 * Kotak di layar (px view: x, y, lebar, tinggi) untuk PreviewView FILL_CENTER. Diberi ruang tepi dan ukuran minimum
 * supaya kurung sudut tergambar utuh, dan dijaga tetap di dalam view.
 */
fun trackInView(tr: NumTrack, vw: Int, vh: Int, dens: Float): FloatArray {
    val sc = max(vw.toFloat() / tr.fw, vh.toFloat() / tr.fh)
    val ox = (tr.fw * sc - vw) / 2f; val oy = (tr.fh * sc - vh) / 2f
    val cx = tr.cx * tr.fw * sc - ox; val cy = tr.cy * tr.fh * sc - oy
    val w = min(((tr.r - tr.l) * tr.fw * sc * 1.24f).coerceAtLeast(130f * dens), vw * 0.96f)
    val h = min(((tr.b - tr.t) * tr.fh * sc * 1.7f).coerceAtLeast(60f * dens), vh * 0.5f)
    return floatArrayOf((cx - w / 2f).coerceIn(0f, vw - w), (cy - h / 2f).coerceIn(0f, vh - h), w, h)
}

/** Titik di view (px) -> koordinat normal (0..1) pada frame [fw] x [fh] untuk PreviewView FILL_CENTER. */
fun viewToNorm(px: Float, py: Float, vw: Int, vh: Int, fw: Int, fh: Int): Pair<Float, Float> {
    val sc = max(vw.toFloat() / fw, vh.toFloat() / fh)
    val ox = (fw * sc - vw) / 2f; val oy = (fh * sc - vh) / 2f
    return ((px + ox) / (fw * sc)).coerceIn(0f, 1f) to ((py + oy) / (fh * sc)).coerceIn(0f, 1f)
}

/** Dua nilai dianggap sama bila selisihnya kurang dari 0,05. */
fun sameValue(a: Double?, b: Double?): Boolean = a != null && b != null && abs(a - b) < 0.05

fun intDigits(x: Double): Int = floor(abs(x)).toLong().toString().length
