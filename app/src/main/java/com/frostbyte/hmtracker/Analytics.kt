package com.frostbyte.hmtracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DAY_MS = 864e5

/** Pembacaan sejak titik servis terakhir (termasuk titik servis itu sendiri). */
private fun UnitData.segment(): List<Reading> {
    val r = readings
    if (r.isEmpty()) return emptyList()
    var s = 0
    for (i in r.indices) if (r[i].svc) s = i
    return r.subList(s, r.size)
}

/**
 * Laju pemakaian (jam/km per hari) dari pembacaan sejak servis terakhir.
 * [tail] = pakai hanya n pembacaan terakhir (laju terbaru). Null bila data belum cukup atau tidak ada pemakaian.
 */
fun ratePerDay(u: UnitData, tail: Int? = null): Double? {
    val seg0 = u.segment()
    val seg = if (tail != null && seg0.size > tail) seg0.takeLast(tail) else seg0
    if (seg.size < 2) return null
    val days = (seg.last().t - seg.first().t) / DAY_MS
    val used = seg.last().hm - seg.first().hm
    if (days <= 0.04 || used <= 0.0) return null
    return used / days
}

enum class Trend { NAIK, TURUN, STABIL, TIDAK_CUKUP }

data class Forecast(
    val rate: Double?,          // laju untuk prediksi: pembacaan terbaru bila cukup, selain itu laju keseluruhan
    val overallRate: Double?,   // laju sejak servis terakhir
    val recentRate: Double?,    // laju 4 pembacaan terakhir
    val daysLeft: Double?,      // perkiraan hari hingga servis
    val dueAt: Long?,           // perkiraan tanggal servis (ms epoch)
    val trend: Trend,
    val confidence: String,     // tinggi | sedang | rendah
    val projected: Double? = null   // perkiraan HM/km sekarang: pembacaan terakhir + laju x waktu sejak itu
)

/** Prediksi servis. [now] diberikan dari luar agar hasilnya bisa diuji. */
fun forecast(u: UnitData, now: Long): Forecast {
    val c = calc(u)
    val points = u.segment().size
    val overall = ratePerDay(u)
    val recent = ratePerDay(u, tail = 4)
    val rate = recent ?: overall
    // Sisa interval (c.rem) dihitung pada saat pembacaan terakhir, jadi jatuh tempo dihitung dari waktu itu, bukan dari
    // sekarang; kalau tidak, pembacaan yang sudah lama akan menggeser prediksi ke belakang.
    val last = u.readings.lastOrNull()
    val dueAt = if (rate != null && rate > 0 && last != null) (last.t + maxOf(0.0, c.rem) / rate * DAY_MS).toLong() else null
    val daysLeft = dueAt?.let { maxOf(0.0, (it - now) / DAY_MS) }
    val projected = if (rate != null && rate > 0 && last != null && now > last.t) c.c + rate * (now - last.t) / DAY_MS else null
    val trend = if (points < 4 || overall == null || recent == null) Trend.TIDAK_CUKUP else {
        val ratio = recent / overall
        when {
            ratio > 1.2 -> Trend.NAIK
            ratio < 0.8 -> Trend.TURUN
            else -> Trend.STABIL
        }
    }
    val confidence = when {
        points >= 5 -> "tinggi"
        points >= 3 -> "sedang"
        else -> "rendah"
    }
    return Forecast(rate, overall, recent, daysLeft, dueAt, trend, confidence, projected)
}

data class UnitRow(val name: String, val hm: Double, val rem: Double, val pct: Double, val st: String, val rate: Double?)

/** Ringkasan semua unit yang sudah punya data, diurutkan dari sisa servis terkecil (paling mendesak dulu). */
fun compareUnits(units: List<UnitData>): List<UnitRow> = units.filter { it.cfg() }.map { u ->
    val c = calc(u)
    UnitRow(u.name.ifEmpty { "Unit belum diatur" }, c.c, c.rem, c.pct, c.st, ratePerDay(u, tail = 4))
}.sortedBy { it.rem }

fun csvCell(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

fun isoTime(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(t))

/** Ekspor semua riwayat (semua unit) sebagai CSV, siap dibuka di Excel / Sheets. */
fun csvOf(d: AppData): String {
    val sb = StringBuilder("unit,kategori,jenis,satuan,waktu,hm_km,titik_servis\n")
    d.units.forEach { u ->
        u.readings.forEach { r ->
            val row = listOf(
                u.name, u.cat, u.type, u.un(), isoTime(r.t),
                String.format(Locale.US, "%.1f", r.hm), if (r.svc) "ya" else "tidak"
            )
            sb.append(row.joinToString(",") { csvCell(it) }).append('\n')
        }
    }
    return sb.toString()
}

/** Laporan teks ringkas satu unit untuk dibagikan (WhatsApp, email, dll). */
fun reportOf(u: UnitData, now: Long): String {
    val c = calc(u)
    val f = forecast(u, now)
    val z = u.un()
    val st = when (c.st) { "er" -> "WAJIB SERVIS"; "wa" -> "Mendekati servis"; else -> "Lancar" }
    val sb = StringBuilder()
    sb.append("Laporan HM Tracker: ").append(u.name.ifEmpty { "Unit belum diatur" }).append('\n')
    if (u.type.isNotBlank()) sb.append("Jenis: ").append(u.type).append('\n')
    if (u.loc.isNotBlank()) sb.append("Lokasi: ").append(u.loc).append('\n')
    sb.append("HM saat ini: ").append(f1(c.c)).append(' ').append(z).append('\n')
    sb.append("Terpakai sejak servis: ").append(f1(c.used)).append(' ').append(z)
        .append(" (").append(Math.round(c.pct)).append("%)\n")
    sb.append("Sisa sampai servis: ").append(f1(maxOf(0.0, c.rem))).append(' ').append(z).append(" · status: ").append(st).append('\n')
    f.rate?.let { sb.append("Laju pemakaian: ").append(f1(it)).append(' ').append(z).append("/hari\n") }
    f.projected?.let { sb.append("Perkiraan ").append(if (u.sat == "km") "KM" else "HM").append(" sekarang: ").append(f1(it)).append(' ').append(z).append(" (estimasi dari laju pemakaian)\n") }
    f.dueAt?.let { sb.append("Perkiraan servis: ").append(dt(it)).append('\n') }
    val tr = when (f.trend) {
        Trend.NAIK -> "pemakaian sedang naik"
        Trend.TURUN -> "pemakaian sedang turun"
        Trend.STABIL -> "pemakaian stabil"
        Trend.TIDAK_CUKUP -> "data belum cukup untuk tren"
    }
    sb.append("Tren: ").append(tr).append(" · keyakinan prediksi ").append(f.confidence).append('\n')
    return sb.toString().trimEnd()
}
