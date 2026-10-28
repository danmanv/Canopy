package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRowsTest {
    @Test fun defaultsLeadAndOnlyTheyShowUntilSaved() {
        assertEquals(listOf("w1" to true, "w2" to true, "pop" to false, "top" to false),
            arrangeHomeRows(listOf("pop", "w1", "top", "w2"), setOf("w1", "w2"), null))
    }

    @Test fun savedOrderWinsAndNewCatalogsGoLast() {
        val saved = listOf("top" to true, "gone" to true, "w1" to false)
        assertEquals(listOf("top" to true, "w1" to false, "pop" to false, "w2" to true),
            arrangeHomeRows(listOf("pop", "w1", "top", "w2"), setOf("w1", "w2"), saved))
    }

    @Test fun tonightKeepsPinOrderAndLeavesTheRest() {
        val (tonight, rest) = splitTonight(listOf("a", "b", "c", "d"), { it }, listOf("c", "x", "a"))
        assertEquals(listOf("c", "a"), tonight)
        assertEquals(listOf("b", "d"), rest)
    }

    @Test fun movesStopAtTheEnds() {
        assertEquals(listOf("b", "a", "c"), moved(listOf("a", "b", "c"), 1, -1))
        assertEquals(listOf("a", "b", "c"), moved(listOf("a", "b", "c"), 0, -1))
        assertEquals(listOf("c", "a", "b"), moved(listOf("a", "b", "c"), 2, -2))
        assertEquals(listOf("a", "c", "b"), moved(listOf("a", "b", "c"), 1, 5))
    }
}
