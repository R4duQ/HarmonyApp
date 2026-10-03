package com.harmony.core.common.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagTextRepairTest {
    private fun fix(text: String, romanian: Boolean = false) = TagTextRepair.repair(text, romanian)

    @Test fun `windows-1250 read as latin-1 becomes Romanian again`() {
        assertEquals("Ștefan cel Mare", fix("ªtefan cel Mare"))
        assertEquals("București", fix("Bucureºti"))
        assertEquals("Țara mea", fix("Þara mea"))
        assertEquals("Și mă iubești", fix("ªi mã iubeºti"))
        assertEquals("Fără tine", fix("Fãrã tine", romanian = true))
        // î and â share their byte in both code pages, so they were never broken.
        assertEquals("Mâine în zori", fix("Mâine în zori"))
    }

    @Test fun `utf-8 read as latin-1 or windows-1252 becomes Romanian again`() {
        assertEquals("ștefan", fix("È™tefan"))           // Windows-1252 view of C8 99
        assertEquals("ștefan", fix("È\u0099tefan"))       // Latin-1 view of the same bytes
        assertEquals("Țară", fix("ÈšarÄƒ"))
        assertEquals("în", fix("Ã®n"))
        assertEquals("Mâine", fix("MÃ¢ine"))
    }

    @Test fun `the modern comma-below letters are used`() {
        val fixed = fix("ºi þi")!!
        assertEquals("și ți", fixed)
        assertFalse(fixed.any { it == 'ş' || it == 'ţ' })
    }

    @Test fun `correct text in any language is left alone`() {
        for (text in listOf(
            "Ștefan", "Țara mea", "Fără tine", "Mâine",          // already proper Romanian
            "São Paulo", "Coração", "Mãe",                        // Portuguese, even on a Romanian phone
            "1º de Maio", "Sinfonía Nº 5", "2ª Parte",            // ordinals
            "Þú og ég", "Ágætis byrjun",                          // Icelandic
            "Beyoncé", "Café del Mar", "Motörhead", "Björk",      // everyday accents
            "AC/DC", "",
        )) {
            assertEquals(text, fix(text, romanian = true))
        }
        assertEquals(null, TagTextRepair.repair(null))
    }

    @Test fun `ã alone needs a Romanian context`() {
        assertEquals("Mãi", fix("Mãi"))                       // could be anything on its own
        assertEquals("Măi", fix("Mãi", romanian = true))
    }

    @Test fun `strong markers are what the scanner uses to treat a file as Romanian`() {
        assertTrue(TagTextRepair.hasStrongMarker("Bucureºti"))
        assertTrue(TagTextRepair.hasStrongMarker("Þara"))
        assertFalse(TagTextRepair.hasStrongMarker("Mãi"))
        assertFalse(TagTextRepair.hasStrongMarker("1º de Maio"))
        assertFalse(TagTextRepair.hasStrongMarker("Ștefan"))
        assertFalse(TagTextRepair.hasStrongMarker(null))
    }
}
