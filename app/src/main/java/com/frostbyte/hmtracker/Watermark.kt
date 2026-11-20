package com.frostbyte.hmtracker

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date

private fun col(s: String) = android.graphics.Color.parseColor(s)
private fun rgba(r: Int, g: Int, b: Int, a: Float) = android.graphics.Color.argb((a * 255).toInt(), r, g, b)

/**
 * Menggambar watermark ke Canvas (foto final maupun overlay kamera). Port 1:1 dari fungsi wm() versi web.
 * geo = koordinat GPS (lat, lon) atau null.
 */
/**
 * Keterangan rinci untuk template Detail: selisih dari pembacaan sebelumnya (hanya bila ada kenaikan), laju pemakaian, dan
 * perkiraan tanggal servis. Pembacaan sebelumnya = pembacaan terakhir yang waktunya SEBELUM [t], jadi benar baik untuk foto
 * yang sudah disimpan (pembacaan baru sudah ada di riwayat) maupun pratinjau (belum ada).
 */
fun wmDetailLines(u: UnitData, m: Calc, t: Long): List<String> {
    val z = u.un(); val out = ArrayList<String>()
    val prev = u.readings.lastOrNull { it.t < t }
    if (prev != null && m.c - prev.hm > 0.0)
        out += "+" + f1(m.c - prev.hm) + " $z sejak " + dt(prev.t) + " (" + fmtElapsed(t - prev.t) + ")"
    val rate = ratePerDay(u, tail = 4)
    val est = m.est?.let { SimpleDateFormat("d MMM yyyy", ID).format(Date(it)) }
    val parts = listOfNotNull(rate?.let { "Laju ≈" + f1(it) + " $z/hari" }, est?.let { "Perkiraan servis $it" })
    if (parts.isNotEmpty()) out += parts.joinToString("  ·  ")
    return out
}

/** Kode referensi pendek dari waktu foto; cocok dengan kunci jejak audit (waktu) untuk pelacakan, bukan tanda tangan. */
fun wmRef(t: Long): String = t.toString(36).uppercase().takeLast(6).padStart(6, '0')

/**
 * Bukti yang ikut dicetak di baris bawah Detail Card.
 * [srcNote] = asal angka ("OCR TERVERIFIKASI", "DIEDIT MANUAL", ...), [proof] = kode Ref bertanda tangan (null = tidak tersedia,
 * dipakai kode waktu biasa), [source] = cam | gal | imp, [skewMs] = selisih jam HP terhadap GPS, [warnCount] = jumlah peringatan validasi.
 */
data class WmEvidence(
    val srcNote: String? = null, val proof: String? = null, val source: String = "cam",
    val skewMs: Long = 0L, val warnCount: Int = 0
)

/** Satu baris bukti; [warn] = digambar kuning. */
data class EvLine(val text: String, val warn: Boolean)

/** Ambang selisih jam yang dianggap layak disebut (sama dengan layar Detail Foto). */
const val WM_SKEW_NOTE_MS = 120_000L

/** Baris bukti Detail Card: (1) sumber waktu + asal angka, (2) sumber foto, status GPS, jumlah peringatan. Fungsi murni agar bisa dites di JVM. */
fun wmEvidenceLines(ev: WmEvidence, clockSynced: Boolean, gpsWanted: Boolean, hasGps: Boolean): List<EvLine> {
    val clock = if (clockSynced) "Waktu GPS" else "Waktu HP"
    val skew = if (ev.skewMs >= WM_SKEW_NOTE_MS) " (jam HP selisih ${ev.skewMs / 60_000} mnt)" else ""
    val l1 = listOfNotNull(clock + skew, ev.srcNote).joinToString("  ·  ")
    val manual = ev.srcNote?.startsWith("DIEDIT") == true
    val l2 = ArrayList<String>()
    l2 += when (ev.source) {
        "gal" -> "Foto GALERI, tanpa cek kamera/GPS"
        "imp" -> "Foto IMPOR"
        else -> "Foto kamera"
    }
    if (gpsWanted && !hasGps) l2 += "GPS tidak tersedia"
    if (ev.warnCount > 0) l2 += "${ev.warnCount} peringatan"
    val warn2 = ev.source != "cam" || (gpsWanted && !hasGps) || ev.warnCount > 0
    return listOf(EvLine(l1, manual), EvLine(l2.joinToString("  ·  "), warn2))
}

