package com.frostbyte.hmtracker

/**
 * Profil tampilan meter, bukan database angka HM.
 * Angka HM/KM berbeda pada setiap unit sehingga tidak aman dijadikan data acuan.
 * Profil ini berisi istilah panel dan kecenderungan format yang membantu OCR memilih
 * kandidat yang benar; sampel foto nyata tetap menjadi sumber training utama.
 */
data class MeterProfile(
    val id: String,
    val label: String,
    val aliases: Set<String> = emptySet(),
    val typicalDigits: IntRange = 4..7,
    val decimalLikely: Boolean = false,
    val note: String = ""
)

const val DISPLAY_AUTO = "auto"
const val DISPLAY_ODO = "odo"
const val DISPLAY_HM_LCD = "hm_lcd"
const val DISPLAY_DRUM = "drum"

fun displayLabel(id: String): String = when (id) {
    DISPLAY_ODO -> "ODO / KM digital"
    DISPLAY_HM_LCD -> "HM digital / LCD"
    DISPLAY_DRUM -> "HM drum mekanik"
    else -> "Otomatis dari satuan"
}

val METER_PROFILES: List<MeterProfile> = listOf(
    MeterProfile("auto", "Otomatis / umum", typicalDigits = 3..8),
    MeterProfile("mitsubishi", "Mitsubishi / kendaraan", setOf("SMR", "HOUR", "ODO", "KM"), 5..7, false, "Panel kendaraan dan service meter"),
    MeterProfile("cat_excavator", "CAT / excavator", setOf("HOUR", "HOURS", "HMR", "SMR"), 4..7, true, "Hour meter LCD atau panel digital"),
    MeterProfile("cat_dozer", "CAT / dozer", setOf("HOUR", "HOURS", "HMR", "SMR"), 4..7, true, "Service meter hour"),
    MeterProfile("komatsu", "Komatsu", setOf("HOUR", "HOURS", "SMR", "SERVICE"), 4..7, true, "Hour meter panel"),
    MeterProfile("hitachi", "Hitachi", setOf("HOUR", "HOURS", "HMR"), 4..7, true, "Hour meter panel"),
    MeterProfile("volvo", "Volvo CE", setOf("HOUR", "HOURS", "SERVICE"), 4..7, true, "Service hour meter"),
    MeterProfile("john_deere", "John Deere", setOf("HOUR", "HOURS", "HR"), 4..7, true, "Engine/hour meter"),
    MeterProfile("kubota", "Kubota", setOf("HOUR", "HOURS", "HR"), 4..7, true, "Hour meter"),
    MeterProfile("dozer", "Dozer / umum", setOf("HOUR", "HOURS", "HM", "SMR"), 4..7, true),
    MeterProfile("excavator", "Excavator / umum", setOf("HOUR", "HOURS", "HM", "SMR"), 4..7, true),
    MeterProfile("truck", "Kendaraan / odometer umum", setOf("ODO", "KM", "MILEAGE", "TOTALKM"), 5..7, false),
    MeterProfile("lv", "LV / kendaraan ringan", setOf("ODO", "KM", "MILEAGE", "TOTALKM"), 5..7, false, "Odometer mobil/pickup/light vehicle"),
    MeterProfile("dump_truck", "Dump truck", setOf("ODO", "KM", "MILEAGE", "HOUR", "HOURS"), 5..7, false, "Odometer utama; HM hanya bila unit menampilkan engine hour"),
    MeterProfile("tanker", "Truk tangki", setOf("ODO", "KM", "MILEAGE"), 5..7, false),
    MeterProfile("pickup", "Pickup / double cabin", setOf("ODO", "KM", "MILEAGE"), 5..7, false),
    MeterProfile("bus", "Bus", setOf("ODO", "KM", "MILEAGE"), 5..7, false)
)

fun meterProfile(id: String?): MeterProfile =
    METER_PROFILES.firstOrNull { it.id == id } ?: METER_PROFILES.first()

fun UnitData.profile(): MeterProfile = meterProfile(meterProfileId)

fun UnitData.meterKind(): MeterKind = when (meterDisplayId) {
    DISPLAY_ODO -> MeterKind.KM
    DISPLAY_HM_LCD, DISPLAY_DRUM -> MeterKind.HM
    else -> if (un() == "km") MeterKind.KM else MeterKind.HM
}

fun UnitData.ocrProfile(): MeterProfile = when (meterDisplayId) {
    // Drum mekanik (mis. QUARTZ "ENGINE HOURS 1/10"): HM = drum gelap di kiri (mis. 01058). Drum 1/10 jam merah tidak wajib
    // dan dipotong sebelum OCR (DrumSplit.kt), jadi tidak ada prior desimal: "01058" tidak boleh berubah menjadi 105.8.
    DISPLAY_DRUM -> MeterProfile("drum", "HM drum mekanik", setOf("HOUR", "HOURS", "HM", "SMR", "ENGINE"), 3..6, false, "Hanya drum yang dihitung (tanpa drum 1/10 merah)")
    DISPLAY_ODO -> meterProfile(meterProfileId.ifBlank { "truck" })
    DISPLAY_HM_LCD -> meterProfile(meterProfileId.ifBlank { "excavator" })
    else -> if (meterProfileId.isBlank()) inferredMeterProfile(type, cat, sat) else profile()
}

/** Profil dipilih eksplisit bila ada; jika belum, pilih tebakan aman dari jenis unit. */
fun inferredMeterProfile(type: String, cat: String, sat: String): MeterProfile {
    val s = "$type $cat".lowercase()
    return when {
        sat == "km" && (s.contains("dump") || s.contains("truck")) -> meterProfile("dump_truck")
        sat == "km" && (s.contains("lv") || s.contains("light vehicle") || s.contains("pickup")) -> meterProfile("lv")
        sat == "km" || s.contains("truck") || s.contains("angkut") -> meterProfile("truck")
        s.contains("dozer") -> meterProfile("dozer")
        s.contains("excavator") -> meterProfile("excavator")
        else -> meterProfile("auto")
    }
}
