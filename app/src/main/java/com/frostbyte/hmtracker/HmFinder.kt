package com.frostbyte.hmtracker

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Logika murni (tanpa Android) untuk kamera pembaca meter:
 *  1. mengenali angka HM di antara semua teks pada frame lewat label di dekatnya (HM, HOUR, SMR, KM, ODO, RPM, ...),
 *  2. menjaga kotak pelacak tetap stabil (tidak loncat antar angka),
 *  3. memutuskan kapan fokus perlu dipicu ulang,
 *  4. menentukan zoom otomatis bila angka terlalu kecil.
 * Dipisah dari Camera.kt supaya semuanya bisa diuji di JVM.
 */

// ===== label di dekat angka =====

/** Jenis meter yang dicari: jam kerja (HM) atau odometer (KM). */
enum class MeterKind { HM, KM }

/** Arti sebuah label: HM = jam kerja, KM = jarak tempuh, NOISE = angka lain (RPM, suhu, nomor seri, ...). */
enum class Lbl { HM, KM, NOISE }

/** [strong] false untuk label ambigu yang hanya satu huruf (mis. "H"). */
data class LabelTag(val kind: Lbl, val strong: Boolean = true)

// Istilah umum di panel alat berat Indonesia: HM, SMR (Service Meter Reading), HOUR METER, JAM.
private val HM_STRONG = setOf(
    "HM", "HMR", "SMR", "HOUR", "HOURS", "HOURMETER", "HOURMTR", "HRS", "JAM",
    "ENGHRS", "ENGINEHOURS", "SERVICEMETER", "TOTALHOURS"
)
private val HM_WEAK = setOf("H", "HR")
private val KM_WORDS = setOf("KM", "ODO", "ODOMETER", "MI", "MILES", "MILEAGE", "TOTALKM")
// "km/h" sengaja tidak termasuk: odometer hampir selalu tepat di bawah tulisan "km/h" pada speedometer.
private val NOISE_WORDS = setOf(
    "RPM", "TEMP", "TEMPERATURE", "FUEL", "BAR", "PSI", "KPA", "MPA", "VOLT", "VDC", "VOLTAGE",
    "SN", "SERIAL", "SERIALNO", "MODEL", "TYPE", "DATE", "TIME", "KW", "HP", "KG", "TRIP",
    "SPEED", "PRESS", "PRESSURE", "OIL", "COOLANT", "LOAD", "LITER", "LTR", "AMP", "HZ"
)

/** Label dari token yang hanya berisi huruf ("HM", "Hours", "ODO"); null bila bukan label yang dikenal. */
fun labelOf(raw: String): LabelTag? {
    if (raw.contains('%')) return LabelTag(Lbl.NOISE)
    val w = raw.uppercase().filter { it.isLetter() }
    if (w.isEmpty() || w.length > 14) return null
    return when (w) {
        in HM_STRONG -> LabelTag(Lbl.HM)
        in HM_WEAK -> LabelTag(Lbl.HM, false)
        in KM_WORDS -> LabelTag(Lbl.KM)
        in NOISE_WORDS -> LabelTag(Lbl.NOISE)
        else -> null
    }
}

private val LEAD = Regex("^([A-Za-z]{1,12})[:.\\-=]*(?=\\d)")
private val TRAIL = Regex("(?<=\\d)[\\s.:-]*([A-Za-z]{1,12})$")

/**
 * Pisahkan label yang menempel pada angka dalam satu token, mis. "SN12345" -> ("12345", SN) atau "1234.5H" ->
 * ("1234.5", H). Hanya label yang dikenal yang dipisah; selain itu token dikembalikan apa adanya (mis. "1O3OO").
 */
fun splitGlued(raw: String): Pair<String, LabelTag?> {
    val t = raw.trim()
    val m = LEAD.find(t) ?: TRAIL.find(t) ?: return t to null
    val tag = labelOf(m.groupValues[1]) ?: return t to null
    return t.removeRange(m.range) to tag
}

/** Label yang menempel pada angka dalam satu token; null bila tidak ada. */
fun gluedLabelOf(raw: String): LabelTag? = splitGlued(raw).second

private val DATE = Regex("\\d\\s*[:/]\\s*\\d|\\d{1,2}-\\d{1,2}-\\d{2,4}|\\d{1,2}\\.\\d{2}\\.\\d{2,4}")
private val MULT = Regex("^[xX×*]\\s*\\d{2,4}$")

/** Jam ("12:30", "22.22.44") atau tanggal ("12/03/2026"), juga pecahan "1/10" pada meter: bukan angka meter. */
fun isDateLike(raw: String): Boolean = DATE.containsMatchIn(raw)

/** Pengali skala ("x100", "x1000") yang sering tercetak di dekat angka RPM. */
fun isMultiplier(raw: String): Boolean = MULT.matches(raw.trim())

/**
 * Cocokkan label dengan jenis meter yang dicari: true = cocok, false = bertentangan (KM/HM tertukar atau
 * label lain seperti RPM), null = tidak ada label.
 */
