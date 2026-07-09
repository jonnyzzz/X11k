package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class XGenericEventProtocolTest {
    @Test
    fun `Generic Event Extension reports version and validates framing`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(queryExtensionRequest("Generic Event Extension"))
                out.write(listExtensionsRequest())
                out.write(request(XGenericEvent.MajorOpcode, XGenericEvent.QueryVersion, ByteArray(0)))
                out.write(xgeQueryVersionRequest(0, 0))
                out.write(xgeQueryVersionRequest(1, 2))
                out.write(xgeQueryVersionRequest(2, 0))
                out.flush()

                val extension = readReply(socket.getInputStream())
                assertEquals(1, extension[8].toInt() and 0xff)
                assertEquals(XGenericEvent.MajorOpcode, extension[9].toInt() and 0xff)
                assertEquals(XGenericEvent.FirstEvent, extension[10].toInt() and 0xff)
                assertEquals(XGenericEvent.FirstError, extension[11].toInt() and 0xff)

                val extensions = readReply(socket.getInputStream())
                assertContains(extensionNames(extensions), "Generic Event Extension")

                assertError(socket.getInputStream(), error = 16, opcode = XGenericEvent.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XGenericEvent.QueryVersion)
                assertError(socket.getInputStream(), error = 2, opcode = XGenericEvent.MajorOpcode, badValue = 0, sequence = 4, minorOpcode = XGenericEvent.QueryVersion)

                val current = readReply(socket.getInputStream())
                assertEquals(5, u16le(current, 2))
                assertEquals(0, u32le(current, 4))
                assertEquals(XGenericEvent.MajorVersion, u16le(current, 8))
                assertEquals(XGenericEvent.MinorVersion, u16le(current, 10))

                val future = readReply(socket.getInputStream())
                assertEquals(6, u16le(future, 2))
                assertEquals(XGenericEvent.MajorVersion, u16le(future, 8))
                assertEquals(XGenericEvent.MinorVersion, u16le(future, 10))

                val text = httpGet(server.localPort, "/text.txt")
                assertContains(text, "Generic Event Extension.QueryVersion")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun setup(socket: Socket) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        out.write(byteArrayOf(0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        out.flush()
        val prefix = input.readExactly(8)
        assertEquals(1, prefix[0].toInt())
        input.readExactly(u16le(prefix, 6) * 4)
    }

    private fun queryExtensionRequest(name: String): ByteArray {
        val encoded = name.encodeToByteArray()
        val body = ByteArray(4 + ((encoded.size + 3) and -4))
        put16le(body, 0, encoded.size)
        encoded.copyInto(body, 4)
        return request(98, 0, body)
    }

    private fun listExtensionsRequest(): ByteArray =
        request(99, 0, ByteArray(0))

    private fun xgeQueryVersionRequest(major: Int, minor: Int): ByteArray {
        val body = ByteArray(4)
        put16le(body, 0, major)
        put16le(body, 2, minor)
        return request(XGenericEvent.MajorOpcode, XGenericEvent.QueryVersion, body)
    }

    private fun request(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16le(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun readReply(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        assertEquals(1, header[0].toInt() and 0xff)
        val extra = u32le(header, 4) * 4
        return if (extra == 0) header else header + input.readExactly(extra)
    }

    private fun assertError(
        input: InputStream,
        error: Int,
        opcode: Int,
        badValue: Int,
        sequence: Int,
        minorOpcode: Int,
    ) {
        val bytes = input.readExactly(32)
        assertEquals(0, bytes[0].toInt() and 0xff)
        assertEquals(error, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16le(bytes, 2))
        assertEquals(badValue, u32le(bytes, 4))
        assertEquals(minorOpcode, u16le(bytes, 8))
        assertEquals(opcode, bytes[10].toInt() and 0xff)
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

    private fun put16le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        u16le(bytes, offset) or (u16le(bytes, offset + 2) shl 16)
}
