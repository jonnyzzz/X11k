package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class XShapeProtocolTest {
    @Test
    fun `SHAPE QueryVersion and input selection validate and recover stream`() {
        withServer { socket ->
            val out = socket.getOutputStream()
            out.write(createWindowRequest(WindowId))
            out.write(request(XShape.MajorOpcode, XShape.QueryVersion, ByteArray(4)))
            out.write(request(XShape.MajorOpcode, XShape.QueryVersion, ByteArray(0)))
            out.write(shapeSelectInput(WindowId, enabled = true))
            out.write(shapeInputSelected(WindowId))
            out.write(shapeSelectInput(WindowId, enabled = false))
            out.write(shapeInputSelected(WindowId))
            out.flush()

            assertError(socket.getInputStream(), error = 16, sequence = 2, minorOpcode = XShape.QueryVersion)

            val version = readReply(socket.getInputStream())
            assertEquals(XShape.MajorVersion, u16le(version, 8))
            assertEquals(XShape.MinorVersion, u16le(version, 10))

            val enabled = readReply(socket.getInputStream())
            assertEquals(1, enabled[1].toInt() and 0xff)

            val disabled = readReply(socket.getInputStream())
            assertEquals(0, disabled[1].toInt() and 0xff)
        }
    }

    @Test
    fun `SHAPE Rectangles Combine Offset QueryExtents and GetRectangles share window shape state`() {
        withServer { socket ->
            val out = socket.getOutputStream()
            out.write(createWindowRequest(WindowId))
            out.write(createWindowRequest(SourceWindowId))
            out.write(shapeRectangles(WindowId, XFixes.ShapeBounding, XShape.OpSet, listOf(XRectangleCommand(0, 0, 1, 1))))
            out.write(shapeRectangles(SourceWindowId, XFixes.ShapeBounding, XShape.OpSet, listOf(XRectangleCommand(0, 0, 2, 2))))
            out.write(shapeCombine(WindowId, XFixes.ShapeBounding, SourceWindowId, XFixes.ShapeBounding, XShape.OpUnion, xOffset = 5, yOffset = 0))
            out.write(shapeOffset(WindowId, XFixes.ShapeBounding, xOffset = 1, yOffset = 2))
            out.write(shapeGetRectangles(WindowId, XFixes.ShapeBounding))
            out.write(shapeQueryExtents(WindowId))
            out.flush()

            assertRectanglesReply(
                socket.getInputStream(),
                listOf(
                    XRectangleCommand(1, 2, 1, 1),
                    XRectangleCommand(6, 2, 2, 1),
                    XRectangleCommand(6, 3, 2, 1),
                ),
            )

            val extents = readReply(socket.getInputStream())
            assertEquals(1, extents[8].toInt() and 0xff)
            assertEquals(0, extents[9].toInt() and 0xff)
            assertEquals(1, i16le(extents, 12))
            assertEquals(2, i16le(extents, 14))
            assertEquals(7, u16le(extents, 16))
            assertEquals(2, u16le(extents, 18))
            assertEquals(0, i16le(extents, 20))
            assertEquals(0, i16le(extents, 22))
            assertEquals(100, u16le(extents, 24))
            assertEquals(80, u16le(extents, 26))
        }
    }

    @Test
    fun `SHAPE Mask validates bitmap source and clears client region for None`() {
        withServer { socket ->
            val out = socket.getOutputStream()
            val missingPixmap = PixmapId + 1
            out.write(createWindowRequest(WindowId))
            out.write(createPixmapRequest(PixmapId, depth = 24, width = 2, height = 2))
            out.write(shapeMask(WindowId, XFixes.ShapeClip, XShape.OpSet, missingPixmap))
            out.write(shapeMask(WindowId, XFixes.ShapeClip, XShape.OpSet, PixmapId))
            out.write(shapeRectangles(WindowId, XFixes.ShapeClip, XShape.OpSet, listOf(XRectangleCommand(1, 1, 2, 2))))
            out.write(shapeMask(WindowId, XFixes.ShapeClip, XShape.OpIntersect, 0))
            out.write(shapeGetRectangles(WindowId, XFixes.ShapeClip))
            out.write(shapeQueryExtents(WindowId))
            out.flush()

            assertError(socket.getInputStream(), error = 4, badValue = missingPixmap, sequence = 3, minorOpcode = XShape.Mask)
            assertError(socket.getInputStream(), error = 8, badValue = PixmapId, sequence = 4, minorOpcode = XShape.Mask)
            assertRectanglesReply(socket.getInputStream(), listOf(XRectangleCommand(0, 0, 100, 80)))

            val extents = readReply(socket.getInputStream())
            assertEquals(0, extents[9].toInt() and 0xff)
            assertEquals(0, i16le(extents, 20))
            assertEquals(0, i16le(extents, 22))
            assertEquals(100, u16le(extents, 24))
            assertEquals(80, u16le(extents, 26))
        }
    }

    @Test
    fun `SHAPE operations use stored client region semantics when destination is unset`() {
        withServer { socket ->
            val out = socket.getOutputStream()
            out.write(createWindowRequest(WindowId))
            out.write(shapeRectangles(WindowId, XFixes.ShapeClip, XShape.OpUnion, listOf(XRectangleCommand(1, 1, 2, 2))))
            out.write(shapeGetRectangles(WindowId, XFixes.ShapeClip))
            out.write(shapeQueryExtents(WindowId))
            out.write(shapeRectangles(WindowId, XFixes.ShapeClip, XShape.OpIntersect, listOf(XRectangleCommand(1, 1, 2, 2))))
            out.write(shapeGetRectangles(WindowId, XFixes.ShapeClip))
            out.write(shapeMask(WindowId, XFixes.ShapeClip, XShape.OpSet, 0))
            out.write(shapeRectangles(WindowId, XFixes.ShapeClip, XShape.OpSubtract, listOf(XRectangleCommand(0, 0, 10, 10))))
            out.write(shapeGetRectangles(WindowId, XFixes.ShapeClip))
            out.write(shapeMask(WindowId, XFixes.ShapeClip, XShape.OpSet, 0))
            out.write(shapeRectangles(WindowId, XFixes.ShapeClip, XShape.OpInvert, listOf(XRectangleCommand(1, 1, 2, 2))))
            out.write(shapeGetRectangles(WindowId, XFixes.ShapeClip))
            out.write(shapeQueryExtents(WindowId))
            out.flush()

            val input = socket.getInputStream()
            assertRectanglesReply(input, listOf(XRectangleCommand(0, 0, 100, 80)))
            val unshapedExtents = readReply(input)
            assertEquals(0, unshapedExtents[9].toInt() and 0xff)

            assertRectanglesReply(input, listOf(XRectangleCommand(1, 1, 2, 2)))
            assertRectanglesReply(
                input,
                listOf(
                    XRectangleCommand(10, 0, 90, 10),
                    XRectangleCommand(0, 10, 100, 70),
                ),
            )
            assertRectanglesReply(input, emptyList())

            val emptyClientExtents = readReply(input)
            assertEquals(1, emptyClientExtents[9].toInt() and 0xff)
            assertEquals(0, u16le(emptyClientExtents, 24))
            assertEquals(0, u16le(emptyClientExtents, 26))
        }
    }

    @Test
    fun `SHAPE SelectInput receives ShapeNotify events for shape and XFIXES changes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { mutator ->
                Socket("127.0.0.1", server.localPort).use { observer ->
                    mutator.soTimeout = 2_000
                    observer.soTimeout = 2_000
                    setup(mutator)
                    setup(observer)

                    val mutatorOut = mutator.getOutputStream()
                    mutatorOut.write(createWindowRequest(WindowId))
                    mutatorOut.write(shapeGetRectangles(WindowId, XFixes.ShapeBounding))
                    mutatorOut.flush()
                    assertRectanglesReply(mutator.getInputStream(), listOf(XRectangleCommand(-1, -1, 102, 82)))

                    val observerOut = observer.getOutputStream()
                    observerOut.write(shapeSelectInput(WindowId, enabled = true))
                    observerOut.write(shapeInputSelected(WindowId))
                    observerOut.flush()
                    assertEquals(1, readReply(observer.getInputStream())[1].toInt() and 0xff)

                    mutatorOut.write(shapeRectangles(WindowId, XFixes.ShapeBounding, XShape.OpSet, listOf(XRectangleCommand(1, 2, 3, 4))))
                    mutatorOut.write(shapeMask(WindowId, XFixes.ShapeBounding, XShape.OpSet, 0))
                    mutatorOut.write(xfixesCreateRegion(RegionId, listOf(XRectangleCommand(4, 5, 6, 7))))
                    mutatorOut.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeClip, region = RegionId))
                    mutatorOut.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeBounding, region = 0))
                    mutatorOut.flush()

                    val observerInput = observer.getInputStream()
                    assertShapeNotify(observerInput, XFixes.ShapeBounding, WindowId, XRectangleCommand(1, 2, 3, 4), shaped = true)
                    assertShapeNotify(observerInput, XFixes.ShapeBounding, WindowId, XRectangleCommand(-1, -1, 102, 82), shaped = false)
                    assertShapeNotify(observerInput, XFixes.ShapeClip, WindowId, XRectangleCommand(4, 5, 6, 7), shaped = true)
                    assertShapeNotify(observerInput, XFixes.ShapeBounding, WindowId, XRectangleCommand(-1, -1, 102, 82), shaped = false)

                    mutatorOut.write(shapeMask(WindowId, XFixes.ShapeBounding, XShape.OpSet, 0))
                    mutatorOut.flush()
                    assertNoBytes(observer)

                    observerOut.write(shapeSelectInput(WindowId, enabled = false))
                    observerOut.write(shapeInputSelected(WindowId))
                    observerOut.flush()
                    assertEquals(0, readReply(observerInput)[1].toInt() and 0xff)

                    mutatorOut.write(shapeRectangles(WindowId, XFixes.ShapeBounding, XShape.OpSet, listOf(XRectangleCommand(8, 9, 10, 11))))
                    mutatorOut.flush()
                    assertNoBytes(observer)
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SHAPE Offset notifies even when client region is unset`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { mutator ->
                Socket("127.0.0.1", server.localPort).use { observer ->
                    mutator.soTimeout = 2_000
                    observer.soTimeout = 2_000
                    setup(mutator)
                    setup(observer)

                    val mutatorOut = mutator.getOutputStream()
                    mutatorOut.write(createWindowRequest(WindowId))
                    mutatorOut.write(shapeGetRectangles(WindowId, XFixes.ShapeBounding))
                    mutatorOut.flush()
                    assertRectanglesReply(mutator.getInputStream(), listOf(XRectangleCommand(-1, -1, 102, 82)))

                    val observerOut = observer.getOutputStream()
                    observerOut.write(shapeSelectInput(WindowId, enabled = true))
                    observerOut.write(shapeInputSelected(WindowId))
                    observerOut.flush()
                    assertEquals(1, readReply(observer.getInputStream())[1].toInt() and 0xff)

                    mutatorOut.write(shapeOffset(WindowId, XFixes.ShapeBounding, xOffset = 7, yOffset = 9))
                    mutatorOut.write(shapeGetRectangles(WindowId, XFixes.ShapeBounding))
                    mutatorOut.flush()

                    val observerInput = observer.getInputStream()
                    assertShapeNotify(observerInput, XFixes.ShapeBounding, WindowId, XRectangleCommand(-1, -1, 102, 82), shaped = false)
                    assertRectanglesReply(mutator.getInputStream(), listOf(XRectangleCommand(-1, -1, 102, 82)))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SHAPE Rectangles Mask and Combine are root window no-ops`() {
        withServer { socket ->
            val out = socket.getOutputStream()
            out.write(createWindowRequest(SourceWindowId))
            out.write(shapeSelectInput(X11Ids.RootWindow, enabled = true))
            out.write(shapeRectangles(X11Ids.RootWindow, XFixes.ShapeBounding, XShape.OpSet, listOf(XRectangleCommand(1, 2, 3, 4))))
            out.write(shapeMask(X11Ids.RootWindow, XFixes.ShapeBounding, XShape.OpSet, 0))
            out.write(shapeCombine(X11Ids.RootWindow, XFixes.ShapeBounding, SourceWindowId, XFixes.ShapeBounding, XShape.OpSet, xOffset = 0, yOffset = 0))
            out.write(shapeGetRectangles(X11Ids.RootWindow, XFixes.ShapeBounding))
            out.flush()

            assertRectanglesReply(socket.getInputStream(), listOf(XRectangleCommand(0, 0, 120, 90)))
        }
    }

    @Test
    fun `SHAPE Rectangles rejects malformed ordered rectangles`() {
        withServer { socket ->
            val out = socket.getOutputStream()
            out.write(createWindowRequest(WindowId))
            out.write(
                shapeRectangles(
                    WindowId,
                    XFixes.ShapeClip,
                    XShape.OpSet,
                    listOf(XRectangleCommand(0, 10, 1, 1), XRectangleCommand(0, 0, 1, 1)),
                    ordering = XShape.OrderingYSorted,
                ),
            )
            out.write(
                shapeRectangles(
                    WindowId,
                    XFixes.ShapeClip,
                    XShape.OpSet,
                    listOf(XRectangleCommand(10, 0, 1, 1), XRectangleCommand(0, 0, 1, 1)),
                    ordering = XShape.OrderingYXSorted,
                ),
            )
            out.write(
                shapeRectangles(
                    WindowId,
                    XFixes.ShapeClip,
                    XShape.OpSet,
                    listOf(XRectangleCommand(0, 0, 10, 10), XRectangleCommand(0, 5, 10, 10)),
                    ordering = XShape.OrderingYXBanded,
                ),
            )
            out.write(shapeGetRectangles(WindowId, XFixes.ShapeClip))
            out.flush()

            val input = socket.getInputStream()
            assertError(input, error = 8, sequence = 2, minorOpcode = XShape.Rectangles)
            assertError(input, error = 8, sequence = 3, minorOpcode = XShape.Rectangles)
            assertError(input, error = 8, sequence = 4, minorOpcode = XShape.Rectangles)
            assertRectanglesReply(input, listOf(XRectangleCommand(0, 0, 100, 80)))
        }
    }

    @Test
    fun `SHAPE default bounding and input regions include border while clip is interior`() {
        withServer { socket ->
            val out = socket.getOutputStream()
            out.write(createWindowRequest(WindowId, borderWidth = 2))
            out.write(shapeGetRectangles(WindowId, XFixes.ShapeBounding))
            out.write(shapeGetRectangles(WindowId, XFixes.ShapeClip))
            out.write(shapeGetRectangles(WindowId, XFixes.ShapeInput))
            out.write(shapeQueryExtents(WindowId))
            out.flush()

            val input = socket.getInputStream()
            assertRectanglesReply(input, listOf(XRectangleCommand(-2, -2, 104, 84)))
            assertRectanglesReply(input, listOf(XRectangleCommand(0, 0, 100, 80)))
            assertRectanglesReply(input, listOf(XRectangleCommand(-2, -2, 104, 84)))

            val extents = readReply(input)
            assertEquals(0, extents[8].toInt() and 0xff)
            assertEquals(0, extents[9].toInt() and 0xff)
            assertEquals(-2, i16le(extents, 12))
            assertEquals(-2, i16le(extents, 14))
            assertEquals(104, u16le(extents, 16))
            assertEquals(84, u16le(extents, 18))
            assertEquals(0, i16le(extents, 20))
            assertEquals(0, i16le(extents, 22))
            assertEquals(100, u16le(extents, 24))
            assertEquals(80, u16le(extents, 26))
        }
    }

    private fun withServer(block: (Socket) -> Unit) {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                block(socket)
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

    private fun createWindowRequest(id: Int, borderWidth: Int = 1): ByteArray {
        val body = ByteArray(28)
        put32le(body, 0, id)
        put32le(body, 4, X11Ids.RootWindow)
        put16le(body, 8, 10)
        put16le(body, 10, 20)
        put16le(body, 12, 100)
        put16le(body, 14, 80)
        put16le(body, 16, borderWidth)
        put16le(body, 18, 1)
        put32le(body, 20, X11Ids.RootVisual)
        return request(1, 24, body)
    }

    private fun createPixmapRequest(id: Int, depth: Int, width: Int, height: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, id)
        put32le(body, 4, X11Ids.RootWindow)
        put16le(body, 8, width)
        put16le(body, 10, height)
        return request(53, depth, body)
    }

    private fun shapeSelectInput(window: Int, enabled: Boolean): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        body[4] = if (enabled) 1 else 0
        return request(XShape.MajorOpcode, XShape.SelectInput, body)
    }

    private fun shapeInputSelected(window: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, window)
        return request(XShape.MajorOpcode, XShape.InputSelected, body)
    }

    private fun shapeRectangles(
        window: Int,
        kind: Int,
        operation: Int,
        rectangles: List<XRectangleCommand>,
        ordering: Int = XShape.OrderingYXBanded,
    ): ByteArray {
        val body = ByteArray(12 + rectangles.size * 8)
        body[0] = operation.toByte()
        body[1] = kind.toByte()
        body[2] = ordering.toByte()
        put32le(body, 4, window)
        rectangles.forEachIndexed { index, rectangle ->
            val offset = 12 + index * 8
            put16le(body, offset, rectangle.x)
            put16le(body, offset + 2, rectangle.y)
            put16le(body, offset + 4, rectangle.width)
            put16le(body, offset + 6, rectangle.height)
        }
        return request(XShape.MajorOpcode, XShape.Rectangles, body)
    }

    private fun shapeMask(window: Int, kind: Int, operation: Int, bitmap: Int): ByteArray {
        val body = ByteArray(16)
        body[0] = operation.toByte()
        body[1] = kind.toByte()
        put32le(body, 4, window)
        put32le(body, 12, bitmap)
        return request(XShape.MajorOpcode, XShape.Mask, body)
    }

    private fun shapeCombine(
        destinationWindow: Int,
        destinationKind: Int,
        sourceWindow: Int,
        sourceKind: Int,
        operation: Int,
        xOffset: Int,
        yOffset: Int,
    ): ByteArray {
        val body = ByteArray(16)
        body[0] = operation.toByte()
        body[1] = destinationKind.toByte()
        body[2] = sourceKind.toByte()
        put32le(body, 4, destinationWindow)
        put16le(body, 8, xOffset)
        put16le(body, 10, yOffset)
        put32le(body, 12, sourceWindow)
        return request(XShape.MajorOpcode, XShape.Combine, body)
    }

    private fun shapeOffset(window: Int, kind: Int, xOffset: Int, yOffset: Int): ByteArray {
        val body = ByteArray(12)
        body[0] = kind.toByte()
        put32le(body, 4, window)
        put16le(body, 8, xOffset)
        put16le(body, 10, yOffset)
        return request(XShape.MajorOpcode, XShape.Offset, body)
    }

    private fun shapeQueryExtents(window: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, window)
        return request(XShape.MajorOpcode, XShape.QueryExtents, body)
    }

    private fun shapeGetRectangles(window: Int, kind: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        body[4] = kind.toByte()
        return request(XShape.MajorOpcode, XShape.GetRectangles, body)
    }

    private fun xfixesCreateRegion(region: Int, rectangles: List<XRectangleCommand>): ByteArray {
        val body = ByteArray(4 + rectangles.size * 8)
        put32le(body, 0, region)
        rectangles.forEachIndexed { index, rectangle ->
            val offset = 4 + index * 8
            put16le(body, offset, rectangle.x)
            put16le(body, offset + 2, rectangle.y)
            put16le(body, offset + 4, rectangle.width)
            put16le(body, offset + 6, rectangle.height)
        }
        return request(XFixes.MajorOpcode, XFixes.CreateRegion, body)
    }

    private fun xfixesSetWindowShapeRegion(window: Int, kind: Int, region: Int, xOffset: Int = 0, yOffset: Int = 0): ByteArray {
        val body = ByteArray(16)
        put32le(body, 0, window)
        body[4] = kind.toByte()
        put16le(body, 8, xOffset)
        put16le(body, 10, yOffset)
        put32le(body, 12, region)
        return request(XFixes.MajorOpcode, XFixes.SetWindowShapeRegion, body)
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
        val payloadUnits = u32le(header, 4)
        return header + input.readExactly(payloadUnits * 4)
    }

    private fun assertError(input: InputStream, error: Int, badValue: Int = 0, sequence: Int, minorOpcode: Int) {
        val bytes = input.readExactly(32)
        assertEquals(0, bytes[0].toInt())
        assertEquals(error, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16le(bytes, 2))
        assertEquals(badValue, u32le(bytes, 4))
        assertEquals(minorOpcode, u16le(bytes, 8))
        assertEquals(XShape.MajorOpcode, bytes[10].toInt() and 0xff)
    }

    private fun assertRectanglesReply(input: InputStream, rectangles: List<XRectangleCommand>) {
        val reply = readReply(input)
        assertEquals(1, reply[0].toInt())
        assertEquals(XShape.OrderingYXBanded, reply[1].toInt() and 0xff)
        assertEquals(rectangles.size, u32le(reply, 8))
        rectangles.forEachIndexed { index, rectangle ->
            val offset = 32 + index * 8
            assertEquals(rectangle.x, i16le(reply, offset))
            assertEquals(rectangle.y, i16le(reply, offset + 2))
            assertEquals(rectangle.width, u16le(reply, offset + 4))
            assertEquals(rectangle.height, u16le(reply, offset + 6))
        }
    }

    private fun assertShapeNotify(input: InputStream, kind: Int, window: Int, extents: XRectangleCommand, shaped: Boolean) {
        val event = input.readExactly(32)
        assertEquals(XShape.FirstEvent + XShape.Notify, event[0].toInt() and 0xff)
        assertEquals(kind, event[1].toInt() and 0xff)
        assertEquals(window, u32le(event, 4))
        assertEquals(extents.x, i16le(event, 8))
        assertEquals(extents.y, i16le(event, 10))
        assertEquals(extents.width, u16le(event, 12))
        assertEquals(extents.height, u16le(event, 14))
        assertEquals(if (shaped) 1 else 0, event[20].toInt() and 0xff)
    }

    private fun assertNoBytes(socket: Socket) {
        val previousTimeout = socket.soTimeout
        socket.soTimeout = 200
        try {
            val byte = socket.getInputStream().read()
            fail("Expected no event bytes, read $byte")
        } catch (_: SocketTimeoutException) {
            // expected
        } finally {
            socket.soTimeout = previousTimeout
        }
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            if (read < 0) error("EOF after $offset/$size bytes")
            offset += read
        }
        return bytes
    }

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

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun i16le(bytes: ByteArray, offset: Int): Int =
        u16le(bytes, offset).toShort().toInt()

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val WindowId = 0x0020_1001
        private const val SourceWindowId = 0x0020_1002
        private const val PixmapId = 0x0020_2001
        private const val RegionId = 0x0020_3001
    }
}
