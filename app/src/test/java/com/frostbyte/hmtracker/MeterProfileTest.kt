package com.frostbyte.hmtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterProfileTest {
    @Test fun catalogContainsRequestedEquipmentFamilies() {
        val ids = METER_PROFILES.map { it.id }.toSet()
        assertTrue(ids.containsAll(setOf("mitsubishi", "cat_excavator", "cat_dozer", "excavator", "dozer", "truck", "lv", "dump_truck", "tanker", "pickup", "bus", "john_deere", "kubota", "massey_ferguson", "new_holland", "case_ih", "fendt", "yanmar", "mahindra", "deutz_fahr", "iseki", "kioti", "farm_tractor")))
    }

    @Test fun explicitProfileWinsAndEmptyProfileInfersSafeDefaults() {
        assertEquals("cat_dozer", meterProfile("cat_dozer").id)
        assertEquals("dozer", inferredMeterProfile("Dozer", "Alat berat", "jam").id)
        assertEquals("dump_truck", inferredMeterProfile("Dump truck (DT)", "Angkut", "km").id)
        assertEquals("lv", inferredMeterProfile("LV / light vehicle", "Kendaraan ringan (LV)", "km").id)
        assertEquals("farm_tractor", inferredMeterProfile("John Deere", "Farm tractor", "jam").id)
        assertEquals("farm_tractor", inferredMeterProfile("Kubota", "Farm tractor", "jam").id)
        assertEquals("farm_tractor", inferredMeterProfile("Traktor lainnya", "Farm tractor", "jam").id)
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

    @Test fun drumProfileHasNoDecimalPriorSoIntegerDrumsStayIntegers() {
        // meter drum QUARTZ: HM = drum gelap (01058); drum 1/10 merah tidak wajib. "01058" tidak boleh menjadi 105.8.
        val u = UnitData(sat = "jam", meterDisplayId = DISPLAY_DRUM)
        val p = u.ocrProfile()
        assertFalse(p.decimalLikely)
        assertTrue(5 in p.typicalDigits)
        assertEquals(1058.0, resolveReading(u, "01058", 0L, true, 0L, null, p.decimalLikely)?.value ?: -1.0, 0.0)
    }
}
