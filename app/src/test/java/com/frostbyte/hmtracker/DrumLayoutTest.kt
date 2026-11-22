package com.frostbyte.hmtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zona meter drum QUARTZ: merah (dihitung), hijau (posisi koma), kuning (drum 1/10 = menit). Foto acuan: 00991, drum 1/10 = 2. */
class DrumLayoutTest {
    @Test fun strayDotsOnCountedDrumsAreDroppedSoDecimalIsNeverInvented() {
        assertEquals("00991", drumDigitsOnly("0099.1"))
        assertEquals("00991", drumDigitsOnly("00,991"))
        assertEquals("00991", drumDigitsOnly("00991"))
        // tanpa pembersihan, "0099.1" lolos sebagai desimal 99,1 (salah 10x)
        assertEquals("0099.1", normalizeCandidate("0099.1"))
    }

    @Test fun tenthNeedsTwoAgreeingSingleDigitReads() {
        assertEquals(2, pickTenth(listOf("2", "2", "")))
        assertEquals(2, pickTenth(listOf("2", "2", "7")))
        assertNull(pickTenth(listOf("2", "7", "")))          // tidak ada yang disepakati
        assertNull(pickTenth(listOf("2", "2", "7", "7")))    // seri
        assertNull(pickTenth(listOf("23", "23")))            // dua digit = drum sebelah ikut terbaca
        assertNull(pickTenth(emptyList()))
    }

    @Test fun oneTenthDigitIsSixMinutes() {
        assertEquals(12, tenthMinutes(2)); assertEquals(0, tenthMinutes(0)); assertEquals(54, tenthMinutes(9))
    }

    @Test fun commaSitsRightAfterTheLastCountedDrum() {
        assertEquals("00991", composeDrumValue("00991", 2))             // bawaan: 1/10 tidak dijumlahkan
        assertEquals("991.2", composeDrumValue("00991", 2, true))
        assertEquals("0.5", composeDrumValue("00000", 5, true))
        assertEquals("991", composeDrumValue("00991", null, true))      // digit 1/10 tidak yakin: tanpa desimal
    }

    @Test fun infoTextMentionsMinutesAndThatHmIsUnchanged() {
        val t = tenthInfo(2)
        assertTrue(t.contains("12 menit")); assertTrue(t.contains("Tidak ditambahkan"))
    }
}
