package com.frostbyte.hmtracker

import org.junit.Assert.*
import org.junit.Test

class CameraAssistTest {
    private fun still(g: MotionGate, n: Int = 40) { repeat(n) { g.feed(0.02f, -0.01f, 9.81f) } }

    @Test fun stillPhoneIsNotMoving() {
        val g = MotionGate(); still(g)
        assertFalse(g.moving)
        assertTrue(g.level < 0.2f)
    }

    @Test fun shakingIsDetectedAndClearsAfterRest() {
        val g = MotionGate(); still(g)
        repeat(30) { i -> g.feed(if (i % 2 == 0) 3f else -3f, 0f, 9.81f) }
        assertTrue(g.moving)
        still(g, 80)
        assertFalse(g.moving)
    }

    @Test fun slowTiltIsNotCountedAsShake() {
        val g = MotionGate(); still(g)
        // HP dimiringkan pelan (±6 dtk): gravitasi berpindah sumbu sedikit demi sedikit
        for (i in 0..300) { val a = i / 300f * 0.6f; g.feed(9.81f * kotlin.math.sin(a), 0f, 9.81f * kotlin.math.cos(a)) }
        assertFalse(g.moving)
    }

    @Test fun moderateShakeStillCounts() {
        val g = MotionGate()
        still(g)
        repeat(20) { i -> g.feed(if (i % 2 == 0) 2f else -2f, 0f, 9.81f) }
        assertTrue(g.moving)
    }

    @Test fun resetClearsState() {
        val g = MotionGate(); repeat(20) { i -> g.feed(if (i % 2 == 0) 3f else -3f, 0f, 9.81f) }
        g.reset()
        assertFalse(g.moving); assertEquals(0f, g.level, 0f)
    }

    @Test fun zoomPresetsFollowLensRange() {
        assertEquals(listOf(1f, 2f, 4f), zoomPresets(1f, 8f))
        assertEquals(listOf(1f, 2f), zoomPresets(1f, 2f))
        assertTrue(zoomPresets(1f, 1f).isEmpty())
        assertEquals(listOf(2f, 4f), zoomPresets(2f, 10f))
    }

    @Test fun onPresetTolerance() {
        assertTrue(onPreset(2.05f, 2f)); assertTrue(onPreset(1f, 1f))
        assertFalse(onPreset(1.5f, 1f)); assertFalse(onPreset(3f, 2f))
    }

    @Test fun trainReadingAcceptsAnyNumberWithoutUnitChecks() {
        val r = trainReading("1041,0")
        assertEquals(1041.0, r!!.value, 0.0); assertEquals("1041.0", r.text)
        assertFalse(r.blocked); assertFalse(r.warned)
        assertNull(trainReading("abc")); assertNull(trainReading(""))
    }
}

class AutoShootGateTest {
    @Test fun firesOnlyAfterHold() {
        val g = AutoShootGate(1000, 3000)
        assertFalse(g.feed(0, true, "853.1", true))
        assertFalse(g.feed(600, true, "853.1", true))
        assertTrue(g.feed(1100, true, "853.1", true))
    }

    @Test fun valueChangeRestartsHold() {
        val g = AutoShootGate(1000, 3000)
        g.feed(0, true, "853.1", true)
        assertFalse(g.feed(900, true, "853.2", true))
        assertFalse(g.feed(1500, true, "853.2", true))
        assertTrue(g.feed(1950, true, "853.2", true))
    }

    @Test fun shakeOrNotReadyResets() {
        val g = AutoShootGate(1000, 3000)
        g.feed(0, true, "9127.0", true)
        assertFalse(g.feed(800, true, "9127.0", false))
        assertFalse(g.feed(1200, true, "9127.0", true))     // mulai hitung lagi
        assertTrue(g.feed(2300, true, "9127.0", true))
        g.reset()
        assertFalse(g.feed(2400, false, "9127.0", true))
    }

    @Test fun cooldownBlocksRepeat() {
        val g = AutoShootGate(1000, 3000)
        g.feed(0, true, "113775", true)
        assertTrue(g.feed(1000, true, "113775", true))
        assertFalse(g.feed(2200, true, "113775", true))
        assertFalse(g.feed(3900, true, "113775", true))
        assertTrue(g.feed(4100, true, "113775", true))
    }
}

class TrimRoiTest {
    private val core = Roi(100, 100, 200, 40)

    @Test fun stampBelowTrimsBottomButKeepsNumber() {
        val roi = Roi(70, 60, 260, 200)                       // bantalan lebar, bawahnya kena stempel di y=170
        val out = trimRoiAwayFromStamps(roi, core, listOf(Roi(80, 170, 200, 20)))
        assertEquals(170, out.y + out.h)
        assertTrue(out.y + out.h >= core.y + core.h)
        assertEquals(60, out.y)
    }

    @Test fun stampAtRightTrimsRight() {
        val roi = Roi(70, 60, 400, 120)
        val out = trimRoiAwayFromStamps(roi, core, listOf(Roi(380, 90, 90, 50)))
        assertEquals(380, out.x + out.w)
        assertTrue(out.x + out.w >= core.x + core.w)
    }

    @Test fun stampOverlappingNumberIsIgnored() {
        val roi = Roi(70, 60, 260, 120)
        assertEquals(roi, trimRoiAwayFromStamps(roi, core, listOf(Roi(150, 110, 60, 20))))
    }

    @Test fun farawayStampChangesNothing() {
        val roi = Roi(70, 60, 260, 120)
        assertEquals(roi, trimRoiAwayFromStamps(roi, core, listOf(Roi(700, 700, 90, 50))))
    }

    @Test fun trimNeverCutsBelowCore() {
        val roi = Roi(90, 90, 220, 60)
        val out = trimRoiAwayFromStamps(roi, core, listOf(Roi(90, 130, 220, 20)))   // menempel tepat di tepi bawah angka
        assertTrue(out.y + out.h >= core.y + core.h)
    }
}
