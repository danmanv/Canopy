package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class ScreensaverTest {
    @Test fun continueWatchingComesFirstThenMyListOnceEach() {
        val picks = saverPicks(listOf("tt3", "tt1"), listOf("tt1", "tt2", "kitsu:9", "tt4"), Random(1))
        assertEquals(listOf("tt3", "tt1"), picks.take(2))
        assertEquals(setOf("tt1", "tt2", "tt3", "tt4"), picks.toSet())
        assertEquals(4, picks.size)
    }

    @Test fun onlyTheMostRecentAndAtMostSoMany() {
        val watching = (1..20).map { "tt$it" }
        assertEquals(watching.take(12), saverPicks(watching, emptyList(), Random(1)))
        assertEquals(5, saverPicks(watching, (30..90).map { "tt$it" }, Random(1), most = 5).size)
    }

    @Test fun episodeFromTheVideoId() {
        assertEquals("S2 · E5", episodeLabel("tt0944947:2:5"))
        assertNull(episodeLabel("tt0111161"))
        assertNull(episodeLabel(null))
    }
}
