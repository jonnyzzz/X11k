package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class XXInputProtocolTest {
    @Test
    fun `XInputExtension reports version and minimal core device inventories`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(queryExtensionRequest("XInputExtension"))
                out.write(listExtensionsRequest())
                out.write(xinputGetExtensionVersionRequest("XInputExtension"))
                out.write(request(XXInput.MajorOpcode, XXInput.ListInputDevices, ByteArray(0)))
                out.write(xinputXiQueryVersionRequest(2, 4))
                out.write(xinputXiQueryVersionRequest(2, 2))
                out.write(xinputXiQueryDeviceRequest(0))
                out.write(xinputXiListPropertiesRequest(0))
                out.write(xinputXiGetPropertyRequest(XXInput.MasterPointerId, property = 1))
                out.write(queryPointerRequest())
                out.flush()

                val extension = readReply(socket.getInputStream())
                assertEquals(1, extension[8].toInt() and 0xff)
                assertEquals(XXInput.MajorOpcode, extension[9].toInt() and 0xff)
                assertEquals(XXInput.FirstEvent, extension[10].toInt() and 0xff)
                assertEquals(XXInput.FirstError, extension[11].toInt() and 0xff)

                val extensions = extensionNames(readReply(socket.getInputStream()))
                assertContains(extensions, "XInputExtension")

                val legacyVersion = readReply(socket.getInputStream())
                assertEquals(XXInput.GetExtensionVersion, legacyVersion[1].toInt() and 0xff)
                assertEquals(3, u16le(legacyVersion, 2))
                assertEquals(XXInput.MajorVersion, u16le(legacyVersion, 8))
                assertEquals(XXInput.MinorVersion, u16le(legacyVersion, 10))
                assertEquals(1, legacyVersion[12].toInt() and 0xff)

                val legacyDevices = readReply(socket.getInputStream())
                assertEquals(XXInput.ListInputDevices, legacyDevices[1].toInt() and 0xff)
                assertEquals(4, u16le(legacyDevices, 2))
                assertEquals(18, u32le(legacyDevices, 4))
                assertEquals(2, legacyDevices[8].toInt() and 0xff)
                assertLegacyDeviceList(legacyDevices)

                val xiVersion = readReply(socket.getInputStream())
                assertEquals(XXInput.XIQueryVersion, xiVersion[1].toInt() and 0xff)
                assertEquals(5, u16le(xiVersion, 2))
                assertEquals(XXInput.MajorVersion, u16le(xiVersion, 8))
                assertEquals(XXInput.MinorVersion, u16le(xiVersion, 10))

                val xi22Version = readReply(socket.getInputStream())
                assertEquals(XXInput.XIQueryVersion, xi22Version[1].toInt() and 0xff)
                assertEquals(6, u16le(xi22Version, 2))
                assertEquals(XXInput.MajorVersion, u16le(xi22Version, 8))
                assertEquals(2, u16le(xi22Version, 10))

                val xiDevices = readReply(socket.getInputStream())
                assertEquals(XXInput.XIQueryDevice, xiDevices[1].toInt() and 0xff)
                assertEquals(7, u16le(xiDevices, 2))
                assertEquals(2, u16le(xiDevices, 8))
                assertXi2DeviceList(xiDevices)

                val properties = readReply(socket.getInputStream())
                assertEquals(XXInput.XIListProperties, properties[1].toInt() and 0xff)
                assertEquals(8, u16le(properties, 2))
                assertEquals(0, u16le(properties, 8))

                val property = readReply(socket.getInputStream())
                assertEquals(XXInput.XIGetProperty, property[1].toInt() and 0xff)
                assertEquals(9, u16le(property, 2))
                assertEquals(0, u32le(property, 4))
                assertEquals(0, u32le(property, 8))
                assertEquals(0, u32le(property, 12))
                assertEquals(0, u32le(property, 16))
                assertEquals(0, property[20].toInt() and 0xff)

                val pointer = readReply(socket.getInputStream())
                assertEquals(10, u16le(pointer, 2))

                val text = httpGet(server.localPort, "/text.txt")
                assertContains(text, "XInputExtension supported=true")
                assertFalse(text.contains("XInputExtension.${XXInput.operationName(XXInput.XIQueryVersion)} opcode="), text)
                assertFalse(text.contains("XInputExtension.${XXInput.operationName(XXInput.XIGetProperty)} opcode="), text)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XInputExtension validates XI2 selection framing and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(XXInput.MajorOpcode, XXInput.XIQueryVersion, ByteArray(0)))
                out.write(xinputXiQueryVersionRequest(1, 5))
                out.write(xinputXiSelectEventsRequest(0x0102_0304, emptyList()))
                out.write(request(XXInput.MajorOpcode, XXInput.XISelectEvents, u32leBytes(X11Ids.RootWindow)))
                out.write(xinputXiSelectEventsRequest(X11Ids.RootWindow, emptyList()))
                out.write(xinputXiSelectEventsRequest(X11Ids.RootWindow, listOf(0 to byteArrayOf(0x01, 0x00, 0x00, 0x00))))
                out.write(request(XXInput.MajorOpcode, XXInput.XIGetSelectedEvents, ByteArray(0)))
                out.write(xinputXiGetSelectedEventsRequest(0x0102_0304))
                out.write(xinputXiGetSelectedEventsRequest(X11Ids.RootWindow))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, sequence = 1, minorOpcode = XXInput.XIQueryVersion)
                assertError(socket.getInputStream(), error = 2, badValue = 1, sequence = 2, minorOpcode = XXInput.XIQueryVersion)

                assertError(socket.getInputStream(), error = 3, badValue = 0x0102_0304, sequence = 3, minorOpcode = XXInput.XISelectEvents)
                assertError(socket.getInputStream(), error = 16, sequence = 4, minorOpcode = XXInput.XISelectEvents)
                assertError(socket.getInputStream(), error = 2, sequence = 5, minorOpcode = XXInput.XISelectEvents)

                assertError(socket.getInputStream(), error = 16, sequence = 7, minorOpcode = XXInput.XIGetSelectedEvents)
                assertError(socket.getInputStream(), error = 3, badValue = 0x0102_0304, sequence = 8, minorOpcode = XXInput.XIGetSelectedEvents)

                val selected = readReply(socket.getInputStream())
                assertEquals(9, u16le(selected, 2))
                assertEquals(XXInput.XIGetSelectedEvents, selected[1].toInt() and 0xff)
                assertEquals(0, u16le(selected, 8))

                val pointer = readReply(socket.getInputStream())
                assertEquals(10, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun setup(socket: Socket) {
        socket.getOutputStream().write(byteArrayOf(0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        socket.getOutputStream().flush()
        val prefix = socket.getInputStream().readExactly(8)
        assertEquals(1, prefix[0].toInt())
        socket.getInputStream().readExactly(u16le(prefix, 6) * 4)
    }

    private fun queryExtensionRequest(name: String): ByteArray {
        val encoded = name.encodeToByteArray()
        val padded = (encoded.size + 3) and -4
        val body = ByteArray(4 + padded)
        put16le(body, 0, encoded.size)
        encoded.copyInto(body, 4)
        return request(98, 0, body)
    }

    private fun listExtensionsRequest(): ByteArray =
        request(99, 0, ByteArray(0))

    private fun xinputGetExtensionVersionRequest(name: String): ByteArray {
        val encoded = name.encodeToByteArray()
        val padded = (encoded.size + 3) and -4
        val body = ByteArray(4 + padded)
        put16le(body, 0, encoded.size)
        encoded.copyInto(body, 4)
        return request(XXInput.MajorOpcode, XXInput.GetExtensionVersion, body)
    }

    private fun xinputXiQueryVersionRequest(major: Int, minor: Int): ByteArray {
        val body = ByteArray(4)
        put16le(body, 0, major)
        put16le(body, 2, minor)
        return request(XXInput.MajorOpcode, XXInput.XIQueryVersion, body)
    }

    private fun xinputXiQueryDeviceRequest(deviceId: Int): ByteArray {
        val body = ByteArray(4)
        put16le(body, 0, deviceId)
        return request(XXInput.MajorOpcode, XXInput.XIQueryDevice, body)
    }

    private fun xinputXiListPropertiesRequest(deviceId: Int): ByteArray {
        val body = ByteArray(4)
        put16le(body, 0, deviceId)
        return request(XXInput.MajorOpcode, XXInput.XIListProperties, body)
    }

    private fun xinputXiGetPropertyRequest(deviceId: Int, property: Int, delete: Int = 0, type: Int = 0, offset: Int = 0, length: Int = 0): ByteArray {
        val body = ByteArray(20)
        put16le(body, 0, deviceId)
        body[2] = delete.toByte()
        put32le(body, 4, property)
        put32le(body, 8, type)
        put32le(body, 12, offset)
        put32le(body, 16, length)
        return request(XXInput.MajorOpcode, XXInput.XIGetProperty, body)
    }

    private fun xinputXiSelectEventsRequest(windowId: Int, masks: List<Pair<Int, ByteArray>>): ByteArray {
        val maskBytes = masks.sumOf { 4 + ((it.second.size + 3) and -4) }
        val body = ByteArray(8 + maskBytes)
        put32le(body, 0, windowId)
        put16le(body, 4, masks.size)
        var offset = 8
        for ((deviceId, mask) in masks) {
            val padded = (mask.size + 3) and -4
            put16le(body, offset, deviceId)
            put16le(body, offset + 2, padded / 4)
            mask.copyInto(body, offset + 4)
            offset += 4 + padded
        }
        return request(XXInput.MajorOpcode, XXInput.XISelectEvents, body)
    }

    private fun xinputXiGetSelectedEventsRequest(windowId: Int): ByteArray =
        request(XXInput.MajorOpcode, XXInput.XIGetSelectedEvents, u32leBytes(windowId))

    private fun queryPointerRequest(): ByteArray =
        request(38, 0, u32leBytes(X11Ids.RootWindow))

    private fun request(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16le(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun assertError(input: InputStream, error: Int, sequence: Int, minorOpcode: Int, badValue: Int = 0) {
        val bytes = input.readExactly(32)
        assertEquals(0, bytes[0].toInt() and 0xff)
        assertEquals(error, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16le(bytes, 2))
        assertEquals(badValue, u32le(bytes, 4))
        assertEquals(minorOpcode, u16le(bytes, 8))
        assertEquals(XXInput.MajorOpcode, bytes[10].toInt() and 0xff)
    }

    private fun readReply(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        assertEquals(1, header[0].toInt() and 0xff)
        val extra = u32le(header, 4) * 4
        return if (extra == 0) header else header + input.readExactly(extra)
    }

    private fun assertLegacyDeviceList(reply: ByteArray) {
        val pointerOffset = 32
        val keyboardOffset = 40
        assertEquals(XXInput.MasterPointerId, reply[pointerOffset + 4].toInt() and 0xff)
        assertEquals(1, reply[pointerOffset + 5].toInt() and 0xff)
        assertEquals(XXInput.IsXPointer, reply[pointerOffset + 6].toInt() and 0xff)
        assertEquals(XXInput.MasterKeyboardId, reply[keyboardOffset + 4].toInt() and 0xff)
        assertEquals(1, reply[keyboardOffset + 5].toInt() and 0xff)
        assertEquals(XXInput.IsXKeyboard, reply[keyboardOffset + 6].toInt() and 0xff)

        val buttonOffset = 48
        assertEquals(XXInput.ButtonClass, reply[buttonOffset].toInt() and 0xff)
        assertEquals(4, reply[buttonOffset + 1].toInt() and 0xff)
        assertEquals(255, u16le(reply, buttonOffset + 2))

        val keyOffset = 52
        assertEquals(XXInput.KeyClass, reply[keyOffset].toInt() and 0xff)
        assertEquals(8, reply[keyOffset + 1].toInt() and 0xff)
        assertEquals(XKeyboard.MinKeycode, reply[keyOffset + 2].toInt() and 0xff)
        assertEquals(XKeyboard.MaxKeycode, reply[keyOffset + 3].toInt() and 0xff)
        assertEquals(XKeyboard.MaxKeycode - XKeyboard.MinKeycode + 1, u16le(reply, keyOffset + 4))

        val namesOffset = 60
        val pointerNameLength = reply[namesOffset].toInt() and 0xff
        assertEquals("Virtual core pointer", reply.copyOfRange(namesOffset + 1, namesOffset + 1 + pointerNameLength).decodeToString())
        val keyboardNameOffset = namesOffset + 1 + pointerNameLength
        val keyboardNameLength = reply[keyboardNameOffset].toInt() and 0xff
        assertEquals("Virtual core keyboard", reply.copyOfRange(keyboardNameOffset + 1, keyboardNameOffset + 1 + keyboardNameLength).decodeToString())
    }

    private fun assertXi2DeviceList(reply: ByteArray) {
        var offset = 32
        assertEquals(XXInput.MasterPointerId, u16le(reply, offset))
        assertEquals(XXInput.XIMasterPointer, u16le(reply, offset + 2))
        assertEquals(XXInput.MasterKeyboardId, u16le(reply, offset + 4))
        assertEquals(1, u16le(reply, offset + 6))
        val pointerNameLength = u16le(reply, offset + 8)
        assertEquals(1, reply[offset + 10].toInt() and 0xff)
        assertEquals("Virtual core pointer", reply.copyOfRange(offset + 12, offset + 12 + pointerNameLength).decodeToString())
        var classOffset = offset + 12 + padded(pointerNameLength)
        assertEquals(XXInput.XIButtonClass, u16le(reply, classOffset))
        val buttonClassUnits = u16le(reply, classOffset + 2)
        assertEquals(XXInput.MasterPointerId, u16le(reply, classOffset + 4))
        assertEquals(255, u16le(reply, classOffset + 6))

        offset = classOffset + buttonClassUnits * 4
        assertEquals(XXInput.MasterKeyboardId, u16le(reply, offset))
        assertEquals(XXInput.XIMasterKeyboard, u16le(reply, offset + 2))
        assertEquals(XXInput.MasterPointerId, u16le(reply, offset + 4))
        assertEquals(1, u16le(reply, offset + 6))
        val keyboardNameLength = u16le(reply, offset + 8)
        assertEquals(1, reply[offset + 10].toInt() and 0xff)
        assertEquals("Virtual core keyboard", reply.copyOfRange(offset + 12, offset + 12 + keyboardNameLength).decodeToString())
        classOffset = offset + 12 + padded(keyboardNameLength)
        assertEquals(XXInput.XIKeyClass, u16le(reply, classOffset))
        val keyClassUnits = u16le(reply, classOffset + 2)
        assertEquals(XXInput.MasterKeyboardId, u16le(reply, classOffset + 4))
        assertEquals(XKeyboard.MaxKeycode - XKeyboard.MinKeycode + 1, u16le(reply, classOffset + 6))
        assertEquals(XKeyboard.MinKeycode, u32le(reply, classOffset + 8))
        assertEquals(XKeyboard.MaxKeycode, u32le(reply, classOffset + 8 + (XKeyboard.MaxKeycode - XKeyboard.MinKeycode) * 4))
        assertEquals(reply.size, classOffset + keyClassUnits * 4)
    }

    private fun padded(size: Int): Int =
        (size + 3) and -4

    private fun httpGet(port: Int, path: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().decodeToString().substringAfter("\r\n\r\n")
        }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            if (read < 0) error("EOF")
            offset += read
        }
        return bytes
    }

    private fun extensionNames(reply: ByteArray): List<String> {
        val count = reply[1].toInt() and 0xff
        val names = mutableListOf<String>()
        var offset = 32
        repeat(count) {
            val length = reply[offset++].toInt() and 0xff
            names += reply.copyOfRange(offset, offset + length).decodeToString()
            offset += length
        }
        return names
    }

    private fun u32leBytes(value: Int): ByteArray {
        val bytes = ByteArray(4)
        put32le(bytes, 0, value)
        return bytes
    }

    private fun put16le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32le(bytes: ByteArray, offset: Int, value: Int) {
        put16le(bytes, offset, value)
        put16le(bytes, offset + 2, value ushr 16)
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        u16le(bytes, offset) or (u16le(bytes, offset + 2) shl 16)
}