/** Asal angka untuk baris bukti: diedit manual > OCR live dan foto sepakat > OCR saja. Null bila tidak relevan atau OCR masih berjalan. */
fun srcNoteOf(need: Boolean, train: Boolean, ocrState: String, input: String, autoVal: String): String? = when {
    !need || train || ocrState == "run" -> null
    input.trim().replace(',', '.') != autoVal -> "DIEDIT MANUAL"
    ocrState == "ok" -> "OCR TERVERIFIKASI"
    ocrState == "photo" || ocrState == "live" || ocrState == "conflict" -> "OCR"
    else -> null
}

/**
 * Tinggi kartu info (Pro/Detail) dalam satuan k (dikali k saat digambar). Tiap bagian yang dimatikan di Atur tidak memakan tinggi.
 * [hasNums] = tiga angka utama, [hasSmr] = baris SMR, [hasBar] = bar interval, [nEv] = jumlah baris bukti (0 = bagian bukti mati).
 */
fun cardHeightUnits(
    nRows: Int, nLines: Int, hasLoc: Boolean, nEv: Int,
    hasNums: Boolean = true, hasSmr: Boolean = true, hasBar: Boolean = true
): Int =
    77 + 19 * nRows + (if (hasNums) 40 else 0) + 14 * nLines + (if (hasSmr) 14 else 0) + (if (nLines > 0) 2 else 0) +
        (if (hasBar) 16 else 0) + (if (hasLoc) 16 else 0) + (if (nEv > 0) 12 + 12 * nEv else 8)

/** Tinggi kartu Detail dengan semua bagian aktif (pembungkus [cardHeightUnits]; dipertahankan untuk tes lama). */
fun detailHeightUnits(nRows: Int, nLines: Int, hasLoc: Boolean, nEv: Int): Int = cardHeightUnits(nRows, nLines, hasLoc, nEv)

/**
 * [ev] = bukti untuk template Detail (asal angka, kode Ref, sumber foto, selisih jam, peringatan); null = hanya sumber waktu.
 */
