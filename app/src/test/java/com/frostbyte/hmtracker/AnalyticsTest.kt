package com.frostbyte.hmtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsTest {
    private val day = 86_400_000L

    /** Servis di hari 0 (HM 100), lalu +10 per hari sampai hari 5 (HM 150). */
    private fun steady(): UnitData = UnitData(
        name = "DT01", interval = 200.0,
        readings = listOf(
            Reading(0, 100.0, true), Reading(1 * day, 110.0, false), Reading(2 * day, 120.0, false),
            Reading(3 * day, 130.0, false), Reading(4 * day, 140.0, false), Reading(5 * day, 150.0, false)
        )
    )

    @Test fun lajuHarianDariServisTerakhir() {
        assertEquals(10.0, ratePerDay(steady())!!, 1e-9)
        assertEquals(10.0, ratePerDay(steady(), tail = 4)!!, 1e-9)
    }

    @Test fun lajuKosongBilaDataBelumCukup() {
        assertNull(ratePerDay(UnitData(readings = listOf(Reading(0, 100.0, true)))))
        assertNull(ratePerDay(UnitData(readings = listOf(Reading(0, 100.0, true), Reading(day, 100.0, false)))))
    }

    @Test fun prediksiServisDanTanggal() {
        val now = 5 * day
        val f = forecast(steady(), now)
        assertEquals(15.0, f.daysLeft!!, 1e-9)            // sisa 150 jam / 10 jam per hari
        assertEquals((now + 15 * day), f.dueAt!!)
        assertEquals(Trend.STABIL, f.trend)
        assertEquals("tinggi", f.confidence)              // 6 pembacaan
    }

    @Test fun trenNaikSaatPemakaianMelonjak() {
        val u = UnitData(
            name = "EX1", interval = 200.0,
            readings = listOf(
                Reading(0, 0.0, true), Reading(1 * day, 10.0, false), Reading(2 * day, 20.0, false),
                Reading(3 * day, 30.0, false), Reading(4 * day, 60.0, false), Reading(5 * day, 90.0, false)
            )
        )
        assertEquals(Trend.NAIK, forecast(u, 5 * day).trend)   // 18/hari keseluruhan, ~23/hari 4 terakhir
    }

    @Test fun trenBelumCukupDenganDuaPembacaan() {
        val u = UnitData(readings = listOf(Reading(0, 100.0, true), Reading(day, 110.0, false)))
        val f = forecast(u, day)
        assertEquals(Trend.TIDAK_CUKUP, f.trend)
        assertEquals("rendah", f.confidence)
    }

    @Test fun csvMengutipKolomBerkoma() {
        val d = AppData(units = listOf(UnitData(name = "DT, 01", readings = listOf(Reading(0, 100.0, true)))))
        val lines = csvOf(d).trimEnd().split("\n")
        assertEquals("unit,kategori,jenis,satuan,waktu,hm_km,titik_servis", lines[0])
        assertTrue(lines[1].startsWith("\"DT, 01\",Alat berat,Excavator,jam,"))
        assertTrue(lines[1].endsWith(",100.0,ya"))
    }

    @Test fun bandingUnitDiurutkanSisaTerkecil() {
        val a = UnitData(name = "A", readings = listOf(Reading(0, 100.0, true), Reading(day, 150.0, false)))   // sisa 150
        val b = UnitData(name = "B", readings = listOf(Reading(0, 100.0, true), Reading(day, 285.0, false)))   // HM 285 - servis 100 = 185 terpakai, sisa 15
        val rows = compareUnits(listOf(a, b, UnitData(name = "kosong")))   // unit tanpa data dilewati
        assertEquals(listOf("B", "A"), rows.map { it.name })
        assertEquals(15.0, rows[0].rem, 1e-9)
    }

    @Test fun laporanMemuatRingkasan() {
        val r = reportOf(steady(), 5 * day)
        assertTrue(r.startsWith("Laporan HM Tracker: DT01"))
        assertTrue(r.contains("Sisa sampai servis"))
        assertTrue(r.contains("Tren: pemakaian stabil"))
    }
}
