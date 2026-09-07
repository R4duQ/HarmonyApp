package com.harmony.core.common.text

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTextNormalizerTest {

    @Test
    fun romanianDiacriticsCompareLikeBaseLetters() {
        assertEquals("fara tine", SearchTextNormalizer.foldForSearch("Fără Tine"))
        assertEquals("inima", SearchTextNormalizer.foldForSearch("ÎNIMĂ"))
        assertEquals("sapte vieti", SearchTextNormalizer.foldForSearch("Șapte Vieți"))
    }

    @Test
    fun legacyCedillaSpellingsAreFoldedToo() {
        assertEquals("sansa ta", SearchTextNormalizer.foldForSearch("Şansa Ta"))
        assertEquals("tara", SearchTextNormalizer.foldForSearch("Ţară"))
    }

    @Test
    fun spacingIsStableForSearchInput() {
        assertEquals("fara tine", SearchTextNormalizer.foldForSearch("  Fără   Tine  "))
    }
}
