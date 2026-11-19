package com.frostbyte.hmtracker

import org.json.JSONArray
import org.json.JSONObject

/** Sumber terminology panel yang dipakai sebagai rujukan anotasi, bukan sumber angka HM. */
data class DatasetSource(val id: String, val title: String, val urls: List<String>, val terms: List<String>, val negatives: List<String>)

val DATASET_SOURCES = listOf(
    DatasetSource(
        "mitsubishi_fuso_canter",
        "FUSO FE/FG Owner's Manual 2015/2017",
        listOf("https://mitfuso.com/files/Owners-Manual-FUSO-2017-FEFG.pdf", "https://mitfuso.com/files/Owners-Manual-FUSO-2015-FEFG.pdf"),
        listOf("ODO", "TRIP", "TRIP A", "TRIP B", "odometer", "trip meter", "multi-information display", "speedometer", "tachometer", "RPM"),
        listOf("WARNING", "INDICATOR", "FUEL", "DEF", "DPF", "CLOCK", "OUTSIDE TEMPERATURE", "MAINTENANCE", "ENGINE OIL", "OIL FILTER")
    ),
    DatasetSource(
        "cat_307e2",
        "Caterpillar 307E2 OMM SEBU9353-11",
        listOf("https://westcountyequipment.com/files/307E2.pdf", "https://catpublications.com/"),
        listOf("Service Hour Meter", "Monitoring System", "Fuel Level Gauge", "Coolant Temperature Gauge", "Action Lamp", "Action Alarm", "CHECK FLTR/FLUID INFO"),
        listOf("ODO", "odometer", "trip", "RPM", "7-segment", "mechanical drum", "analog meter")
    ),
    DatasetSource(
        "cat_d3k2_d5k2",
        "Caterpillar D3K2/D4K2/D5K2 OMM SEBU8498-04",
        listOf("https://www.gawest.com/assets/uploads/CAT-D5K.pdf", "https://www.cat.com/en_US/support/maintenance/service-manuals.html"),
        listOf("Compact Instrument Cluster", "LCD Screen", "Odometer", "Total Distance Traveled", "Service Hour Meter", "Engine Speed", "rpm"),
        listOf("trip", "trip meter", "mechanical drum meter", "analog meter", "7-segment display")
    ),
    DatasetSource(
        "komatsu_pc210_pc138",
        "Komatsu PC210LC-11 / PC138US OMM",
        listOf("https://www.fairfaxhire.co.uk/wp-content/uploads/2025/01/KOMATSU-PC210LC-11-OPS-MANUAL.pdf", "https://s564272a96de90d1a.jimcontent.com/download/version/1710767173/module/11466610473/name/Komatsu%20PC138US%20Operation%20%26%20Maintenance%20Manual.pdf"),
        listOf("machine monitor", "liquid crystal display", "service meter/clock", "meter display", "ECO gauge", "fuel gauge", "message display", "camera image display"),
        listOf("ODO", "odometer", "trip", "RPM", "tachometer", "hour meter", "mechanical drum")
    ),
    DatasetSource(
        "hitachi_zxu13us_5b",
        "Hitachi ZXU13US-5B Operator's Manual",
        listOf("https://www.completehire.com.au/media/downloadable/pdfs/product-information/Complete-Hire-Sydney-Hitachi-ZXU13us-5B-13T-Excavator-Operators-Manual.pdf"),
        listOf("Multi Function Monitor", "Hour Meter", "Clock", "Sub Meter", "Fuel Gauge", "Coolant Temperature Gauge", "Basic Screen", "Starting Screen"),
        listOf("ODO", "odometer", "trip meter", "service meter", "RPM", "tachometer", "7-segment", "mechanical drum")
    ),
    DatasetSource(
        "volvo_ec140e",
        "Volvo CE EC140E Operator's Manual",
        listOf("https://irp.cdn-website.com/28d45882/files/uploaded/EC140EL%20O.pdf", "https://www.volvoce.com/global/en/products-and-services/past-products/"),
        listOf("IC screen", "Instrument Cluster", "engine speed graph", "segmented bar graph", "rpm", "HOURS 1/10", "Fault Active", "Error Code"),
        listOf("ODO", "odometer", "trip", "service meter", "LCD", "7-segment", "mechanical drum", "analog display")
    )
)

val DATASET_SCHEMA_FIELDS = listOf(
    "id", "file", "label", "exact_text", "semantic_label", "target_status", "region_type", "unit", "unit_name",
    "machine_type", "brand_profile", "display_mode", "source_kind", "source_id", "model_context", "glare", "blur",
    "occlusion", "crop", "brightness", "notes", "learn_source", "vehicle_model", "unit_year", "country_market", "light_condition", "glare_level", "camera_angle", "display_state"
)