fun drawWatermark(g: Canvas, W: Float, H: Float, s: AppData, u: UnitData, m: Calc, t: Long, geo: Pair<Double, Double>?, full: Boolean = true, ev: WmEvidence? = null) {
    val wsz = s.wmScale.coerceIn(WM_SCALE_MIN, WM_SCALE_MAX) / 100f          // ukuran bebas 35–120%
    val ca = s.wmAlpha.coerceIn(WM_ALPHA_MIN, 100) / 100f                      // kepekatan kartu: 100% = pekat, kecil = transparan
    val k = minOf(W, H) / 400f * wsz
    val d = Date(t)
    val tpl = s.wmTpl
    val st = m.st
    val stCol = col(when (st) { "wa" -> "#FFB800"; "er" -> "#FF453A"; else -> "#34C759" })
    val stLb = when (st) { "wa" -> "PERINGATAN"; "er" -> "WAJIB SERVIS"; else -> "LANCAR" }
    val timeStr = SimpleDateFormat("HH:mm", ID).format(d)
    val dateStr = SimpleDateFormat("EEEE, d MMM yyyy", ID).format(d)
    val sh = s.wmShow
    val locParts = ArrayList<String>()
    if (sh.loc) locParts.add(u.loc.ifEmpty { "Lokasi" })
    if (s.wmGps && geo != null) locParts.add(String.format(java.util.Locale.US, "%.5f, %.5f", geo.first, geo.second))
    val locLine = locParts.joinToString("  ·  ")
    val operator = u.operator.ifEmpty { s.profile.name }.ifEmpty { "-" }
    val unitName = u.name.ifEmpty { "—" } + if (u.type.isNotEmpty()) " · ${u.type}" else ""
    val z = u.un()

    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    val med = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    fun font(sz: Float, b: Boolean) { p.textSize = sz * k; p.typeface = if (b) bold else med }
    fun mw(str: String, sz: Float, b: Boolean): Float { font(sz, b); return p.measureText(str) }
    /** Kartu transparan: teks diberi bayangan tipis supaya tetap terbaca di atas foto terang. */
    fun shade() { if (ca < 0.65f) p.setShadowLayer(2.2f * k, 0f, 1f * k, rgba(0, 0, 0, .75f)) else p.clearShadowLayer() }
    fun T(str: String, x: Float, y: Float, sz: Float, b: Boolean, c: Int = col("#FFFFFF")) {
        font(sz, b); p.color = c; p.style = Paint.Style.FILL; shade()
        g.drawText(str, x, y - p.fontMetrics.ascent, p)          // baseline "top"
    }
    fun Tm(str: String, x: Float, ymid: Float, sz: Float, b: Boolean, c: Int) {
        font(sz, b); p.color = c; p.style = Paint.Style.FILL; shade()
        g.drawText(str, x, ymid - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2, p)   // baseline "middle"
    }
    fun rr(x: Float, y: Float, w: Float, h: Float, r: Float, c: Int) {
        p.clearShadowLayer(); p.style = Paint.Style.FILL; p.color = c; g.drawRoundRect(RectF(x, y, x + w, y + h), r, r, p)
    }
    fun rect(x: Float, y: Float, w: Float, h: Float, c: Int) { p.clearShadowLayer(); p.style = Paint.Style.FILL; p.color = c; g.drawRect(x, y, x + w, y + h, p) }
    val dark = col("#111111")
    val curLb = if (u.sat == "km") "ODO SAAT INI" else "HM SAAT INI"

    /** Teks satu baris yang mengecil lalu dipotong "…" bila melebihi [maxW]. */
    fun FT(str0: String, tx: Float, ty: Float, sz: Float, b: Boolean, c: Int, maxW: Float, floor: Float = 0.6f) {
        var str = str0; var f = sz; var w = mw(str, f, b)
        if (w > maxW) {
            f = maxOf(sz * floor, sz * maxW / w); w = mw(str, f, b)
            if (w > maxW) { while (str.length > 1 && mw("$str…", f, b) > maxW) str = str.dropLast(1); str += "…" }
        }
        T(str, tx, ty, f, b, c)
    }

    /** Pil status di kiri atas foto (dipakai Pro Card dan pratinjau ringkas). */
    fun drawStatusPill() {
        val pad = 12 * k
        val pillCol = col(when (st) { "wa" -> "#F5A623"; "er" -> "#FF453A"; else -> "#8B95A8" })
        val pw = mw(stLb, 12f, true) + 28 * k; val ph = 28 * k
        rr(pad, pad, pw, ph, ph / 2, rgba(28, 24, 20, ca * .93f))
        Tm(stLb, pad + 14 * k, pad + ph / 2 + 0.5f * k, 12f, true, pillCol)
    }

    /**
     * Kartu info lengkap untuk template Pro ([pillInside] = false: status jadi pil di pojok foto) dan Detail ([pillInside] = true:
     * status + zona waktu di dalam kartu). Lebar mengikuti isi; tiap bagian bisa dimatikan lewat Atur (wmShow); kepekatan latar = wmAlpha.
     */
    fun infoCard(pillInside: Boolean) {
        val pad = 12 * k; val x = pad
        val labelC = col("#8B95A8"); val valC = col("#FFFFFF"); val softC = col("#C4CAD4"); val amber = col("#FFB800")
        val rows = ArrayList<Pair<String, String>>()
        if (sh.unit) rows += "Unit" to unitName
        if (sh.op) rows += "Operator" to operator
        val nums = if (sh.hm) listOf(
            curLb to f1(m.c) + " " + z, "TERPAKAI" to f1(m.used) + " " + z, "SISA" to f1(maxOf(0.0, m.rem)) + " " + z
        ) else emptyList()
        val lines = if (sh.more) wmDetailLines(u, m, t) else emptyList()
        val smr = "SMR " + f1(m.b) + "  ·  Next Maintenance " + f1(m.b + u.interval) + " " + z
        val hasLoc = locLine.isNotEmpty()
        val pctS = Math.round(m.pct.coerceIn(0.0, 100.0)).toString() + "%"
        val evLines = if (sh.ref) wmEvidenceLines(ev ?: WmEvidence(), TrustedClock.synced, s.wmGps, geo != null) else emptyList()
        val refS = "Ref " + (ev?.proof ?: wmRef(t))
        val zoneS = SimpleDateFormat("z", ID).format(d)
        val pwl = mw(stLb, 10f, true) + 20 * k

        // lebar kartu menyesuaikan teks terpanjang (batas atas 340/360 k, tidak melebihi foto)
        var labW = 0f; rows.forEach { labW = maxOf(labW, mw(it.first, 9.5f, false)) }
        val vxOff = 14 * k + labW + 10 * k
        var need = maxOf(mw(timeStr, 26f, true), mw(dateStr, 10f, false)) + 28 * k
        if (pillInside) need = maxOf(need, 14 * k + mw(timeStr, 26f, true) + 6 * k + mw(zoneS, 9f, false) + 14 * k + pwl + 14 * k)
        rows.forEach { need = maxOf(need, vxOff + mw(it.second, 12f, true) + 14 * k) }
        if (nums.isNotEmpty()) {
            var cw = 0f; nums.forEach { cw = maxOf(cw, mw(it.first, 8.5f, true), mw(it.second, 15f, true)) }
            need = maxOf(need, 3 * (cw + 6 * k) + 28 * k)
        }
        lines.forEachIndexed { i, ln -> need = maxOf(need, mw(ln, 9.5f, i == 0 && ln.startsWith("+")) + 28 * k) }
        if (sh.smr) need = maxOf(need, mw(smr, 9f, false) + 28 * k)
        if (hasLoc) need = maxOf(need, 26 * k + mw(locLine, 9f, false) + 14 * k)
        evLines.forEachIndexed { i, ln -> need = maxOf(need, mw(ln.text, 8.5f, false) + 28 * k + if (i == 0) mw(refS, 9f, true) + 10 * k else 0f) }
        val cardW = minOf(W - 2 * pad, (if (pillInside) 360f else 340f) * k, maxOf(need, 170 * k))
        val inner = cardW - 28 * k
        val cardH = cardHeightUnits(rows.size, lines.size, hasLoc, evLines.size, nums.isNotEmpty(), sh.smr, sh.bar) * k
        val y = H - cardH - pad

        if (!pillInside) drawStatusPill()
        rr(x, y, cardW, cardH, 16 * k, rgba(24, 22, 20, ca))

        // kepala: waktu (+ zona + pil status bila di dalam kartu), tanggal
        T(timeStr, x + 14 * k, y + 14 * k, 26f, true, valC)
        if (pillInside) {
            T(zoneS, x + 14 * k + mw(timeStr, 26f, true) + 6 * k, y + 27 * k, 9f, false, labelC)
            rr(x + cardW - pwl - 14 * k, y + 16 * k, pwl, 22 * k, 11 * k, stCol)
            Tm(stLb, x + cardW - pwl - 4 * k, y + 27 * k, 10f, true, dark)
        }
        T(dateStr, x + 14 * k, y + 46 * k, 10f, false, softC)
        rect(x + 14 * k, y + 66 * k, inner, 1 * k, rgba(255, 255, 255, .12f))

        // unit & operator
        var cy = y + 77 * k
        rows.forEach { (lb, v) ->
            T(lb, x + 14 * k, cy, 9.5f, false, labelC)
            FT(v, x + vxOff, cy - 1 * k, 12f, true, valC, cardW - vxOff - 14 * k)
            cy += 19 * k
        }

        // tiga angka utama: HM/ODO saat ini, terpakai, sisa
        if (nums.isNotEmpty()) {
            val colW = inner / 3f
            nums.forEachIndexed { i, (lb, v) ->
                val cx = x + 14 * k + colW * i
                val hot = i == 2 && st != "ok"
                T(lb, cx, cy + 4 * k, 8.5f, true, if (hot) stCol else labelC)
                FT(v, cx, cy + 16 * k, 15f, true, if (hot) stCol else valC, colW - 6 * k)
            }
            cy += 40 * k
        }

        // keterangan: selisih, laju, perkiraan servis; lalu SMR
        lines.forEachIndexed { i, ln -> FT(ln, x + 14 * k, cy, 9.5f, i == 0 && ln.startsWith("+"), if (i == 0 && ln.startsWith("+")) valC else softC, inner); cy += 14 * k }
        if (sh.smr) { FT(smr, x + 14 * k, cy, 9f, false, labelC, inner); cy += 14 * k }
        if (lines.isNotEmpty()) cy += 2 * k

        // bar interval + persen
        if (sh.bar) {
            val pctW = mw(pctS, 9f, true)
            val barW = inner - pctW - 8 * k
            val barCol = if (st == "ok") rgba(255, 255, 255, .45f) else stCol
            rr(x + 14 * k, cy + 4 * k, barW, 5 * k, 2.5f * k, rgba(255, 255, 255, .18f))
            rr(x + 14 * k, cy + 4 * k, maxOf(4 * k, barW * m.pct.coerceIn(0.0, 100.0).toFloat() / 100f), 5 * k, 2.5f * k, barCol)
            T(pctS, x + cardW - 14 * k - pctW, cy + 1 * k, 9f, true, if (st == "ok") softC else stCol)
            cy += 16 * k
        }

        // lokasi
        if (hasLoc) {
            p.clearShadowLayer(); p.style = Paint.Style.FILL; p.color = if (st == "ok") labelC else stCol
            g.drawCircle(x + 17 * k, cy + 5 * k, 2.5f * k, p)
            FT(locLine, x + 26 * k, cy, 9f, false, softC, cardW - 26 * k - 14 * k)
            cy += 16 * k
        }

        // baris bukti: sumber waktu + asal angka (+ kode Ref di kanan), sumber foto + GPS + peringatan
        if (evLines.isNotEmpty()) {
            rect(x + 14 * k, cy + 2 * k, inner, 1 * k, rgba(255, 255, 255, .12f))
            val refW = mw(refS, 9f, true)
            evLines.forEachIndexed { i, ln ->
                val maxW = if (i == 0) inner - refW - 10 * k else inner
                FT(ln.text, x + 14 * k, cy + 9 * k + 12 * k * i, 8.5f, false, if (ln.warn) amber else labelC, maxW, 0.8f)
            }
            T(refS, x + cardW - 14 * k - refW, cy + 8 * k, 9f, true, if (ev?.proof != null) softC else labelC)
        }
    }

    when (tpl) {
        "minimal" -> {
            val bh = 56 * k; val x = 12 * k; val y = H - bh - 12 * k; val w = W - 24 * k
            rr(x, y, w, bh, 12 * k, rgba(12, 16, 22, ca))
            T(timeStr, x + 14 * k, y + 10 * k, 18f, true)
            T(dateStr, x + 14 * k, y + 32 * k, 10f, false, col("#A8B2C2"))
            val pw = mw(stLb, 9f, true) + 16 * k
            rr(x + w - pw - 12 * k, y + 16 * k, pw, 20 * k, 10 * k, stCol)
            T(stLb, x + w - pw - 4 * k, y + 20 * k, 9f, true, dark)
            return
        }
        "strip" -> {
            val th = 40 * k
            rect(0f, 0f, W, th, rgba(12, 16, 22, ca))
            T("HM TRACKER", 12 * k, 12 * k, 12f, true, col("#FFB020"))
            T(timeStr + "  ·  " + u.name, W * 0.28f, 13 * k, 11f, false, col("#DDDDDD"))
            val pw = mw(stLb, 9f, true) + 14 * k
            rr(W - pw - 12 * k, 9 * k, pw, 22 * k, 11 * k, stCol)
            Tm(stLb, W - pw - 5 * k, th / 2, 9f, true, dark)
            if (full) {
                val bh = 34 * k
                rect(0f, H - bh, W, bh, rgba(0, 0, 0, ca * .65f))
                Tm(f1(m.c) + " " + z + "  ·  Terpakai " + f1(m.used) + "  ·  Sisa " + f1(maxOf(0.0, m.rem)), 12 * k, H - bh / 2, 11f, true, col("#FFFFFF"))
            }
            return
        }
        "bold" -> {
            val x = 12 * k; val h = 150 * k; val y = H - h - 12 * k; val w = W - 24 * k
            rr(x, y, w, h, 14 * k, rgba(12, 16, 22, ca))
            rect(x, y, 5 * k, h, stCol)
            T(timeStr, x + 16 * k, y + 12 * k, 26f, true)
            T(dateStr, x + 16 * k, y + 42 * k, 11f, false, col("#A8B2C2"))
            val pw = mw(stLb, 10f, true) + 18 * k
            rr(x + w - pw - 14 * k, y + 14 * k, pw, 22 * k, 11 * k, stCol)
            T(stLb, x + w - pw - 5 * k, y + 19 * k, 10f, true, dark)
            if (sh.unit) T(unitName, x + 16 * k, y + 68 * k, 14f, true)
            T(f1(m.c) + " " + z, x + 16 * k, y + 96 * k, 26f, true)
            T("Terpakai " + f1(m.used) + "  ·  Sisa " + f1(maxOf(0.0, m.rem)), x + 16 * k, y + 128 * k, 12f, false, stCol)
            return
        }
        "detail" -> if (full) { infoCard(true); return }
        "classic" -> {
            val x = 12 * k; val w = minOf(320 * k, W - 24 * k); val h = 175 * k; val y = H - h - 12 * k
            rr(x, y, w, h, 12 * k, rgba(16, 20, 24, ca))
            T(timeStr, x + 12 * k, y + 10 * k, 22f, true)
            T(dateStr, x + 12 * k, y + 36 * k, 10f, false, col("#B9C2CE"))
            val pw = mw(stLb, 9f, true) + 16 * k
            rr(x + w - pw - 12 * k, y + 12 * k, pw, 18 * k, 9 * k, stCol)
            T(stLb, x + w - pw - 4 * k, y + 15.5f * k, 9f, true, dark)
            if (sh.unit) T("Unit  $unitName", x + 12 * k, y + 58 * k, 11f, true)
            T((if (u.sat == "km") "ODO " else "HM ") + f1(m.c) + "   Terpakai " + f1(m.used) + "   Sisa " + f1(maxOf(0.0, m.rem)), x + 12 * k, y + 82 * k, 12f, true)
            T("SMR " + f1(m.b) + " · Next " + f1(m.b + u.interval) + " " + z, x + 12 * k, y + 108 * k, 10f, false, col("#A8B2C2"))
            rr(x + 12 * k, y + 130 * k, w - 24 * k, 5 * k, 3 * k, rgba(255, 255, 255, .15f))
            rr(x + 12 * k, y + 130 * k, maxOf(4 * k, (w - 24 * k) * minOf(100.0, m.pct).toFloat() / 100f), 5 * k, 3 * k, stCol)
            T(locLine, x + 12 * k, y + 145 * k, 10f, false, col("#8B95A8"))
            return
        }
    }

    /* ===== PRO — kartu menyesuaikan isi teks ===== */
    if (full) { infoCard(false); return }

    // pratinjau ringkas (tanpa isi): hanya waktu + tanggal
    val pad = 12 * k
    val cardW = minOf(W - 2 * pad, 340 * k, maxOf(mw(timeStr, 26f, true), mw(dateStr, 10f, false)) + 28 * k)
    val y = H - 66 * k - pad
    drawStatusPill()
    rr(pad, y, cardW, 66 * k, 16 * k, rgba(24, 22, 20, ca))
    T(timeStr, pad + 14 * k, y + 14 * k, 26f, true, col("#FFFFFF"))
    T(dateStr, pad + 14 * k, y + 46 * k, 10f, false, col("#C4CAD4"))
}
