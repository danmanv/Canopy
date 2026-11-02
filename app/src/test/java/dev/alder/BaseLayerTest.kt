package dev.alder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseLayerTest {
    @Test fun spotsProfile7() {
        assertTrue(BaseLayerVideoRenderer.isProfile7("dvhe.07.06"))
        assertTrue(BaseLayerVideoRenderer.isProfile7("dvh1.07.06"))
        assertFalse(BaseLayerVideoRenderer.isProfile7("dvhe.08.06"))
        assertFalse(BaseLayerVideoRenderer.isProfile7("dvhe.05.06"))
        assertFalse(BaseLayerVideoRenderer.isProfile7("hvc1.2.4.L153"))
        assertFalse(BaseLayerVideoRenderer.isProfile7(null))
    }
}