fun datasetManifestJson(): JSONObject = JSONObject().apply {
    put("schema", 1)
    put("purpose", "Source-backed OCR dataset metadata; source manuals define terminology, field photos provide actual pixels")
    put("fields", JSONArray().also { x -> DATASET_SCHEMA_FIELDS.forEach { x.put(it) } })
    put("sources", JSONArray().also { a -> DATASET_SOURCES.forEach { s ->
        a.put(JSONObject().put("id", s.id).put("title", s.title)
            .put("urls", JSONArray().also { x -> s.urls.forEach { x.put(it) } })
            .put("terms", JSONArray().also { x -> s.terms.forEach { x.put(it) } })
            .put("negatives", JSONArray().also { x -> s.negatives.forEach { x.put(it) } }))
    } })
}

/** Rujukan istilah saja; foto tetap ditandai sebagai field_photo. */
fun UnitData.referenceSourceId(): String = when {
    meterProfileId in setOf("mitsubishi", "truck", "dump_truck", "tanker", "lv", "pickup", "bus") -> "mitsubishi_fuso_canter"
    meterProfileId.startsWith("cat_") -> if (meterProfileId == "cat_dozer") "cat_d3k2_d5k2" else "cat_307e2"
    meterProfileId == "komatsu" -> "komatsu_pc210_pc138"
    meterProfileId == "hitachi" -> "hitachi_zxu13us_5b"
    meterProfileId == "volvo" -> "volvo_ec140e"
    else -> ""
}

data class DatasetMeta(
    val exactText: String = "",
    val semanticLabel: String = "",
    val targetStatus: String = "target",
    val regionType: String = "numeric_readout",
    val unit: String = "",
    val machineType: String = "",
    val brandProfile: String = "",
    val displayMode: String = DISPLAY_AUTO,
    val sourceKind: String = "field_photo",
    val sourceId: String = "",
    val modelContext: String = "",
    val glare: Boolean = false,
    val blur: Boolean = false,
    val occlusion: Boolean = false,
    val crop: String = "full",
    val brightness: String = "normal",
    val notes: String = "",
    val vehicleModel: String = "",
    val unitYear: String = "",
    val countryMarket: String = "",
    val lightCondition: String = "",
    val glareLevel: String = "",
    val cameraAngle: String = "",
    val displayState: String = "normal"
)

fun DatasetMeta.toJson() = JSONObject().put("exactText", exactText).put("semanticLabel", semanticLabel)
    .put("targetStatus", targetStatus).put("regionType", regionType).put("unit", unit).put("machineType", machineType)
    .put("brandProfile", brandProfile).put("displayMode", displayMode).put("sourceKind", sourceKind).put("sourceId", sourceId)
    .put("modelContext", modelContext).put("glare", glare).put("blur", blur).put("occlusion", occlusion)
    .put("crop", crop).put("brightness", brightness).put("notes", notes)
    .put("vehicleModel", vehicleModel).put("unitYear", unitYear).put("countryMarket", countryMarket)
    .put("lightCondition", lightCondition).put("glareLevel", glareLevel).put("cameraAngle", cameraAngle).put("displayState", displayState)

fun datasetMetaFrom(o: JSONObject?) = DatasetMeta(
    exactText = o?.optString("exactText", "") ?: "", semanticLabel = o?.optString("semanticLabel", "") ?: "",
    targetStatus = o?.optString("targetStatus", "target") ?: "target", regionType = o?.optString("regionType", "numeric_readout") ?: "numeric_readout",
    unit = o?.optString("unit", "") ?: "", machineType = o?.optString("machineType", "") ?: "",
    brandProfile = o?.optString("brandProfile", "") ?: "", displayMode = o?.optString("displayMode", DISPLAY_AUTO) ?: DISPLAY_AUTO,
    sourceKind = o?.optString("sourceKind", "field_photo") ?: "field_photo", sourceId = o?.optString("sourceId", "") ?: "",
    modelContext = o?.optString("modelContext", "") ?: "", glare = o?.optBoolean("glare", false) ?: false,
    blur = o?.optBoolean("blur", false) ?: false, occlusion = o?.optBoolean("occlusion", false) ?: false,
    crop = o?.optString("crop", "full") ?: "full", brightness = o?.optString("brightness", "normal") ?: "normal",
    notes = o?.optString("notes", "") ?: "",
    vehicleModel = o?.optString("vehicleModel", "") ?: "", unitYear = o?.optString("unitYear", "") ?: "",
    countryMarket = o?.optString("countryMarket", "") ?: "", lightCondition = o?.optString("lightCondition", "") ?: "",
    glareLevel = o?.optString("glareLevel", "") ?: "", cameraAngle = o?.optString("cameraAngle", "") ?: "",
    displayState = o?.optString("displayState", "normal") ?: "normal"
)
