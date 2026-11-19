package com.frostbyte.hmtracker

import org.json.JSONObject

/*
 * Sistem latih (logika murni, tanpa Android, bisa dites di JVM).
 *
 * Tiga lapis yang saling melengkapi:
 *  1. Belajar otomatis: tiap bacaan yang dikonfirmasi pengguna menambah statistik (digit yang sering tertukar,
 *     pola digit/desimal tiap unit, lintasan OCR mana yang benar). Statistik dipakai untuk memperbaiki hasil berikutnya.
 *  2. Latih manual: foto meter + angka benar disimpan sebagai sampel; "Latih ulang" membangun statistik dari seluruh
 *     sampel dan mengukur akurasi secara berurutan (tiap sampel diuji sebelum dipelajari).
 *  3. Model sendiri: sampel diekspor, dilatih di komputer (tools/train_digits.py), model TFLite dipasang kembali (DigitModel.kt).
 */

/** Nama lintasan OCR pada [readFinalDetailed]. Yang tidak pernah menang pada banyak sampel boleh dilewati agar lebih cepat. */
val ALL_PASSES: List<String> = listOf("auto", "inv", "raw", "x288", "bin")

/** Statistik satu unit (kunci = nama unit). */
data class UnitStats(
    val total: Int = 0,
    val passWins: Map<String, Int> = emptyMap(),   // lintasan -> berapa kali bacaan teratasnya sama dengan angka benar
    val digitHist: Map<Int, Int> = emptyMap(),     // jumlah digit angka benar -> frekuensi
    val decimal: Int = 0,                          // berapa angka benar yang punya digit desimal
)

/** Hasil belajar. [conf] berkunci "dibaca>benar" (mis. "8>3": OCR membaca 8 padahal sebenarnya 3). */
data class Learned(
    val conf: Map<String, Int> = emptyMap(),
    val units: Map<String, UnitStats> = emptyMap(),
    val events: Int = 0,
)

private fun unitKey(u: String) = u.trim().ifEmpty { "-" }

/** Angka benar dari ketikan pengguna ("1234,5" -> "1234.5"); null bila bukan angka meter. */
fun normTruth(s: String): String? = normalizeCandidate(s.trim().replace(',', '.'))

/** Pasangan salah-baca satu digit pada posisi yang sama; kosong bila panjang beda atau terlalu banyak yang beda (bukan salah baca OCR biasa). */
fun confusionPairs(read: String, truth: String): List<String> {
    if (read == truth || read.length != truth.length) return emptyList()
    val diff = read.indices.filter { read[it] != truth[it] }
    if (diff.isEmpty() || diff.size > 2) return emptyList()
    if (diff.any { !read[it].isDigit() || !truth[it].isDigit() }) return emptyList()
    return diff.map { "${read[it]}>${truth[it]}" }
}

private fun <K> Map<K, Int>.plusN(k: K, n: Int): Map<K, Int> = this + (k to ((this[k] ?: 0) + n))

/**
 * Tambahkan satu kejadian: [truth] angka benar, [read] tebakan teratas mesin (boleh kosong), [winners] nama lintasan
 * yang bacaan teratasnya sudah benar. [w] = bobot (sampel manual lebih berat daripada koreksi otomatis).
 */
fun Learned.withEvent(unit: String, truth: String, read: String, winners: List<String>, w: Int = 1): Learned {
    val k = unitKey(unit)
    var c = conf
    for (p in confusionPairs(read, truth)) c = c.plusN(p, w)
    val st = units[k] ?: UnitStats()
    var pw = st.passWins
    for (n in winners.distinct()) pw = pw.plusN(n, w)
    val digits = truth.count { it.isDigit() }
    val ns = st.copy(
        total = st.total + w,
        passWins = pw,
        digitHist = st.digitHist.plusN(digits, w),
        decimal = st.decimal + if (truth.contains('.')) w else 0,
    )
    return copy(conf = c, units = units + (k to ns), events = events + 1)
}

/**
 * Lintasan OCR yang dijalankan untuk unit ini. Sampai 30 sampel semua lintasan dipakai; sesudahnya lintasan yang tidak
 * pernah menang dilewati (kecuali "auto" dan "inv") sehingga pembacaan foto lebih cepat.
 */
