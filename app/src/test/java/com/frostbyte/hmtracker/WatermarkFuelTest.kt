package com.frostbyte.hmtracker

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Rincian pemakaian antar pembacaan dan estimasi BBM di watermark (penggambaran Canvas sendiri tidak bisa dites di JVM). */
class WatermarkFuelTest {
    private val D = 86_400_000L

    private fun unit(rate: Double = 8.0, tol: Int = 15, sat: String = "jam") = UnitData(
        name = "DT01", sat = sat, interval = 200.0, fuelRate = rate, fuelTolPct = tol,
        readings = listOf(Reading(0, 100.0, true), Reading(1 * D, 110.0, false), Reading(2 * D, 125.0, false))
    )

    @Test fun estimateIsHoursTimesRateWithTolerance() {
        val f = fuelEstimate(unit(), 15.0)!!
        assertEquals(120.0, f.mid, 1e-9); assertEquals(102.0, f.lo, 1e-9); assertEquals(138.0, f.hi, 1e-9)
        assertEquals(18.0, f.half, 1e-9); assertEquals(15, f.tolPct)
    }

    @Test fun kmUnitsDivideByKmPerLiter() {
        assertEquals(25.0, fuelEstimate(unit(rate = 4.0, sat = "km"), 100.0)!!.mid, 1e-9)
    }

    @Test fun noEstimateWithoutRateOrUsage() {
        assertNull(fuelEstimate(unit(rate = 0.0), 15.0))
        assertNull(fuelEstimate(unit(), 0.0))
        assertNull(fuelEstimate(unit(), -3.0))
    }

    @Test fun toleranceIsClampedToFiftyPercent() {
        val f = fuelEstimate(unit(tol = 90), 10.0)!!
        assertEquals(50, f.tolPct); assertEquals(40.0, f.lo, 1e-9); assertEquals(120.0, f.hi, 1e-9)
    }

    @Test fun fuelLineShowsEstimatePlusMinusRangeAndAssumedRate() {
        val u = unit()
        val l = wmFuelLines(u, calc(u), 2 * D)
        assertEquals(1, l.size)
        assertTrue(l[0], l[0].matches(Regex("""Estimasi BBM ≈ 120 L ±18[,.]0 L \(102–138 L\)  ·  8[,.]0 L/jam""")))
        val z = unit(tol = 0)
        assertTrue(wmFuelLines(z, calc(z), 2 * D)[0].matches(Regex("""Estimasi BBM ≈ 120 L  ·  8[,.]0 L/jam""")))
    }

    @Test fun fuelLineIsEmptyWithoutRateOrRise() {
        val u = unit(rate = 0.0)
        assertTrue(wmFuelLines(u, calc(u), 2 * D).isEmpty())
        val v = unit()
        assertTrue(wmFuelLines(v, calc(v), 3 * D).isEmpty())          // angka sama dengan pembacaan terakhir: tidak ada pemakaian
    }

    @Test fun usageLineShowsStartToEndAndDailyAverage() {
        val u = unit()
        val l = wmUsageLines(u, calc(u), 2 * D)
        assertEquals(1, l.size)
        assertTrue(l[0], l[0].matches(Regex("""HM 110[,.]0 \(.+\) → 125[,.]0 \(.+\)  ·  rata-rata 15[,.]0 jam/hari""")))
        val k = unit(sat = "km")
        assertTrue(wmUsageLines(k, calc(k), 2 * D)[0].startsWith("ODO "))
    }

    @Test fun noDailyAverageForGapUnderTwentyHours() {
        val u = UnitData(name = "DT01", readings = listOf(Reading(0, 100.0, true), Reading(5 * 3_600_000L, 103.0, false)))
        val l = wmUsageLines(u, calc(u), 5 * 3_600_000L)
        assertEquals(1, l.size)
        assertFalse(l[0].contains("rata-rata"))
    }

    @Test fun cardLinesAreOrderedAndHonourToggles() {
        val u = unit()
        val all = wmCardLines(u, calc(u), 2 * D, more = true, fuel = true)
        assertEquals(4, all.size)
        assertTrue(all[0].startsWith("+")); assertTrue(all[1].startsWith("HM ")); assertTrue(all[2].startsWith("Laju")); assertTrue(all[3].startsWith("Estimasi BBM"))
        assertEquals(1, wmCardLines(u, calc(u), 2 * D, more = false, fuel = true).size)
        assertEquals(3, wmCardLines(u, calc(u), 2 * D, more = true, fuel = false).size)
        assertTrue(wmCardLines(u, calc(u), 2 * D, more = false, fuel = false).isEmpty())
    }

    @Test fun fuelSettingsRoundTripAndOldDataKeepsDefaults() {
        val a = AppData(units = listOf(unit(rate = 8.5, tol = 20)), wmShow = WmShow(fuel = false))
        val b = parseApp(JSONObject(a.toJson().toString()))!!
        assertEquals(8.5, b.units[0].fuelRate, 0.0); assertEquals(20, b.units[0].fuelTolPct); assertFalse(b.wmShow.fuel)
        val old = parseApp(JSONObject("""{"units":[{"name":"X","readings":[]}]}"""))!!
        assertEquals(0.0, old.units[0].fuelRate, 0.0); assertEquals(DEFAULT_FUEL_TOL, old.units[0].fuelTolPct); assertTrue(old.wmShow.fuel)
    }
}
