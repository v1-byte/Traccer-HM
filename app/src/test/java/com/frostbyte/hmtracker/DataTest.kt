package com.frostbyte.hmtracker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataTest {
    private fun unit(vararg r: Reading) = UnitData(name = "DT01", interval = 200.0, readings = r.toList())

    @Test fun calcMemakaiServisTerakhirSebagaiDasar() {
        val u = unit(Reading(1000, 100.0, true), Reading(2000, 250.0, false), Reading(3000, 300.0, true), Reading(4000, 380.0, false))
        val c = calc(u)
        assertEquals(300.0, c.b, 0.0)
        assertEquals(380.0, c.c, 0.0)
        assertEquals(80.0, c.used, 1e-9)
        assertEquals(120.0, c.rem, 1e-9)
        assertEquals("ok", c.st)
    }

    @Test fun statusPeringatanDanKritis() {
        assertEquals("wa", calc(unit(Reading(0, 100.0, true), Reading(1, 285.0, false))).st)   // sisa 15
        assertEquals("er", calc(unit(Reading(0, 100.0, true), Reading(1, 295.0, false))).st)   // sisa 5
    }

    @Test fun backupBolakBalik() {
        val a = AppData(units = listOf(unit(Reading(1000, 10.0, true), Reading(2000, 25.5, false))), acc = "#30D158")
        val j = a.toJson()
        assertEquals(SCHEMA, j.getInt("schema"))
        val b = parseApp(JSONObject(j.toString()))
        assertEquals(a.units, b?.units)
        assertEquals("#30D158", b?.acc)
    }

    @Test fun backupWebTanpaSchemaTetapTerbaca() {
        val raw = """{"units":[{"name":"EX1","readings":[{"t":1,"hm":5.0,"svc":true}]}]}"""
        val a = parseApp(JSONObject(raw))
        assertEquals("EX1", a?.units?.first()?.name)
        assertEquals(1, JSONObject(raw).optInt("schema", 1))
    }

    @Test fun backupTanpaUnitDitolak() {
        assertNull(parseApp(JSONObject("""{"units":[]}""")))
        assertNull(parseApp(JSONObject("""{}""")))
    }

    @Test fun auditBolakBalikJson() {
        val a = Audit(5L, "DT01", "1234.5", "1234.5", 1234.5, false, 0L, true, "", "abc")
        assertEquals(a, auditFrom(JSONObject(a.toJson().toString())))
        assertTrue(a.toJson().has("sha"))
    }

    @Test fun watermarkSettingsRoundTripAndLegacyMigration() {
        val u = listOf(UnitData(name = "DT01"))
        val a = AppData(units = u, wmScale = 50, wmAlpha = 40, wmShow = WmShow(bar = false, ref = false))
        val b = cfgFrom(JSONObject(a.toJson().toString()), u)
        assertEquals(50, b.wmScale); assertEquals(40, b.wmAlpha)
        assertEquals(false, b.wmShow.bar); assertEquals(false, b.wmShow.ref); assertEquals(true, b.wmShow.hm)
        // data lama: hanya wmSize, tanpa wmScale/wmAlpha/bagian baru
        val old = cfgFrom(JSONObject("""{"wmSize":"sm","wmShow":{"unit":true,"op":false,"loc":true}}"""), u)
        assertEquals(55, old.wmScale); assertEquals(88, old.wmAlpha)
        assertEquals(false, old.wmShow.op); assertEquals(true, old.wmShow.more)
        // nilai di luar batas dijepit
        val x = cfgFrom(JSONObject("""{"wmScale":999,"wmAlpha":0}"""), u)
        assertEquals(WM_SCALE_MAX, x.wmScale); assertEquals(WM_ALPHA_MIN, x.wmAlpha)
    }
}