fun Learned.passPlan(unit: String): Set<String> {
    val st = units[unitKey(unit)]
    if (st == null || st.total < 30) return ALL_PASSES.toSet()
    return ALL_PASSES.filter { it == "auto" || it == "inv" || (st.passWins[it] ?: 0) > 0 }.toSet()
}

/** true bila untuk unit ini pembacaan dengan polaritas terbalik jauh lebih sering benar: kamera live mencoba itu lebih dulu (satu panggilan OCR lebih sedikit). */
fun Learned.preferInverse(unit: String): Boolean {
    val st = units[unitKey(unit)] ?: return false
    return st.total >= 10 && (st.passWins["inv"] ?: 0) >= 3 * ((st.passWins["auto"] ?: 0) + 1)
}

/**
 * Sesuaikan daftar kandidat dengan pola unit (setelah minimal 5 sampel):
 *  - unit yang hampir selalu berdesimal: kandidat tanpa titik dipasangi titik sebelum digit terakhir (dan sebaliknya
 *    untuk unit yang hampir tidak pernah berdesimal, setelah 8 sampel),
 *  - bila kandidat teratas punya jumlah digit yang jarang untuk unit ini (< 5%), kandidat dengan jumlah digit umum (>= 30%) didahulukan.
 */
fun Learned.refine(unit: String, cands: List<String>): List<String> {
    val st = units[unitKey(unit)] ?: return cands
    if (st.total < 5 || cands.isEmpty()) return cands
    val dotted = st.decimal.toDouble() / st.total
    val modeDigits = st.digitHist.maxByOrNull { it.value }?.key ?: 0
    val mapped = cands.map { c ->
        val digits = c.count { it.isDigit() }
        when {
            // hanya bila jumlah digit sama dengan yang lazim: drum desimal merah yang tidak terbaca (mis. "1041" padahal 1041.0) jangan dijadikan "104.1"
            dotted >= 0.8 && !c.contains('.') && digits >= 3 && digits == modeDigits -> c.dropLast(1) + "." + c.last()
            dotted <= 0.05 && st.total >= 8 && c.contains('.') -> c.replace(".", "")
            else -> c
        }
    }.distinct()
    fun share(c: String) = (st.digitHist[c.count { it.isDigit() }] ?: 0).toDouble() / st.total
    if (share(mapped.first()) >= 0.05) return mapped
    val better = mapped.firstOrNull { share(it) >= 0.3 } ?: return mapped
    return listOf(better) + mapped.filter { it != better }
}

/**
 * Seperti [repairByHistory], tetapi hanya mencoba pertukaran digit yang PERNAH terjadi (>= [minCount] kali) dan
 * boleh memilih bila satu pertukaran jauh lebih sering (>= 2x) daripada yang lain. Hanya dipakai bila bacaan asli
 * tidak masuk akal terhadap riwayat unit.
 */
fun learnedRepair(u: UnitData, s: String, now: Long, init: Boolean, conf: Map<String, Int>, minCount: Int = 2): Repair? {
    if (conf.isEmpty() || u.readings.isEmpty() || s.length !in 3..9) return null
    class Opt(val text: String, val pos: Int, val from: Char, val to: Char, val n: Int)
    val opts = ArrayList<Opt>()
    for (i in s.indices) {
        val ch = s[i]
        if (!ch.isDigit()) continue
        for (a in '0'..'9') {
            if (a == ch) continue
            val n = conf["$ch>$a"] ?: 0
            if (n < minCount) continue
            val v = s.substring(0, i) + a + s.substring(i + 1)
            val x = v.toDoubleOrNull() ?: continue
            if (checkReading(u, x, now, init, 0L).any { it.sev >= Sev.WARN }) continue
            opts += Opt(v, i, ch, a, n)
        }
    }
    if (opts.isEmpty()) return null
    val sorted = opts.sortedByDescending { it.n }
    if (sorted.size > 1 && sorted[0].n < sorted[1].n * 2) return null
    val b = sorted[0]
    return Repair(b.text, b.pos, b.from, b.to)
}

// ===== penyimpanan JSON =====