fun LabelTag?.matches(k: MeterKind): Boolean? = when {
    this == null -> null
    kind == Lbl.NOISE -> false
    kind == Lbl.HM -> k == MeterKind.HM
    else -> k == MeterKind.KM
}

/** Pengali skor sebuah angka menurut label di dekatnya. */
internal fun labelFactor(tag: LabelTag?, want: MeterKind?): Double {
    if (tag == null) return 1.0
    if (tag.kind == Lbl.NOISE) return 0.2
    if (want == null) return 1.3
    return if (tag.matches(want) == true) (if (tag.strong) 2.5 else 1.8) else 0.3
}

/** Meter HM/odometer umumnya 5-7 digit; terlalu pendek atau terlalu panjang lebih jarang berupa meter. */
internal fun lenFactor(digits: Int): Double = when {
    digits <= 3 -> 0.7
    digits == 4 -> 0.85
    digits <= 7 -> 1.0
    else -> 0.65
}

internal class LabelBox(val tag: LabelTag, val l: Int, val t: Int, val r: Int, val b: Int)

/** Kumpulkan semua token label (tanpa digit) beserta kotaknya. */
internal fun collectLabels(lines: List<List<OcrTok>>): List<LabelBox> {
    val out = ArrayList<LabelBox>()
    for (ln in lines) for (e in ln) {
        val tag = when {
            isMultiplier(e.s) || e.s.contains('%') -> LabelTag(Lbl.NOISE)
            fixDigits(e.s) == null -> labelOf(e.s)
            else -> null
        } ?: continue
        out += LabelBox(tag, e.l, e.t, e.r, e.b)
    }
    return out
}

/**
 * Label terdekat dari kotak angka (l,t,r,b): di kiri/kanan pada baris yang sama (maks. 3x tinggi angka), atau tepat
 * di atas/bawah (maks. 2,2x tinggi angka, label harus berada kira-kira di atas angka; mis. "ENGINE HOURS" di bawah drum).
 */
internal fun nearbyLabel(labels: List<LabelBox>, l: Int, t: Int, r: Int, b: Int): LabelTag? {
    val h = (b - t).toFloat()
    if (h <= 0f) return null
    var best: LabelTag? = null; var bestD = Float.MAX_VALUE
    for (lb in labels) {
        val sameRow = abs((lb.t + lb.b) / 2f - (t + b) / 2f) < h * 0.9f
        val gapX = when { lb.l >= r -> (lb.l - r).toFloat(); lb.r <= l -> (l - lb.r).toFloat(); else -> 0f }
        val lcx = (lb.l + lb.r) / 2f
        val gapY = when { lb.t >= b -> (lb.t - b).toFloat(); lb.b <= t -> (t - lb.b).toFloat(); else -> 0f }
        val d = when {
            sameRow && gapX <= h * 3f -> gapX / h
            lcx >= l - h && lcx <= r + h && gapY <= h * 2.2f -> gapY / h + 0.5f
            else -> continue
        }
        if (d < bestD) { bestD = d; best = lb.tag }
    }
    return best
}

// ===== kotak pelacak yang stabil =====

/** Kotak yang sedang dilacak beserta skor pencariannya. */
data class TrackState(val track: NumTrack?, val score: Double = 0.0)

private fun sameSpot(a: NumTrack, b: NumTrack): Boolean {
    val w = max(a.r - a.l, b.r - b.l); val h = max(a.b - a.t, b.b - b.t)
    return abs(a.cx - b.cx) < w * 0.6f && abs(a.cy - b.cy) < h * 1.0f
}

private fun lerpTrack(a: NumTrack, b: NumTrack, k: Float): NumTrack = NumTrack(
    a.l + (b.l - a.l) * k, a.t + (b.t - a.t) * k, a.r + (b.r - a.r) * k, a.b + (b.b - a.b) * k,
    b.fw, b.fh, b.tag ?: a.tag
)

/**
 * Gabungkan hasil pencarian terbaru [hit] ke kotak yang sedang dilacak [cur]:
 *  - tidak ada hasil -> pertahankan kotak lama (satu pencarian meleset tidak boleh menghapus kotak),
 *  - belum ada kotak, atau [force] (mis. setelah zoom) -> pakai hasil baru,
 *  - hasil di tempat yang sama -> haluskan gerakan kotak ([alpha]) agar tidak bergetar,
 *  - hasil di tempat lain -> pindah hanya bila skornya minimal [switchRatio] kali skor lama, supaya kotak tidak
 *    bolak-balik antar dua angka yang skornya mirip.
 */
fun mergeTrack(cur: TrackState, hit: NumTrack?, hitScore: Double, force: Boolean = false, switchRatio: Double = 1.4, alpha: Float = 0.5f): TrackState {
    if (hit == null) return cur
    val c = cur.track ?: return TrackState(hit, hitScore)
    if (force) return TrackState(hit, hitScore)
    return when {
        sameSpot(c, hit) -> TrackState(lerpTrack(c, hit, alpha), hitScore)
        hitScore >= cur.score * switchRatio -> TrackState(hit, hitScore)
        else -> cur
    }
}

