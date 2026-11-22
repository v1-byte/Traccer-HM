package com.frostbyte.hmtracker

/**
 * Paket layanan. Basic memuat seluruh fitur yang sudah ada sebelum v1.6 (tidak ada yang dihapus);
 * Premium dan Platinum menambah fitur baru di kamera, edit foto, dan hitungan.
 */
enum class Tier(val id: String, val label: String, val rank: Int) {
    BASIC("basic", "Basic", 0),
    PREMIUM("premium", "Premium", 1),
    PLATINUM("platinum", "Platinum", 2);
}

/** Fitur baru yang dikunci per paket. [min] = paket paling rendah yang boleh memakainya. */
enum class Feature(val label: String, val min: Tier) {
    CAM_LEVEL("Penunjuk level kamera", Tier.PREMIUM),
    CAM_FOCUS_LOCK("Kunci fokus & eksposur (AF/AE lock)", Tier.PLATINUM),
    WM_EXTRA_TEMPLATES("Template watermark tambahan (Minimal, Bold, Top Strip, Detail Card, dan 8 template baru)", Tier.PREMIUM),
    EDIT_ROTATE("Putar foto 90°", Tier.PREMIUM),
    EDIT_CONTRAST("Atur kontras foto", Tier.PLATINUM),
    CALC_FORECAST("Prediksi servis & laju pemakaian", Tier.PREMIUM),
    CALC_EXPORT("Ekspor CSV riwayat", Tier.PREMIUM),
    CALC_TREND("Tren pemakaian (naik / turun)", Tier.PLATINUM),
    CALC_COMPARE("Perbandingan semua unit", Tier.PLATINUM),
    CALC_REPORT("Laporan ringkas siap bagikan", Tier.PLATINUM);
}

fun tierOf(id: String?): Tier = Tier.entries.firstOrNull { it.id == id } ?: Tier.BASIC

fun Tier.allows(f: Feature): Boolean = rank >= f.min.rank

fun AppData.allows(f: Feature): Boolean = tierOf(tier).allows(f)

/**
 * Paket hanya dipercaya bila disimpan bersama tanda tangan kunci Keystore perangkat ini. Mengubah field `tier` di backup
 * atau di basis data tanpa tanda tangan yang cocok mengembalikan paket ke Basic.
 * Catatan: ini menutup celah ubah-data, BUKAN lisensi. Pembayaran/lisensi sungguhan butuh server atau Play Billing.
 */
fun tierSig(signer: Signer, tier: Tier): String? = signer.sign("TIER\u001f" + tier.id)

fun verifiedTier(stored: String?, sig: String?, signer: Signer): Tier {
    val t = tierOf(stored)
    if (t == Tier.BASIC) return Tier.BASIC
    return if (!sig.isNullOrEmpty() && sig == tierSig(signer, t)) t else Tier.BASIC
}
