package com.ffps.fserv

import org.junit.Assert.*
import org.junit.Test

class BrightnessTest {
    @Test fun priorities() {
        // ничего не задано: системная
        assertNull(Brightness.effective(false, false, null, null, null))
        // киоск
        assertEquals(20, Brightness.effective(false, false, null, 20, null))
        // слайд сильнее киоска
        assertEquals(70, Brightness.effective(false, false, 70, 20, null))
        // звонок: своя, иначе киоска (слайд под окном звонка не учитывается)
        assertEquals(90, Brightness.effective(false, true, 70, 20, 90))
        assertEquals(20, Brightness.effective(false, true, 70, 20, null))
        assertNull(Brightness.effective(false, true, 70, null, null))
        // вспышка сильнее всего
        assertEquals(100, Brightness.effective(true, true, 70, 20, 90))
        assertEquals(100, Brightness.effective(true, false, null, null, null))
    }

    @Test fun values() {
        assertTrue(Brightness.valid(null)); assertTrue(Brightness.valid("")); assertTrue(Brightness.valid(org.json.JSONObject.NULL))
        assertTrue(Brightness.valid(1)); assertTrue(Brightness.valid(100)); assertTrue(Brightness.valid("50")); assertTrue(Brightness.valid(30.0))
        assertFalse(Brightness.valid(0)); assertFalse(Brightness.valid(101)); assertFalse(Brightness.valid("abc")); assertFalse(Brightness.valid(5.5)); assertFalse(Brightness.valid(true))
        assertEquals(50, Brightness.toInt("50")); assertNull(Brightness.toInt("")); assertNull(Brightness.toInt(null))
    }
}
