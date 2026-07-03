package org.jonnyzzz.xserver

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerOptionsTest {
    @Test
    fun `root background accepts common hex pixel forms`() {
        assertEquals(0x0000_0000, ServerOptions.parse(arrayOf("--root-background", "000000")).rootBackgroundPixel)
        assertEquals(0x0000_1122, ServerOptions.parse(arrayOf("--root-background", "0x001122")).rootBackgroundPixel)
        assertEquals(0x00aa_bbcc, ServerOptions.parse(arrayOf("--root-background", "#aabbcc")).rootBackgroundPixel)
    }
}
