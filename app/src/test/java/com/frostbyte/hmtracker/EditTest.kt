package com.frostbyte.hmtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditTest {
    @Test fun rotasiDibulatkanKeKelipatan90() {
        assertEquals(90, PhotoEdit(rot = 450).normalized().rot)
        assertEquals(270, PhotoEdit(rot = -90).normalized().rot)
        assertEquals(0, PhotoEdit(rot = 360).normalized().rot)
    }

    @Test fun kontrasDibatasi() {
        assertEquals(0.5f, PhotoEdit(contrast = 0.9f).normalized().contrast, 1e-6f)
        assertEquals(-0.5f, PhotoEdit(contrast = -2f).normalized().contrast, 1e-6f)
    }

    @Test fun editKosongTidakDiterapkan() {
        assertTrue(PhotoEdit().isNone)
        assertTrue(PhotoEdit(rot = 360).normalized().isNone)
        assertTrue(!PhotoEdit(rot = 90).isNone)
    }
}
