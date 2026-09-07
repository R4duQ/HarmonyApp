package com.harmony.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryTest {

    @Test
    fun `every token is a prefix term`() {
        assertEquals("the* bea*", FtsQuery.sanitize("the bea"))
        assertEquals("dra* tei*", FtsQuery.sanitize("dra tei"))
    }

    /**
     * The regression this class exists for.
     *
     * The old sanitizer quoted each token and put the star outside the
     * quotes ("bea"*), which FTS4 reads as the exact phrase "bea" with a
     * stray star. Every intermediate keystroke matched nothing and results
     * only appeared once a whole word had been typed. Each partial word
     * below must stay able to reach a longer one.
     */
    @Test
    fun `partial words stay prefix-searchable at every keystroke`() {
        val expected = listOf("b*", "be*", "bea*", "beat*", "beatl*", "beatle*", "beatles*")
        val actual = (1.."beatles".length).map { FtsQuery.sanitize("beatles".take(it)) }
        assertEquals(expected, actual)
    }

    /**
     * MATCH operator characters are removed, not escaped, so they split the
     * input the same way the FTS tokenizer would rather than throwing.
     */
    @Test
    fun `operator characters are stripped and split the input`() {
        assertEquals("o* zone*", FtsQuery.sanitize("o-zone"))
        assertEquals("o* zone*", FtsQuery.sanitize("\"o*zone\""))
    }

    /**
     * AND / OR / NOT / NEAR are only operators as bare words. The trailing
     * star every token carries makes them ordinary prefix terms, so a
     * library with "Orion" or "Nothing Else Matters" stays searchable.
     */
    @Test
    fun `fts keywords become ordinary prefix terms`() {
        assertEquals("OR*", FtsQuery.sanitize("OR"))
        assertEquals("NEAR*", FtsQuery.sanitize("NEAR"))
        assertEquals("and* justice*", FtsQuery.sanitize("and justice"))
    }

    @Test
    fun `blank and operator-only input yields no query`() {
        assertNull(FtsQuery.sanitize(""))
        assertNull(FtsQuery.sanitize("   "))
        assertNull(FtsQuery.sanitize("\"\"*"))
    }
}
