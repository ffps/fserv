package com.ffps.fserv

import org.junit.Assert.*
import org.junit.Test

class LevelGateTest {
    private fun c(v: Int) = ShortArray(4) { v.toShort() }

    @Test fun zeroThresholdPassesEverything() {
        val g = LevelGate(3, 2)
        for (i in 1..5) assertEquals(1, g.push(c(i), 0, 0).size)
        assertFalse(g.isOpen)
    }

    @Test fun silenceIsDropped() {
        val g = LevelGate(3, 2)
        for (i in 1..10) assertTrue(g.push(c(i), 10, 100).isEmpty())
        assertFalse(g.isOpen)
    }

    @Test fun loudOpensWithPreRollAndHang() {
        val g = LevelGate(3, 2)
        g.push(c(1), 10, 100); g.push(c(2), 10, 100); g.push(c(3), 10, 100) // в запасе последние два: 2 и 3
        val out = g.push(c(9), 500, 100)
        assertEquals(listOf(2, 3, 9), out.map { it[0].toInt() })
        assertTrue(g.isOpen)
        // после громкого куска ещё hang=3 тихих уходят распознавателю, потом ворота закрываются
        assertEquals(1, g.push(c(4), 10, 100).size); assertFalse(g.closedNow)
        assertEquals(1, g.push(c(5), 10, 100).size); assertFalse(g.closedNow)
        assertEquals(1, g.push(c(6), 10, 100).size); assertTrue(g.closedNow)
        assertFalse(g.isOpen)
        assertTrue(g.push(c(7), 10, 100).isEmpty())
        assertFalse(g.closedNow)
    }

    @Test fun loudInsideHangExtendsAndHasNoPreRoll() {
        val g = LevelGate(3, 2)
        g.push(c(1), 500, 100)
        g.push(c(2), 10, 100)
        val out = g.push(c(3), 500, 100) // ворота открыты: только сам кусок, без запаса
        assertEquals(listOf(3), out.map { it[0].toInt() })
        for (i in 4..5) g.push(c(i), 10, 100)
        assertTrue(g.isOpen)
        g.push(c(6), 10, 100)
        assertFalse(g.isOpen)
    }

    @Test fun preRollIsCopiedNotShared() {
        val g = LevelGate(3, 2)
        val buf = c(1)
        g.push(buf, 10, 100)
        buf.fill(7) // читающий поток переиспользует буфер
        val out = g.push(c(9), 500, 100)
        assertEquals(1, out[0][0].toInt())
    }

    @Test fun resetForgetsEverything() {
        val g = LevelGate(3, 2)
        g.push(c(1), 10, 100)
        g.push(c(2), 500, 100)
        g.reset()
        assertFalse(g.isOpen)
        assertEquals(listOf(5), g.push(c(5), 500, 100).map { it[0].toInt() })
    }
}
