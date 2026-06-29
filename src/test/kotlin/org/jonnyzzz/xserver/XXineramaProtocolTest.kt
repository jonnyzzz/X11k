package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class XXineramaProtocolTest {
    @Test
    fun `XINERAMA reports a single active screen and validates request lengths`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(queryExtensionRequest("XINERAMA"))
                out.write(listExtensionsRequest())
                out.write(request(XXinerama.MajorOpcode, XXinerama.QueryVersion, ByteArray(0)))
                out.write(request(XXinerama.MajorOpcode, XXinerama.QueryVersion, byteArrayOf(1, 0, 0, 0)))
                out.write(request(XXinerama.MajorOpcode, XXinerama.IsActive, u32(0)))
                out.write(request(XXinerama.MajorOpcode, XXinerama.QueryScreens, u32(0)))
                out.write(request(XXinerama.MajorOpcode, XXinerama.IsActive, ByteArray(0)))
                out.write(request(XXinerama.MajorOpcode, XXinerama.QueryScreens, ByteArray(0)))
                out.write(queryPointerRequest())
                out.flush()

                val extension = readReply(socket.getInputStream())
                assertEquals(1, extension[8].toInt() and 0xff)
                assertEquals(XXinerama.MajorOpcode, extension[9].toInt() and 0xff)
                assertEquals(XXinerama.FirstEvent, extension[10].toInt() and 0xff)
                assertEquals(XXinerama.FirstError, extension[11].toInt() and 0xff)

                val extensions = readReply(socket.getInputStream())
                assertContains(extensionNames(extensions), "XINERAMA")

                assertError(socket.getInputStream(), error = 16, sequence = 3, minorOpcode = XXinerama.QueryVersion)

                val version = readReply(socket.getInputStream())
                assertEquals(4, u16le(version, 2))
                assertEquals(XXinerama.MajorVersion, u16le(version, 8))
                assertEquals(XXinerama.MinorVersion, u16le(version, 10))

                assertError(socket.getInputStream(), error = 16, sequence = 5, minorOpcode = XXinerama.IsActive)
                assertError(socket.getInputStream(), error = 16, sequence = 6, minorOpcode = XXinerama.QueryScreens)

                val active = readReply(socket.getInputStream())
                assertEquals(7, u16le(active, 2))
                assertEquals(1, u32le(active, 8))

                val screens = readReply(socket.getInputStream())
                assertEquals(8, u16le(screens, 2))
                assertEquals(2, u32le(screens, 4))
                assertEquals(1, u32le(screens, 8))
                assertEquals(0, u16le(screens, 32))
                assertEquals(0, u16le(screens, 34))
                assertEquals(120, u16le(screens, 36))
                assertEquals(90, u16le(screens, 38))

                val pointer = readReply(socket.getInputStream())
                assertEquals(9, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `legacy XINERAMA requests report single screen metadata and recover stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(XXinerama.MajorOpcode, XXinerama.GetState, ByteArray(0)))
                out.write(request(XXinerama.MajorOpcode, XXinerama.GetState, u32(X11Ids.RootWindow)))
                out.write(request(XXinerama.MajorOpcode, XXinerama.GetScreenCount, ByteArray(0)))
                out.write(request(XXinerama.MajorOpcode, XXinerama.GetScreenCount, u32(X11Ids.RootWindow)))
                out.write(request(XXinerama.MajorOpcode, XXinerama.GetScreenSize, u32(X11Ids.RootWindow)))
                out.write(request(XXinerama.MajorOpcode, XXinerama.GetScreenSize, windowAndScreen(X11Ids.RootWindow, 0)))
                out.write(request(XXinerama.MajorOpcode, XXinerama.GetScreenSize, windowAndScreen(X11Ids.RootWindow, 7)))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, sequence = 1, minorOpcode = XXinerama.GetState)

                val state = readReply(socket.getInputStream())
                assertEquals(1, state[1].toInt() and 0xff)
                assertEquals(2, u16le(state, 2))
                assertEquals(X11Ids.RootWindow, u32le(state, 8))

                assertError(socket.getInputStream(), error = 16, sequence = 3, minorOpcode = XXinerama.GetScreenCount)

                val count = readReply(socket.getInputStream())
                assertEquals(1, count[1].toInt() and 0xff)
                assertEquals(4, u16le(count, 2))
                assertEquals(X11Ids.RootWindow, u32le(count, 8))

                assertError(socket.getInputStream(), error = 16, sequence = 5, minorOpcode = XXinerama.GetScreenSize)

                val screen0 = readReply(socket.getInputStream())
                assertEquals(6, u16le(screen0, 2))
                assertEquals(120, u32le(screen0, 8))
                assertEquals(90, u32le(screen0, 12))
                assertEquals(X11Ids.RootWindow, u32le(screen0, 16))
                assertEquals(0, u32le(screen0, 20))

                val screen7 = readReply(socket.getInputStream())
                assertEquals(7, u16le(screen7, 2))
                assertEquals(0, u32le(screen7, 8))
                assertEquals(0, u32le(screen7, 12))
                assertEquals(X11Ids.RootWindow, u32le(screen7, 16))
                assertEquals(7, u32le(screen7, 20))

                val pointer = readReply(socket.getInputStream())
                assertEquals(8, u16le(pointer, 2))
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

    private fun queryPointerRequest(): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, X11Ids.RootWindow)
        return request(38, 0, body)
    }

    private fun request(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16le(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun assertError(input: InputStream, error: Int, sequence: Int, minorOpcode: Int) {
        val bytes = input.readExactly(32)
        assertEquals(0, bytes[0].toInt() and 0xff)
        assertEquals(error, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16le(bytes, 2))
        assertEquals(0, u32le(bytes, 4))
        assertEquals(minorOpcode, u16le(bytes, 8))
        assertEquals(XXinerama.MajorOpcode, bytes[10].toInt() and 0xff)
    }

    private fun readReply(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        assertEquals(1, header[0].toInt() and 0xff)
        val extra = u32le(header, 4) * 4
        return if (extra == 0) header else header + input.readExactly(extra)
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

    private fun u32(value: Int): ByteArray {
        val bytes = ByteArray(4)
        put32le(bytes, 0, value)
        return bytes
    }

    private fun windowAndScreen(window: Int, screen: Int): ByteArray {
        val bytes = ByteArray(8)
        put32le(bytes, 0, window)
        put32le(bytes, 4, screen)
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
