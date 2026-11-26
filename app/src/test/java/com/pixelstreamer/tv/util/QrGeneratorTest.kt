package com.pixelstreamer.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrGeneratorTest {

    @Test
    fun encodePixelBuffer_producesExpectedGridWithBlackAndWhiteModules() {
        val size = 120
        val pixels = QrGenerator.encodePixelBuffer("http://192.168.1.50:8765/?pin=4829", size)
        assertNotNull(pixels)
        assertEquals(size * size, pixels!!.size)
        assertTrue(pixels.contains(0xFF000000.toInt()))
        assertTrue(pixels.contains(0xFFFFFFFF.toInt()))
    }
}
