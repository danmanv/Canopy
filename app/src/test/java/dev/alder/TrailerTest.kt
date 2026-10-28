package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerTest {
    @Test fun findsTheYouTubeId() {
        assertEquals("dQw4w9WgXcQ", youTubeId("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youTubeId(" dQw4w9WgXcQ "))
        assertEquals("dQw4w9WgXcQ", youTubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5"))
        assertEquals("dQw4w9WgXcQ", youTubeId("https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youTubeId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("a-b_c1234XY", youTubeId("https://www.youtube.com/embed/a-b_c1234XY?rel=0"))
    }

    @Test fun refusesWhatIsNotAnId() {
        assertNull(youTubeId(null))
        assertNull(youTubeId(""))
        assertNull(youTubeId("short"))
        assertNull(youTubeId("dQw4w9WgXcQ'+alert(1)+'"))
        assertNull(youTubeId("https://vimeo.com/123456789"))
    }
}