private fun JSONObject.intMap(): Map<String, Int> {
    val out = LinkedHashMap<String, Int>()
    val ks = keys()
    while (ks.hasNext()) { val k = ks.next(); out[k] = optInt(k, 0) }
    return out
}

fun Learned.toJson(): JSONObject = JSONObject().apply {
    put("v", 1)
    put("events", events)
    put("conf", JSONObject().also { o -> conf.forEach { (k, v) -> o.put(k, v) } })
    put("units", JSONObject().also { o ->
        units.forEach { (k, s) ->
            o.put(k, JSONObject()
                .put("total", s.total).put("decimal", s.decimal)
                .put("passWins", JSONObject().also { p -> s.passWins.forEach { (a, b) -> p.put(a, b) } })
                .put("digits", JSONObject().also { p -> s.digitHist.forEach { (a, b) -> p.put(a.toString(), b) } }))
        }
    })
}

fun learnedFrom(j: JSONObject?): Learned {
    if (j == null) return Learned()
    val units = LinkedHashMap<String, UnitStats>()
    val uo = j.optJSONObject("units")
    if (uo != null) {
        val ks = uo.keys()
        while (ks.hasNext()) {
            val k = ks.next(); val o = uo.optJSONObject(k) ?: continue
            units[k] = UnitStats(
                total = o.optInt("total", 0),
                passWins = o.optJSONObject("passWins")?.intMap() ?: emptyMap(),
                digitHist = (o.optJSONObject("digits")?.intMap() ?: emptyMap()).mapNotNull { (a, b) -> a.toIntOrNull()?.let { it to b } }.toMap(),
                decimal = o.optInt("decimal", 0),
            )
        }
    }
    return Learned(j.optJSONObject("conf")?.intMap() ?: emptyMap(), units, j.optInt("events", 0))
}

/** Satu sampel latih: potongan foto area angka + angka benar + metadata dataset yang dapat diaudit. */
data class Sample(val id: Long, val unit: String, val label: String, val src: String, val auto: Boolean, val meta: DatasetMeta = DatasetMeta(exactText = label, unit = unit))

fun Sample.toJson(): JSONObject = JSONObject().put("id", id).put("unit", unit).put("label", label).put("src", src).put("auto", auto).put("meta", meta.toJson())

fun sampleFrom(o: JSONObject): Sample? {
    val label = o.optString("label", "")
    if (!o.has("id")) return null
    val meta = datasetMetaFrom(o.optJSONObject("meta"))
    if (label.isEmpty() && meta.targetStatus == "target") return null
    val unit = o.optString("unit", "-")
    return Sample(o.optLong("id"), unit, label, o.optString("src", "cam"), o.optBoolean("auto", false), meta.let { if (it.exactText.isEmpty() && label.isNotEmpty()) it.copy(exactText = label, unit = unit) else it })
}

// ===== hasil latih ulang =====

/** Laporan "Latih ulang & uji": [base] tebakan teratas ML Kit benar, [learned] sesudah penyesuaian pola unit (diuji berurutan), [model] model sendiri (null bila tidak dipasang). */
data class TrainReport(
    val total: Int, val base: Int, val learned: Int, val model: Int?,
    val passOk: Map<String, Int>, val result: Learned,
)

// ===== dekode CTC (model sendiri) =====

/**
 * Dekode serakah keluaran model CTC [p] (waktu x kelas, sudah softmax; kelas terakhir = kosong): ambil kelas terbesar tiap langkah,
 * gabungkan pengulangan, buang kosong. Mengembalikan teks dan keyakinan rata-rata huruf yang dikeluarkan.
 */
fun ctcGreedy(p: Array<FloatArray>, charset: String): Pair<String, Float> {
    val blank = charset.length
    val sb = StringBuilder(); var prev = -1; var sum = 0f; var n = 0
    for (row in p) {
        var bi = 0; var bv = -1f
        for (i in row.indices) if (row[i] > bv) { bv = row[i]; bi = i }
        if (bi != prev && bi != blank && bi < charset.length) { sb.append(charset[bi]); sum += bv; n++ }
        prev = bi
    }
    return sb.toString() to (if (n == 0) 0f else sum / n)
}
