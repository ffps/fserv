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

    private fun u(vararg peaks: Int): Utterance { val x = Utterance(); for (p in peaks) x.add(ShortArray(4) { p.toShort() }); return x }

    @Test fun utteranceFindsSpeech() {
        val x = u(50, 60, 55, 5000, 6000, 5500, 40, 45)
        assertEquals(3, x.firstLoud())
        assertTrue(x.loudAfter(5))
        assertFalse(x.loudAfter(6))
        assertEquals(2, x.trailingSilence())
    }

    @Test fun utteranceTrimKeepsPreRoll() {
        val x = u(50, 60, 55, 50, 5000, 6000, 40)
        assertTrue(x.trimToSpeech(2))
        assertEquals(5, x.size)
        assertEquals(2, x.firstLoud())
        assertEquals(5, x.all().size)
    }

    @Test fun utteranceNoSpeechAndCap() {
        assertFalse(u(50, 52, 49, 51).trimToSpeech())
        assertEquals(-1, u(50, 52, 49, 51).firstLoud())
        val x = Utterance(3)
        for (p in 1..5) x.add(ShortArray(2) { p.toShort() })
        assertEquals(listOf(3, 4, 5), x.all().map { it[0].toInt() })
        x.clear(); assertEquals(0, x.size); assertEquals(-1, x.firstLoud())
    }

    @Test fun utteranceCopiesChunks() {
        val x = Utterance()
        val b = ShortArray(2) { 9 }
        x.add(b); b.fill(1)
        assertEquals(9, x.all()[0][0].toInt())
    }
}
