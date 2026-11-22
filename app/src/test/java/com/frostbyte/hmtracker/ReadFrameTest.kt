package com.frostbyte.hmtracker

import org.junit.Assert.*
import org.junit.Test

/** Bingkai baca manual: geometri murni (penggambaran dan gestur Compose tidak bisa dites di JVM). */
class ReadFrameTest {
    private val f = ReadFrame(0.5f, 0.5f, 0.4f, 0.2f)       // kiri .3, kanan .7, atas .4, bawah .6

    @Test fun clampKeepsSizeInRangeAndFrameInsideView() {
        val c = ReadFrame(0.5f, 0.5f, 5f, 0f).clamped()
        assertEquals(ReadFrame.MAX_W, c.w, 1e-6f); assertEquals(ReadFrame.MIN_H, c.h, 1e-6f)
        val m = f.moved(1f, 1f)
        assertEquals(0.8f, m.cx, 1e-5f); assertEquals(0.9f, m.cy, 1e-5f)       // tepi kanan/bawah mentok di 1,0
    }

    @Test fun dragCornerKeepsOppositeCornerFixed() {
        val g = f.dragCorner(3, 0.1f, 0.05f)                                      // kanan-bawah ditarik
        assertEquals(0.5f, g.w, 1e-5f); assertEquals(0.25f, g.h, 1e-5f)
        assertEquals(0.3f, g.cx - g.w / 2, 1e-5f); assertEquals(0.4f, g.cy - g.h / 2, 1e-5f)   // kiri-atas diam
    }

    @Test fun dragCornerPastOppositeEdgeStopsAtMinimumSize() {
        val g = f.dragCorner(0, 0.9f, 0f)                                         // kiri-atas ditarik jauh ke kanan
        assertEquals(ReadFrame.MIN_W, g.w, 1e-5f)
        assertEquals(0.7f, g.cx + g.w / 2, 1e-5f)                                 // tepi kanan tetap
        val h = f.dragCorner(1, 5f, -5f)
        assertTrue(h.cx - h.w / 2 >= -1e-5f && h.cx + h.w / 2 <= 1f + 1e-5f)
        assertTrue(h.cy - h.h / 2 >= -1e-5f && h.cy + h.h / 2 <= 1f + 1e-5f)
    }

    @Test fun toViewMapsNormalizedToPixels() {
        val b = f.toView(1000, 2000)
        assertEquals(300f, b[0], 0.01f); assertEquals(800f, b[1], 0.01f); assertEquals(400f, b[2], 0.01f); assertEquals(400f, b[3], 0.01f)
    }

    @Test fun encodeDecodeRoundTripAndRejectsBadText() {
        val d = ReadFrame.decode(ReadFrame.DEFAULT.encode())!!
        assertEquals(ReadFrame.DEFAULT.cx, d.cx, 1e-4f); assertEquals(ReadFrame.DEFAULT.w, d.w, 1e-4f); assertEquals(ReadFrame.DEFAULT.h, d.h, 1e-4f)
        assertNull(ReadFrame.decode(null)); assertNull(ReadFrame.decode("")); assertNull(ReadFrame.decode("1,2,3")); assertNull(ReadFrame.decode("a,b,c,d"))
        val big = ReadFrame.decode("0.5,0.5,9,9")!!
        assertEquals(ReadFrame.MAX_W, big.w, 1e-6f); assertEquals(ReadFrame.MAX_H, big.h, 1e-6f)
    }

    @Test fun focusSizeStaysSmallSoOnlyTheDigitsAreMetered() {
        assertEquals(0.096f, ReadFrame(0.5f, 0.5f, 0.8f, 0.12f).focusSize, 1e-5f)
        assertEquals(0.05f, ReadFrame(0.5f, 0.5f, 0.8f, 0.04f).focusSize, 1e-6f)
        assertEquals(0.14f, ReadFrame(0.5f, 0.5f, 0.8f, 0.5f).focusSize, 1e-6f)
    }

    @Test fun rectToFrameMapsViewRectToPhotoCoordinates() {
        // view 1000x2000, foto 1500x2000 (FILL_CENTER): skala 1, pergeseran x 250
        assertEquals(Roi(500, 400, 500, 200), rectToFrame(floatArrayOf(250f, 400f, 500f, 200f), 1000, 2000, 1500, 2000))
        assertEquals(Roi(450, 380, 600, 240), rectToFrame(floatArrayOf(250f, 400f, 500f, 200f), 1000, 2000, 1500, 2000, 0.1f))
        assertEquals(boxToFrame(1080, 1920, 3f, 1536, 2048, 0.06f), rectToFrame(boxInView(1080, 1920, 3f), 1080, 1920, 1536, 2048, 0.06f))
    }
}
