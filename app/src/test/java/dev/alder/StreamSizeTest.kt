package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamSizeTest {
    @Test fun readsTheSizeAnAddonWrites() {
        assertEquals(12_400_000_000L, sizeIn("Movie.2023.2160p.WEB-DL\n👤 120 💾 12.4 GB ⚙️ ThePirateBay"))
        assertEquals(850_000_000L, sizeIn("💾 850 MB"))
        assertEquals(58_000_000_000L, sizeIn("REMUX 58,0 GiB"))
        assertEquals(1_200_000_000_000L, sizeIn("1.2 TB"))
    }

    @Test fun findsNoneWhereThereIsNone() {
        assertEquals(0L, sizeIn("Torrentio 4k DV | HDR"))
        assertEquals(0L, sizeIn("DDP5.1 Atmos 12 Mbps"))
        assertEquals(0L, sizeIn(""))
    }
}
