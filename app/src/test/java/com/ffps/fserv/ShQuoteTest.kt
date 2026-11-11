package com.ffps.fserv

import org.junit.Assert.assertEquals
import org.junit.Test

class ShQuoteTest {
    @Test fun valuesAreSingleQuoted() {
        assertEquals("'192.168.1.1'", Sh.quote("192.168.1.1"))
        assertEquals("''", Sh.quote(""))
        assertEquals("'a b; rm -rf /'", Sh.quote("a b; rm -rf /"))
        assertEquals("'it'\\''s'", Sh.quote("it's"))
        assertEquals("'$(reboot) `id`'", Sh.quote("$(reboot) `id`"))
    }
}
