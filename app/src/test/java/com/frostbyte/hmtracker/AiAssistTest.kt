package com.frostbyte.hmtracker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAssistTest {
    private fun reply(text: String) =
        """{"candidates":[{"content":{"parts":[{"text":${JSONObject.quote(text)}}]}}]}"""

    @Test fun balasanAngkaDiterima() {
        assertEquals("01058", AiAssist.parseReply(reply("01058")))
        assertEquals("1041.5", AiAssist.parseReply(reply(" 1041.5\n")))
        assertEquals("1041.5", AiAssist.parseReply(reply("1041,5")))
    }

    @Test fun balasanBukanAngkaDitolak() {
        assertNull(AiAssist.parseReply(reply("TIDAK")))
        assertNull(AiAssist.parseReply(reply("Angkanya 1041 jam")))
        assertNull(AiAssist.parseReply(reply("1.041,5")))
        assertNull(AiAssist.parseReply(reply("10.55")))
        assertNull(AiAssist.parseReply("""{"candidates":[]}"""))
        assertNull(AiAssist.parseReply("{}"))
    }

    @Test fun badanPermintaanBerisiTeksDanGambar() {
        val o = JSONObject(AiAssist.buildBody("QUJD"))
        val parts = o.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(AiAssist.PROMPT, parts.getJSONObject(0).getString("text"))
        val img = parts.getJSONObject(1).getJSONObject("inline_data")
        assertEquals("image/jpeg", img.getString("mime_type"))
        assertEquals("QUJD", img.getString("data"))
        assertTrue(!o.toString().contains("api"))      // kunci tidak pernah ada di badan permintaan
    }
}
