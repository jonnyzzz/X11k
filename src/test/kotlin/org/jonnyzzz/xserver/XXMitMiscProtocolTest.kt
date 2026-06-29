package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class XXMitMiscProtocolTest {
    @Test
    fun `MIT-MISC reports extension accepts requests and keeps bug mode disabled`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(queryExtensionRequest("MIT-SUNDRY-NONSTANDARD"))
                out.write(queryExtensionRequest("MIT-MISC"))
                out.write(listExtensionsRequest())
                out.write(request(XXMitMisc.MajorOpcode, XXMitMisc.GetBugMode, u32(0)))
                out.write(request(XXMitMisc.MajorOpcode, XXMitMisc.GetBugMode, ByteArray(0)))
                out.write(request(XXMitMisc.MajorOpcode, XXMitMisc.SetBugMode, byteArrayOf(1, 0, 0, 0)))
                out.write(request(XXMitMisc.MajorOpcode, XXMitMisc.GetBugMode, ByteArray(0)))
                out.write(request(XXMitMisc.MajorOpcode, XXMitMisc.SetBugMode, byteArrayOf(2, 0, 0, 0)))
                out.write(request(XXMitMisc.MajorOpcode, XXMitMisc.SetBugMode, ByteArray(0)))
                out.write(request(XXMitMisc.MajorOpcode, XXMitMisc.SetBugMode, byteArrayOf(0, 0, 0, 0)))
                out.write(request(XXMitMisc.MajorOpcode, XXMitMisc.GetBugMode, ByteArray(0)))
                out.flush()

                val extension = readReply(socket.getInputStream())
                assertEquals(1, extension[8].toInt() and 0xff)
                assertEquals(XXMitMisc.MajorOpcode, extension[9].toInt() and 0xff)
                assertEquals(XXMitMisc.FirstEvent, extension[10].toInt() and 0xff)
                assertEquals(XXMitMisc.FirstError, extension[11].toInt() and 0xff)

                val alias = readReply(socket.getInputStream())
                assertEquals(1, alias[8].toInt() and 0xff)
                assertEquals(XXMitMisc.MajorOpcode, alias[9].toInt() and 0xff)

                val extensions = readReply(socket.getInputStream())
                assertContains(extensionNames(extensions), "MIT-SUNDRY-NONSTANDARD")

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 4, minorOpcode = XXMitMisc.GetBugMode)

                val initialMode = readReply(socket.getInputStream())
                assertEquals(5, u16le(initialMode, 2))
                assertEquals(0, initialMode[1].toInt() and 0xff)

                val enabledMode = readReply(socket.getInputStream())
                assertEquals(7, u16le(enabledMode, 2))
                assertEquals(0, enabledMode[1].toInt() and 0xff)

                assertError(socket.getInputStream(), error = 2, badValue = 2, sequence = 8, minorOpcode = XXMitMisc.SetBugMode)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 9, minorOpcode = XXMitMisc.SetBugMode)

                val disabledMode = readReply(socket.getInputStream())
                assertEquals(11, u16le(disabledMode, 2))
                assertEquals(0, disabledMode[1].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `MIT-MISC swaps replies for big endian clients`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket, byteOrderByte = 0x42)
                val out = socket.getOutputStream()
                out.write(requestBe(XXMitMisc.MajorOpcode, XXMitMisc.SetBugMode, byteArrayOf(1, 0, 0, 0)))
                out.write(requestBe(XXMitMisc.MajorOpcode, XXMitMisc.GetBugMode, ByteArray(0)))
                out.flush()

                val enabledMode = readReply(socket.getInputStream(), byteOrderByte = 0x42)
                assertEquals(2, u16be(enabledMode, 2))
                assertEquals(0, u32be(enabledMode, 4))
                assertEquals(0, enabledMode[1].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun setup(socket: Socket, byteOrderByte: Int = 0x6c) {
        val setup = ByteArray(12)
        setup[0] = byteOrderByte.toByte()
        when (byteOrderByte) {
            0x42 -> put16be(setup, 2, 11)
            else -> put16le(setup, 2, 11)
        }
        socket.getOutputStream().write(setup)
        socket.getOutputStream().flush()
        val prefix = socket.getInputStream().readExactly(8)
        assertEquals(1, prefix[0].toInt())
        val payloadUnits = if (byteOrderByte == 0x42) u16be(prefix, 6) else u16le(prefix, 6)
        socket.getInputStream().readExactly(payloadUnits * 4)
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

    private fun request(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16le(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun requestBe(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16be(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun readReply(input: InputStream, byteOrderByte: Int = 0x6c): ByteArray {
        val header = input.readExactly(32)
        if (header[0].toInt() != 1) return header
        val payloadUnits = if (byteOrderByte == 0x42) u32be(header, 4) else u32le(header, 4)
        val extra = payloadUnits * 4
        return header + input.readExactly(extra)
    }

    private fun assertError(input: InputStream, error: Int, badValue: Int, sequence: Int, minorOpcode: Int) {
        val reply = input.readExactly(32)
        assertEquals(0, reply[0].toInt())
        assertEquals(error, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(badValue, u32le(reply, 4))
        assertEquals(minorOpcode, u16le(reply, 8))
        assertEquals(XXMitMisc.MajorOpcode, reply[10].toInt() and 0xff)
    }

    private fun extensionNames(reply: ByteArray): List<String> {
        val names = mutableListOf<String>()
        var offset = 32
        repeat(reply[1].toInt() and 0xff) {
            val length = reply[offset++].toInt() and 0xff
            names += reply.copyOfRange(offset, offset + length).decodeToString()
            offset += length
        }
        return names
    }

    private fun u32(value: Int): ByteArray =
        ByteArray(4).also { put32le(it, 0, value) }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun u16be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun u32be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun put16le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun put16be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            if (read < 0) error("EOF after $offset of $size bytes")
            offset += read
        }
        return bytes
    }
}
