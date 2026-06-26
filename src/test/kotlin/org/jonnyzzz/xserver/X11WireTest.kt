package org.jonnyzzz.xserver

import kotlin.test.Test
import kotlin.test.assertEquals

class X11WireTest {
    @Test
    fun `valueListU8 reads least significant byte for both byte orders`() {
        assertEquals(0x12, ByteOrder.LsbFirst.valueListU8(byteArrayOf(0x12, 0x34, 0x56, 0x78), 0))
        assertEquals(0x78, ByteOrder.MsbFirst.valueListU8(byteArrayOf(0x12, 0x34, 0x56, 0x78), 0))
    }
}