// ===== kebijakan fokus =====

enum class AfState { IDLE, SEARCHING, LOCKED, FAILED }

/**
 * Memutuskan kapan fokus perlu dipicu ulang supaya kamera tidak "berburu" fokus terus-menerus:
 *  - angka baru atau berpindah lebih dari [moveThresh] (pecahan frame) -> fokus,
 *  - foto angka mulai buram ([blurSharp]) dan sudah >= 1,5 dtk sejak fokus terakhir -> fokus ulang,
 *  - fokus gagal -> coba lagi dengan jeda makin panjang, berhenti setelah [maxRetries] sampai angka bergeser,
 *  - selain itu hanya penyegaran berkala tiap [refreshMs],
 *  - tidak pernah lebih sering dari [minGapMs], dan tidak saat pengguna baru mengetuk layar.
 */
class FocusPolicy(
    private val minGapMs: Long = 900,
    private val refreshMs: Long = 8000,
    private val moveThresh: Float = 0.06f,
    private val blurSharp: Double = SHARP_WARN,
    private val maxRetries: Int = 4,
) {
    private var started = false
    private var lastAt = 0L
    private var lastX = 0f
    private var lastY = 0f
    var failures = 0
        private set

    fun reset() { started = false; lastAt = 0L; failures = 0 }

    fun markTriggered(now: Long, cx: Float, cy: Float) { started = true; lastAt = now; lastX = cx; lastY = cy }

    fun onResult(ok: Boolean) { if (ok) failures = 0 else failures++ }

    /** [cx],[cy] titik angka (normal 0..1), [sharp] ketajaman area angka (null bila belum diukur), [manualUntil] batas waktu ketukan manual. */
    fun shouldTrigger(now: Long, cx: Float, cy: Float, sharp: Double?, manualUntil: Long): Boolean {
        if (now < manualUntil) return false
        if (!started) return true
        val since = now - lastAt
        if (since < minGapMs) return false
        val moved = hypot(cx - lastX, cy - lastY) > moveThresh
        if (moved) return true
        if (failures > 0) return failures <= maxRetries && since >= minGapMs * (1L shl failures.coerceAtMost(4))
        if (sharp != null && sharp < blurSharp && since >= 1500) return true
        return since >= refreshMs
    }
}

/** Teks petunjuk fokus untuk pengguna; kosong bila tidak perlu ditampilkan. */
fun afHint(state: AfState, failures: Int): String = when (state) {
    AfState.SEARCHING -> "Mencari fokus…"
    AfState.FAILED -> if (failures >= 3) "Fokus sulit · coba jauhkan atau dekatkan HP" else "Fokus belum kena · menyesuaikan…"
    else -> ""
}

// ===== zoom otomatis =====

/**
 * Faktor pembesaran maksimum (>= 1) agar kotak [tr] tetap utuh di dalam frame (margin [margin]) setelah zoom,
 * karena zoom kamera selalu memusat di tengah frame.
 */
fun zoomRoom(tr: NumTrack, margin: Float = 0.04f): Float {
    fun room(a: Float, b: Float): Float {
        var k = Float.MAX_VALUE
        for (v in floatArrayOf(a, b)) { val d = abs(v - 0.5f); if (d > 1e-4f) k = min(k, (0.5f - margin) / d) }
        return k
    }
    return min(room(tr.l, tr.r), room(tr.t, tr.b)).coerceAtLeast(1f)
}

/**
 * Rasio zoom yang disarankan untuk angka [tr] saat zoom sekarang [cur]; sama dengan [cur] bila tidak perlu berubah.
 * Tinggi angka < 5,5% tinggi frame -> diperbesar menuju 11% (maks. 1,6x per langkah, dan hanya bila angka tetap utuh
 * di dalam frame). Lebih dari 30% -> diperkecil. Perubahan di bawah 8% diabaikan agar zoom tidak bergoyang.
 */
fun autoZoomFor(tr: NumTrack, cur: Float, minZ: Float, maxZ: Float): Float {
    val h = tr.b - tr.t
    if (h <= 0f || cur <= 0f) return cur
    val want = when {
        h < 0.055f -> cur * min(0.11f / h, 1.6f)
        h > 0.30f -> cur * max(0.20f / h, 0.6f)
        else -> return cur
    }
    var z = want.coerceIn(minZ, maxZ)
    if (z > cur) z = min(z, cur * zoomRoom(tr))
    return if (abs(z - cur) / cur < 0.08f) cur else z
}

/** Hitung ulang koordinat kotak setelah zoom berubah sebesar [ratio] (zoom baru / zoom lama); memusat di tengah frame. */
fun remapForZoom(tr: NumTrack, ratio: Float): NumTrack {
    if (ratio <= 0f || abs(ratio - 1f) < 1e-3f) return tr
    fun m(v: Float) = (0.5f + (v - 0.5f) * ratio).coerceIn(0f, 1f)
    return tr.copy(l = m(tr.l), t = m(tr.t), r = m(tr.r), b = m(tr.b))
}
