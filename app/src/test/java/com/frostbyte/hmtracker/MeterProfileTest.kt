package com.frostbyte.hmtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterProfileTest {
    @Test fun catalogContainsRequestedEquipmentFamilies() {
        val ids = METER_PROFILES.map { it.id }.toSet()
        assertTrue(ids.containsAll(setOf("mitsubishi", "cat_excavator", "cat_dozer", "excavator", "dozer", "truck", "lv", "dump_truck", "tanker", "pickup", "bus")))
    }

    @Test fun explicitProfileWinsAndEmptyProfileInfersSafeDefaults() {
        assertEquals("cat_dozer", meterProfile("cat_dozer").id)
        assertEquals("dozer", inferredMeterProfile("Dozer", "Alat berat", "jam").id)
        assertEquals("dump_truck", inferredMeterProfile("Dump truck (DT)", "Angkut", "km").id)
        assertEquals("lv", inferredMeterProfile("LV / light vehicle", "Kendaraan ringan (LV)", "km").id)
    }

    @Test fun profileIsOnlyALightPrior() {
        val lines = listOf(
            listOf(OcrTok("9127", 100, 400, 300, 460), OcrTok("H", 310, 400, 340, 460)),
            listOf(OcrTok("123456", 100, 700, 400, 760))
        )
        val hit = pickMeter(lines, 800, 1000, want = MeterKind.HM, profile = meterProfile("excavator"))
        assertEquals("9127", hit?.text)
    }

    @Test fun displayModeOverridesUnitSatuanWhenExplicit() {
        val km = UnitData(sat = "jam", meterDisplayId = DISPLAY_ODO)
        val drum = UnitData(sat = "km", meterDisplayId = DISPLAY_DRUM)
        assertEquals(MeterKind.KM, km.meterKind())
        assertEquals(MeterKind.HM, drum.meterKind())
        assertEquals("HM drum mekanik", drum.ocrProfile().label)
    }

    @Test fun datasetManifestHasRealSourceIdsAndRequiredFields() {
        val j = datasetManifestJson()
        assertTrue(j.getJSONArray("sources").length() >= 6)
        assertTrue(DATASET_SCHEMA_FIELDS.containsAll(listOf("semantic_label", "source_id", "display_mode", "glare", "occlusion")))
    }
}
