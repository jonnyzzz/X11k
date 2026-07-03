package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class XXkbProtocolTest {
    @Test
    fun `XKEYBOARD exposes query-only UseExtension metadata`() {
        withServer { socket, port ->
            socket.getOutputStream().write(queryExtensionRequest("XKEYBOARD"))
            socket.getOutputStream().write(useExtensionRequest())
            socket.getOutputStream().flush()

            val extension = readReply(socket.getInputStream())
            assertEquals(1, extension[8].toInt())
            assertEquals(XXkb.MajorOpcode, extension[9].toInt() and 0xff)
            assertEquals(XXkb.FirstEvent, extension[10].toInt() and 0xff)
            assertEquals(XXkb.FirstError, extension[11].toInt() and 0xff)

            val version = readReply(socket.getInputStream())
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(0, u32le(version, 4))
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
            assertEquals(XXkb.MinorVersion, u16le(version, 10))

            assertContains(httpGet(port, "/text.txt"), "XKEYBOARD supported=true")
        }
    }

    @Test
    fun `XKEYBOARD alias resolves through QueryExtension`() {
        withServer { socket, _ ->
            socket.getOutputStream().write(queryExtensionRequest("XKB"))
            socket.getOutputStream().flush()

            val extension = readReply(socket.getInputStream())
            assertEquals(1, extension[8].toInt())
            assertEquals(XXkb.MajorOpcode, extension[9].toInt() and 0xff)
            assertEquals(XXkb.FirstEvent, extension[10].toInt() and 0xff)
            assertEquals(XXkb.FirstError, extension[11].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD UseExtension validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.UseExtension, ByteArray(0)))
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.UseExtension)
            val version = readReply(socket.getInputStream())
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
            assertEquals(XXkb.MinorVersion, u16le(version, 10))
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents accepts fixed prefix no-op and recovers stream`() {
        withServer { socket, port ->
            val out = socket.getOutputStream()
            out.write(selectEventsRequest())
            out.write(useExtensionRequest())
            out.flush()

            val version = readReply(socket.getInputStream())
            assertEquals(2, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
            assertEquals(XXkb.MinorVersion, u16le(version, 10))

            assertContains(httpGet(port, "/text.txt"), "XKEYBOARD.SelectEvents: 1")
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents validates fixed prefix length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SelectEvents, ByteArray(8)))
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SelectEvents)
            val version = readReply(socket.getInputStream())
            assertEquals(2, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents validates ExtensionDeviceNotify detail mask`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventExtensionDeviceNotify,
                    details = selectEvents16Details(XXkb.AllExtensionDeviceEvents or 0x0020, 0),
                ),
            )
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 0x0020, sequence = 1, minorOpcode = XXkb.SelectEvents)
            val version = readReply(socket.getInputStream())
            assertEquals(2, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents validates variable details and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(selectEventsRequest(details = ByteArray(4)))
            out.write(
                selectEventsRequest(
                    affectWhich = 1 shl 12,
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventMapNotify,
                    affectMap = 0,
                    map = XXkb.MapPartKeyTypes,
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventMapNotify,
                    affectMap = XXkb.MapPartKeySyms,
                    map = XXkb.MapPartKeySyms,
                    details = selectEvents16Details(XXkb.MapPartKeySyms, XXkb.MapPartKeySyms),
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventStateNotify,
                    details = selectEvents16Details(0x0001, 0x0002),
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventStateNotify,
                    details = selectEvents16Details(0x0003, 0x0002),
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventBellNotify,
                    details = selectEvents8Details(0x01, 0x01),
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventBellNotify or XXkb.EventAccessXNotify,
                    details = selectEvents8Details(0x01, 0x01) + selectEvents16Details(0x0003, 0x0002),
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventBellNotify or XXkb.EventAccessXNotify,
                    details = selectEvents8Details(0x01, 0x01) + selectEvents16Details(0x0001, 0x0002),
                ),
            )
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SelectEvents)
            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 1 shl 12, sequence = 2, minorOpcode = XXkb.SelectEvents)
            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SelectEvents)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 4, minorOpcode = XXkb.SelectEvents)
            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 5, minorOpcode = XXkb.SelectEvents)
            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 9, minorOpcode = XXkb.SelectEvents)
            val version = readReply(socket.getInputStream())
            assertEquals(10, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
        }
    }

    @Test
    fun `XKEYBOARD Bell accepts valid signed percent and recovers stream`() {
        withServer { socket, port ->
            val out = socket.getOutputStream()
            out.write(xkbBellRequest(percent = 50))
            out.write(xkbBellRequest(percent = -100))
            out.write(useExtensionRequest())
            out.flush()

            val version = readReply(socket.getInputStream())
            assertEquals(3, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
            assertEquals(XXkb.MinorVersion, u16le(version, 10))

            assertContains(httpGet(port, "/text.txt"), "XKEYBOARD.Bell: 2")
        }
    }

    @Test
    fun `XKEYBOARD BellNotify reports selected accepted bell requests`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventBellNotify,
                    details = selectEvents8Details(
                        affect = XXkb.AllBellEventsMask,
                        selected = XXkb.AllBellEventsMask,
                    ),
                ),
            )
            out.write(
                xkbBellRequest(
                    percent = 50,
                    bellClass = 2,
                    bellId = 3,
                    eventOnly = true,
                    pitch = 440,
                    duration = 120,
                    name = 0x12345678,
                    window = X11Ids.RootWindow,
                ),
            )
            out.write(useExtensionRequest())
            out.flush()

            assertBellNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                bellClass = 2,
                bellId = 3,
                percent = 50,
                pitch = 440,
                duration = 120,
                name = 0x12345678,
                window = X11Ids.RootWindow,
                eventOnly = true,
            )
            assertEquals(3, u16le(readReply(socket.getInputStream()), 2))
        }
    }

    @Test
    fun `XKEYBOARD BellNotify suppresses invalid and unselected bell requests`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventBellNotify,
                    details = selectEvents8Details(
                        affect = XXkb.AllBellEventsMask,
                        selected = 0,
                    ),
                ),
            )
            out.write(xkbBellRequest(percent = 50))
            out.write(useExtensionRequest())
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventBellNotify,
                    details = selectEvents8Details(
                        affect = XXkb.AllBellEventsMask,
                        selected = XXkb.AllBellEventsMask,
                    ),
                ),
            )
            out.write(xkbBellRequest(percent = 101))
            out.write(useExtensionRequest())
            out.flush()

            assertEquals(3, u16le(readReply(socket.getInputStream()), 2))
            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 101, sequence = 5, minorOpcode = XXkb.Bell)
            assertEquals(6, u16le(readReply(socket.getInputStream()), 2))
        }
    }

    @Test
    fun `XKEYBOARD BellNotify suppresses forced bells and rejects forced event-only bells`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventBellNotify,
                    selectAll = XXkb.EventBellNotify,
                ),
            )
            out.write(xkbBellRequest(percent = 50, forceSound = true))
            out.write(useExtensionRequest())
            out.write(xkbBellRequest(percent = 50, forceSound = true, eventOnly = true))
            out.write(useExtensionRequest())
            out.flush()

            assertEquals(3, u16le(readReply(socket.getInputStream()), 2))
            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 4, minorOpcode = XXkb.Bell)
            assertEquals(5, u16le(readReply(socket.getInputStream()), 2))
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents clear removes BellNotify selection`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventBellNotify,
                    selectAll = XXkb.EventBellNotify,
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventBellNotify,
                    clear = XXkb.EventBellNotify,
                ),
            )
            out.write(xkbBellRequest(percent = 50))
            out.write(useExtensionRequest())
            out.flush()

            assertEquals(4, u16le(readReply(socket.getInputStream()), 2))
        }
    }

    @Test
    fun `XKEYBOARD Bell validates fixed length and signed percent range`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(xkbBellRequest(percent = 101))
            out.write(xkbBellRequest(percent = -101))
            out.write(request(XXkb.MajorOpcode, XXkb.Bell, ByteArray(20)))
            out.write(request(XXkb.MajorOpcode, XXkb.Bell, ByteArray(28)))
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 101, sequence = 1, minorOpcode = XXkb.Bell)
            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = -101, sequence = 2, minorOpcode = XXkb.Bell)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.Bell)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 4, minorOpcode = XXkb.Bell)
            val version = readReply(socket.getInputStream())
            assertEquals(5, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
        }
    }

    @Test
    fun `XKEYBOARD GetState returns default core keyboard state`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getStateRequest())
            out.flush()

            val state = readReply(socket.getInputStream())
            assertEquals(0, state[1].toInt() and 0xff)
            assertEquals(1, u16le(state, 2))
            assertEquals(0, u32le(state, 4))
            assertEquals(0, state[8].toInt() and 0xff)
            assertEquals(0, state[9].toInt() and 0xff)
            assertEquals(0, state[10].toInt() and 0xff)
            assertEquals(0, state[11].toInt() and 0xff)
            assertEquals(0, state[12].toInt() and 0xff)
            assertEquals(0, state[13].toInt() and 0xff)
            assertEquals(0, u16le(state, 14))
            assertEquals(0, u16le(state, 16))
            assertEquals(0, state[18].toInt() and 0xff)
            assertEquals(0, state[19].toInt() and 0xff)
            assertEquals(0, state[20].toInt() and 0xff)
            assertEquals(0, state[21].toInt() and 0xff)
            assertEquals(0, state[22].toInt() and 0xff)
            assertEquals(0, u16le(state, 24))
        }
    }

    @Test
    fun `XKEYBOARD GetState separates pointer buttons from core aggregate modifier state`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val buttonMask = 0x100
                server.input.pointerDown(10, 10, button = 1)
                server.input.keyDown(10, modifiers = 5)

                val out = socket.getOutputStream()
                out.write(getStateRequest())
                out.write(queryPointerRequest())
                out.flush()

                val state = readReply(socket.getInputStream())
                assertEquals(5, state[8].toInt() and 0xff)
                assertEquals(5, state[9].toInt() and 0xff)
                assertEquals(0, state[10].toInt() and 0xff)
                assertEquals(0, state[11].toInt() and 0xff)
                assertEquals(5, state[18].toInt() and 0xff)
                assertEquals(5, state[19].toInt() and 0xff)
                assertEquals(5, state[20].toInt() and 0xff)
                assertEquals(5, state[21].toInt() and 0xff)
                assertEquals(5, state[22].toInt() and 0xff)
                assertEquals(buttonMask, u16le(state, 24))

                val pointer = readReply(socket.getInputStream())
                assertEquals(buttonMask or 5, u16le(pointer, 24))

                server.input.keyUp(10, modifiers = 0)
                out.write(getStateRequest())
                out.write(queryPointerRequest())
                out.flush()

                val releasedState = readReply(socket.getInputStream())
                assertEquals(0, releasedState[8].toInt() and 0xff)
                assertEquals(0, releasedState[9].toInt() and 0xff)
                assertEquals(0, releasedState[18].toInt() and 0xff)
                assertEquals(0, releasedState[19].toInt() and 0xff)
                assertEquals(0, releasedState[20].toInt() and 0xff)
                assertEquals(0, releasedState[21].toInt() and 0xff)
                assertEquals(0, releasedState[22].toInt() and 0xff)
                assertEquals(buttonMask, u16le(releasedState, 24))

                val releasedPointer = readReply(socket.getInputStream())
                assertEquals(buttonMask, u16le(releasedPointer, 24))

                server.input.pointerUp(10, 10, button = 1)
                out.write(getStateRequest())
                out.write(queryPointerRequest())
                out.flush()

                val finalState = readReply(socket.getInputStream())
                assertEquals(0, u16le(finalState, 24))

                val finalPointer = readReply(socket.getInputStream())
                assertEquals(0, u16le(finalPointer, 24))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XKEYBOARD GetState validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetState, ByteArray(0)))
            out.write(getStateRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetState)
            val state = readReply(socket.getInputStream())
            assertEquals(2, u16le(state, 2))
            assertEquals(0, state[1].toInt() and 0xff)
            assertEquals(0, u16le(state, 24))
        }
    }

    @Test
    fun `XKEYBOARD LatchLockState accepts fixed request and recovers stream`() {
        withServer { socket, port ->
            val out = socket.getOutputStream()
            out.write(latchLockStateRequest(modLocks = 0x05, groupLock = 1, latchGroup = true, groupLatch = -1))
            out.write(latchLockStateRequest(modLocks = 0, groupLock = 0, latchGroup = false, groupLatch = 0))
            out.write(useExtensionRequest())
            out.flush()

            val version = readReply(socket.getInputStream())
            assertEquals(3, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
            assertEquals(XXkb.MinorVersion, u16le(version, 10))

            assertContains(httpGet(port, "/text.txt"), "XKEYBOARD.LatchLockState: 2")
        }
    }

    @Test
    fun `XKEYBOARD LatchLockState updates XKB state without changing core state`() {
        withServer { socket, port ->
            val out = socket.getOutputStream()
            out.write(
                latchLockStateRequest(
                    modLocks = 0x05,
                    groupLock = 0,
                    latchGroup = false,
                    groupLatch = 0,
                    affectModLocks = 0x05,
                    lockGroup = false,
                    affectModLatches = 0x02,
                    modLatches = 0x02,
                ),
            )
            out.write(getStateRequest())
            out.write(queryPointerRequest())
            out.flush()

            val locked = readReply(socket.getInputStream())
            assertEquals(2, u16le(locked, 2))
            assertEquals(0x07, locked[8].toInt() and 0xff)
            assertEquals(0x00, locked[9].toInt() and 0xff)
            assertEquals(0x02, locked[10].toInt() and 0xff)
            assertEquals(0x05, locked[11].toInt() and 0xff)
            assertEquals(0x07, locked[18].toInt() and 0xff)
            assertEquals(0x07, locked[19].toInt() and 0xff)
            assertEquals(0x07, locked[20].toInt() and 0xff)
            assertEquals(0x07, locked[21].toInt() and 0xff)
            assertEquals(0x07, locked[22].toInt() and 0xff)
            assertEquals(0, u16le(locked, 24))

            val pointer = readReply(socket.getInputStream())
            assertEquals(3, u16le(pointer, 2))
            assertEquals(0, u16le(pointer, 24))

            assertContains(httpGet(port, "/state.json"), """"xkbLatchedModifiers":2""")
            assertContains(httpGet(port, "/state.json"), """"xkbLockedModifiers":5""")

            out.write(
                latchLockStateRequest(
                    modLocks = 0,
                    groupLock = 0,
                    latchGroup = false,
                    groupLatch = 0,
                    affectModLocks = 0xff,
                    lockGroup = false,
                    affectModLatches = 0xff,
                    modLatches = 0,
                ),
            )
            out.write(getStateRequest())
            out.write(queryPointerRequest())
            out.flush()

            val cleared = readReply(socket.getInputStream())
            assertEquals(5, u16le(cleared, 2))
            assertEquals(0, cleared[8].toInt() and 0xff)
            assertEquals(0, cleared[10].toInt() and 0xff)
            assertEquals(0, cleared[11].toInt() and 0xff)
            assertEquals(0, u16le(cleared, 24))

            val clearedPointer = readReply(socket.getInputStream())
            assertEquals(6, u16le(clearedPointer, 2))
            assertEquals(0, u16le(clearedPointer, 24))
        }
    }

    @Test
    fun `XKEYBOARD StateNotify reports selected latch and lock changes`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventStateNotify,
                    details = selectEvents16Details(
                        affect = XXkb.ModifierStateMask or
                            XXkb.ModifierLatchMask or
                            XXkb.ModifierLockMask or
                            XXkb.GroupLatchMask or
                            XXkb.GroupLockMask,
                        selected = XXkb.ModifierStateMask or
                            XXkb.ModifierLatchMask or
                            XXkb.ModifierLockMask or
                            XXkb.GroupLatchMask or
                            XXkb.GroupLockMask,
                    ),
                ),
            )
            out.write(
                latchLockStateRequest(
                    modLocks = 0x05,
                    groupLock = 255,
                    latchGroup = true,
                    groupLatch = -1,
                    affectModLocks = 0x05,
                    affectModLatches = 0x02,
                    modLatches = 0x02,
                ),
            )
            out.write(getStateRequest())
            out.flush()

            val event = socket.getInputStream().readExactly(32)
            assertEquals(XXkb.FirstEvent, event[0].toInt() and 0xff)
            assertEquals(XXkb.StateNotify, event[1].toInt() and 0xff)
            assertEquals(2, u16le(event, 2))
            assertEquals(0, event[8].toInt() and 0xff)
            assertEquals(0x07, event[9].toInt() and 0xff)
            assertEquals(0x00, event[10].toInt() and 0xff)
            assertEquals(0x02, event[11].toInt() and 0xff)
            assertEquals(0x05, event[12].toInt() and 0xff)
            assertEquals(0, event[13].toInt() and 0xff)
            assertEquals(0, u16le(event, 14))
            assertEquals(0, u16le(event, 16))
            assertEquals(0, event[18].toInt() and 0xff)
            assertEquals(0x07, event[19].toInt() and 0xff)
            assertEquals(0x07, event[20].toInt() and 0xff)
            assertEquals(0x07, event[21].toInt() and 0xff)
            assertEquals(0x07, event[22].toInt() and 0xff)
            assertEquals(0x07, event[23].toInt() and 0xff)
            assertEquals(0, u16le(event, 24))
            assertEquals(
                XXkb.ModifierStateMask or
                    XXkb.ModifierLatchMask or
                    XXkb.ModifierLockMask,
                u16le(event, 26),
            )
            assertEquals(0, event[28].toInt() and 0xff)
            assertEquals(0, event[29].toInt() and 0xff)
            assertEquals(XXkb.MajorOpcode, event[30].toInt() and 0xff)
            assertEquals(XXkb.LatchLockState, event[31].toInt() and 0xff)

            val state = readReply(socket.getInputStream())
            assertEquals(3, u16le(state, 2))
            assertEquals(0x07, state[8].toInt() and 0xff)
            assertEquals(0x02, state[10].toInt() and 0xff)
            assertEquals(0x05, state[11].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD StateNotify is suppressed when selected details do not intersect changes`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventStateNotify,
                    details = selectEvents16Details(
                        affect = XXkb.ModifierBaseMask,
                        selected = XXkb.ModifierBaseMask,
                    ),
                ),
            )
            out.write(
                latchLockStateRequest(
                    modLocks = 0x04,
                    groupLock = 0,
                    latchGroup = false,
                    groupLatch = 0,
                    affectModLocks = 0x04,
                    affectModLatches = 0x08,
                    modLatches = 0x08,
                ),
            )
            out.write(getStateRequest())
            out.flush()

            val state = readReply(socket.getInputStream())
            assertEquals(3, u16le(state, 2))
            assertEquals(0x0c, state[8].toInt() and 0xff)
            assertEquals(0x08, state[10].toInt() and 0xff)
            assertEquals(0x04, state[11].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents clear removes StateNotify selection`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventStateNotify,
                    selectAll = XXkb.EventStateNotify,
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventStateNotify,
                    clear = XXkb.EventStateNotify,
                ),
            )
            out.write(
                latchLockStateRequest(
                    modLocks = 0x01,
                    groupLock = 0,
                    latchGroup = false,
                    groupLatch = 0,
                    affectModLocks = 0x01,
                ),
            )
            out.write(getStateRequest())
            out.flush()

            val state = readReply(socket.getInputStream())
            assertEquals(4, u16le(state, 2))
            assertEquals(0x01, state[8].toInt() and 0xff)
            assertEquals(0x01, state[11].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD StateNotify reports selected pointer button changes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(
                    selectEventsRequest(
                        affectWhich = XXkb.EventStateNotify,
                        details = selectEvents16Details(
                            affect = XXkb.PointerButtonMask,
                            selected = XXkb.PointerButtonMask,
                        ),
                    ),
                )
                out.write(getStateRequest())
                out.flush()
                readReply(socket.getInputStream())

                server.input.pointerDown(10, 10, button = 1)
                val pressed = socket.getInputStream().readExactly(32)
                assertEquals(XXkb.FirstEvent, pressed[0].toInt() and 0xff)
                assertEquals(XXkb.StateNotify, pressed[1].toInt() and 0xff)
                assertEquals(2, u16le(pressed, 2))
                assertEquals(0x100, u16le(pressed, 24))
                assertEquals(XXkb.PointerButtonMask, u16le(pressed, 26))
                assertEquals(0, pressed[30].toInt() and 0xff)
                assertEquals(0, pressed[31].toInt() and 0xff)

                server.input.pointerUp(10, 10, button = 1)
                val released = socket.getInputStream().readExactly(32)
                assertEquals(XXkb.FirstEvent, released[0].toInt() and 0xff)
                assertEquals(XXkb.StateNotify, released[1].toInt() and 0xff)
                assertEquals(0, u16le(released, 24))
                assertEquals(XXkb.PointerButtonMask, u16le(released, 26))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XKEYBOARD StateNotify reports selected base modifier changes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(
                    selectEventsRequest(
                        affectWhich = XXkb.EventStateNotify,
                        details = selectEvents16Details(
                            affect = XXkb.ModifierStateMask or XXkb.ModifierBaseMask,
                            selected = XXkb.ModifierStateMask or XXkb.ModifierBaseMask,
                        ),
                    ),
                )
                out.write(getStateRequest())
                out.flush()
                readReply(socket.getInputStream())

                server.input.keyDown(XKeyboard.MinKeycode, modifiers = 5)
                val pressed = socket.getInputStream().readExactly(32)
                assertEquals(XXkb.FirstEvent, pressed[0].toInt() and 0xff)
                assertEquals(XXkb.StateNotify, pressed[1].toInt() and 0xff)
                assertEquals(5, pressed[9].toInt() and 0xff)
                assertEquals(5, pressed[10].toInt() and 0xff)
                assertEquals(XXkb.ModifierStateMask or XXkb.ModifierBaseMask, u16le(pressed, 26))
                assertEquals(0, pressed[30].toInt() and 0xff)
                assertEquals(0, pressed[31].toInt() and 0xff)

                server.input.keyUp(XKeyboard.MinKeycode, modifiers = 0)
                val released = socket.getInputStream().readExactly(32)
                assertEquals(XXkb.FirstEvent, released[0].toInt() and 0xff)
                assertEquals(XXkb.StateNotify, released[1].toInt() and 0xff)
                assertEquals(0, released[9].toInt() and 0xff)
                assertEquals(0, released[10].toInt() and 0xff)
                assertEquals(XXkb.ModifierStateMask or XXkb.ModifierBaseMask, u16le(released, 26))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XKEYBOARD LatchLockState rejects invalid modifier masks without changing XKB state`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                latchLockStateRequest(
                    modLocks = 0x04,
                    groupLock = 0,
                    latchGroup = false,
                    groupLatch = 0,
                    affectModLocks = 0x04,
                    lockGroup = false,
                    affectModLatches = 0x08,
                    modLatches = 0x08,
                ),
            )
            out.write(
                latchLockStateRequest(
                    modLocks = 0x01,
                    groupLock = 0,
                    latchGroup = false,
                    groupLatch = 0,
                    affectModLocks = 0,
                    lockGroup = false,
                    affectModLatches = 0x02,
                    modLatches = 0x0a,
                ),
            )
            out.write(getStateRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.LatchLockState)
            val state = readReply(socket.getInputStream())
            assertEquals(3, u16le(state, 2))
            assertEquals(0x0c, state[8].toInt() and 0xff)
            assertEquals(0, state[9].toInt() and 0xff)
            assertEquals(0x08, state[10].toInt() and 0xff)
            assertEquals(0x04, state[11].toInt() and 0xff)
            assertEquals(0x0c, state[18].toInt() and 0xff)
            assertEquals(0x0c, state[19].toInt() and 0xff)
            assertEquals(0x0c, state[20].toInt() and 0xff)
            assertEquals(0x0c, state[21].toInt() and 0xff)
            assertEquals(0x0c, state[22].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetState normalizes group fields to advertised group count`() {
        withServer { socket, port ->
            val out = socket.getOutputStream()
            out.write(getControlsRequest())
            out.write(
                latchLockStateRequest(
                    modLocks = 0,
                    groupLock = 255,
                    latchGroup = true,
                    groupLatch = -1,
                    affectModLocks = 0,
                    affectModLatches = 0,
                    modLatches = 0,
                ),
            )
            out.write(getStateRequest())
            out.flush()

            val controls = readReply(socket.getInputStream())
            assertGetControls(controls, sequence = 1, enabledControls = XXkb.BoolCtrlRepeatKeys)
            assertEquals(1, XXkb.DefaultGroupCount)

            val state = readReply(socket.getInputStream())
            assertEquals(3, u16le(state, 2))
            assertEquals(0, state[12].toInt() and 0xff)
            assertEquals(0, state[13].toInt() and 0xff)
            assertEquals(0, u16le(state, 14))
            assertEquals(0, u16le(state, 16))

            val json = httpGet(port, "/state.json")
            assertContains(json, """"xkbLockedGroup":255""")
            assertContains(json, """"xkbLatchedGroup":-1""")
        }
    }

    @Test
    fun `XKEYBOARD LatchLockState validates fixed request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.LatchLockState, ByteArray(8)))
            out.write(request(XXkb.MajorOpcode, XXkb.LatchLockState, ByteArray(16)))
            out.write(latchLockStateRequest(modLocks = 0, groupLock = 0, latchGroup = false, groupLatch = 0))
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.LatchLockState)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.LatchLockState)
            val version = readReply(socket.getInputStream())
            assertEquals(4, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
        }
    }

    @Test
    fun `XKEYBOARD GetControls returns core keyboard controls`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getControlsRequest())
            out.flush()

            val controls = readReply(socket.getInputStream())
            assertGetControls(controls, sequence = 1, enabledControls = XXkb.BoolCtrlRepeatKeys)
            assertEquals(0xff, controls[60 + 5].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetControls reflects core auto repeat changes`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(changeKeyboardControlRequest(0x40 to 40, 0x80 to 0))
            out.write(getControlsRequest())
            out.write(changeKeyboardControlRequest(0x80 to 0))
            out.write(getControlsRequest())
            out.flush()

            val perKeyControls = readReply(socket.getInputStream())
            assertGetControls(perKeyControls, sequence = 2, enabledControls = XXkb.BoolCtrlRepeatKeys)
            assertEquals(0xfe, perKeyControls[60 + 5].toInt() and 0xff)

            val globalControls = readReply(socket.getInputStream())
            assertGetControls(globalControls, sequence = 4, enabledControls = 0)
            assertEquals(0xfe, globalControls[60 + 5].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetControls validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetControls, ByteArray(0)))
            out.write(getControlsRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetControls)
            val controls = readReply(socket.getInputStream())
            assertGetControls(controls, sequence = 2, enabledControls = XXkb.BoolCtrlRepeatKeys)
        }
    }

    @Test
    fun `XKEYBOARD SetControls updates supported RepeatKeys enabled state`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getControlsRequest())
            out.write(setControlsRequest(affectEnabledControls = XXkb.BoolCtrlRepeatKeys, enabledControls = 0))
            out.write(getControlsRequest())
            out.write(getKeyboardControlRequest())
            out.write(setControlsRequest(affectEnabledControls = XXkb.BoolCtrlRepeatKeys, enabledControls = XXkb.BoolCtrlRepeatKeys))
            out.write(getControlsRequest())
            out.write(getKeyboardControlRequest())
            out.flush()

            assertGetControls(readReply(socket.getInputStream()), sequence = 1, enabledControls = XXkb.BoolCtrlRepeatKeys)
            assertGetControls(readReply(socket.getInputStream()), sequence = 3, enabledControls = 0)
            val disabledCore = readReply(socket.getInputStream())
            assertEquals(4, u16le(disabledCore, 2))
            assertEquals(0, disabledCore[1].toInt() and 0xff)
            assertGetControls(readReply(socket.getInputStream()), sequence = 6, enabledControls = XXkb.BoolCtrlRepeatKeys)
            val enabledCore = readReply(socket.getInputStream())
            assertEquals(7, u16le(enabledCore, 2))
            assertEquals(1, enabledCore[1].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD ControlsNotify reports selected RepeatKeys enabled changes`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventControlsNotify,
                    details = selectEvents32Details(
                        affect = XXkb.ControlEnabledMask,
                        selected = XXkb.ControlEnabledMask,
                    ),
                ),
            )
            out.write(setControlsRequest(affectEnabledControls = XXkb.BoolCtrlRepeatKeys, enabledControls = 0))
            out.write(getControlsRequest())
            out.flush()

            assertControlsNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                changedControls = XXkb.ControlEnabledMask,
                enabledControls = 0,
                enabledControlChanges = XXkb.BoolCtrlRepeatKeys,
            )
            assertGetControls(readReply(socket.getInputStream()), sequence = 3, enabledControls = 0)
        }
    }

    @Test
    fun `XKEYBOARD ControlsNotify selectAll reports RepeatKeys enabled changes`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventControlsNotify,
                    selectAll = XXkb.EventControlsNotify,
                ),
            )
            out.write(setControlsRequest(affectEnabledControls = XXkb.BoolCtrlRepeatKeys, enabledControls = 0))
            out.write(getControlsRequest())
            out.flush()

            assertControlsNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                changedControls = XXkb.ControlEnabledMask,
                enabledControls = 0,
                enabledControlChanges = XXkb.BoolCtrlRepeatKeys,
            )
            assertGetControls(readReply(socket.getInputStream()), sequence = 3, enabledControls = 0)
        }
    }

    @Test
    fun `XKEYBOARD ControlsNotify suppresses unchanged or unselected RepeatKeys changes`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventControlsNotify,
                    details = selectEvents32Details(
                        affect = XXkb.ControlEnabledMask,
                        selected = XXkb.ControlEnabledMask,
                    ),
                ),
            )
            out.write(setControlsRequest(affectEnabledControls = XXkb.BoolCtrlRepeatKeys, enabledControls = 0))
            out.write(setControlsRequest(affectEnabledControls = XXkb.BoolCtrlRepeatKeys, enabledControls = 0))
            out.write(getControlsRequest())
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventControlsNotify,
                    details = selectEvents32Details(
                        affect = XXkb.ControlEnabledMask or XXkb.BoolCtrlRepeatKeys,
                        selected = XXkb.BoolCtrlRepeatKeys,
                    ),
                ),
            )
            out.write(setControlsRequest(affectEnabledControls = XXkb.BoolCtrlRepeatKeys, enabledControls = XXkb.BoolCtrlRepeatKeys))
            out.write(getControlsRequest())
            out.flush()

            assertControlsNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                changedControls = XXkb.ControlEnabledMask,
                enabledControls = 0,
                enabledControlChanges = XXkb.BoolCtrlRepeatKeys,
            )
            assertGetControls(readReply(socket.getInputStream()), sequence = 4, enabledControls = 0)
            assertGetControls(readReply(socket.getInputStream()), sequence = 7, enabledControls = XXkb.BoolCtrlRepeatKeys)
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents clear removes ControlsNotify selection`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventControlsNotify,
                    selectAll = XXkb.EventControlsNotify,
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventControlsNotify,
                    clear = XXkb.EventControlsNotify,
                ),
            )
            out.write(setControlsRequest(affectEnabledControls = XXkb.BoolCtrlRepeatKeys, enabledControls = 0))
            out.write(getControlsRequest())
            out.flush()

            assertGetControls(readReply(socket.getInputStream()), sequence = 4, enabledControls = 0)
        }
    }

    @Test
    fun `XKEYBOARD SetControls ignores unsupported controls and validates fixed length`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setControlsRequest(affectEnabledControls = 1 shl 9, enabledControls = 0))
            out.write(getControlsRequest())
            out.write(request(XXkb.MajorOpcode, XXkb.SetControls, ByteArray(92)))
            out.write(request(XXkb.MajorOpcode, XXkb.SetControls, ByteArray(100)))
            out.write(getControlsRequest())
            out.flush()

            assertGetControls(readReply(socket.getInputStream()), sequence = 2, enabledControls = XXkb.BoolCtrlRepeatKeys)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetControls)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 4, minorOpcode = XXkb.SetControls)
            assertGetControls(readReply(socket.getInputStream()), sequence = 5, enabledControls = XXkb.BoolCtrlRepeatKeys)
        }
    }

    @Test
    fun `XKEYBOARD GetMap reports key range with no map parts`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getMapRequest(full = 0, partial = 0))
            out.flush()

            val map = readReply(socket.getInputStream())
            assertEquals(1, map[0].toInt())
            assertEquals(0, map[1].toInt() and 0xff)
            assertEquals(1, u16le(map, 2))
            assertEquals(2, u32le(map, 4))
            assertEquals(0, u16le(map, 8))
            assertEquals(XKeyboard.MinKeycode, map[10].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, map[11].toInt() and 0xff)
            assertEquals(0, u16le(map, 12))
            assertEquals(0, map[14].toInt() and 0xff)
            assertEquals(0, map[15].toInt() and 0xff)
            assertEquals(0, map[16].toInt() and 0xff)
            assertEquals(0, map[17].toInt() and 0xff)
            assertEquals(0, u16le(map, 18))
            assertEquals(0, map[20].toInt() and 0xff)
            assertEquals(0, map[21].toInt() and 0xff)
            assertEquals(0, u16le(map, 22))
            assertEquals(0, map[24].toInt() and 0xff)
            assertEquals(0, map[25].toInt() and 0xff)
            assertEquals(0, map[26].toInt() and 0xff)
            assertEquals(0, map[27].toInt() and 0xff)
            assertEquals(0, map[28].toInt() and 0xff)
            assertEquals(0, map[29].toInt() and 0xff)
            assertEquals(0, map[30].toInt() and 0xff)
            assertEquals(0, map[31].toInt() and 0xff)
            assertEquals(0, map[32].toInt() and 0xff)
            assertEquals(0, map[33].toInt() and 0xff)
            assertEquals(0, map[34].toInt() and 0xff)
            assertEquals(0, map[35].toInt() and 0xff)
            assertEquals(0, map[36].toInt() and 0xff)
            assertEquals(0, map[37].toInt() and 0xff)
            assertEquals(0, u16le(map, 38))
            assertEquals(40, map.size)
        }
    }

    @Test
    fun `XKEYBOARD GetMap returns requested default key symbols`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartKeySyms, firstKeySym = 38, nKeySyms = 2))
            out.flush()

            val map = readReply(socket.getInputStream())
            assertEquals(1, map[0].toInt())
            assertEquals(1, u16le(map, 2))
            assertEquals(10, u32le(map, 4))
            assertEquals(0, u16le(map, 8))
            assertEquals(XXkb.MapPartKeySyms, u16le(map, 12))
            assertEquals(XKeyboard.MinKeycode, map[10].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, map[11].toInt() and 0xff)
            assertEquals(38, map[17].toInt() and 0xff)
            assertEquals(4, u16le(map, 18))
            assertEquals(2, map[20].toInt() and 0xff)
            val symsOffset = xkbKeySymMapBaseOffset(map)
            assertXkbKeySymMap(map, offset = symsOffset, width = 2, 0x0061, 0x0041)
            assertXkbKeySymMap(map, offset = symsOffset + 16, width = 2, 0x0073, 0x0053)
            assertEquals(72, map.size)
        }
    }

    @Test
    fun `XKEYBOARD MapNotify reports selected core key symbol mapping changes`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventMapNotify,
                    affectMap = XXkb.MapPartKeySyms,
                    map = XXkb.MapPartKeySyms,
                ),
            )
            out.write(changeKeyboardMappingRequest(38, 2, 0x0061, 0x0041, 0x0062, 0x0042))
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartKeySyms, firstKeySym = 38, nKeySyms = 2))
            out.flush()

            assertMapNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                changed = XXkb.MapPartKeySyms,
                firstKeySym = 38,
                nKeySyms = 2,
            )
            assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 2, request = 1, firstKeycode = 38, count = 2)
            val map = readReply(socket.getInputStream())
            assertEquals(3, u16le(map, 2))
            assertEquals(XXkb.MapPartKeySyms, u16le(map, 12))
            assertXkbKeySymMap(map, offset = xkbKeySymMapOffset(map, keycode = 38), width = 2, 0x0061, 0x0041)
            assertXkbKeySymMap(map, offset = xkbKeySymMapOffset(map, keycode = 39), width = 2, 0x0062, 0x0042)
        }
    }

    @Test
    fun `XKEYBOARD MapNotify is suppressed when selected details do not intersect key symbol changes`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventMapNotify,
                    affectMap = XXkb.MapPartModifierMap,
                    map = XXkb.MapPartModifierMap,
                ),
            )
            out.write(changeKeyboardMappingRequest(38, 1, 0x007a))
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartKeySyms, firstKeySym = 38, nKeySyms = 1))
            out.flush()

            assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 2, request = 1, firstKeycode = 38, count = 1)
            val map = readReply(socket.getInputStream())
            assertEquals(3, u16le(map, 2))
            assertXkbKeySymMap(map, offset = xkbKeySymMapOffset(map, keycode = 38), width = 1, 0x007a)
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents clear removes MapNotify selection`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventMapNotify,
                    selectAll = XXkb.EventMapNotify,
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventMapNotify,
                    clear = XXkb.EventMapNotify,
                ),
            )
            out.write(changeKeyboardMappingRequest(38, 1, 0x0078))
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartKeySyms, firstKeySym = 38, nKeySyms = 1))
            out.flush()

            assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 3, request = 1, firstKeycode = 38, count = 1)
            val map = readReply(socket.getInputStream())
            assertEquals(4, u16le(map, 2))
            assertXkbKeySymMap(map, offset = xkbKeySymMapOffset(map, keycode = 38), width = 1, 0x0078)
        }
    }

    @Test
    fun `XKEYBOARD GetMap full key symbols returns complete core range`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getMapRequest(full = XXkb.MapPartKeySyms, partial = 0, firstKeySym = 38, nKeySyms = 1))
            out.flush()

            val map = readReply(socket.getInputStream())
            val keycodeCount = XKeyboard.MaxKeycode - XKeyboard.MinKeycode + 1
            assertEquals(1, u16le(map, 2))
            assertEquals(map.size, 32 + u32le(map, 4) * 4)
            assertEquals(0, u16le(map, 8))
            assertEquals(XXkb.MapPartKeySyms, u16le(map, 12))
            assertEquals(XKeyboard.MinKeycode, map[17].toInt() and 0xff)
            assertEquals(keycodeCount, map[20].toInt() and 0xff)
            assertEquals(xkbTotalKeySyms(map), u16le(map, 18))
            val aOffset = xkbKeySymMapOffset(map, keycode = 38)
            assertXkbKeySymMap(map, offset = aOffset, width = 2, 0x0061, 0x0041)
        }
    }

    @Test
    fun `XKEYBOARD GetMap returns IntelliJ requested key types symbols modifiers and virtual mods`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val requested = XXkb.MapPartKeyTypes or XXkb.MapPartKeySyms or XXkb.MapPartModifierMap or XXkb.MapPartVirtualMods
            out.write(getMapRequest(full = requested, partial = 0, firstKeySym = 38, nKeySyms = 1))
            out.flush()

            val map = readReply(socket.getInputStream())
            val keycodeCount = XKeyboard.MaxKeycode - XKeyboard.MinKeycode + 1
            assertEquals(1, u16le(map, 2))
            assertEquals(map.size, 32 + u32le(map, 4) * 4)
            assertEquals(0, u16le(map, 8))
            assertEquals(requested, u16le(map, 12))
            assertEquals(XKeyboard.MinKeycode, map[10].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, map[11].toInt() and 0xff)
            assertEquals(0, map[14].toInt() and 0xff)
            assertEquals(4, map[15].toInt() and 0xff)
            assertEquals(4, map[16].toInt() and 0xff)
            assertEquals(XKeyboard.MinKeycode, map[17].toInt() and 0xff)
            assertEquals(keycodeCount, map[20].toInt() and 0xff)
            assertEquals(xkbTotalKeySyms(map), u16le(map, 18))
            assertEquals(XKeyboard.MinKeycode, map[31].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode - XKeyboard.MinKeycode + 1, map[32].toInt() and 0xff)
            assertEquals(9, map[33].toInt() and 0xff)
            assertEquals(0, u16le(map, 38))

            assertXkbDefaultKeyTypes(map, offset = 40)
            assertXkbKeySymMap(map, offset = xkbKeySymMapOffset(map, keycode = 38), width = 2, 0x0061, 0x0041)
            assertXkbModifierMap(
                map,
                37 to 0x04,
                50 to 0x01,
                62 to 0x01,
                64 to 0x08,
                66 to 0x02,
                105 to 0x04,
                108 to 0x08,
                133 to 0x40,
                134 to 0x40,
            )
        }
    }

    @Test
    fun `XKEYBOARD GetMap filters partial modifier map to requested keycode range`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                getMapRequest(
                    full = 0,
                    partial = XXkb.MapPartModifierMap,
                    firstModMapKey = 50,
                    nModMapKeys = 1,
                ),
            )
            out.flush()

            val map = readReply(socket.getInputStream())
            assertEquals(1, u16le(map, 2))
            assertEquals(map.size, 32 + u32le(map, 4) * 4)
            assertEquals(0, u16le(map, 8))
            assertEquals(XXkb.MapPartModifierMap, u16le(map, 12))
            assertEquals(XKeyboard.MinKeycode, map[10].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, map[11].toInt() and 0xff)
            assertEquals(50, map[31].toInt() and 0xff)
            assertEquals(1, map[32].toInt() and 0xff)
            assertEquals(1, map[33].toInt() and 0xff)
            assertEquals(0, u16le(map, 38))
            assertXkbModifierMap(map, 50 to 0x01)
            assertEquals(44, map.size)
        }
    }

    @Test
    fun `XKEYBOARD GetMap validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetMap, ByteArray(20)))
            out.write(getMapRequest(full = 0, partial = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetMap)
            val map = readReply(socket.getInputStream())
            assertEquals(2, u16le(map, 2))
            assertEquals(2, u32le(map, 4))
            assertEquals(XKeyboard.MinKeycode, map[10].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, map[11].toInt() and 0xff)
            assertEquals(0, u16le(map, 12))
            assertEquals(40, map.size)
        }
    }

    @Test
    fun `XKEYBOARD GetMap validates map part masks and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val unknownPart = 1 shl 8
            out.write(getMapRequest(full = unknownPart, partial = 0))
            out.write(getMapRequest(full = 0, partial = unknownPart))
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartModifierMap, firstModMapKey = 50, nModMapKeys = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = unknownPart, sequence = 1, minorOpcode = XXkb.GetMap)
            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = unknownPart, sequence = 2, minorOpcode = XXkb.GetMap)
            val map = readReply(socket.getInputStream())
            assertEquals(3, u16le(map, 2))
            assertEquals(XXkb.MapPartModifierMap, u16le(map, 12))
            assertXkbModifierMap(map, 50 to 0x01)
        }
    }

    @Test
    fun `XKEYBOARD GetMap validates partial modifier map keycode ranges`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartModifierMap, firstModMapKey = 7, nModMapKeys = 1))
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartModifierMap, firstModMapKey = XKeyboard.MaxKeycode, nModMapKeys = 2))
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartModifierMap, firstModMapKey = 50, nModMapKeys = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 7, sequence = 1, minorOpcode = XXkb.GetMap)
            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = XKeyboard.MaxKeycode, sequence = 2, minorOpcode = XXkb.GetMap)
            val map = readReply(socket.getInputStream())
            assertEquals(3, u16le(map, 2))
            assertEquals(XXkb.MapPartModifierMap, u16le(map, 12))
            assertXkbModifierMap(map, 50 to 0x01)
        }
    }

    @Test
    fun `XKEYBOARD SetMap persists key symbols from full map payload`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setMapRequest(includeAllParts = true))
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartKeySyms, firstKeySym = 38, nKeySyms = 2))
            out.flush()

            assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 1, request = 0, firstKeycode = 0, count = 0)
            val map = readReply(socket.getInputStream())
            assertEquals(2, u16le(map, 2))
            assertEquals(9, u32le(map, 4))
            assertEquals(XXkb.MapPartKeySyms, u16le(map, 12))
            assertEquals(XKeyboard.MinKeycode, map[10].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, map[11].toInt() and 0xff)
            assertEquals(38, map[17].toInt() and 0xff)
            assertEquals(3, u16le(map, 18))
            assertEquals(2, map[20].toInt() and 0xff)
            assertXkbKeySymMap(map, offset = 40, width = 2, 0x0078, 0x0058)
            assertXkbKeySymMap(map, offset = 56, width = 1, 0x0079)
            assertEquals(68, map.size)
        }
    }

    @Test
    fun `XKEYBOARD SetMap accepts odd explicit and modifier map counts with padding`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setMapRequest(includeAllParts = true, oddExplicitAndModifierMapCounts = true))
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartKeySyms, firstKeySym = 38, nKeySyms = 1))
            out.flush()

            assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 1, request = 0, firstKeycode = 0, count = 0)
            val map = readReply(socket.getInputStream())
            assertEquals(2, u16le(map, 2))
            assertEquals(6, u32le(map, 4))
            assertEquals(XXkb.MapPartKeySyms, u16le(map, 12))
            assertEquals(38, map[17].toInt() and 0xff)
            assertEquals(2, u16le(map, 18))
            assertEquals(1, map[20].toInt() and 0xff)
            assertXkbKeySymMap(map, offset = 40, width = 2, 0x0078, 0x0058)
            assertEquals(56, map.size)
        }
    }

    @Test
    fun `XKEYBOARD SetMap emits selected MapNotify for key symbols`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventMapNotify,
                    affectMap = XXkb.MapPartKeySyms,
                    map = XXkb.MapPartKeySyms,
                ),
            )
            out.write(setMapKeySymsRequest())
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartKeySyms, firstKeySym = 38, nKeySyms = 1))
            out.flush()

            assertMapNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                changed = XXkb.MapPartKeySyms,
                firstKeySym = 38,
                nKeySyms = 2,
            )
            val map = readReply(socket.getInputStream())
            assertEquals(3, u16le(map, 2))
            assertEquals(XXkb.MapPartKeySyms, u16le(map, 12))
            assertXkbKeySymMap(map, offset = 40, width = 2, 0x0078, 0x0058)
        }
    }

    @Test
    fun `XKEYBOARD SetMap suppresses MapNotify when selected details do not intersect key symbols`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventMapNotify,
                    affectMap = XXkb.MapPartModifierMap,
                    map = XXkb.MapPartModifierMap,
                ),
            )
            out.write(setMapKeySymsRequest())
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartKeySyms, firstKeySym = 38, nKeySyms = 1))
            out.flush()

            val map = readReply(socket.getInputStream())
            assertEquals(3, u16le(map, 2))
            assertEquals(XXkb.MapPartKeySyms, u16le(map, 12))
            assertXkbKeySymMap(map, offset = 40, width = 2, 0x0078, 0x0058)
        }
    }

    @Test
    fun `XKEYBOARD SetMap validates key symbol range and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setMapKeySymsRequest(firstKeySym = XKeyboard.MaxKeycode))
            out.write(getMapRequest(full = 0, partial = 0))
            out.flush()

            assertError(
                socket.getInputStream(),
                error = 8,
                opcode = XXkb.MajorOpcode,
                badValue = XKeyboard.MaxKeycode,
                sequence = 1,
                minorOpcode = XXkb.SetMap,
            )
            val map = readReply(socket.getInputStream())
            assertEquals(2, u16le(map, 2))
            assertEquals(0, u16le(map, 12))
        }
    }

    @Test
    fun `XKEYBOARD SetMap persists modifier map into core state`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setMapModifierMapRequest(firstModMapKey = 77, nModMapKeys = 1, entries = listOf(77 to 0x08)))
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartModifierMap, firstModMapKey = 77, nModMapKeys = 1))
            out.flush()

            assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 1, request = 0, firstKeycode = 0, count = 0)
            val map = readReply(socket.getInputStream())
            assertEquals(2, u16le(map, 2))
            assertEquals(XXkb.MapPartModifierMap, u16le(map, 12))
            assertEquals(77, map[31].toInt() and 0xff)
            assertEquals(1, map[32].toInt() and 0xff)
            assertEquals(1, map[33].toInt() and 0xff)
            assertXkbModifierMap(map, 77 to 0x08)
        }
    }

    @Test
    fun `XKEYBOARD SetMap updates modifier map while changed key is down`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventMapNotify,
                    affectMap = XXkb.MapPartModifierMap,
                    map = XXkb.MapPartModifierMap,
                ),
            )
            out.write(xtestFakeInputRequest(type = XXTest.KeyPress, detail = 77, x = 0, y = 0))
            out.write(setMapModifierMapRequest(firstModMapKey = 77, nModMapKeys = 1, entries = listOf(77 to 0x08)))
            out.write(xtestFakeInputRequest(type = XXTest.KeyRelease, detail = 77, x = 0, y = 0))
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartModifierMap, firstModMapKey = 77, nModMapKeys = 1))
            out.flush()

            assertMapNotify(
                socket.getInputStream().readExactly(32),
                sequence = 3,
                changed = XXkb.MapPartModifierMap,
                firstKeySym = 0,
                nKeySyms = 0,
                firstModMapKey = 77,
                nModMapKeys = 1,
            )
            assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 3, request = 0, firstKeycode = 0, count = 0)
            val map = readReply(socket.getInputStream())
            assertEquals(5, u16le(map, 2))
            assertEquals(XXkb.MapPartModifierMap, u16le(map, 12))
            assertXkbModifierMap(map, 77 to 0x08)
        }
    }

    @Test
    fun `XKEYBOARD SetMap emits selected MapNotify and core MappingNotify for modifier map`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventMapNotify,
                    affectMap = XXkb.MapPartModifierMap,
                    map = XXkb.MapPartModifierMap,
                ),
            )
            out.write(setMapModifierMapRequest(firstModMapKey = 77, nModMapKeys = 1, entries = listOf(77 to 0x08)))
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartModifierMap, firstModMapKey = 77, nModMapKeys = 1))
            out.flush()

            assertMapNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                changed = XXkb.MapPartModifierMap,
                firstKeySym = 0,
                nKeySyms = 0,
                firstModMapKey = 77,
                nModMapKeys = 1,
            )
            assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 2, request = 0, firstKeycode = 0, count = 0)
            val map = readReply(socket.getInputStream())
            assertEquals(3, u16le(map, 2))
            assertEquals(XXkb.MapPartModifierMap, u16le(map, 12))
            assertXkbModifierMap(map, 77 to 0x08)
        }
    }

    @Test
    fun `XKEYBOARD SetMap suppresses modifier MapNotify when selected details do not intersect`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventMapNotify,
                    affectMap = XXkb.MapPartKeySyms,
                    map = XXkb.MapPartKeySyms,
                ),
            )
            out.write(setMapModifierMapRequest(firstModMapKey = 77, nModMapKeys = 1, entries = listOf(77 to 0x08)))
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartModifierMap, firstModMapKey = 77, nModMapKeys = 1))
            out.flush()

            assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 2, request = 0, firstKeycode = 0, count = 0)
            val map = readReply(socket.getInputStream())
            assertEquals(3, u16le(map, 2))
            assertEquals(XXkb.MapPartModifierMap, u16le(map, 12))
            assertXkbModifierMap(map, 77 to 0x08)
        }
    }

    @Test
    fun `XKEYBOARD SetMap persists virtual modifiers`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setMapVirtualModsRequest(virtualMods = 0x0003, realModifiers = listOf(0x02, 0x04)))
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartVirtualMods, virtualMods = 0x0003))
            out.flush()

            val map = readReply(socket.getInputStream())
            assertEquals(2, u16le(map, 2))
            assertEquals(XXkb.MapPartVirtualMods, u16le(map, 12))
            assertEquals(0x0003, u16le(map, 38))
            assertXkbVirtualMods(map, 0x02, 0x04)
        }
    }

    @Test
    fun `XKEYBOARD SetMap emits selected MapNotify for virtual modifiers`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventMapNotify,
                    affectMap = XXkb.MapPartVirtualMods,
                    map = XXkb.MapPartVirtualMods,
                ),
            )
            out.write(setMapVirtualModsRequest(virtualMods = 0x0003, realModifiers = listOf(0x02, 0x04)))
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartVirtualMods, virtualMods = 0x0003))
            out.flush()

            assertMapNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                changed = XXkb.MapPartVirtualMods,
                firstKeySym = 0,
                nKeySyms = 0,
                virtualMods = 0x0003,
            )
            val map = readReply(socket.getInputStream())
            assertEquals(3, u16le(map, 2))
            assertEquals(XXkb.MapPartVirtualMods, u16le(map, 12))
            assertXkbVirtualMods(map, 0x02, 0x04)
        }
    }

    @Test
    fun `XKEYBOARD SetMap suppresses virtual modifier MapNotify when selected details do not intersect`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventMapNotify,
                    affectMap = XXkb.MapPartKeySyms,
                    map = XXkb.MapPartKeySyms,
                ),
            )
            out.write(setMapVirtualModsRequest(virtualMods = 0x0003, realModifiers = listOf(0x02, 0x04)))
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartVirtualMods, virtualMods = 0x0003))
            out.flush()

            val map = readReply(socket.getInputStream())
            assertEquals(3, u16le(map, 2))
            assertEquals(XXkb.MapPartVirtualMods, u16le(map, 12))
            assertXkbVirtualMods(map, 0x02, 0x04)
        }
    }

    @Test
    fun `XKEYBOARD SetMap validates modifier map range and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                setMapModifierMapRequest(
                    firstModMapKey = XKeyboard.MaxKeycode,
                    nModMapKeys = 2,
                    entries = listOf(XKeyboard.MaxKeycode to 0x08),
                ),
            )
            out.write(getMapRequest(full = 0, partial = 0))
            out.flush()

            assertError(
                socket.getInputStream(),
                error = 8,
                opcode = XXkb.MajorOpcode,
                badValue = XKeyboard.MaxKeycode,
                sequence = 1,
                minorOpcode = XXkb.SetMap,
            )
            val map = readReply(socket.getInputStream())
            assertEquals(2, u16le(map, 2))
            assertEquals(0, u16le(map, 12))
        }
    }

    @Test
    fun `XKEYBOARD SetMap rejects invalid modifier map without partial key symbol update`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                setMapRequest(
                    includeAllParts = true,
                    firstModMapKey = XKeyboard.MaxKeycode,
                    nModMapKeys = 2,
                    modifierMapEntries = listOf(XKeyboard.MaxKeycode to 0x08),
                ),
            )
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartKeySyms, firstKeySym = 38, nKeySyms = 1))
            out.flush()

            assertError(
                socket.getInputStream(),
                error = 8,
                opcode = XXkb.MajorOpcode,
                badValue = XKeyboard.MaxKeycode,
                sequence = 1,
                minorOpcode = XXkb.SetMap,
            )
            val map = readReply(socket.getInputStream())
            assertEquals(2, u16le(map, 2))
            assertEquals(XXkb.MapPartKeySyms, u16le(map, 12))
            assertXkbKeySymMap(map, offset = 40, width = 2, 0x0061, 0x0041)
        }
    }

    @Test
    fun `XKEYBOARD SetMap rejects invalid modifier map without partial virtual modifier update`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                setMapRequest(
                    includeAllParts = true,
                    firstModMapKey = XKeyboard.MaxKeycode,
                    nModMapKeys = 2,
                    modifierMapEntries = listOf(XKeyboard.MaxKeycode to 0x08),
                ),
            )
            out.write(getMapRequest(full = 0, partial = XXkb.MapPartVirtualMods, virtualMods = 0x0003))
            out.flush()

            assertError(
                socket.getInputStream(),
                error = 8,
                opcode = XXkb.MajorOpcode,
                badValue = XKeyboard.MaxKeycode,
                sequence = 1,
                minorOpcode = XXkb.SetMap,
            )
            val map = readReply(socket.getInputStream())
            assertEquals(2, u16le(map, 2))
            assertEquals(XXkb.MapPartVirtualMods, u16le(map, 12))
            assertEquals(0, u16le(map, 38))
            assertEquals(40, map.size)
        }
    }

    @Test
    fun `XKEYBOARD SetMap validates variable payload length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SetMap, ByteArray(28)))
            out.write(setMapRequest(includeAllParts = true, bodySize = setMapBodySize(includeAllParts = true) - 4))
            out.write(setMapRequest(includeAllParts = true, bodySize = setMapBodySize(includeAllParts = true) + 4))
            out.write(setMapRequest(includeAllParts = false))
            out.write(getMapRequest(full = 0, partial = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetMap)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetMap)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetMap)
            val map = readReply(socket.getInputStream())
            assertEquals(5, u16le(map, 2))
            assertEquals(2, u32le(map, 4))
            assertEquals(XKeyboard.MinKeycode, map[10].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, map[11].toInt() and 0xff)
            assertEquals(0, u16le(map, 12))
            assertEquals(40, map.size)
        }
    }

    @Test
    fun `XKEYBOARD GetCompatMap returns empty compatibility map`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getCompatMapRequest(groups = -1, getAllSI = true, firstSI = 0, nSI = 0xffff))
            out.flush()

            val compatMap = readReply(socket.getInputStream())
            assertEquals(1, compatMap[0].toInt())
            assertEquals(0, compatMap[1].toInt() and 0xff)
            assertEquals(1, u16le(compatMap, 2))
            assertEquals(0, u32le(compatMap, 4))
            assertEquals(0, compatMap[8].toInt() and 0xff)
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(0, u16le(compatMap, 12))
            assertEquals(0, u16le(compatMap, 14))
            assertEquals(32, compatMap.size)
        }
    }

    @Test
    fun `XKEYBOARD GetCompatMap validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetCompatMap, ByteArray(4)))
            out.write(getCompatMapRequest(groups = 0, getAllSI = false, firstSI = 0, nSI = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetCompatMap)
            val compatMap = readReply(socket.getInputStream())
            assertEquals(2, u16le(compatMap, 2))
            assertEquals(0, compatMap[1].toInt() and 0xff)
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(0, u16le(compatMap, 12))
            assertEquals(0, u16le(compatMap, 14))
        }
    }

    @Test
    fun `XKEYBOARD SetCompatMap accepts symbol interpretations without changing empty group map`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setCompatMapRequest(groups = 0x5, nSI = 2))
            out.write(getCompatMapRequest(groups = 0, getAllSI = false, firstSI = 0, nSI = 0))
            out.flush()

            val compatMap = readReply(socket.getInputStream())
            assertEquals(2, u16le(compatMap, 2))
            assertEquals(0, compatMap[1].toInt() and 0xff)
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(0, u16le(compatMap, 12))
            assertEquals(2, u16le(compatMap, 14))
        }
    }

    @Test
    fun `XKEYBOARD SetCompatMap persists requested group compatibility maps`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                setCompatMapRequest(
                    groups = 0x5,
                    nSI = 0,
                    groupMaps = listOf(
                        byteArrayOf(0x01, 0x02, 0x34, 0x12),
                        byteArrayOf(0x04, 0x08, 0x78, 0x56),
                    ),
                ),
            )
            out.write(getCompatMapRequest(groups = -1, getAllSI = true, firstSI = 0, nSI = 0xffff))
            out.flush()

            val compatMap = readReply(socket.getInputStream())
            assertEquals(2, u16le(compatMap, 2))
            assertEquals(2, u32le(compatMap, 4))
            assertEquals(0x5, compatMap[8].toInt() and 0xff)
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(0, u16le(compatMap, 12))
            assertEquals(0, u16le(compatMap, 14))
            assertEquals(0x01, compatMap[32].toInt() and 0xff)
            assertEquals(0x02, compatMap[33].toInt() and 0xff)
            assertEquals(0x1234, u16le(compatMap, 34))
            assertEquals(0x04, compatMap[36].toInt() and 0xff)
            assertEquals(0x08, compatMap[37].toInt() and 0xff)
            assertEquals(0x5678, u16le(compatMap, 38))
            assertEquals(40, compatMap.size)
        }
    }

    @Test
    fun `XKEYBOARD GetCompatMap returns intersection of requested persisted groups`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                setCompatMapRequest(
                    groups = 0x7,
                    nSI = 0,
                    groupMaps = listOf(
                        byteArrayOf(0x01, 0x01, 0x11, 0x11),
                        byteArrayOf(0x02, 0x02, 0x22, 0x22),
                        byteArrayOf(0x03, 0x03, 0x33, 0x33),
                    ),
                ),
            )
            out.write(getCompatMapRequest(groups = 0x2, getAllSI = false, firstSI = 0, nSI = 0))
            out.flush()

            val compatMap = readReply(socket.getInputStream())
            assertEquals(2, u16le(compatMap, 2))
            assertEquals(1, u32le(compatMap, 4))
            assertEquals(0x2, compatMap[8].toInt() and 0xff)
            assertEquals(0x02, compatMap[32].toInt() and 0xff)
            assertEquals(0x02, compatMap[33].toInt() and 0xff)
            assertEquals(0x2222, u16le(compatMap, 34))
            assertEquals(36, compatMap.size)
        }
    }

    @Test
    fun `XKEYBOARD SetCompatMap persists symbol interpretations before group maps`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val firstInterpret = byteArrayOf(
                0x11, 0x22, 0x33, 0x44,
                0x55, 0x66, 0x77, 0x08,
                0x01, 0x02, 0x03, 0x04,
                0x05, 0x06, 0x07, 0x08,
            )
            val secondInterpret = byteArrayOf(
                0x21, 0x32, 0x43, 0x54,
                0x65, 0x76, 0x07, 0x18,
                0x11, 0x12, 0x13, 0x14,
                0x15, 0x16, 0x17, 0x18,
            )
            out.write(
                setCompatMapRequest(
                    groups = 0x1,
                    nSI = 2,
                    symInterprets = listOf(firstInterpret, secondInterpret),
                    groupMaps = listOf(byteArrayOf(0x01, 0x04, 0x34, 0x12)),
                ),
            )
            out.write(getCompatMapRequest(groups = 0x1, getAllSI = true, firstSI = 99, nSI = 99))
            out.flush()

            val compatMap = readReply(socket.getInputStream())
            assertEquals(2, u16le(compatMap, 2))
            assertEquals(9, u32le(compatMap, 4))
            assertEquals(0x1, compatMap[8].toInt() and 0xff)
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(2, u16le(compatMap, 12))
            assertEquals(2, u16le(compatMap, 14))
            assertContentEquals(firstInterpret, compatMap.copyOfRange(32, 48))
            assertContentEquals(secondInterpret, compatMap.copyOfRange(48, 64))
            assertEquals(0x01, compatMap[64].toInt() and 0xff)
            assertEquals(0x04, compatMap[65].toInt() and 0xff)
            assertEquals(0x1234, u16le(compatMap, 66))
            assertEquals(68, compatMap.size)
        }
    }

    @Test
    fun `XKEYBOARD GetCompatMap returns requested symbol interpretation subset`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val firstInterpret = ByteArray(16) { index -> (0x10 + index).toByte() }
            val secondInterpret = ByteArray(16) { index -> (0x40 + index).toByte() }
            out.write(
                setCompatMapRequest(
                    groups = 0,
                    nSI = 2,
                    symInterprets = listOf(firstInterpret, secondInterpret),
                ),
            )
            out.write(getCompatMapRequest(groups = 0, getAllSI = false, firstSI = 1, nSI = 1))
            out.flush()

            val compatMap = readReply(socket.getInputStream())
            assertEquals(2, u16le(compatMap, 2))
            assertEquals(4, u32le(compatMap, 4))
            assertEquals(0, compatMap[8].toInt() and 0xff)
            assertEquals(1, u16le(compatMap, 10))
            assertEquals(1, u16le(compatMap, 12))
            assertEquals(2, u16le(compatMap, 14))
            assertContentEquals(secondInterpret, compatMap.copyOfRange(32, 48))
            assertEquals(48, compatMap.size)
        }
    }

    @Test
    fun `XKEYBOARD CompatMapNotify reports total symbol interpretations after partial update`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val firstInterpret = ByteArray(16) { index -> (0x10 + index).toByte() }
            val secondInterpret = ByteArray(16) { index -> (0x20 + index).toByte() }
            val replacement = ByteArray(16) { index -> (0x50 + index).toByte() }
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventCompatMapNotify,
                    details = selectEvents8Details(XXkb.CompatMapSymInterpret, XXkb.CompatMapSymInterpret),
                ),
            )
            out.write(
                setCompatMapRequest(
                    groups = 0,
                    nSI = 2,
                    symInterprets = listOf(firstInterpret, secondInterpret),
                ),
            )
            out.write(
                setCompatMapRequest(
                    groups = 0,
                    firstSI = 1,
                    nSI = 1,
                    truncateSI = false,
                    symInterprets = listOf(replacement),
                ),
            )
            out.write(getCompatMapRequest(groups = 0, getAllSI = true, firstSI = 0, nSI = 0))
            out.flush()

            assertCompatMapNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                changedGroups = 0,
                firstSI = 0,
                nSI = 2,
                nTotalSI = 2,
            )
            assertCompatMapNotify(
                socket.getInputStream().readExactly(32),
                sequence = 3,
                changedGroups = 0,
                firstSI = 1,
                nSI = 1,
                nTotalSI = 2,
            )
            val compatMap = readReply(socket.getInputStream())
            assertEquals(4, u16le(compatMap, 2))
            assertEquals(8, u32le(compatMap, 4))
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(2, u16le(compatMap, 12))
            assertEquals(2, u16le(compatMap, 14))
            assertContentEquals(firstInterpret, compatMap.copyOfRange(32, 48))
            assertContentEquals(replacement, compatMap.copyOfRange(48, 64))
            assertEquals(64, compatMap.size)
        }
    }

    @Test
    fun `XKEYBOARD SetCompatMap truncateSI shrinks symbol interpretation list`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val firstInterpret = ByteArray(16) { index -> (0x10 + index).toByte() }
            val secondInterpret = ByteArray(16) { index -> (0x20 + index).toByte() }
            val replacement = ByteArray(16) { index -> (0x70 + index).toByte() }
            out.write(
                setCompatMapRequest(
                    groups = 0,
                    nSI = 2,
                    symInterprets = listOf(firstInterpret, secondInterpret),
                ),
            )
            out.write(
                setCompatMapRequest(
                    groups = 0,
                    firstSI = 0,
                    nSI = 1,
                    truncateSI = true,
                    symInterprets = listOf(replacement),
                ),
            )
            out.write(getCompatMapRequest(groups = 0, getAllSI = true, firstSI = 0, nSI = 0))
            out.flush()

            val compatMap = readReply(socket.getInputStream())
            assertEquals(3, u16le(compatMap, 2))
            assertEquals(4, u32le(compatMap, 4))
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(1, u16le(compatMap, 12))
            assertEquals(1, u16le(compatMap, 14))
            assertContentEquals(replacement, compatMap.copyOfRange(32, 48))
            assertEquals(48, compatMap.size)
        }
    }

    @Test
    fun `XKEYBOARD GetCompatMap rejects undefined symbol interpretation range and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val original = ByteArray(16) { index -> (0x10 + index).toByte() }
            out.write(setCompatMapRequest(groups = 0, nSI = 1, symInterprets = listOf(original)))
            out.write(getCompatMapRequest(groups = 0, getAllSI = false, firstSI = 1, nSI = 1))
            out.write(getCompatMapRequest(groups = 0, getAllSI = true, firstSI = 0, nSI = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 1, sequence = 2, minorOpcode = XXkb.GetCompatMap)
            val compatMap = readReply(socket.getInputStream())
            assertEquals(3, u16le(compatMap, 2))
            assertEquals(4, u32le(compatMap, 4))
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(1, u16le(compatMap, 12))
            assertEquals(1, u16le(compatMap, 14))
            assertContentEquals(original, compatMap.copyOfRange(32, 48))
            assertEquals(48, compatMap.size)
        }
    }

    @Test
    fun `XKEYBOARD SetCompatMap rejects impossible symbol interpretation range without mutation`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val original = ByteArray(16) { index -> (index + 1).toByte() }
            val replacement = ByteArray(16) { index -> (0x60 + index).toByte() }
            out.write(setCompatMapRequest(groups = 0, nSI = 1, symInterprets = listOf(original)))
            out.write(setCompatMapRequest(groups = 0x1, firstSI = 3, nSI = 1, symInterprets = listOf(replacement)))
            out.write(getCompatMapRequest(groups = 0x1, getAllSI = true, firstSI = 0, nSI = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 3, sequence = 2, minorOpcode = XXkb.SetCompatMap)
            val compatMap = readReply(socket.getInputStream())
            assertEquals(3, u16le(compatMap, 2))
            assertEquals(4, u32le(compatMap, 4))
            assertEquals(0, compatMap[8].toInt() and 0xff)
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(1, u16le(compatMap, 12))
            assertEquals(1, u16le(compatMap, 14))
            assertContentEquals(original, compatMap.copyOfRange(32, 48))
            assertEquals(48, compatMap.size)
        }
    }

    @Test
    fun `XKEYBOARD CompatMapNotify reports selected SetCompatMap changes`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventCompatMapNotify,
                    details = selectEvents8Details(XXkb.AllCompatMapMask, XXkb.AllCompatMapMask),
                ),
            )
            out.write(setCompatMapRequest(groups = 0x5, firstSI = 0, nSI = 2))
            out.write(getCompatMapRequest(groups = 0, getAllSI = false, firstSI = 0, nSI = 0))
            out.flush()

            assertCompatMapNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                changedGroups = 0x5,
                firstSI = 0,
                nSI = 2,
                nTotalSI = 2,
            )
            val compatMap = readReply(socket.getInputStream())
            assertEquals(3, u16le(compatMap, 2))
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(0, u16le(compatMap, 12))
            assertEquals(2, u16le(compatMap, 14))
        }
    }

    @Test
    fun `XKEYBOARD CompatMapNotify is suppressed when selected details do not intersect changes`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventCompatMapNotify,
                    details = selectEvents8Details(XXkb.CompatMapSymInterpret, XXkb.CompatMapSymInterpret),
                ),
            )
            out.write(setCompatMapRequest(groups = 0x2, nSI = 0))
            out.write(getCompatMapRequest(groups = 0, getAllSI = false, firstSI = 0, nSI = 0))
            out.flush()

            val compatMap = readReply(socket.getInputStream())
            assertEquals(3, u16le(compatMap, 2))
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(0, u16le(compatMap, 12))
            assertEquals(0, u16le(compatMap, 14))
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents clear removes CompatMapNotify selection`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventCompatMapNotify,
                    selectAll = XXkb.EventCompatMapNotify,
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventCompatMapNotify,
                    clear = XXkb.EventCompatMapNotify,
                ),
            )
            out.write(setCompatMapRequest(groups = 0, nSI = 1))
            out.write(getCompatMapRequest(groups = 0, getAllSI = false, firstSI = 0, nSI = 0))
            out.flush()

            val compatMap = readReply(socket.getInputStream())
            assertEquals(4, u16le(compatMap, 2))
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(0, u16le(compatMap, 12))
            assertEquals(1, u16le(compatMap, 14))
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents validates CompatMapNotify details and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventCompatMapNotify,
                    details = selectEvents8Details(XXkb.CompatMapSymInterpret, XXkb.CompatMapGroupCompat),
                ),
            )
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SelectEvents)
            val version = readReply(socket.getInputStream())
            assertEquals(2, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
        }
    }

    @Test
    fun `XKEYBOARD SetCompatMap rejects out of range group masks and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setCompatMapRequest(groups = 0x10, nSI = 0))
            out.write(getCompatMapRequest(groups = -1, getAllSI = true, firstSI = 0, nSI = 0xffff))
            out.flush()

            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 0x10, sequence = 1, minorOpcode = XXkb.SetCompatMap)
            val compatMap = readReply(socket.getInputStream())
            assertEquals(2, u16le(compatMap, 2))
            assertEquals(0, u32le(compatMap, 4))
            assertEquals(0, compatMap[8].toInt() and 0xff)
            assertEquals(32, compatMap.size)
        }
    }

    @Test
    fun `XKEYBOARD SetCompatMap validates variable payload length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SetCompatMap, ByteArray(8)))
            out.write(setCompatMapRequest(groups = 0x3, nSI = 1, bodySize = 28))
            out.write(setCompatMapRequest(groups = 0x3, nSI = 1, bodySize = 40))
            out.write(setCompatMapRequest(groups = 0, nSI = 0))
            out.write(getCompatMapRequest(groups = 0, getAllSI = false, firstSI = 0, nSI = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetCompatMap)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetCompatMap)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetCompatMap)
            val compatMap = readReply(socket.getInputStream())
            assertEquals(5, u16le(compatMap, 2))
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(0, u16le(compatMap, 12))
            assertEquals(0, u16le(compatMap, 14))
        }
    }

    @Test
    fun `XKEYBOARD indicator queries return empty state and map`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getIndicatorStateRequest())
            out.write(getIndicatorMapRequest(which = -1))
            out.flush()

            val state = readReply(socket.getInputStream())
            assertEquals(0, state[1].toInt() and 0xff)
            assertEquals(1, u16le(state, 2))
            assertEquals(0, u32le(state, 4))
            assertEquals(0, u32le(state, 8))

            val map = readReply(socket.getInputStream())
            assertEquals(0, map[1].toInt() and 0xff)
            assertEquals(2, u16le(map, 2))
            assertEquals(0, u32le(map, 4))
            assertEquals(0, u32le(map, 8))
            assertEquals(0, u32le(map, 12))
            assertEquals(0, map[16].toInt() and 0xff)
            assertEquals(32, map.size)
        }
    }

    @Test
    fun `XKEYBOARD indicator queries validate request lengths and recover stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetIndicatorState, ByteArray(0)))
            out.write(getIndicatorStateRequest())
            out.write(request(XXkb.MajorOpcode, XXkb.GetIndicatorMap, ByteArray(4)))
            out.write(getIndicatorMapRequest(which = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetIndicatorState)
            val state = readReply(socket.getInputStream())
            assertEquals(2, u16le(state, 2))
            assertEquals(0, u32le(state, 8))

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.GetIndicatorMap)
            val map = readReply(socket.getInputStream())
            assertEquals(4, u16le(map, 2))
            assertEquals(0, u32le(map, 8))
            assertEquals(0, map[16].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SetIndicatorMap persists map records without creating indicator state`() {
        withServer { socket, _ ->
            val records = listOf(indicatorMapRecord(1), indicatorMapRecord(2))
            val out = socket.getOutputStream()
            out.write(setIndicatorMapRequest(which = 0x3, maps = records))
            out.write(getIndicatorMapRequest(which = 0x3))
            out.write(getIndicatorStateRequest())
            out.flush()

            val map = readReply(socket.getInputStream())
            assertEquals(2, u16le(map, 2))
            assertEquals(6, u32le(map, 4))
            assertEquals(0x3, u32le(map, 8))
            assertEquals(0, u32le(map, 12))
            assertEquals(2, map[16].toInt() and 0xff)
            assertIndicatorMaps(map, records)

            val state = readReply(socket.getInputStream())
            assertEquals(3, u16le(state, 2))
            assertEquals(0, u32le(state, 8))
        }
    }

    @Test
    fun `XKEYBOARD GetIndicatorMap filters to requested stored maps`() {
        withServer { socket, _ ->
            val records = listOf(indicatorMapRecord(1), indicatorMapRecord(2))
            val out = socket.getOutputStream()
            out.write(setIndicatorMapRequest(which = 0x3, maps = records))
            out.write(getIndicatorMapRequest(which = 0x2))
            out.flush()

            val map = readReply(socket.getInputStream())
            assertEquals(2, u16le(map, 2))
            assertEquals(3, u32le(map, 4))
            assertEquals(0x2, u32le(map, 8))
            assertEquals(0, u32le(map, 12))
            assertEquals(1, map[16].toInt() and 0xff)
            assertIndicatorMaps(map, listOf(records[1]))
        }
    }

    @Test
    fun `XKEYBOARD IndicatorMapNotify reports selected SetIndicatorMap changes`() {
        withServer { socket, _ ->
            val records = listOf(indicatorMapRecord(1), indicatorMapRecord(2))
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventIndicatorMapNotify,
                    details = selectEvents32Details(0x3, 0x3),
                ),
            )
            out.write(setIndicatorMapRequest(which = 0x3, maps = records))
            out.write(getIndicatorMapRequest(which = 0x3))
            out.flush()

            assertIndicatorMapNotify(socket.getInputStream().readExactly(32), sequence = 2, state = 0, changed = 0x3)
            val map = readReply(socket.getInputStream())
            assertEquals(3, u16le(map, 2))
            assertEquals(0x3, u32le(map, 8))
            assertEquals(2, map[16].toInt() and 0xff)
            assertIndicatorMaps(map, records)
        }
    }

    @Test
    fun `XKEYBOARD IndicatorMapNotify is suppressed when selected details do not intersect changed indicators`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventIndicatorMapNotify,
                    details = selectEvents32Details(0x4, 0x4),
                ),
            )
            out.write(setIndicatorMapRequest(which = 0x3))
            out.write(getIndicatorMapRequest(which = 0x3))
            out.flush()

            val map = readReply(socket.getInputStream())
            assertEquals(3, u16le(map, 2))
            assertEquals(0x3, u32le(map, 8))
            assertEquals(2, map[16].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents clear removes IndicatorMapNotify selection`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventIndicatorMapNotify,
                    selectAll = XXkb.EventIndicatorMapNotify,
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventIndicatorMapNotify,
                    clear = XXkb.EventIndicatorMapNotify,
                ),
            )
            out.write(setIndicatorMapRequest(which = 0x2))
            out.write(getIndicatorMapRequest(which = 0x2))
            out.flush()

            val map = readReply(socket.getInputStream())
            assertEquals(4, u16le(map, 2))
            assertEquals(0x2, u32le(map, 8))
            assertEquals(1, map[16].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents validates IndicatorMapNotify details and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventIndicatorMapNotify,
                    details = selectEvents32Details(0x1, 0x2),
                ),
            )
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SelectEvents)
            val version = readReply(socket.getInputStream())
            assertEquals(2, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
        }
    }

    @Test
    fun `XKEYBOARD SetIndicatorMap validates variable map length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SetIndicatorMap, ByteArray(4)))
            out.write(setIndicatorMapRequest(which = 0x3, bodySize = 20))
            out.write(setIndicatorMapRequest(which = 0x3, bodySize = 44))
            out.write(setIndicatorMapRequest(which = 0))
            out.write(getIndicatorMapRequest(which = 0x3))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetIndicatorMap)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetIndicatorMap)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetIndicatorMap)
            val map = readReply(socket.getInputStream())
            assertEquals(5, u16le(map, 2))
            assertEquals(0, u32le(map, 8))
            assertEquals(0, map[16].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetNamedIndicator reports queried indicator absent`() {
        withServer { socket, _ ->
            val indicator = 0x0020_0400
            val out = socket.getOutputStream()
            out.write(getNamedIndicatorRequest(indicator))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(indicator, u32le(reply, 8))
            assertEquals(0, reply[12].toInt() and 0xff)
            assertEquals(0, reply[13].toInt() and 0xff)
            assertEquals(0, reply[14].toInt() and 0xff)
            assertEquals(0, reply[15].toInt() and 0xff)
            assertEquals(0, reply[16].toInt() and 0xff)
            assertEquals(0, reply[17].toInt() and 0xff)
            assertEquals(0, reply[18].toInt() and 0xff)
            assertEquals(0, reply[19].toInt() and 0xff)
            assertEquals(0, reply[20].toInt() and 0xff)
            assertEquals(0, reply[21].toInt() and 0xff)
            assertEquals(0, u16le(reply, 22))
            assertEquals(0, u32le(reply, 24))
            assertEquals(0, reply[28].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetNamedIndicator validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetNamedIndicator, ByteArray(8)))
            out.write(getNamedIndicatorRequest(0x0020_0400))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetNamedIndicator)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0x0020_0400, u32le(reply, 8))
            assertEquals(0, reply[12].toInt() and 0xff)
            assertEquals(0, reply[28].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SetNamedIndicator tracks named indicator state`() {
        withServer { socket, _ ->
            val indicator = 0x0020_0400
            val out = socket.getOutputStream()
            out.write(setNamedIndicatorRequest(indicator, setState = true, on = true, setMap = true, createMap = true))
            out.write(getNamedIndicatorRequest(indicator))
            out.write(getIndicatorStateRequest())
            out.flush()

            val named = readReply(socket.getInputStream())
            assertEquals(2, u16le(named, 2))
            assertEquals(indicator, u32le(named, 8))
            assertEquals(1, named[12].toInt() and 0xff)
            assertEquals(1, named[13].toInt() and 0xff)
            assertEquals(1, named[14].toInt() and 0xff)
            assertEquals(0, named[15].toInt() and 0xff)
            assertEquals(1, named[28].toInt() and 0xff)

            val state = readReply(socket.getInputStream())
            assertEquals(3, u16le(state, 2))
            assertEquals(1, u32le(state, 8))
        }
    }

    @Test
    fun `XKEYBOARD SetNamedIndicator persists embedded indicator map`() {
        withServer { socket, _ ->
            val indicator = 0x0020_0400
            val map = indicatorMapRecord(7).also { it[4] = 0 }
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventIndicatorMapNotify,
                    details = selectEvents32Details(0x1, 0x1),
                ),
            )
            out.write(setNamedIndicatorRequest(indicator, setState = false, on = false, setMap = true, createMap = true, map = map))
            out.write(getNamedIndicatorRequest(indicator))
            out.write(getIndicatorMapRequest(which = 0x1))
            out.flush()

            assertIndicatorMapNotify(socket.getInputStream().readExactly(32), sequence = 2, state = 0, changed = 0x1)
            val named = readReply(socket.getInputStream())
            assertEquals(3, u16le(named, 2))
            assertEquals(indicator, u32le(named, 8))
            assertEquals(1, named[12].toInt() and 0xff)
            assertEquals(0, named[13].toInt() and 0xff)
            assertEquals(1, named[14].toInt() and 0xff)
            assertEquals(0, named[15].toInt() and 0xff)
            assertEquals(map.toList(), named.copyOfRange(16, 28).toList())
            assertEquals(1, named[28].toInt() and 0xff)

            val maps = readReply(socket.getInputStream())
            assertEquals(4, u16le(maps, 2))
            assertEquals(0x1, u32le(maps, 8))
            assertIndicatorMaps(maps, listOf(map))
        }
    }

    @Test
    fun `XKEYBOARD SetNamedIndicator map does not create absent indicator when createMap is false`() {
        withServer { socket, _ ->
            val indicator = 0x0020_0400
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventIndicatorMapNotify,
                    selectAll = XXkb.EventIndicatorMapNotify,
                ),
            )
            out.write(setNamedIndicatorRequest(indicator, setState = false, on = false, setMap = true, createMap = false, map = indicatorMapRecord(9)))
            out.write(getNamedIndicatorRequest(indicator))
            out.write(getIndicatorMapRequest(which = 0x1))
            out.flush()

            val named = readReply(socket.getInputStream())
            assertEquals(3, u16le(named, 2))
            assertEquals(indicator, u32le(named, 8))
            assertEquals(0, named[12].toInt() and 0xff)
            assertEquals(0, named[28].toInt() and 0xff)

            val maps = readReply(socket.getInputStream())
            assertEquals(4, u16le(maps, 2))
            assertEquals(0, u32le(maps, 8))
            assertEquals(0, maps[16].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SetNamedIndicator does not create absent indicator state when createMap is false`() {
        withServer { socket, _ ->
            val indicator = 0x0020_0400
            val out = socket.getOutputStream()
            out.write(setNamedIndicatorRequest(indicator, setState = true, on = true, setMap = false, createMap = false))
            out.write(getNamedIndicatorRequest(indicator))
            out.write(getIndicatorStateRequest())
            out.flush()

            val named = readReply(socket.getInputStream())
            assertEquals(2, u16le(named, 2))
            assertEquals(indicator, u32le(named, 8))
            assertEquals(0, named[12].toInt() and 0xff)
            assertEquals(0, named[13].toInt() and 0xff)
            assertEquals(0, named[28].toInt() and 0xff)

            val state = readReply(socket.getInputStream())
            assertEquals(3, u16le(state, 2))
            assertEquals(0, u32le(state, 8))
        }
    }

    @Test
    fun `XKEYBOARD IndicatorStateNotify reports selected SetNamedIndicator state changes`() {
        withServer { socket, _ ->
            val indicator = 0x0020_0400
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventIndicatorStateNotify,
                    details = selectEvents32Details(0x1, 0x1),
                ),
            )
            out.write(setNamedIndicatorRequest(indicator, setState = true, on = true, setMap = false, createMap = true))
            out.write(getIndicatorStateRequest())
            out.flush()

            assertIndicatorStateNotify(socket.getInputStream().readExactly(32), sequence = 2, state = 1, changed = 1)
            val state = readReply(socket.getInputStream())
            assertEquals(3, u16le(state, 2))
            assertEquals(1, u32le(state, 8))
        }
    }

    @Test
    fun `XKEYBOARD IndicatorStateNotify is suppressed when selected details do not intersect changed indicators`() {
        withServer { socket, _ ->
            val indicator = 0x0020_0400
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventIndicatorStateNotify,
                    details = selectEvents32Details(0x2, 0x2),
                ),
            )
            out.write(setNamedIndicatorRequest(indicator, setState = true, on = true, setMap = false, createMap = true))
            out.write(getIndicatorStateRequest())
            out.flush()

            val state = readReply(socket.getInputStream())
            assertEquals(3, u16le(state, 2))
            assertEquals(1, u32le(state, 8))
        }
    }

    @Test
    fun `XKEYBOARD SetNamedIndicator toggles existing indicator state without createMap`() {
        withServer { socket, _ ->
            val indicator = 0x0020_0400
            val out = socket.getOutputStream()
            out.write(setNamedIndicatorRequest(indicator, setState = true, on = true, setMap = false, createMap = true))
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventIndicatorStateNotify,
                    details = selectEvents32Details(0x1, 0x1),
                ),
            )
            out.write(setNamedIndicatorRequest(indicator, setState = true, on = false, setMap = false, createMap = false))
            out.write(getIndicatorStateRequest())
            out.flush()

            assertIndicatorStateNotify(socket.getInputStream().readExactly(32), sequence = 3, state = 0, changed = 1)
            val state = readReply(socket.getInputStream())
            assertEquals(4, u16le(state, 2))
            assertEquals(0, u32le(state, 8))
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents clear removes IndicatorStateNotify selection`() {
        withServer { socket, _ ->
            val indicator = 0x0020_0400
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventIndicatorStateNotify,
                    selectAll = XXkb.EventIndicatorStateNotify,
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventIndicatorStateNotify,
                    clear = XXkb.EventIndicatorStateNotify,
                ),
            )
            out.write(setNamedIndicatorRequest(indicator, setState = true, on = true, setMap = false, createMap = true))
            out.write(getIndicatorStateRequest())
            out.flush()

            val state = readReply(socket.getInputStream())
            assertEquals(4, u16le(state, 2))
            assertEquals(1, u32le(state, 8))
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents validates IndicatorStateNotify details and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventIndicatorStateNotify,
                    details = selectEvents32Details(0x1, 0x2),
                ),
            )
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SelectEvents)
            val version = readReply(socket.getInputStream())
            assertEquals(2, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
        }
    }

    @Test
    fun `XKEYBOARD SetNamedIndicator validates request length and recovers stream`() {
        withServer { socket, _ ->
            val indicator = 0x0020_0400
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SetNamedIndicator, ByteArray(24)))
            out.write(request(XXkb.MajorOpcode, XXkb.SetNamedIndicator, ByteArray(32)))
            out.write(setNamedIndicatorRequest(indicator, setState = false, on = false, setMap = false, createMap = false))
            out.write(getNamedIndicatorRequest(indicator))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetNamedIndicator)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetNamedIndicator)
            val reply = readReply(socket.getInputStream())
            assertEquals(4, u16le(reply, 2))
            assertEquals(indicator, u32le(reply, 8))
            assertEquals(0, reply[12].toInt() and 0xff)
            assertEquals(0, reply[28].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetNames reports key range with no requested named atoms`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getNamesRequest(which = 0))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u32le(reply, 8))
            assertEquals(XKeyboard.MinKeycode, reply[12].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[13].toInt() and 0xff)
            assertEquals(0, reply[14].toInt() and 0xff)
            assertEquals(0, reply[15].toInt() and 0xff)
            assertEquals(0, u16le(reply, 16))
            assertEquals(0, reply[18].toInt() and 0xff)
            assertEquals(0, reply[19].toInt() and 0xff)
            assertEquals(0, u32le(reply, 20))
            assertEquals(0, reply[24].toInt() and 0xff)
            assertEquals(0, reply[25].toInt() and 0xff)
            assertEquals(0, u16le(reply, 26))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetNames reports component name atoms`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val requested = XXkb.ComponentNameDetails
            out.write(getNamesRequest(which = requested))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(6, u32le(reply, 4))
            assertEquals(requested, u32le(reply, 8))
            assertEquals(XKeyboard.MinKeycode, reply[12].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[13].toInt() and 0xff)
            assertEquals(0, reply[14].toInt() and 0xff)
            assertEquals(0, reply[15].toInt() and 0xff)
            assertEquals(0, u16le(reply, 16))
            assertEquals(0, reply[18].toInt() and 0xff)
            assertEquals(0, reply[19].toInt() and 0xff)
            assertEquals(0, u32le(reply, 20))
            assertEquals(0, reply[24].toInt() and 0xff)
            assertEquals(0, reply[25].toInt() and 0xff)
            assertEquals(0, u16le(reply, 26))
            assertEquals(56, reply.size)

            val atoms = List(6) { index -> u32le(reply, 32 + index * 4) }
            assertEquals(listOf("evdev", "pc(pc105)", "us", "us", "complete", "complete"), atoms.map { atomName(socket, it) })
        }
    }

    @Test
    fun `XKEYBOARD GetNames all bits only reports implemented component name atoms`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getNamesRequest(which = -1))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(6, u32le(reply, 4))
            assertEquals(XXkb.ComponentNameDetails, u32le(reply, 8))
            assertEquals(0, reply[14].toInt() and 0xff)
            assertEquals(0, reply[15].toInt() and 0xff)
            assertEquals(0, u16le(reply, 16))
            assertEquals(0, reply[18].toInt() and 0xff)
            assertEquals(0, reply[19].toInt() and 0xff)
            assertEquals(0, u32le(reply, 20))
            assertEquals(0, reply[24].toInt() and 0xff)
            assertEquals(0, reply[25].toInt() and 0xff)
            assertEquals(0, u16le(reply, 26))
            assertEquals(56, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetNames reports component atoms in wire order for sparse mask`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val requested = XXkb.NameDetailSymbols or XXkb.NameDetailTypes
            out.write(getNamesRequest(which = requested))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(2, u32le(reply, 4))
            assertEquals(requested, u32le(reply, 8))
            assertEquals(XKeyboard.MinKeycode, reply[12].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[13].toInt() and 0xff)
            assertEquals(40, reply.size)

            val atoms = List(2) { index -> u32le(reply, 32 + index * 4) }
            assertEquals(listOf("us", "complete"), atoms.map { atomName(socket, it) })
        }
    }

    @Test
    fun `XKEYBOARD GetNames swaps component atoms for big endian clients`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setupBigEndian(socket)

                val body = ByteArray(8)
                put16be(body, 0, 0x0100)
                put32be(body, 4, XXkb.NameDetailSymbols or XXkb.NameDetailTypes)
                socket.getOutputStream().write(requestBigEndian(XXkb.MajorOpcode, XXkb.GetNames, body))
                socket.getOutputStream().flush()

                val reply = readReplyBigEndian(socket.getInputStream())
                assertEquals(1, reply[0].toInt())
                assertEquals(0, reply[1].toInt() and 0xff)
                assertEquals(1, u16be(reply, 2))
                assertEquals(2, u32be(reply, 4))
                assertEquals(XXkb.NameDetailSymbols or XXkb.NameDetailTypes, u32be(reply, 8))
                assertEquals(XKeyboard.MinKeycode, reply[12].toInt() and 0xff)
                assertEquals(XKeyboard.MaxKeycode, reply[13].toInt() and 0xff)
                assertEquals(40, reply.size)
                assertEquals(listOf("us", "complete"), listOf(u32be(reply, 32), u32be(reply, 36)).map { atomNameBigEndian(socket, it) })
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XKEYBOARD GetNames validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetNames, ByteArray(4)))
            out.write(getNamesRequest(which = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetNames)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0, u32le(reply, 8))
            assertEquals(XKeyboard.MinKeycode, reply[12].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[13].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SetNames accepts full names payload without changing empty names`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setNamesRequest(includeAllDetails = true))
            out.write(getNamesRequest(which = 0))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0, u32le(reply, 8))
            assertEquals(XKeyboard.MinKeycode, reply[12].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[13].toInt() and 0xff)
            assertEquals(0, reply[14].toInt() and 0xff)
            assertEquals(0, reply[15].toInt() and 0xff)
            assertEquals(0, u16le(reply, 16))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetNames persists component name atoms`() {
        withServer { socket, _ ->
            val names = listOf(
                "xkb-keycodes-custom",
                "xkb-geometry-custom",
                "xkb-symbols-custom",
                "xkb-phys-symbols-custom",
                "xkb-types-custom",
                "xkb-compat-custom",
            )
            val out = socket.getOutputStream()
            for (name in names) {
                out.write(internAtomRequest(name))
            }
            out.flush()
            val atoms = names.map { u32le(readReply(socket.getInputStream()), 8) }

            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventNamesNotify,
                    details = selectEvents16Details(XXkb.ComponentNameDetails, XXkb.ComponentNameDetails),
                ),
            )
            out.write(setNamesComponentNamesRequest(XXkb.ComponentNameDetails, atoms))
            out.write(getNamesRequest(which = XXkb.ComponentNameDetails))
            out.flush()

            assertNamesNotify(
                socket.getInputStream().readExactly(32),
                sequence = 8,
                changed = XXkb.ComponentNameDetails,
                firstType = 0,
                nTypes = 0,
                firstLevelName = 0,
                nLevelNames = 0,
                nRadioGroups = 0,
                nAliases = 0,
                changedGroupNames = 0,
                changedVirtualMods = 0,
                firstKey = 0,
                nKeys = 0,
                changedIndicators = 0,
            )
            val reply = readReply(socket.getInputStream())
            assertEquals(9, u16le(reply, 2))
            assertEquals(6, u32le(reply, 4))
            assertEquals(XXkb.ComponentNameDetails, u32le(reply, 8))
            assertEquals(atoms, List(6) { index -> u32le(reply, 32 + index * 4) })
            assertEquals(names, atoms.map { atomName(socket, it) })
        }
    }

    @Test
    fun `XKEYBOARD SetNames ignores zero component atoms and preserves defaults`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val requested = XXkb.NameDetailSymbols or XXkb.NameDetailTypes
            out.write(setNamesComponentNamesRequest(requested, listOf(0, 0)))
            out.write(getNamesRequest(which = requested))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(2, u32le(reply, 4))
            assertEquals(requested, u32le(reply, 8))
            val atoms = List(2) { index -> u32le(reply, 32 + index * 4) }
            assertEquals(listOf("us", "complete"), atoms.map { atomName(socket, it) })
        }
    }

    @Test
    fun `XKEYBOARD SetNames rejects invalid component name atom without changing names`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val invalidAtom = 0x0102_0304
            val requested = XXkb.NameDetailSymbols
            out.write(setNamesComponentNamesRequest(requested, listOf(invalidAtom)))
            out.write(getNamesRequest(which = requested))
            out.flush()

            assertError(socket.getInputStream(), error = 5, opcode = XXkb.MajorOpcode, badValue = invalidAtom, sequence = 1, minorOpcode = XXkb.SetNames)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(1, u32le(reply, 4))
            assertEquals(requested, u32le(reply, 8))
            assertEquals("us", atomName(socket, u32le(reply, 32)))
        }
    }

    @Test
    fun `XKEYBOARD NamesNotify reports selected SetNames changes`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventNamesNotify,
                    details = selectEvents16Details(XXkb.AllNameEventsMask, XXkb.AllNameEventsMask),
                ),
            )
            out.write(setNamesRequest(includeAllDetails = true))
            out.write(getNamesRequest(which = 0))
            out.flush()

            assertNamesNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                changed = XXkb.AllNameEventsMask,
                firstType = 0,
                nTypes = 2,
                firstLevelName = 0,
                nLevelNames = 3,
                nRadioGroups = 2,
                nAliases = 1,
                changedGroupNames = 0x3,
                changedVirtualMods = 0x3,
                firstKey = XKeyboard.MinKeycode,
                nKeys = 2,
                changedIndicators = 0x3,
            )
            val reply = readReply(socket.getInputStream())
            assertEquals(3, u16le(reply, 2))
            assertEquals(0, u32le(reply, 8))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD NamesNotify is suppressed when selected details do not intersect changes`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventNamesNotify,
                    details = selectEvents16Details(XXkb.NameDetailKeycodes, XXkb.NameDetailKeycodes),
                ),
            )
            out.write(setNamesGroupNamesRequest(groupNames = 0x2))
            out.write(getNamesRequest(which = 0))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(3, u16le(reply, 2))
            assertEquals(0, u32le(reply, 8))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD NamesNotify zeros fields for unchanged name details`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventNamesNotify,
                    selectAll = XXkb.EventNamesNotify,
                ),
            )
            out.write(setNamesGroupNamesRequest(groupNames = 0x2))
            out.write(getNamesRequest(which = 0))
            out.flush()

            assertNamesNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                changed = XXkb.NameDetailGroupNames,
                firstType = 0,
                nTypes = 0,
                firstLevelName = 0,
                nLevelNames = 0,
                nRadioGroups = 0,
                nAliases = 0,
                changedGroupNames = 0x2,
                changedVirtualMods = 0,
                firstKey = 0,
                nKeys = 0,
                changedIndicators = 0,
            )
            val reply = readReply(socket.getInputStream())
            assertEquals(3, u16le(reply, 2))
            assertEquals(0, u32le(reply, 8))
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents clear removes NamesNotify selection`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventNamesNotify,
                    selectAll = XXkb.EventNamesNotify,
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventNamesNotify,
                    clear = XXkb.EventNamesNotify,
                ),
            )
            out.write(setNamesRequest(includeAllDetails = true))
            out.write(getNamesRequest(which = 0))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(4, u16le(reply, 2))
            assertEquals(0, u32le(reply, 8))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents validates NamesNotify details and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventNamesNotify,
                    details = selectEvents16Details(XXkb.NameDetailKeycodes, XXkb.NameDetailGeometry),
                ),
            )
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SelectEvents)
            val version = readReply(socket.getInputStream())
            assertEquals(2, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
        }
    }

    @Test
    fun `XKEYBOARD SetNames validates variable payload length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SetNames, ByteArray(20)))
            out.write(setNamesRequest(includeAllDetails = true, bodySize = setNamesBodySize(includeAllDetails = true) - 4))
            out.write(setNamesRequest(includeAllDetails = true, bodySize = setNamesBodySize(includeAllDetails = true) + 4))
            out.write(setNamesRequest(includeAllDetails = false))
            out.write(getNamesRequest(which = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetNames)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetNames)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetNames)
            val reply = readReply(socket.getInputStream())
            assertEquals(5, u16le(reply, 2))
            assertEquals(0, u32le(reply, 8))
            assertEquals(XKeyboard.MinKeycode, reply[12].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[13].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetGeometry reports missing valid geometry`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getGeometryRequest(name = 0x40))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0x40, u32le(reply, 8))
            assertEquals(0, reply[12].toInt() and 0xff)
            assertEquals(0, reply[13].toInt() and 0xff)
            assertEquals(0, u16le(reply, 14))
            assertEquals(0, u16le(reply, 16))
            assertEquals(0, u16le(reply, 18))
            assertEquals(0, u16le(reply, 20))
            assertEquals(0, u16le(reply, 22))
            assertEquals(0, u16le(reply, 24))
            assertEquals(0, u16le(reply, 26))
            assertEquals(0, reply[28].toInt() and 0xff)
            assertEquals(0, reply[29].toInt() and 0xff)
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetGeometry rejects invalid nonzero geometry atom and recovers stream`() {
        withServer { socket, _ ->
            val invalidAtom = 0x1122_3344
            val out = socket.getOutputStream()
            out.write(getGeometryRequest(name = invalidAtom))
            out.write(getGeometryRequest(name = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 5, opcode = XXkb.MajorOpcode, badValue = invalidAtom, sequence = 1, minorOpcode = XXkb.GetGeometry)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u32le(reply, 8))
            assertEquals(0, reply[12].toInt() and 0xff)
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetGeometry validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetGeometry, ByteArray(4)))
            out.write(getGeometryRequest(name = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetGeometry)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u32le(reply, 8))
            assertEquals(0, reply[12].toInt() and 0xff)
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetGeometry persists named geometry payload`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(internAtomRequest("xkb-geometry-round-trip"))
            out.flush()
            val geometryAtom = u32le(readReply(socket.getInputStream()), 8)
            val geometryBody = setGeometryBody(name = geometryAtom)

            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, geometryBody))
            out.write(getGeometryRequest(name = geometryAtom))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertGeometryReply(reply, sequence = 3, geometryBody = geometryBody)
        }
    }

    @Test
    fun `XKEYBOARD SetGeometry updates geometry component name and selected NamesNotify`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(internAtomRequest("xkb-geometry-selected"))
            out.flush()
            val geometryAtom = u32le(readReply(socket.getInputStream()), 8)
            val geometryBody = setGeometryBody(name = geometryAtom)

            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventNamesNotify,
                    details = selectEvents16Details(XXkb.NameDetailGeometry, XXkb.NameDetailGeometry),
                ),
            )
            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, geometryBody))
            out.write(getNamesRequest(which = XXkb.NameDetailGeometry))
            out.flush()

            assertNamesNotify(
                socket.getInputStream().readExactly(32),
                sequence = 3,
                changed = XXkb.NameDetailGeometry,
                firstType = 0,
                nTypes = 0,
                firstLevelName = 0,
                nLevelNames = 0,
                nRadioGroups = 0,
                nAliases = 0,
                changedGroupNames = 0,
                changedVirtualMods = 0,
                firstKey = 0,
                nKeys = 0,
                changedIndicators = 0,
            )
            val reply = readReply(socket.getInputStream())
            assertEquals(4, u16le(reply, 2))
            assertEquals(1, u32le(reply, 4))
            assertEquals(XXkb.NameDetailGeometry, u32le(reply, 8))
            assertEquals(geometryAtom, u32le(reply, 32))
        }
    }

    @Test
    fun `XKEYBOARD SetGeometry rejects invalid name atom without replacing geometry`() {
        withServer { socket, _ ->
            val invalidAtom = 0x0102_0304
            val out = socket.getOutputStream()
            out.write(internAtomRequest("xkb-geometry-valid"))
            out.flush()
            val geometryAtom = u32le(readReply(socket.getInputStream()), 8)
            val geometryBody = setGeometryBody(name = geometryAtom)

            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, geometryBody))
            out.write(setGeometryRequest(name = invalidAtom))
            out.write(getGeometryRequest(name = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 5, opcode = XXkb.MajorOpcode, badValue = invalidAtom, sequence = 3, minorOpcode = XXkb.SetGeometry)
            val reply = readReply(socket.getInputStream())
            assertGeometryReply(reply, sequence = 4, geometryBody = geometryBody)
        }
    }

    @Test
    fun `XKEYBOARD SetGeometry rejects invalid color and shape counts without replacing geometry`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(internAtomRequest("xkb-geometry-counts"))
            out.flush()
            val geometryAtom = u32le(readReply(socket.getInputStream()), 8)
            val geometryBody = setGeometryBody(name = geometryAtom)

            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, geometryBody))
            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, minimalGeometryBody(name = geometryAtom, nColors = 1)))
            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, minimalGeometryBody(name = geometryAtom, nShapes = 0)))
            out.write(getGeometryRequest(name = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 1, sequence = 3, minorOpcode = XXkb.SetGeometry)
            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 4, minorOpcode = XXkb.SetGeometry)
            val reply = readReply(socket.getInputStream())
            assertGeometryReply(reply, sequence = 5, geometryBody = geometryBody)
        }
    }

    @Test
    fun `XKEYBOARD SetGeometry rejects invalid color indexes without replacing geometry`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(internAtomRequest("xkb-geometry-color-indexes"))
            out.flush()
            val geometryAtom = u32le(readReply(socket.getInputStream()), 8)
            val geometryBody = setGeometryBody(name = geometryAtom)

            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, geometryBody))
            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, minimalGeometryBody(name = geometryAtom, baseColor = 0, labelColor = 0)))
            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, minimalGeometryBody(name = geometryAtom, baseColor = 2, labelColor = 1)))
            out.write(getGeometryRequest(name = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetGeometry)
            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 2, sequence = 4, minorOpcode = XXkb.SetGeometry)
            val reply = readReply(socket.getInputStream())
            assertGeometryReply(reply, sequence = 5, geometryBody = geometryBody)
        }
    }

    @Test
    fun `XKEYBOARD SetGeometry rejects invalid shape name atom without replacing geometry`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(internAtomRequest("xkb-geometry-shape-atom"))
            out.flush()
            val geometryAtom = u32le(readReply(socket.getInputStream()), 8)
            val geometryBody = setGeometryBody(name = geometryAtom)
            val invalidAtom = 0x7fff_fffd

            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, geometryBody))
            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, minimalGeometryBody(name = geometryAtom, shapeName = invalidAtom)))
            out.write(getGeometryRequest(name = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 5, opcode = XXkb.MajorOpcode, badValue = invalidAtom, sequence = 3, minorOpcode = XXkb.SetGeometry)
            val reply = readReply(socket.getInputStream())
            assertGeometryReply(reply, sequence = 4, geometryBody = geometryBody)
        }
    }

    @Test
    fun `XKEYBOARD SetGeometry validates variable payload length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, ByteArray(20)))
            out.write(setGeometryRequest(bodySize = setGeometryBodySize() - 4))
            out.write(setGeometryRequest(bodySize = setGeometryBodySize() + 4))
            out.write(setGeometryRequest())
            out.write(getGeometryRequest(name = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetGeometry)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetGeometry)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetGeometry)
            val reply = readReply(socket.getInputStream())
            assertGeometryReply(reply, sequence = 5, geometryBody = setGeometryBody(name = 0x40))
        }
    }

    @Test
    fun `XKEYBOARD PerClientFlags stores per-client flags`() {
        withServer { socket, _ ->
            val flags = XXkb.PcfDetectableAutoRepeat or
                XXkb.PcfGrabsUseXkbState or
                XXkb.PcfLookupStateWhenGrabbed or
                XXkb.PcfSendEventUsesXkbState
            val out = socket.getOutputStream()
            out.write(perClientFlagsRequest(change = 0, value = 0, ctrlsToChange = 0, autoCtrls = 0, autoCtrlsValues = 0))
            out.write(perClientFlagsRequest(change = flags, value = flags, ctrlsToChange = 0, autoCtrls = 0, autoCtrlsValues = 0))
            out.write(perClientFlagsRequest(change = 0, value = 0, ctrlsToChange = 0, autoCtrls = 0, autoCtrlsValues = 0))
            out.write(perClientFlagsRequest(change = flags, value = 0, ctrlsToChange = 0, autoCtrls = 0, autoCtrlsValues = 0))
            out.write(perClientFlagsRequest(change = 0, value = 0, ctrlsToChange = 0, autoCtrls = 0, autoCtrlsValues = 0))
            out.flush()

            assertPerClientFlagsReply(readReply(socket.getInputStream()), sequence = 1, value = 0)
            assertPerClientFlagsReply(readReply(socket.getInputStream()), sequence = 2, value = flags)
            assertPerClientFlagsReply(readReply(socket.getInputStream()), sequence = 3, value = flags)
            assertPerClientFlagsReply(readReply(socket.getInputStream()), sequence = 4, value = 0)
            assertPerClientFlagsReply(readReply(socket.getInputStream()), sequence = 5, value = 0)
        }
    }

    @Test
    fun `XKEYBOARD PerClientFlags state is isolated between clients`() {
        withServer { socket, port ->
            val out = socket.getOutputStream()
            out.write(perClientFlagsRequest(change = XXkb.PcfDetectableAutoRepeat, value = XXkb.PcfDetectableAutoRepeat, ctrlsToChange = 0, autoCtrls = 0, autoCtrlsValues = 0))
            out.flush()
            assertPerClientFlagsReply(readReply(socket.getInputStream()), sequence = 1, value = XXkb.PcfDetectableAutoRepeat)

            Socket("127.0.0.1", port).use { second ->
                second.soTimeout = 2_000
                setup(second)
                val secondOut = second.getOutputStream()
                secondOut.write(perClientFlagsRequest(change = 0, value = 0, ctrlsToChange = 0, autoCtrls = 0, autoCtrlsValues = 0))
                secondOut.flush()

                assertPerClientFlagsReply(readReply(second.getInputStream()), sequence = 1, value = 0)
            }
        }
    }

    @Test
    fun `XKEYBOARD PerClientFlags stores auto reset controls`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                perClientFlagsRequest(
                    change = XXkb.PcfAutoResetControls,
                    value = XXkb.PcfAutoResetControls,
                    ctrlsToChange = XXkb.BoolCtrlRepeatKeys,
                    autoCtrls = XXkb.BoolCtrlRepeatKeys,
                    autoCtrlsValues = 0,
                ),
            )
            out.write(perClientFlagsRequest(change = 0, value = 0, ctrlsToChange = 0, autoCtrls = 0, autoCtrlsValues = 0))
            out.write(
                perClientFlagsRequest(
                    change = XXkb.PcfAutoResetControls,
                    value = XXkb.PcfAutoResetControls,
                    ctrlsToChange = XXkb.BoolCtrlRepeatKeys,
                    autoCtrls = XXkb.BoolCtrlRepeatKeys,
                    autoCtrlsValues = XXkb.BoolCtrlRepeatKeys,
                ),
            )
            out.write(perClientFlagsRequest(change = XXkb.PcfAutoResetControls, value = 0, ctrlsToChange = 0, autoCtrls = 0, autoCtrlsValues = 0))
            out.flush()

            assertPerClientFlagsReply(
                readReply(socket.getInputStream()),
                sequence = 1,
                value = XXkb.PcfAutoResetControls,
                autoCtrls = XXkb.BoolCtrlRepeatKeys,
            )
            assertPerClientFlagsReply(
                readReply(socket.getInputStream()),
                sequence = 2,
                value = XXkb.PcfAutoResetControls,
                autoCtrls = XXkb.BoolCtrlRepeatKeys,
            )
            assertPerClientFlagsReply(
                readReply(socket.getInputStream()),
                sequence = 3,
                value = XXkb.PcfAutoResetControls,
                autoCtrls = XXkb.BoolCtrlRepeatKeys,
                autoCtrlValues = XXkb.BoolCtrlRepeatKeys,
            )
            assertPerClientFlagsReply(readReply(socket.getInputStream()), sequence = 4, value = 0)
        }
    }

    @Test
    fun `XKEYBOARD PerClientFlags auto reset controls apply when client disconnects`() {
        withServer { socket, port ->
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventControlsNotify,
                    details = selectEvents32Details(
                        affect = XXkb.ControlEnabledMask,
                        selected = XXkb.ControlEnabledMask,
                    ),
                ),
            )
            out.write(setControlsRequest(affectEnabledControls = XXkb.BoolCtrlRepeatKeys, enabledControls = 0))
            out.write(getControlsRequest())
            out.flush()

            assertControlsNotify(
                input.readExactly(32),
                sequence = 2,
                changedControls = XXkb.ControlEnabledMask,
                enabledControls = 0,
                enabledControlChanges = XXkb.BoolCtrlRepeatKeys,
            )
            assertGetControls(readReply(input), sequence = 3, enabledControls = 0)

            Socket("127.0.0.1", port).use { owner ->
                owner.soTimeout = 2_000
                setup(owner)
                val ownerOut = owner.getOutputStream()
                ownerOut.write(
                    perClientFlagsRequest(
                        change = XXkb.PcfAutoResetControls,
                        value = XXkb.PcfAutoResetControls,
                        ctrlsToChange = XXkb.BoolCtrlRepeatKeys,
                        autoCtrls = XXkb.BoolCtrlRepeatKeys,
                        autoCtrlsValues = XXkb.BoolCtrlRepeatKeys,
                    ),
                )
                ownerOut.flush()

                assertPerClientFlagsReply(
                    readReply(owner.getInputStream()),
                    sequence = 1,
                    value = XXkb.PcfAutoResetControls,
                    autoCtrls = XXkb.BoolCtrlRepeatKeys,
                    autoCtrlValues = XXkb.BoolCtrlRepeatKeys,
                )
            }

            assertControlsNotify(
                input.readExactly(32),
                sequence = 3,
                changedControls = XXkb.ControlEnabledMask,
                enabledControls = XXkb.BoolCtrlRepeatKeys,
                enabledControlChanges = XXkb.BoolCtrlRepeatKeys,
                requestMinor = XXkb.PerClientFlags,
            )
            out.write(getControlsRequest())
            out.flush()
            assertGetControls(readReply(input), sequence = 4, enabledControls = XXkb.BoolCtrlRepeatKeys)
        }
    }

    @Test
    fun `XKEYBOARD PerClientFlags validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.PerClientFlags, ByteArray(20)))
            out.write(perClientFlagsRequest(change = 0x20, value = 0, ctrlsToChange = 0, autoCtrls = 0, autoCtrlsValues = 0))
            out.write(perClientFlagsRequest(change = 0, value = 0x20, ctrlsToChange = 0, autoCtrls = 0, autoCtrlsValues = 0))
            out.write(perClientFlagsRequest(change = 0, value = XXkb.PcfDetectableAutoRepeat, ctrlsToChange = 0, autoCtrls = 0, autoCtrlsValues = 0))
            out.write(perClientFlagsRequest(change = XXkb.PcfDetectableAutoRepeat, value = XXkb.PcfDetectableAutoRepeat or XXkb.PcfGrabsUseXkbState, ctrlsToChange = 0, autoCtrls = 0, autoCtrlsValues = 0))
            out.write(perClientFlagsRequest(change = XXkb.PcfAutoResetControls, value = XXkb.PcfAutoResetControls, ctrlsToChange = XXkb.ControlEnabledMask, autoCtrls = 0, autoCtrlsValues = 0))
            out.write(perClientFlagsRequest(change = XXkb.PcfAutoResetControls, value = XXkb.PcfAutoResetControls, ctrlsToChange = 0, autoCtrls = XXkb.ControlEnabledMask, autoCtrlsValues = 0))
            out.write(perClientFlagsRequest(change = XXkb.PcfAutoResetControls, value = XXkb.PcfAutoResetControls, ctrlsToChange = 0, autoCtrls = 0, autoCtrlsValues = XXkb.ControlEnabledMask))
            out.write(perClientFlagsRequest(change = XXkb.PcfAutoResetControls, value = XXkb.PcfAutoResetControls, ctrlsToChange = 0, autoCtrls = XXkb.BoolCtrlRepeatKeys, autoCtrlsValues = 0))
            out.write(perClientFlagsRequest(change = XXkb.PcfAutoResetControls, value = XXkb.PcfAutoResetControls, ctrlsToChange = XXkb.BoolCtrlRepeatKeys, autoCtrls = 0, autoCtrlsValues = XXkb.BoolCtrlRepeatKeys))
            out.write(perClientFlagsRequest(change = XXkb.PcfDetectableAutoRepeat, value = XXkb.PcfDetectableAutoRepeat, ctrlsToChange = XXkb.BoolCtrlRepeatKeys, autoCtrls = 0, autoCtrlsValues = 0))
            out.write(getControlsRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.PerClientFlags)
            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 0x20, sequence = 2, minorOpcode = XXkb.PerClientFlags)
            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 0x20, sequence = 3, minorOpcode = XXkb.PerClientFlags)
            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 4, minorOpcode = XXkb.PerClientFlags)
            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 5, minorOpcode = XXkb.PerClientFlags)
            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = XXkb.ControlEnabledMask, sequence = 6, minorOpcode = XXkb.PerClientFlags)
            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = XXkb.ControlEnabledMask, sequence = 7, minorOpcode = XXkb.PerClientFlags)
            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = XXkb.ControlEnabledMask, sequence = 8, minorOpcode = XXkb.PerClientFlags)
            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 9, minorOpcode = XXkb.PerClientFlags)
            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 10, minorOpcode = XXkb.PerClientFlags)
            assertPerClientFlagsReply(readReply(socket.getInputStream()), sequence = 11, value = XXkb.PcfDetectableAutoRepeat)
            assertGetControls(readReply(socket.getInputStream()), sequence = 12, enabledControls = XXkb.BoolCtrlRepeatKeys)
        }
    }

    @Test
    fun `XKEYBOARD ListComponents without trailing specs returns built-in component names`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(listComponentsRequest(maxNames = 64))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(17, u32le(reply, 4))
            assertEquals(1, u16le(reply, 8))
            assertEquals(1, u16le(reply, 10))
            assertEquals(1, u16le(reply, 12))
            assertEquals(1, u16le(reply, 14))
            assertEquals(1, u16le(reply, 16))
            assertEquals(1, u16le(reply, 18))
            assertEquals(0, u16le(reply, 20))
            assertEquals(
                listOf(
                    listOf(XXkb.ListComponentDefault to "base"),
                    listOf(XXkb.ListComponentDefault to "evdev"),
                    listOf(XXkb.ListComponentDefault to "complete"),
                    listOf(XXkb.ListComponentDefault to "complete"),
                    listOf(XXkb.ListComponentDefault to "us"),
                    listOf(XXkb.ListComponentDefault to "pc(pc105)"),
                ),
                xkbListingsByCategory(reply),
            )
            assertEquals(100, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD ListComponents with explicit empty specs reports no component names`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(listComponentsRequest(maxNames = 64, trailingPatterns = xkbComponentSpecs()))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u16le(reply, 8))
            assertEquals(0, u16le(reply, 10))
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(0, u16le(reply, 16))
            assertEquals(0, u16le(reply, 18))
            assertEquals(0, u16le(reply, 20))
            assertEquals(emptyList(), xkbListingsByCategory(reply).flatten())
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD ListComponents accepts trailing pattern data and throttles matches`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val trailingPatterns = xkbComponentSpecs("base", "evdev", "complete", "complete", "us", "pc(pc105)")
            out.write(listComponentsRequest(maxNames = 1, trailingPatterns = trailingPatterns))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(2, u32le(reply, 4))
            assertEquals(1, u16le(reply, 8))
            assertEquals(0, u16le(reply, 10))
            assertEquals(5, u16le(reply, 20))
            assertEquals(
                listOf(
                    listOf(XXkb.ListComponentDefault to "base"),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                xkbListingsByCategory(reply),
            )
            assertEquals(40, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD ListComponents returns built-in component names matching patterns`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val trailingPatterns = xkbComponentSpecs("base", "evdev", "complete", "complete", "u+s", "p c(*)")
            out.write(listComponentsRequest(maxNames = 64, trailingPatterns = trailingPatterns))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(17, u32le(reply, 4))
            assertEquals(1, u16le(reply, 8))
            assertEquals(1, u16le(reply, 10))
            assertEquals(1, u16le(reply, 12))
            assertEquals(1, u16le(reply, 14))
            assertEquals(1, u16le(reply, 16))
            assertEquals(1, u16le(reply, 18))
            assertEquals(0, u16le(reply, 20))
            assertEquals(
                listOf(
                    listOf(XXkb.ListComponentDefault to "base"),
                    listOf(XXkb.ListComponentDefault to "evdev"),
                    listOf(XXkb.ListComponentDefault to "complete"),
                    listOf(XXkb.ListComponentDefault to "complete"),
                    listOf(XXkb.ListComponentDefault to "us"),
                    listOf(XXkb.ListComponentDefault to "pc(pc105)"),
                ),
                xkbListingsByCategory(reply),
            )
            assertEquals(100, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD ListComponents throttles names and reports extra matches`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val trailingPatterns = xkbComponentSpecs("*", "*", "*", "*", "*", "pc(*)")
            out.write(listComponentsRequest(maxNames = 3, trailingPatterns = trailingPatterns))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(8, u32le(reply, 4))
            assertEquals(1, u16le(reply, 8))
            assertEquals(1, u16le(reply, 10))
            assertEquals(1, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(0, u16le(reply, 16))
            assertEquals(0, u16le(reply, 18))
            assertEquals(3, u16le(reply, 20))
            assertEquals(
                listOf(
                    listOf(XXkb.ListComponentDefault to "base"),
                    listOf(XXkb.ListComponentDefault to "evdev"),
                    listOf(XXkb.ListComponentDefault to "complete"),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                xkbListingsByCategory(reply),
            )
            assertEquals(64, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD ListComponents validates component pattern lengths and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(listComponentsRequest(maxNames = 1, trailingPatterns = byteArrayOf(5, 'b'.code.toByte(), 0, 0)))
            out.write(listComponentsRequest(maxNames = 1, trailingPatterns = xkbComponentSpecs("base")))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.ListComponents)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(1, u16le(reply, 8))
            assertEquals(0, u16le(reply, 20))
            assertEquals(
                listOf(
                    listOf(XXkb.ListComponentDefault to "base"),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                xkbListingsByCategory(reply),
            )
            assertEquals(40, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD ListComponents validates fixed prefix length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.ListComponents, ByteArray(0)))
            out.write(listComponentsRequest(maxNames = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.ListComponents)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(1, u16le(reply, 8))
            assertEquals(5, u16le(reply, 20))
            assertEquals(
                listOf(
                    listOf(XXkb.ListComponentDefault to "base"),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                xkbListingsByCategory(reply),
            )
            assertEquals(40, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName reports found map components but no descriptions when mandatory pieces are missing`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getKbdByNameRequest(need = -1, want = -1, load = true))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(XKeyboard.MinKeycode, reply[8].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[9].toInt() and 0xff)
            assertEquals(0, reply[10].toInt() and 0xff)
            assertEquals(0, reply[11].toInt() and 0xff)
            assertEquals(XXkb.GbnTypes or XXkb.GbnClientSymbols, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName accepts and ignores trailing component names`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val trailingNames = xkbComponentSpecs("base", "evdev", "complete", "pc", "us", "pc105")
            out.write(getKbdByNameRequest(need = 0, want = 0, load = false, trailingNames = trailingNames))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(XKeyboard.MinKeycode, reply[8].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[9].toInt() and 0xff)
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName reports key types and client symbols when requested`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnTypes or XXkb.GbnClientSymbols, load = false))
            out.flush()

            val reply = readReply(socket.getInputStream())
            val map = reply.copyOfRange(32, reply.size)
            assertEquals(1, u16le(reply, 2))
            assertEquals(map.size / 4, u32le(reply, 4))
            assertEquals(XXkb.GbnTypes or XXkb.GbnClientSymbols, u16le(reply, 12))
            assertEquals(XXkb.GbnTypes or XXkb.GbnClientSymbols, u16le(reply, 14))
            assertMapReplyHeader(
                map,
                sequence = 1,
                present = XXkb.MapPartKeyTypes or XXkb.MapPartKeySyms or XXkb.MapPartModifierMap,
            )
            assertEquals(XKeyboard.MinKeycode, map[17].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode - XKeyboard.MinKeycode + 1, map[20].toInt() and 0xff)
            assertEquals(XKeyboard.MinKeycode, map[31].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode - XKeyboard.MinKeycode + 1, map[32].toInt() and 0xff)
            assertEquals(9, map[33].toInt() and 0xff)
            assertXkbDefaultKeyTypes(map, offset = 40)
            assertXkbKeySymMap(map, offset = xkbKeySymMapOffset(map, keycode = 38), width = 2, 0x0061, 0x0041)
            assertXkbModifierMap(
                map,
                37 to 0x04,
                50 to 0x01,
                62 to 0x01,
                64 to 0x08,
                66 to 0x02,
                105 to 0x04,
                108 to 0x08,
                133 to 0x40,
                134 to 0x40,
            )
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName does not report key types for nonmatching type expression`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val nonmatchingComponents = xkbComponentSpecs("", "", "does-not-match", "", "does-not-match")
            out.write(getKbdByNameRequest(need = XXkb.GbnTypes, want = XXkb.GbnTypes, load = false, trailingNames = nonmatchingComponents))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName does not report client symbols for nonmatching symbols expression`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val nonmatchingComponents = xkbComponentSpecs("", "", "", "", "does-not-match")
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnClientSymbols, load = false, trailingNames = nonmatchingComponents))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName does not report client symbols for nonmatching keycodes expression`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val nonmatchingComponents = xkbComponentSpecs("", "does-not-match", "", "", "")
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnClientSymbols, load = false, trailingNames = nonmatchingComponents))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName does not report client symbols for nonmatching types expression`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val nonmatchingComponents = xkbComponentSpecs("", "", "does-not-match", "", "")
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnClientSymbols, load = false, trailingNames = nonmatchingComponents))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName does not report server symbols until server side map pieces are modeled`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnServerSymbols, load = false))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName reports map before compat map when both are requested`() {
        withServer { socket, _ ->
            val groupMaps = listOf(byteArrayOf(0x01, 0x02, 0x34, 0x12))
            val out = socket.getOutputStream()
            out.write(setCompatMapRequest(groups = 0x1, nSI = 0, groupMaps = groupMaps))
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnTypes or XXkb.GbnCompatMap, load = false))
            out.flush()

            val reply = readReply(socket.getInputStream())
            val reported = XXkb.GbnTypes or XXkb.GbnCompatMap
            val mapSize = 96
            val map = reply.copyOfRange(32, 32 + mapSize)
            val compat = reply.copyOfRange(32 + mapSize, reply.size)
            assertEquals(2, u16le(reply, 2))
            assertEquals(reported, u16le(reply, 12))
            assertEquals(reported, u16le(reply, 14))
            assertMapReplyHeader(map, sequence = 2, present = XXkb.MapPartKeyTypes)
            assertCompatMapReply(compat, sequence = 2, groups = 0x1, groupMaps = groupMaps)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName reports stored geometry when requested`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(internAtomRequest("xkb-getkbd-geometry"))
            out.flush()
            val geometryAtom = u32le(readReply(socket.getInputStream()), 8)
            val geometryBody = setGeometryBody(name = geometryAtom)

            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, geometryBody))
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnGeometry, load = false))
            out.flush()

            val reply = readReply(socket.getInputStream())
            val geometryPayload = geometryBody.copyOfRange(24, geometryBody.size)
            assertEquals(3, u16le(reply, 2))
            assertEquals((32 + geometryPayload.size) / 4, u32le(reply, 4))
            assertEquals(XKeyboard.MinKeycode, reply[8].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[9].toInt() and 0xff)
            assertEquals(0, reply[10].toInt() and 0xff)
            assertEquals(0, reply[11].toInt() and 0xff)
            assertEquals(XXkb.GbnGeometry, u16le(reply, 12))
            assertEquals(XXkb.GbnGeometry, u16le(reply, 14))
            assertGeometryReply(reply.copyOfRange(32, reply.size), sequence = 3, geometryBody = geometryBody)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName reports stored geometry for matching component expression`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(internAtomRequest("xkb-getkbd-geometry-matching"))
            out.flush()
            val geometryAtom = u32le(readReply(socket.getInputStream()), 8)
            val geometryBody = setGeometryBody(name = geometryAtom)
            val matchingComponents = xkbComponentSpecs("", "", "", "", "", "pc(*)")

            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, geometryBody))
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnGeometry, load = false, trailingNames = matchingComponents))
            out.flush()

            val reply = readReply(socket.getInputStream())
            val geometryPayload = geometryBody.copyOfRange(24, geometryBody.size)
            assertEquals(3, u16le(reply, 2))
            assertEquals((32 + geometryPayload.size) / 4, u32le(reply, 4))
            assertEquals(XXkb.GbnGeometry, u16le(reply, 12))
            assertEquals(XXkb.GbnGeometry, u16le(reply, 14))
            assertGeometryReply(reply.copyOfRange(32, reply.size), sequence = 3, geometryBody = geometryBody)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName reports stored compat map for matching component expression`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val groupMaps = listOf(
                byteArrayOf(0x01, 0x02, 0x34, 0x12),
                byteArrayOf(0x04, 0x08, 0x78, 0x56),
            )
            val matchingComponents = xkbComponentSpecs("", "", "", "comp*")

            out.write(setCompatMapRequest(groups = 0x5, nSI = 0, groupMaps = groupMaps))
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnCompatMap, load = false, trailingNames = matchingComponents))
            out.flush()

            val reply = readReply(socket.getInputStream())
            val compatSize = 32 + groupMaps.size * 4
            assertEquals(2, u16le(reply, 2))
            assertEquals(compatSize / 4, u32le(reply, 4))
            assertEquals(XXkb.GbnCompatMap, u16le(reply, 12))
            assertEquals(XXkb.GbnCompatMap, u16le(reply, 14))
            assertCompatMapReply(reply.copyOfRange(32, reply.size), sequence = 2, groups = 0x5, groupMaps = groupMaps)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName does not report stored compat map for nonmatching component expression`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val nonmatchingComponents = xkbComponentSpecs("", "", "", "does-not-match")

            out.write(
                setCompatMapRequest(
                    groups = 0x1,
                    nSI = 0,
                    groupMaps = listOf(byteArrayOf(0x01, 0x02, 0x34, 0x12)),
                ),
            )
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnCompatMap, load = false, trailingNames = nonmatchingComponents))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName with mandatory unsupported pieces suppresses compat map report`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                setCompatMapRequest(
                    groups = 0x1,
                    nSI = 0,
                    groupMaps = listOf(byteArrayOf(0x01, 0x02, 0x34, 0x12)),
                ),
            )
            out.write(getKbdByNameRequest(need = XXkb.GbnCompatMap or (1 shl 5), want = XXkb.GbnCompatMap, load = false))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(XXkb.GbnCompatMap, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName reports stored indicator maps when requested`() {
        withServer { socket, _ ->
            val records = listOf(indicatorMapRecord(1), indicatorMapRecord(2))
            val out = socket.getOutputStream()
            out.write(setIndicatorMapRequest(which = 0x3, maps = records))
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnIndicatorMap, load = false))
            out.flush()

            val reply = readReply(socket.getInputStream())
            val indicatorSize = 32 + records.size * 12
            assertEquals(2, u16le(reply, 2))
            assertEquals(indicatorSize / 4, u32le(reply, 4))
            assertEquals(XXkb.GbnIndicatorMap, u16le(reply, 12))
            assertEquals(XXkb.GbnIndicatorMap, u16le(reply, 14))
            assertIndicatorMapReply(reply.copyOfRange(32, reply.size), sequence = 2, which = 0x3, maps = records)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName reports stored indicator maps for matching compat expression`() {
        withServer { socket, _ ->
            val records = listOf(indicatorMapRecord(1), indicatorMapRecord(2))
            val matchingComponents = xkbComponentSpecs("", "", "", "comp*")
            val out = socket.getOutputStream()
            out.write(setIndicatorMapRequest(which = 0x3, maps = records))
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnIndicatorMap, load = false, trailingNames = matchingComponents))
            out.flush()

            val reply = readReply(socket.getInputStream())
            val indicatorSize = 32 + records.size * 12
            assertEquals(2, u16le(reply, 2))
            assertEquals(indicatorSize / 4, u32le(reply, 4))
            assertEquals(XXkb.GbnIndicatorMap, u16le(reply, 12))
            assertEquals(XXkb.GbnIndicatorMap, u16le(reply, 14))
            assertIndicatorMapReply(reply.copyOfRange(32, reply.size), sequence = 2, which = 0x3, maps = records)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName does not report stored indicator maps for nonmatching compat expression`() {
        withServer { socket, _ ->
            val records = listOf(indicatorMapRecord(1), indicatorMapRecord(2))
            val nonmatchingComponents = xkbComponentSpecs("", "", "", "does-not-match")
            val out = socket.getOutputStream()
            out.write(setIndicatorMapRequest(which = 0x3, maps = records))
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnIndicatorMap, load = false, trailingNames = nonmatchingComponents))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName with mandatory unsupported pieces suppresses indicator map report`() {
        withServer { socket, _ ->
            val records = listOf(indicatorMapRecord(1))
            val out = socket.getOutputStream()
            out.write(setIndicatorMapRequest(which = 0x1, maps = records))
            out.write(getKbdByNameRequest(need = XXkb.GbnIndicatorMap or (1 shl 5), want = XXkb.GbnIndicatorMap, load = false))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(XXkb.GbnIndicatorMap, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName reports compat map before geometry when both are requested`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val groupMaps = listOf(
                byteArrayOf(0x01, 0x02, 0x34, 0x12),
                byteArrayOf(0x04, 0x08, 0x78, 0x56),
            )
            out.write(internAtomRequest("xkb-getkbd-compat-geometry"))
            out.flush()
            val geometryAtom = u32le(readReply(socket.getInputStream()), 8)
            val geometryBody = setGeometryBody(name = geometryAtom)

            out.write(setCompatMapRequest(groups = 0x5, nSI = 0, groupMaps = groupMaps))
            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, geometryBody))
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnCompatMap or XXkb.GbnGeometry, load = false))
            out.flush()

            val reply = readReply(socket.getInputStream())
            val compatSize = 32 + groupMaps.size * 4
            val geometrySize = geometryBody.copyOfRange(24, geometryBody.size).size + 32
            val reported = XXkb.GbnCompatMap or XXkb.GbnGeometry
            assertEquals(4, u16le(reply, 2))
            assertEquals((compatSize + geometrySize) / 4, u32le(reply, 4))
            assertEquals(reported, u16le(reply, 12))
            assertEquals(reported, u16le(reply, 14))
            assertCompatMapReply(reply.copyOfRange(32, 32 + compatSize), sequence = 4, groups = 0x5, groupMaps = groupMaps)
            assertGeometryReply(reply.copyOfRange(32 + compatSize, reply.size), sequence = 4, geometryBody = geometryBody)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName reports compat map indicator maps and geometry in protocol order`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val groupMaps = listOf(
                byteArrayOf(0x01, 0x02, 0x34, 0x12),
                byteArrayOf(0x04, 0x08, 0x78, 0x56),
            )
            val indicatorMaps = listOf(indicatorMapRecord(1), indicatorMapRecord(2))
            out.write(internAtomRequest("xkb-getkbd-compat-indicators-geometry"))
            out.flush()
            val geometryAtom = u32le(readReply(socket.getInputStream()), 8)
            val geometryBody = setGeometryBody(name = geometryAtom)

            out.write(setCompatMapRequest(groups = 0x5, nSI = 0, groupMaps = groupMaps))
            out.write(setIndicatorMapRequest(which = 0x3, maps = indicatorMaps))
            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, geometryBody))
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnCompatMap or XXkb.GbnIndicatorMap or XXkb.GbnGeometry, load = false))
            out.flush()

            val reply = readReply(socket.getInputStream())
            val compatSize = 32 + groupMaps.size * 4
            val indicatorSize = 32 + indicatorMaps.size * 12
            val geometrySize = geometryBody.copyOfRange(24, geometryBody.size).size + 32
            val reported = XXkb.GbnCompatMap or XXkb.GbnIndicatorMap or XXkb.GbnGeometry
            assertEquals(5, u16le(reply, 2))
            assertEquals((compatSize + indicatorSize + geometrySize) / 4, u32le(reply, 4))
            assertEquals(reported, u16le(reply, 12))
            assertEquals(reported, u16le(reply, 14))
            assertCompatMapReply(reply.copyOfRange(32, 32 + compatSize), sequence = 5, groups = 0x5, groupMaps = groupMaps)
            assertIndicatorMapReply(
                reply.copyOfRange(32 + compatSize, 32 + compatSize + indicatorSize),
                sequence = 5,
                which = 0x3,
                maps = indicatorMaps,
            )
            assertGeometryReply(reply.copyOfRange(32 + compatSize + indicatorSize, reply.size), sequence = 5, geometryBody = geometryBody)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName does not report stored geometry for nonmatching component expression`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(internAtomRequest("xkb-getkbd-geometry-nonmatching"))
            out.flush()
            val geometryAtom = u32le(readReply(socket.getInputStream()), 8)
            val geometryBody = setGeometryBody(name = geometryAtom)
            val nonmatchingComponents = xkbComponentSpecs("", "", "", "", "", "does-not-match")

            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, geometryBody))
            out.write(getKbdByNameRequest(need = 0, want = XXkb.GbnGeometry, load = false, trailingNames = nonmatchingComponents))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(3, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName with mandatory unsupported pieces suppresses reports`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(internAtomRequest("xkb-getkbd-geometry-need"))
            out.flush()
            val geometryAtom = u32le(readReply(socket.getInputStream()), 8)
            val geometryBody = setGeometryBody(name = geometryAtom)

            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, geometryBody))
            out.write(getKbdByNameRequest(need = 1 shl 5, want = XXkb.GbnGeometry, load = false))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(3, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(XXkb.GbnGeometry, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName does not report geometry before one is stored`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getKbdByNameRequest(need = XXkb.GbnGeometry, want = XXkb.GbnGeometry, load = false))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName validates component name lengths and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getKbdByNameRequest(need = 0, want = 0, load = false, trailingNames = byteArrayOf(5, 'b'.code.toByte(), 0, 0)))
            out.write(getKbdByNameRequest(need = 0, want = 0, load = false, trailingNames = xkbComponentSpecs("base")))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetKbdByName)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(XKeyboard.MinKeycode, reply[8].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[9].toInt() and 0xff)
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName validates fixed prefix length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetKbdByName, ByteArray(4)))
            out.write(getKbdByNameRequest(need = 0, want = 0, load = false))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetKbdByName)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(XKeyboard.MinKeycode, reply[8].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[9].toInt() and 0xff)
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
        }
    }

    @Test
    fun `XKEYBOARD GetDeviceInfo reports unsupported XI features and pointer button count`() {
        withServer { socket, _ ->
            val wanted = XXkb.XiFeatureButtonActions or XXkb.XiFeatureIndicatorNames or XXkb.XiFeatureIndicatorMaps or XXkb.XiFeatureIndicatorState
            val out = socket.getOutputStream()
            out.write(getDeviceInfoRequest(wanted = wanted, allButtons = true, firstButton = 1, nButtons = 3, ledClass = 0x0300, ledId = 0x0400))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(511, u32le(reply, 4))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 10))
            assertEquals(wanted and XXkb.XiFeatureButtonActions.inv(), u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(1, reply[16].toInt() and 0xff)
            assertEquals(3, reply[17].toInt() and 0xff)
            assertEquals(1, reply[18].toInt() and 0xff)
            assertEquals(255, reply[19].toInt() and 0xff)
            assertEquals(255, reply[20].toInt() and 0xff)
            assertEquals(0, reply[21].toInt() and 0xff)
            assertEquals(0, u16le(reply, 22))
            assertEquals(0, u16le(reply, 24))
            assertEquals(0, u32le(reply, 28))
            assertEquals(0, u16le(reply, 32))
            assertEquals(true, reply.copyOfRange(36, reply.size).all { it == 0.toByte() })
            assertEquals(2076, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetDeviceInfo only reports button actions for core pointer device`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                getDeviceInfoRequest(
                    wanted = XXkb.XiFeatureButtonActions,
                    allButtons = false,
                    firstButton = 1,
                    nButtons = 1,
                    deviceSpec = XXkb.DeviceSpecUseCoreKeyboard,
                ),
            )
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, u16le(reply, 2))
            assertEquals(1, u32le(reply, 4))
            assertEquals(0, u16le(reply, 8))
            assertEquals(0, u16le(reply, 10))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 12))
            assertEquals(0, reply[18].toInt() and 0xff)
            assertEquals(0, reply[19].toInt() and 0xff)
            assertEquals(0, reply[20].toInt() and 0xff)
            assertEquals(0, u16le(reply, 32))
            assertEquals(36, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetDeviceInfo validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetDeviceInfo, ByteArray(8)))
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureButtonActions, allButtons = false, firstButton = 2, nButtons = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetDeviceInfo)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(3, u32le(reply, 4))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 10))
            assertEquals(0, u16le(reply, 12))
            assertEquals(2, reply[16].toInt() and 0xff)
            assertEquals(1, reply[17].toInt() and 0xff)
            assertEquals(2, reply[18].toInt() and 0xff)
            assertEquals(1, reply[19].toInt() and 0xff)
            assertEquals(255, reply[20].toInt() and 0xff)
            assertEquals(0, u16le(reply, 32))
            assertEquals(true, reply.copyOfRange(36, reply.size).all { it == 0.toByte() })
            assertEquals(44, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetDeviceInfo reports Match for illegal button action range`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureButtonActions, allButtons = false, firstButton = 0, nButtons = 1))
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureButtonActions, allButtons = false, firstButton = 255, nButtons = 2))
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureButtonActions, allButtons = true, firstButton = 0, nButtons = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetDeviceInfo)
            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.GetDeviceInfo)
            val reply = readReply(socket.getInputStream())
            assertEquals(3, u16le(reply, 2))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(1, reply[18].toInt() and 0xff)
            assertEquals(255, reply[19].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetDeviceInfo accepts empty button action range`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureButtonActions, allButtons = false, firstButton = 0, nButtons = 0))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, u16le(reply, 2))
            assertEquals(1, u32le(reply, 4))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 10))
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, reply[18].toInt() and 0xff)
            assertEquals(0, reply[19].toInt() and 0xff)
            assertEquals(255, reply[20].toInt() and 0xff)
            assertEquals(36, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetDeviceInfo accepts button actions without changing empty LED device info`() {
        withServer { socket, _ ->
            val wanted = XXkb.XiFeatureButtonActions or XXkb.XiFeatureIndicatorNames or XXkb.XiFeatureIndicatorMaps
            val out = socket.getOutputStream()
            out.write(setDeviceInfoRequest(nButtons = 2, nDeviceLedFeedbacks = 1, change = XXkb.XiFeatureButtonActions))
            out.write(getDeviceInfoRequest(wanted = wanted, allButtons = false, firstButton = 1, nButtons = 2))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(5, u32le(reply, 4))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 10))
            assertEquals(wanted and XXkb.XiFeatureButtonActions.inv(), u16le(reply, 12))
            assertEquals(1, reply[16].toInt() and 0xff)
            assertEquals(2, reply[17].toInt() and 0xff)
            assertEquals(1, reply[18].toInt() and 0xff)
            assertEquals(2, reply[19].toInt() and 0xff)
            assertEquals(255, reply[20].toInt() and 0xff)
            assertEquals(0, u16le(reply, 32))
            assertEquals(1, reply[36].toInt() and 0xff)
            assertEquals(true, reply.copyOfRange(37, 44).all { it == 0.toByte() })
            assertEquals(1, reply[44].toInt() and 0xff)
            assertEquals(true, reply.copyOfRange(45, 52).all { it == 0.toByte() })
            assertEquals(52, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetDeviceInfo reports Match for illegal button action range without partial LED update`() {
        withServer { socket, _ ->
            val wanted = XXkb.XiFeatureIndicatorState
            val out = socket.getOutputStream()
            out.write(
                setDeviceInfoRequest(
                    nButtons = 1,
                    nDeviceLedFeedbacks = 1,
                    change = XXkb.XiFeatureButtonActions or XXkb.XiFeatureIndicatorState,
                    firstButton = 0,
                    ledNamesPresent = 0,
                    ledMapsPresent = 0,
                    ledState = 0x0000_0001,
                ),
            )
            out.write(getDeviceInfoRequest(wanted = wanted, allButtons = false, firstButton = 0, nButtons = 0, ledClass = XXkb.DfltXIClass, ledId = XXkb.DfltXIId))
            out.flush()

            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetDeviceInfo)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0, u16le(reply, 8))
            assertEquals(wanted, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(36, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetDeviceInfo emits ExtensionDeviceNotify for selected button actions`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventExtensionDeviceNotify,
                    details = selectEvents16Details(XXkb.XiFeatureButtonActions, XXkb.XiFeatureButtonActions),
                ),
            )
            out.write(
                setDeviceInfoRequest(
                    nButtons = 2,
                    nDeviceLedFeedbacks = 0,
                    change = XXkb.XiFeatureButtonActions,
                    firstButton = 2,
                ),
            )
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureButtonActions, allButtons = false, firstButton = 2, nButtons = 2))
            out.flush()

            assertXkbExtensionDeviceNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                reason = XXkb.XiFeatureButtonActions,
                firstButton = 2,
                nButtons = 2,
            )
            val reply = readReply(socket.getInputStream())
            assertEquals(3, u16le(reply, 2))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(2, reply[18].toInt() and 0xff)
            assertEquals(2, reply[19].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SetDeviceInfo suppresses ExtensionDeviceNotify when detail mask does not intersect`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventExtensionDeviceNotify,
                    details = selectEvents16Details(XXkb.XiFeatureIndicatorState, XXkb.XiFeatureIndicatorState),
                ),
            )
            out.write(
                setDeviceInfoRequest(
                    nButtons = 1,
                    nDeviceLedFeedbacks = 0,
                    change = XXkb.XiFeatureButtonActions,
                ),
            )
            out.write(useExtensionRequest())
            out.flush()

            val version = readReply(socket.getInputStream())
            assertEquals(3, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents clear removes ExtensionDeviceNotify selection`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventExtensionDeviceNotify,
                    selectAll = XXkb.EventExtensionDeviceNotify,
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventExtensionDeviceNotify,
                    clear = XXkb.EventExtensionDeviceNotify,
                ),
            )
            out.write(
                setDeviceInfoRequest(
                    nButtons = 1,
                    nDeviceLedFeedbacks = 0,
                    change = XXkb.XiFeatureButtonActions,
                ),
            )
            out.write(useExtensionRequest())
            out.flush()

            val version = readReply(socket.getInputStream())
            assertEquals(4, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SetDeviceInfo emits ExtensionDeviceNotify for selected indicator feedback`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val reason = XXkb.XiFeatureIndicatorNames or XXkb.XiFeatureIndicatorMaps or XXkb.XiFeatureIndicatorState
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventExtensionDeviceNotify,
                    details = selectEvents16Details(reason, reason),
                ),
            )
            out.write(
                setDeviceInfoRequest(
                    nButtons = 0,
                    nDeviceLedFeedbacks = 1,
                    change = reason,
                    ledNamesPresent = 0x0000_0003,
                    ledMapsPresent = 0x0000_0002,
                    ledState = 0x0000_0002,
                ),
            )
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureIndicators, allButtons = false, firstButton = 0, nButtons = 0, ledClass = XXkb.DfltXIClass, ledId = XXkb.DfltXIId))
            out.flush()

            assertXkbExtensionDeviceNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                reason = reason,
                ledClass = XXkb.DfltXIClass,
                ledId = XXkb.DfltXIId,
                ledsDefined = 0x0000_0003,
                ledState = 0x0000_0002,
            )
            val reply = readReply(socket.getInputStream())
            assertEquals(3, u16le(reply, 2))
            assertEquals(reason, u16le(reply, 8))
            assertEquals(reason, u16le(reply, 10) and reason)
        }
    }

    @Test
    fun `XKEYBOARD ExtensionDeviceNotify reports current LED definitions after state-only update`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                setDeviceInfoRequest(
                    nButtons = 0,
                    nDeviceLedFeedbacks = 1,
                    change = XXkb.XiFeatureIndicatorNames or XXkb.XiFeatureIndicatorMaps,
                    ledNamesPresent = 0x0000_0003,
                    ledMapsPresent = 0x0000_0002,
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventExtensionDeviceNotify,
                    details = selectEvents16Details(XXkb.XiFeatureIndicatorState, XXkb.XiFeatureIndicatorState),
                ),
            )
            out.write(
                setDeviceInfoRequest(
                    nButtons = 0,
                    nDeviceLedFeedbacks = 1,
                    change = XXkb.XiFeatureIndicatorState,
                    ledNamesPresent = 0,
                    ledMapsPresent = 0,
                    ledState = 0x0000_0002,
                ),
            )
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureIndicators, allButtons = false, firstButton = 0, nButtons = 0, ledClass = XXkb.DfltXIClass, ledId = XXkb.DfltXIId))
            out.flush()

            assertXkbExtensionDeviceNotify(
                socket.getInputStream().readExactly(32),
                sequence = 3,
                reason = XXkb.XiFeatureIndicatorState,
                ledClass = XXkb.DfltXIClass,
                ledId = XXkb.DfltXIId,
                ledsDefined = 0x0000_0003,
                ledState = 0x0000_0002,
            )
            val reply = readReply(socket.getInputStream())
            assertEquals(4, u16le(reply, 2))
            assertEquals(XXkb.XiFeatureIndicators, u16le(reply, 8))
        }
    }

    @Test
    fun `XKEYBOARD ExtensionDeviceNotify reports current LED state after names maps update`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val reason = XXkb.XiFeatureIndicatorNames or XXkb.XiFeatureIndicatorMaps
            out.write(
                setDeviceInfoRequest(
                    nButtons = 0,
                    nDeviceLedFeedbacks = 1,
                    change = XXkb.XiFeatureIndicatorState,
                    ledNamesPresent = 0,
                    ledMapsPresent = 0,
                    ledState = 0x0000_0002,
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventExtensionDeviceNotify,
                    details = selectEvents16Details(reason, reason),
                ),
            )
            out.write(
                setDeviceInfoRequest(
                    nButtons = 0,
                    nDeviceLedFeedbacks = 1,
                    change = reason,
                    ledNamesPresent = 0x0000_0003,
                    ledMapsPresent = 0x0000_0002,
                    ledState = 0,
                ),
            )
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureIndicators, allButtons = false, firstButton = 0, nButtons = 0, ledClass = XXkb.DfltXIClass, ledId = XXkb.DfltXIId))
            out.flush()

            assertXkbExtensionDeviceNotify(
                socket.getInputStream().readExactly(32),
                sequence = 3,
                reason = reason,
                ledClass = XXkb.DfltXIClass,
                ledId = XXkb.DfltXIId,
                ledsDefined = 0x0000_0003,
                ledState = 0x0000_0002,
            )
            val reply = readReply(socket.getInputStream())
            assertEquals(4, u16le(reply, 2))
            assertEquals(XXkb.XiFeatureIndicators, u16le(reply, 8))
        }
    }

    @Test
    fun `XKEYBOARD SetDeviceInfo emits requester-only unsupported feature notify for non-core device`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventExtensionDeviceNotify,
                    details = selectEvents16Details(XXkb.XiFeatureUnsupportedFeature, XXkb.XiFeatureUnsupportedFeature),
                ),
            )
            out.write(
                setDeviceInfoRequest(
                    nButtons = 1,
                    nDeviceLedFeedbacks = 0,
                    change = XXkb.XiFeatureButtonActions,
                    deviceSpec = XXkb.DeviceSpecUseCoreKeyboard,
                ),
            )
            out.write(useExtensionRequest())
            out.flush()

            assertXkbExtensionDeviceNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                reason = XXkb.XiFeatureUnsupportedFeature,
                supported = 0,
                unsupported = XXkb.XiFeatureButtonActions,
            )
            val version = readReply(socket.getInputStream())
            assertEquals(3, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SetDeviceInfo rejects illegal change mask and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                setDeviceInfoRequest(
                    nButtons = 0,
                    nDeviceLedFeedbacks = 0,
                    change = 0x0020,
                ),
            )
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 0x0020, sequence = 1, minorOpcode = XXkb.SetDeviceInfo)
            val version = readReply(socket.getInputStream())
            assertEquals(2, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD unsupported indicator feature notify reports requested feedback identity`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventExtensionDeviceNotify,
                    details = selectEvents16Details(XXkb.XiFeatureUnsupportedFeature, XXkb.XiFeatureUnsupportedFeature),
                ),
            )
            out.write(
                setDeviceInfoRequest(
                    nButtons = 0,
                    nDeviceLedFeedbacks = 1,
                    change = XXkb.XiFeatureIndicatorState,
                    ledClass = XXkb.KbdFeedbackClass,
                    ledId = 7,
                    ledNamesPresent = 0,
                    ledMapsPresent = 0,
                    ledState = 0x0000_0001,
                    deviceSpec = XXkb.DeviceSpecUseCoreKeyboard,
                ),
            )
            out.write(useExtensionRequest())
            out.flush()

            assertXkbExtensionDeviceNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                reason = XXkb.XiFeatureUnsupportedFeature,
                ledClass = XXkb.KbdFeedbackClass,
                ledId = 7,
                supported = 0,
                unsupported = XXkb.XiFeatureIndicatorState,
            )
            val version = readReply(socket.getInputStream())
            assertEquals(3, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD unsupported feature notify is not broadcast to other selected clients`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { requester ->
                Socket("127.0.0.1", server.localPort).use { observer ->
                    requester.soTimeout = 2_000
                    observer.soTimeout = 2_000
                    setup(requester)
                    setup(observer)

                    val requesterOut = requester.getOutputStream()
                    requesterOut.write(
                        selectEventsRequest(
                            affectWhich = XXkb.EventExtensionDeviceNotify,
                            details = selectEvents16Details(XXkb.XiFeatureUnsupportedFeature, XXkb.XiFeatureUnsupportedFeature),
                        ),
                    )
                    requesterOut.flush()

                    val observerOut = observer.getOutputStream()
                    observerOut.write(
                        selectEventsRequest(
                            affectWhich = XXkb.EventExtensionDeviceNotify,
                            details = selectEvents16Details(XXkb.XiFeatureUnsupportedFeature, XXkb.XiFeatureUnsupportedFeature),
                        ),
                    )
                    observerOut.write(useExtensionRequest())
                    observerOut.flush()
                    val observerSelectionReply = observer.getInputStream().readExactly(32)
                    assertEquals(1, observerSelectionReply[0].toInt() and 0xff)
                    assertEquals(2, u16le(observerSelectionReply, 2))

                    requesterOut.write(
                        setDeviceInfoRequest(
                            nButtons = 1,
                            nDeviceLedFeedbacks = 0,
                            change = XXkb.XiFeatureButtonActions,
                            deviceSpec = XXkb.DeviceSpecUseCoreKeyboard,
                        ),
                    )
                    requesterOut.flush()

                    assertXkbExtensionDeviceNotify(
                        requester.getInputStream().readExactly(32),
                        sequence = 2,
                        reason = XXkb.XiFeatureUnsupportedFeature,
                        supported = 0,
                        unsupported = XXkb.XiFeatureButtonActions,
                    )
                    observerOut.write(useExtensionRequest())
                    observerOut.flush()
                    val observerReply = observer.getInputStream().readExactly(32)
                    assertEquals(1, observerReply[0].toInt() and 0xff)
                    assertEquals(3, u16le(observerReply, 2))
                    assertEquals(XXkb.MajorVersion, u16le(observerReply, 8))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XKEYBOARD SetDeviceInfo reports unsupported feature after partial core pointer success`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventExtensionDeviceNotify,
                    details = selectEvents16Details(
                        XXkb.XiFeatureButtonActions or XXkb.XiFeatureUnsupportedFeature,
                        XXkb.XiFeatureButtonActions or XXkb.XiFeatureUnsupportedFeature,
                    ),
                ),
            )
            out.write(
                setDeviceInfoRequest(
                    nButtons = 1,
                    nDeviceLedFeedbacks = 0,
                    change = XXkb.XiFeatureButtonActions or XXkb.XiFeatureKeyboards,
                ),
            )
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureButtonActions, allButtons = false, firstButton = 1, nButtons = 1))
            out.flush()

            assertXkbExtensionDeviceNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                reason = XXkb.XiFeatureButtonActions,
                firstButton = 1,
                nButtons = 1,
            )
            assertXkbExtensionDeviceNotify(
                socket.getInputStream().readExactly(32),
                sequence = 2,
                reason = XXkb.XiFeatureUnsupportedFeature,
                unsupported = XXkb.XiFeatureKeyboards,
            )
            val reply = readReply(socket.getInputStream())
            assertEquals(3, u16le(reply, 2))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(1, reply[18].toInt() and 0xff)
            assertEquals(1, reply[19].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD unsupported feature notify requires selected detail`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventExtensionDeviceNotify,
                    details = selectEvents16Details(XXkb.XiFeatureButtonActions, XXkb.XiFeatureButtonActions),
                ),
            )
            out.write(
                setDeviceInfoRequest(
                    nButtons = 1,
                    nDeviceLedFeedbacks = 0,
                    change = XXkb.XiFeatureButtonActions,
                    deviceSpec = XXkb.DeviceSpecUseCoreKeyboard,
                ),
            )
            out.write(useExtensionRequest())
            out.flush()

            val version = readReply(socket.getInputStream())
            assertEquals(3, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SetDeviceInfo persists matching LED feedback`() {
        withServer { socket, _ ->
            val wanted = XXkb.XiFeatureIndicatorNames or XXkb.XiFeatureIndicatorMaps or XXkb.XiFeatureIndicatorState
            val ledMap = byteArrayOf(2, 3, 4, 5, 0, 6, 0x34, 0x12, 0x78, 0x56, 0x34, 0x12)
            val out = socket.getOutputStream()
            out.write(
                setDeviceInfoRequest(
                    nButtons = 0,
                    nDeviceLedFeedbacks = 1,
                    change = wanted,
                    ledNamesPresent = 0x0000_0001,
                    ledMapsPresent = 0x0000_0001,
                    ledPhysIndicators = 0x0000_0003,
                    ledState = 0x0000_0001,
                    ledNameAtoms = listOf(0x40),
                    ledMaps = listOf(ledMap),
                ),
            )
            out.write(
                getDeviceInfoRequest(
                    wanted = wanted,
                    allButtons = false,
                    firstButton = 0,
                    nButtons = 0,
                    ledClass = XXkb.DfltXIClass,
                    ledId = XXkb.DfltXIId,
                ),
            )
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(10, u32le(reply, 4))
            assertEquals(wanted, u16le(reply, 8))
            assertEquals(XXkb.XiFeatureButtonActions or XXkb.XiFeatureIndicators, u16le(reply, 10))
            assertEquals(0, u16le(reply, 12))
            assertEquals(1, u16le(reply, 14))
            assertEquals(0, reply[18].toInt() and 0xff)
            assertEquals(0, reply[19].toInt() and 0xff)
            assertEquals(255, reply[20].toInt() and 0xff)
            assertEquals(0, u32le(reply, 32))
            assertEquals(XXkb.DfltXIClass, u16le(reply, 36))
            assertEquals(XXkb.DfltXIId, u16le(reply, 38))
            assertEquals(0x0000_0001, u32le(reply, 40))
            assertEquals(0x0000_0001, u32le(reply, 44))
            assertEquals(0x0000_0003, u32le(reply, 48))
            assertEquals(0x0000_0001, u32le(reply, 52))
            assertEquals(0x40, u32le(reply, 56))
            assertEquals(ledMap.toList(), reply.copyOfRange(60, 72).toList())
            assertEquals(72, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetDeviceInfo rejects invalid LED name atom without partial button update`() {
        withServer { socket, _ ->
            val invalidAtom = 0x7fff_fffe
            val out = socket.getOutputStream()
            out.write(
                setDeviceInfoRequest(
                    nButtons = 1,
                    nDeviceLedFeedbacks = 1,
                    ledNameAtoms = listOf(invalidAtom),
                ),
            )
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureButtonActions, allButtons = false, firstButton = 1, nButtons = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 5, opcode = XXkb.MajorOpcode, badValue = invalidAtom, sequence = 1, minorOpcode = XXkb.SetDeviceInfo)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(3, u32le(reply, 4))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(1, reply[18].toInt() and 0xff)
            assertEquals(1, reply[19].toInt() and 0xff)
            assertEquals(true, reply.copyOfRange(36, 44).all { it == 0.toByte() })
        }
    }

    @Test
    fun `XKEYBOARD SetDeviceInfo state-only LED update preserves names and maps`() {
        withServer { socket, _ ->
            val wanted = XXkb.XiFeatureIndicatorNames or XXkb.XiFeatureIndicatorMaps or XXkb.XiFeatureIndicatorState
            val ledMap = byteArrayOf(7, 6, 5, 4, 0, 3, 0x21, 0x43, 0x65, 0x43, 0x21, 0)
            val out = socket.getOutputStream()
            out.write(
                setDeviceInfoRequest(
                    nButtons = 0,
                    nDeviceLedFeedbacks = 1,
                    change = wanted,
                    ledNamesPresent = 0x0000_0001,
                    ledMapsPresent = 0x0000_0001,
                    ledPhysIndicators = 0x0000_0003,
                    ledState = 0x0000_0001,
                    ledNameAtoms = listOf(0x40),
                    ledMaps = listOf(ledMap),
                ),
            )
            out.write(
                setDeviceInfoRequest(
                    nButtons = 0,
                    nDeviceLedFeedbacks = 1,
                    change = XXkb.XiFeatureIndicatorState,
                    ledNamesPresent = 0,
                    ledMapsPresent = 0,
                    ledPhysIndicators = 0x0000_0007,
                    ledState = 0x0000_0004,
                ),
            )
            out.write(
                getDeviceInfoRequest(
                    wanted = wanted,
                    allButtons = false,
                    firstButton = 0,
                    nButtons = 0,
                    ledClass = XXkb.DfltXIClass,
                    ledId = XXkb.DfltXIId,
                ),
            )
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(3, u16le(reply, 2))
            assertEquals(wanted, u16le(reply, 8))
            assertEquals(1, u16le(reply, 14))
            assertEquals(0x0000_0001, u32le(reply, 40))
            assertEquals(0x0000_0001, u32le(reply, 44))
            assertEquals(0x0000_0007, u32le(reply, 48))
            assertEquals(0x0000_0004, u32le(reply, 52))
            assertEquals(0x40, u32le(reply, 56))
            assertEquals(ledMap.toList(), reply.copyOfRange(60, 72).toList())
            assertEquals(72, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetDeviceInfo default LED selector matches explicit keyboard feedback`() {
        withServer { socket, _ ->
            val wanted = XXkb.XiFeatureIndicatorNames or XXkb.XiFeatureIndicatorState
            val out = socket.getOutputStream()
            out.write(
                setDeviceInfoRequest(
                    nButtons = 0,
                    nDeviceLedFeedbacks = 1,
                    change = wanted,
                    ledClass = XXkb.KbdFeedbackClass,
                    ledId = 7,
                    ledNamesPresent = 0x0000_0001,
                    ledMapsPresent = 0,
                    ledPhysIndicators = 0x0000_0005,
                    ledState = 0x0000_0004,
                    ledNameAtoms = listOf(0x40),
                ),
            )
            out.write(
                getDeviceInfoRequest(
                    wanted = wanted,
                    allButtons = false,
                    firstButton = 0,
                    nButtons = 0,
                    ledClass = XXkb.DfltXIClass,
                    ledId = XXkb.DfltXIId,
                ),
            )
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(7, u32le(reply, 4))
            assertEquals(wanted, u16le(reply, 8))
            assertEquals(XXkb.XiFeatureButtonActions or XXkb.XiFeatureIndicators, u16le(reply, 10))
            assertEquals(1, u16le(reply, 14))
            assertEquals(XXkb.KbdFeedbackClass, u16le(reply, 36))
            assertEquals(7, u16le(reply, 38))
            assertEquals(0x0000_0001, u32le(reply, 40))
            assertEquals(0, u32le(reply, 44))
            assertEquals(0x0000_0005, u32le(reply, 48))
            assertEquals(0x0000_0004, u32le(reply, 52))
            assertEquals(0x40, u32le(reply, 56))
            assertEquals(60, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetDeviceInfo rejects invalid LED selector after feedback is supported`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                setDeviceInfoRequest(
                    nButtons = 0,
                    nDeviceLedFeedbacks = 1,
                    change = XXkb.XiFeatureIndicatorState,
                    ledClass = XXkb.KbdFeedbackClass,
                    ledId = 1,
                    ledNamesPresent = 0,
                    ledMapsPresent = 0,
                    ledState = 0x0000_0001,
                ),
            )
            out.write(
                getDeviceInfoRequest(
                    wanted = XXkb.XiFeatureIndicatorState,
                    allButtons = false,
                    firstButton = 0,
                    nButtons = 0,
                    ledClass = 0x0700,
                    ledId = XXkb.DfltXIId,
                ),
            )
            out.write(
                getDeviceInfoRequest(
                    wanted = XXkb.XiFeatureIndicatorState,
                    allButtons = false,
                    firstButton = 0,
                    nButtons = 0,
                    ledClass = XXkb.KbdFeedbackClass,
                    ledId = 0x0700,
                ),
            )
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureButtonActions, allButtons = false, firstButton = 1, nButtons = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 0x0700, sequence = 2, minorOpcode = XXkb.GetDeviceInfo)
            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 0x0700, sequence = 3, minorOpcode = XXkb.GetDeviceInfo)
            val reply = readReply(socket.getInputStream())
            assertEquals(4, u16le(reply, 2))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(1, reply[18].toInt() and 0xff)
            assertEquals(1, reply[19].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetDeviceInfo rejects invalid LED selector before feedback is stored`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                getDeviceInfoRequest(
                    wanted = XXkb.XiFeatureIndicatorState,
                    allButtons = false,
                    firstButton = 0,
                    nButtons = 0,
                    ledClass = 0x0700,
                    ledId = XXkb.DfltXIId,
                ),
            )
            out.write(
                getDeviceInfoRequest(
                    wanted = XXkb.XiFeatureIndicatorState,
                    allButtons = false,
                    firstButton = 0,
                    nButtons = 0,
                    ledClass = XXkb.KbdFeedbackClass,
                    ledId = 0x0700,
                ),
            )
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureButtonActions, allButtons = false, firstButton = 1, nButtons = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 0x0700, sequence = 1, minorOpcode = XXkb.GetDeviceInfo)
            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 0x0700, sequence = 2, minorOpcode = XXkb.GetDeviceInfo)
            val reply = readReply(socket.getInputStream())
            assertEquals(3, u16le(reply, 2))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(1, reply[18].toInt() and 0xff)
            assertEquals(1, reply[19].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetDeviceInfo reports Match for legal unmatched LED selector`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                setDeviceInfoRequest(
                    nButtons = 0,
                    nDeviceLedFeedbacks = 1,
                    change = XXkb.XiFeatureIndicatorState,
                    ledClass = XXkb.KbdFeedbackClass,
                    ledId = 1,
                    ledNamesPresent = 0,
                    ledMapsPresent = 0,
                    ledPhysIndicators = 0x0000_0003,
                    ledState = 0x0000_0001,
                ),
            )
            out.write(
                getDeviceInfoRequest(
                    wanted = XXkb.XiFeatureIndicatorState,
                    allButtons = false,
                    firstButton = 0,
                    nButtons = 0,
                    ledClass = XXkb.LedFeedbackClass,
                    ledId = XXkb.AllXIIds,
                ),
            )
            out.write(
                getDeviceInfoRequest(
                    wanted = XXkb.XiFeatureIndicatorState,
                    allButtons = false,
                    firstButton = 0,
                    nButtons = 0,
                    ledClass = XXkb.KbdFeedbackClass,
                    ledId = 2,
                ),
            )
            out.write(
                getDeviceInfoRequest(
                    wanted = XXkb.XiFeatureIndicatorState,
                    allButtons = false,
                    firstButton = 0,
                    nButtons = 0,
                    ledClass = XXkb.KbdFeedbackClass,
                    ledId = XXkb.AllXIIds,
                ),
            )
            out.flush()

            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.GetDeviceInfo)
            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.GetDeviceInfo)
            val reply = readReply(socket.getInputStream())
            assertEquals(4, u16le(reply, 2))
            assertEquals(6, u32le(reply, 4))
            assertEquals(XXkb.XiFeatureIndicatorState, u16le(reply, 8))
            assertEquals(1, u16le(reply, 14))
            assertEquals(XXkb.KbdFeedbackClass, u16le(reply, 36))
            assertEquals(1, u16le(reply, 38))
            assertEquals(0, u32le(reply, 40))
            assertEquals(0, u32le(reply, 44))
            assertEquals(0x0000_0003, u32le(reply, 48))
            assertEquals(0x0000_0001, u32le(reply, 52))
            assertEquals(56, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetDeviceInfo rejects invalid LED selector without partial updates`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                setDeviceInfoRequest(
                    nButtons = 1,
                    nDeviceLedFeedbacks = 1,
                    ledClass = XXkb.AllXIClasses,
                    ledId = XXkb.DfltXIId,
                ),
            )
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureButtonActions, allButtons = false, firstButton = 1, nButtons = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = XXkb.AllXIClasses, sequence = 1, minorOpcode = XXkb.SetDeviceInfo)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(true, reply.copyOfRange(36, 44).all { it == 0.toByte() })
        }
    }

    @Test
    fun `XKEYBOARD SetDeviceInfo validates variable payload length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SetDeviceInfo, ByteArray(4)))
            out.write(setDeviceInfoRequest(nButtons = 1, nDeviceLedFeedbacks = 1, bodySize = 48))
            out.write(setDeviceInfoRequest(nButtons = 1, nDeviceLedFeedbacks = 1, bodySize = 56))
            out.write(setDeviceInfoRequest(nButtons = 2, nDeviceLedFeedbacks = 2, change = 0))
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureButtonActions, allButtons = false, firstButton = 2, nButtons = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetDeviceInfo)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetDeviceInfo)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetDeviceInfo)
            val reply = readReply(socket.getInputStream())
            assertEquals(5, u16le(reply, 2))
            assertEquals(3, u32le(reply, 4))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 10))
            assertEquals(0, u16le(reply, 12))
            assertEquals(2, reply[16].toInt() and 0xff)
            assertEquals(1, reply[17].toInt() and 0xff)
            assertEquals(2, reply[18].toInt() and 0xff)
            assertEquals(1, reply[19].toInt() and 0xff)
            assertEquals(255, reply[20].toInt() and 0xff)
            assertEquals(0, u16le(reply, 32))
            assertEquals(true, reply.copyOfRange(36, reply.size).all { it == 0.toByte() })
            assertEquals(44, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetDebuggingFlags reports no supported debugging flags`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setDebuggingFlagsRequest(message = "trace", affectFlags = -1, flags = -1, affectCtrls = -1, ctrls = -1))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u32le(reply, 8))
            assertEquals(0, u32le(reply, 12))
            assertEquals(0, u32le(reply, 16))
            assertEquals(0, u32le(reply, 20))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetDebuggingFlags validates padded message length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val malformed = ByteArray(24)
            put16le(malformed, 0, 5)
            out.write(request(XXkb.MajorOpcode, XXkb.SetDebuggingFlags, ByteArray(16)))
            out.write(request(XXkb.MajorOpcode, XXkb.SetDebuggingFlags, malformed))
            out.write(setDebuggingFlagsRequest(message = "trace", affectFlags = -1, flags = -1, affectCtrls = -1, ctrls = -1, extraBytes = 4))
            out.write(setDebuggingFlagsRequest(message = "", affectFlags = 1, flags = 1, affectCtrls = 1, ctrls = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetDebuggingFlags)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetDebuggingFlags)
            val overlongReply = readReply(socket.getInputStream())
            assertEquals(3, u16le(overlongReply, 2))
            assertEquals(0, u32le(overlongReply, 8))
            assertEquals(0, u32le(overlongReply, 12))
            assertEquals(0, u32le(overlongReply, 16))
            assertEquals(0, u32le(overlongReply, 20))
            val reply = readReply(socket.getInputStream())
            assertEquals(4, u16le(reply, 2))
            assertEquals(0, u32le(reply, 8))
            assertEquals(0, u32le(reply, 12))
            assertEquals(0, u32le(reply, 16))
            assertEquals(0, u32le(reply, 20))
        }
    }

    @Test
    fun `XKEYBOARD unimplemented requests return BadImplementation and recover stream`() {
        withServer { socket, port ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, 26, ByteArray(0)))
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 17, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = 26)
            val version = readReply(socket.getInputStream())
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))

            assertContains(httpGet(port, "/text.txt"), "XKEYBOARD.Unknown:")
        }
    }

    private fun withServer(block: (Socket, Int) -> Unit) {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                block(socket, server.localPort)
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

    private fun setupBigEndian(socket: Socket) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        val setup = ByteArray(12)
        setup[0] = 0x42
        put16be(setup, 2, 11)
        out.write(setup)
        out.flush()
        val prefix = input.readExactly(8)
        assertEquals(1, prefix[0].toInt())
        input.readExactly(u16be(prefix, 6) * 4)
    }

    private fun queryExtensionRequest(name: String): ByteArray {
        val nameBytes = name.encodeToByteArray()
        val body = ByteArray(4 + ((nameBytes.size + 3) and -4))
        put16le(body, 0, nameBytes.size)
        nameBytes.copyInto(body, 4)
        return request(98, 0, body)
    }

    private fun useExtensionRequest(): ByteArray {
        val body = ByteArray(4)
        put16le(body, 0, XXkb.MajorVersion)
        put16le(body, 2, XXkb.MinorVersion)
        return request(XXkb.MajorOpcode, XXkb.UseExtension, body)
    }

    private fun selectEventsRequest(
        affectWhich: Int = 0,
        clear: Int = 0,
        selectAll: Int = 0,
        affectMap: Int = 0,
        map: Int = 0,
        details: ByteArray = ByteArray(0),
    ): ByteArray {
        val body = ByteArray(paddedSize(12 + details.size))
        put16le(body, 0, 0x0100)
        put16le(body, 2, affectWhich)
        put16le(body, 4, clear)
        put16le(body, 6, selectAll)
        put16le(body, 8, affectMap)
        put16le(body, 10, map)
        details.copyInto(body, 12)
        return request(XXkb.MajorOpcode, XXkb.SelectEvents, body)
    }

    private fun selectEvents16Details(affect: Int, selected: Int): ByteArray =
        ByteArray(4).also {
            put16le(it, 0, affect)
            put16le(it, 2, selected)
        }

    private fun selectEvents32Details(affect: Int, selected: Int): ByteArray =
        ByteArray(8).also {
            put32le(it, 0, affect)
            put32le(it, 4, selected)
        }

    private fun selectEvents8Details(affect: Int, selected: Int): ByteArray =
        byteArrayOf(affect.toByte(), selected.toByte())

    private fun xkbBellRequest(
        percent: Int,
        bellClass: Int = 0,
        bellId: Int = 0,
        forceSound: Boolean = false,
        eventOnly: Boolean = false,
        pitch: Int = 0,
        duration: Int = 0,
        name: Int = 0,
        window: Int = 0,
    ): ByteArray {
        val body = ByteArray(24)
        put16le(body, 0, 0x0100)
        put16le(body, 2, bellClass)
        put16le(body, 4, bellId)
        body[6] = percent.toByte()
        body[7] = (if (forceSound) 1 else 0).toByte()
        body[8] = (if (eventOnly) 1 else 0).toByte()
        put16le(body, 10, pitch)
        put16le(body, 12, duration)
        put32le(body, 16, name)
        put32le(body, 20, window)
        return request(XXkb.MajorOpcode, XXkb.Bell, body)
    }

    private fun getStateRequest(): ByteArray {
        val body = ByteArray(4)
        put16le(body, 0, 0x0100)
        return request(XXkb.MajorOpcode, XXkb.GetState, body)
    }

    private fun queryPointerRequest(): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, X11Ids.RootWindow)
        return request(38, 0, body)
    }

    private fun xtestFakeInputRequest(type: Int, detail: Int, root: Int = X11Ids.RootWindow, x: Int, y: Int, delay: Int = 0): ByteArray {
        val body = ByteArray(32)
        body[0] = type.toByte()
        body[1] = detail.toByte()
        put32le(body, 4, delay)
        put32le(body, 8, root)
        put16le(body, 20, x)
        put16le(body, 22, y)
        return request(XXTest.MajorOpcode, XXTest.FakeInput, body)
    }

    private fun latchLockStateRequest(
        modLocks: Int,
        groupLock: Int,
        latchGroup: Boolean,
        groupLatch: Int,
        affectModLocks: Int = 0xff,
        lockGroup: Boolean = true,
        affectModLatches: Int = 0xff,
        modLatches: Int = 0,
    ): ByteArray {
        val body = ByteArray(12)
        put16le(body, 0, 0x0100)
        body[2] = affectModLocks.toByte()
        body[3] = modLocks.toByte()
        body[4] = if (lockGroup) 1 else 0
        body[5] = groupLock.toByte()
        body[6] = affectModLatches.toByte()
        body[7] = modLatches.toByte()
        body[9] = if (latchGroup) 1 else 0
        put16le(body, 10, groupLatch)
        return request(XXkb.MajorOpcode, XXkb.LatchLockState, body)
    }

    private fun getControlsRequest(): ByteArray {
        val body = ByteArray(4)
        put16le(body, 0, 0x0100)
        return request(XXkb.MajorOpcode, XXkb.GetControls, body)
    }

    private fun getKeyboardControlRequest(): ByteArray =
        request(103, 0, ByteArray(0))

    private fun setControlsRequest(affectEnabledControls: Int, enabledControls: Int): ByteArray {
        val body = ByteArray(96) { 0 }
        put16le(body, 0, 0x0100)
        body[14] = XXkb.DefaultMouseKeysButton.toByte()
        body[15] = XXkb.DefaultGroupCount.toByte()
        put32le(body, 20, affectEnabledControls)
        put32le(body, 24, enabledControls)
        put32le(body, 28, affectEnabledControls)
        put16le(body, 32, XXkb.DefaultRepeatDelay)
        put16le(body, 34, XXkb.DefaultRepeatInterval)
        for (index in 64 until 96) {
            body[index] = 0xff.toByte()
        }
        return request(XXkb.MajorOpcode, XXkb.SetControls, body)
    }

    private fun getMapRequest(
        full: Int,
        partial: Int,
        firstKeySym: Int = XKeyboard.MinKeycode,
        nKeySyms: Int = 0xff,
        firstModMapKey: Int = XKeyboard.MinKeycode,
        nModMapKeys: Int = 0xff,
        virtualMods: Int = 0xffff,
    ): ByteArray {
        val body = ByteArray(24)
        put16le(body, 0, 0x0100)
        put16le(body, 2, full)
        put16le(body, 4, partial)
        body[6] = 0
        body[7] = 0xff.toByte()
        body[8] = firstKeySym.toByte()
        body[9] = nKeySyms.toByte()
        body[10] = XKeyboard.MinKeycode.toByte()
        body[11] = 0xff.toByte()
        body[12] = XKeyboard.MinKeycode.toByte()
        body[13] = 0xff.toByte()
        put16le(body, 14, virtualMods)
        body[16] = XKeyboard.MinKeycode.toByte()
        body[17] = 0xff.toByte()
        body[18] = firstModMapKey.toByte()
        body[19] = nModMapKeys.toByte()
        body[20] = XKeyboard.MinKeycode.toByte()
        body[21] = 0xff.toByte()
        return request(XXkb.MajorOpcode, XXkb.GetMap, body)
    }

    private fun changeKeyboardMappingRequest(firstKeycode: Int, keysymsPerKeycode: Int, vararg keysyms: Int): ByteArray {
        require(keysymsPerKeycode > 0)
        require(keysyms.size % keysymsPerKeycode == 0)
        val body = ByteArray(4 + keysyms.size * 4)
        body[0] = firstKeycode.toByte()
        body[1] = keysymsPerKeycode.toByte()
        keysyms.forEachIndexed { index, keysym ->
            put32le(body, 4 + index * 4, keysym)
        }
        return request(100, keysyms.size / keysymsPerKeycode, body)
    }

    private fun setMapKeySymsRequest(
        firstKeySym: Int = 38,
        keySymRows: List<List<Int>> = listOf(listOf(0x0078, 0x0058), listOf(0x0079)),
        bodySize: Int = 32 + keySymRows.sumOf { 8 + it.size * 4 },
    ): ByteArray {
        val body = ByteArray(bodySize)
        put16le(body, 0, 0x0100)
        put16le(body, 2, XXkb.MapPartKeySyms)
        body[6] = XKeyboard.MinKeycode.toByte()
        body[7] = XKeyboard.MaxKeycode.toByte()
        body[10] = firstKeySym.toByte()
        body[11] = keySymRows.size.toByte()
        put16le(body, 12, keySymRows.sumOf { it.size })

        var offset = 32
        for (row in keySymRows) {
            if (offset + 8 + row.size * 4 <= body.size) {
                body[offset] = (if (row.size > 1) 1 else 0).toByte()
                body[offset + 4] = 1
                body[offset + 5] = row.size.toByte()
                put16le(body, offset + 6, row.size)
                row.forEachIndexed { index, keysym ->
                    put32le(body, offset + 8 + index * 4, keysym)
                }
            }
            offset += 8 + row.size * 4
        }
        return request(XXkb.MajorOpcode, XXkb.SetMap, body)
    }

    private fun setMapModifierMapRequest(
        firstModMapKey: Int,
        nModMapKeys: Int,
        entries: List<Pair<Int, Int>>,
        bodySize: Int = 32 + paddedLengthForTest(entries.size * 2),
    ): ByteArray {
        val body = ByteArray(bodySize)
        put16le(body, 0, 0x0100)
        put16le(body, 2, XXkb.MapPartModifierMap)
        body[6] = XKeyboard.MinKeycode.toByte()
        body[7] = XKeyboard.MaxKeycode.toByte()
        body[24] = firstModMapKey.toByte()
        body[25] = nModMapKeys.toByte()
        body[26] = entries.size.toByte()
        var offset = 32
        for ((keycode, modifiers) in entries) {
            if (offset + 2 <= body.size) {
                body[offset] = keycode.toByte()
                body[offset + 1] = modifiers.toByte()
            }
            offset += 2
        }
        return request(XXkb.MajorOpcode, XXkb.SetMap, body)
    }

    private fun setMapVirtualModsRequest(
        virtualMods: Int,
        realModifiers: List<Int>,
        bodySize: Int = 32 + paddedLengthForTest(realModifiers.size),
    ): ByteArray {
        require(Integer.bitCount(virtualMods) == realModifiers.size)
        val body = ByteArray(bodySize)
        put16le(body, 0, 0x0100)
        put16le(body, 2, XXkb.MapPartVirtualMods)
        body[6] = XKeyboard.MinKeycode.toByte()
        body[7] = XKeyboard.MaxKeycode.toByte()
        put16le(body, 30, virtualMods)
        realModifiers.forEachIndexed { index, modifiers ->
            if (32 + index < body.size) body[32 + index] = modifiers.toByte()
        }
        return request(XXkb.MajorOpcode, XXkb.SetMap, body)
    }

    private fun setMapRequest(
        includeAllParts: Boolean,
        oddExplicitAndModifierMapCounts: Boolean = false,
        firstKeySym: Int = 38,
        keySymRows: List<List<Int>> = listOf(listOf(0x0078, 0x0058), listOf(0x0079)),
        firstModMapKey: Int = 77,
        nModMapKeys: Int = 1,
        modifierMapEntries: List<Pair<Int, Int>> = listOf(77 to 0x08),
        bodySize: Int = setMapBodySize(includeAllParts, keySymRows),
    ): ByteArray {
        val body = ByteArray(bodySize)
        val modMapEntries = if (oddExplicitAndModifierMapCounts) modifierMapEntries.take(1) else modifierMapEntries
        val present = if (includeAllParts) {
            XXkb.MapPartKeyTypes or
                XXkb.MapPartKeySyms or
                XXkb.MapPartModifierMap or
                XXkb.MapPartExplicitComponents or
                XXkb.MapPartKeyActions or
                XXkb.MapPartKeyBehaviors or
                XXkb.MapPartVirtualMods or
                XXkb.MapPartVirtualModMap
        } else {
            0
        }
        put16le(body, 0, 0x0100)
        put16le(body, 2, present)
        put16le(body, 4, 0)
        body[6] = XKeyboard.MinKeycode.toByte()
        body[7] = XKeyboard.MaxKeycode.toByte()
        if (includeAllParts) {
            val explicitCount = if (oddExplicitAndModifierMapCounts) 1 else 2
            body[8] = 0
            body[9] = 2
            body[10] = firstKeySym.toByte()
            body[11] = keySymRows.size.toByte()
            put16le(body, 12, keySymRows.sumOf { it.size })
            body[14] = XKeyboard.MinKeycode.toByte()
            body[15] = 3
            put16le(body, 16, 2)
            body[18] = XKeyboard.MinKeycode.toByte()
            body[19] = 2
            body[20] = 2
            body[21] = XKeyboard.MinKeycode.toByte()
            body[22] = 2
            body[23] = explicitCount.toByte()
            body[24] = firstModMapKey.toByte()
            body[25] = nModMapKeys.toByte()
            body[26] = modMapEntries.size.toByte()
            body[27] = XKeyboard.MinKeycode.toByte()
            body[28] = 2
            body[29] = 2
            put16le(body, 30, 0x0003)
        }

        var offset = 32
        fun write(size: Int, block: (Int) -> Unit = {}) {
            if (offset + size <= body.size) block(offset)
            offset += size
        }
        fun align4() {
            offset = (offset + 3) and -4
        }
        if (includeAllParts) {
            write(24) {
                body[it + 4] = 2
                body[it + 5] = 2
                body[it + 6] = 1
            }
            write(12) {
                body[it + 4] = 1
                body[it + 5] = 1
            }
            for (row in keySymRows) {
                write(8 + row.size * 4) {
                    body[it] = (if (row.size > 1) 1 else 0).toByte()
                    body[it + 4] = 1
                    body[it + 5] = row.size.toByte()
                    put16le(body, it + 6, row.size)
                    row.forEachIndexed { index, keysym ->
                        put32le(body, it + 8 + index * 4, keysym)
                    }
                }
            }
            write(3)
            align4()
            write(16)
            write(8)
            write(2)
            align4()
            write(if (oddExplicitAndModifierMapCounts) 1 * 2 else 2 * 2)
            align4()
            write(modMapEntries.size * 2) {
                modMapEntries.forEachIndexed { index, (keycode, modifiers) ->
                    body[it + index * 2] = keycode.toByte()
                    body[it + index * 2 + 1] = modifiers.toByte()
                }
            }
            align4()
            write(8)
        }
        return request(XXkb.MajorOpcode, XXkb.SetMap, body)
    }

    private fun setMapBodySize(
        includeAllParts: Boolean,
        keySymRows: List<List<Int>> = listOf(listOf(0x0078, 0x0058), listOf(0x0079)),
    ): Int =
        if (includeAllParts) 116 + keySymRows.sumOf { 8 + it.size * 4 } else 32

    private fun getCompatMapRequest(groups: Int, getAllSI: Boolean, firstSI: Int, nSI: Int): ByteArray {
        val body = ByteArray(8)
        put16le(body, 0, 0x0100)
        body[2] = groups.toByte()
        body[3] = if (getAllSI) 1 else 0
        put16le(body, 4, firstSI)
        put16le(body, 6, nSI)
        return request(XXkb.MajorOpcode, XXkb.GetCompatMap, body)
    }

    private fun setCompatMapRequest(
        groups: Int,
        firstSI: Int = 0,
        nSI: Int,
        bodySize: Int = 12 + nSI * 16 + Integer.bitCount(groups) * 4,
        truncateSI: Boolean = true,
        symInterprets: List<ByteArray>? = null,
        groupMaps: List<ByteArray>? = null,
    ): ByteArray {
        val body = ByteArray(bodySize)
        put16le(body, 0, 0x0100)
        if (body.size > 3) body[3] = 1
        if (body.size > 4) body[4] = if (truncateSI) 1 else 0
        if (body.size > 5) body[5] = groups.toByte()
        if (body.size >= 8) put16le(body, 6, firstSI)
        if (body.size >= 10) put16le(body, 8, nSI)
        for (index in 0 until nSI) {
            val offset = 12 + index * 16
            if (offset + 16 > body.size) break
            val symInterpret = symInterprets?.getOrNull(index)
            if (symInterpret == null) {
                put32le(body, offset, 0)
                body[offset + 4] = 1
                body[offset + 5] = 1
                body[offset + 7] = 1
            } else {
                symInterpret.copyInto(body, offset, 0, minOf(16, symInterpret.size))
            }
        }
        var offset = 12 + nSI * 16
        repeat(Integer.bitCount(groups)) { index ->
            if (offset + 4 > body.size) return@repeat
            val groupMap = groupMaps?.getOrNull(index)
            if (groupMap == null) {
                body[offset] = 1
                body[offset + 1] = 1
                put16le(body, offset + 2, 1)
            } else {
                groupMap.copyInto(body, offset, 0, minOf(4, groupMap.size))
            }
            offset += 4
        }
        return request(XXkb.MajorOpcode, XXkb.SetCompatMap, body)
    }

    private fun getIndicatorStateRequest(): ByteArray {
        val body = ByteArray(4)
        put16le(body, 0, 0x0100)
        return request(XXkb.MajorOpcode, XXkb.GetIndicatorState, body)
    }

    private fun getIndicatorMapRequest(which: Int): ByteArray {
        val body = ByteArray(8)
        put16le(body, 0, 0x0100)
        put32le(body, 4, which)
        return request(XXkb.MajorOpcode, XXkb.GetIndicatorMap, body)
    }

    private fun setIndicatorMapRequest(
        which: Int,
        maps: List<ByteArray> = List(Integer.bitCount(which)) { indicatorMapRecord(it + 1) },
        bodySize: Int = 8 + Integer.bitCount(which) * 12,
    ): ByteArray {
        val body = ByteArray(bodySize)
        put16le(body, 0, 0x0100)
        if (body.size >= 8) {
            put32le(body, 4, which)
        }
        for (index in 0 until Integer.bitCount(which)) {
            val offset = 8 + index * 12
            if (offset + 12 > body.size) break
            val map = maps.getOrElse(index) { indicatorMapRecord(index + 1) }
            map.copyInto(body, offset)
        }
        return request(XXkb.MajorOpcode, XXkb.SetIndicatorMap, body)
    }

    private fun indicatorMapRecord(seed: Int): ByteArray {
        val map = ByteArray(12)
        map[0] = seed.toByte()
        map[1] = (seed + 1).toByte()
        map[2] = (seed + 2).toByte()
        map[3] = (seed + 3).toByte()
        map[4] = (seed + 4).toByte()
        map[5] = (seed + 5).toByte()
        put16le(map, 6, seed)
        put32le(map, 8, XXkb.BoolCtrlRepeatKeys or seed)
        return map
    }

    private fun getNamedIndicatorRequest(indicator: Int): ByteArray {
        val body = ByteArray(12)
        put16le(body, 0, 0x0100)
        put16le(body, 2, 0)
        put16le(body, 4, 0)
        put32le(body, 8, indicator)
        return request(XXkb.MajorOpcode, XXkb.GetNamedIndicator, body)
    }

    private fun setNamedIndicatorRequest(
        indicator: Int,
        setState: Boolean,
        on: Boolean,
        setMap: Boolean,
        createMap: Boolean,
        map: ByteArray = ByteArray(12),
    ): ByteArray {
        val body = ByteArray(28)
        put16le(body, 0, 0x0100)
        put16le(body, 2, 0)
        put16le(body, 4, 0)
        put32le(body, 8, indicator)
        body[12] = if (setState) 1 else 0
        body[13] = if (on) 1 else 0
        body[14] = if (setMap) 1 else 0
        body[15] = if (createMap) 1 else 0
        if (setMap) {
            body[17] = map[0]
            body[18] = map[1]
            body[19] = map[2]
            body[20] = map[3]
            body[21] = map[5]
            map.copyInto(body, destinationOffset = 22, startIndex = 6, endIndex = 12)
        }
        return request(XXkb.MajorOpcode, XXkb.SetNamedIndicator, body)
    }

    private fun getNamesRequest(which: Int): ByteArray {
        val body = ByteArray(8)
        put16le(body, 0, 0x0100)
        put32le(body, 4, which)
        return request(XXkb.MajorOpcode, XXkb.GetNames, body)
    }

    private fun internAtomRequest(name: String, onlyIfExists: Boolean = false): ByteArray {
        val bytes = name.encodeToByteArray()
        val body = ByteArray(4 + paddedLengthForTest(bytes.size))
        put16le(body, 0, bytes.size)
        bytes.copyInto(body, 4)
        return request(16, if (onlyIfExists) 1 else 0, body)
    }

    private fun atomName(socket: Socket, atom: Int): String {
        val body = ByteArray(4)
        put32le(body, 0, atom)
        socket.getOutputStream().write(request(17, 0, body))
        socket.getOutputStream().flush()
        val reply = readReply(socket.getInputStream())
        val length = u16le(reply, 8)
        return reply.copyOfRange(32, 32 + length).decodeToString()
    }

    private fun atomNameBigEndian(socket: Socket, atom: Int): String {
        val body = ByteArray(4)
        put32be(body, 0, atom)
        socket.getOutputStream().write(requestBigEndian(17, 0, body))
        socket.getOutputStream().flush()
        val reply = readReplyBigEndian(socket.getInputStream())
        val length = u16be(reply, 8)
        return reply.copyOfRange(32, 32 + length).decodeToString()
    }

    private fun setNamesRequest(includeAllDetails: Boolean, bodySize: Int = setNamesBodySize(includeAllDetails)): ByteArray {
        val body = ByteArray(bodySize)
        val which = if (includeAllDetails) {
            XXkb.NameDetailKeycodes or
                XXkb.NameDetailGeometry or
                XXkb.NameDetailSymbols or
                XXkb.NameDetailPhysSymbols or
                XXkb.NameDetailTypes or
                XXkb.NameDetailCompat or
                XXkb.NameDetailKeyTypeNames or
                XXkb.NameDetailKtLevelNames or
                XXkb.NameDetailIndicatorNames or
                XXkb.NameDetailKeyNames or
                XXkb.NameDetailKeyAliases or
                XXkb.NameDetailVirtualModNames or
                XXkb.NameDetailGroupNames or
                XXkb.NameDetailRgNames
        } else {
            0
        }
        put16le(body, 0, 0x0100)
        put16le(body, 2, if (includeAllDetails) 0x0003 else 0)
        put32le(body, 4, which)
        if (includeAllDetails) {
            body[8] = 0
            body[9] = 2
            body[10] = 0
            body[11] = 3
            put32le(body, 12, 0x0000_0003)
            body[16] = 0x3
            body[17] = 2
            body[18] = XKeyboard.MinKeycode.toByte()
            body[19] = 2
            body[20] = 1
            put16le(body, 22, 4)
        }

        var offset = 24
        fun write(size: Int) {
            offset += size
        }
        fun align4() {
            offset = (offset + 3) and -4
        }
        if (includeAllDetails) {
            write(24)
            write(8)
            write(3)
            align4()
            write(16)
            write(8)
            write(8)
            write(8)
            write(8)
            write(8)
            write(8)
        }
        return request(XXkb.MajorOpcode, XXkb.SetNames, body)
    }

    private fun setNamesComponentNamesRequest(which: Int, atoms: List<Int>): ByteArray {
        val body = ByteArray(24 + Integer.bitCount(which and XXkb.ComponentNameDetails) * 4)
        put16le(body, 0, 0x0100)
        put32le(body, 4, which and XXkb.ComponentNameDetails)
        var offset = 24
        var atomIndex = 0
        listOf(
            XXkb.NameDetailKeycodes,
            XXkb.NameDetailGeometry,
            XXkb.NameDetailSymbols,
            XXkb.NameDetailPhysSymbols,
            XXkb.NameDetailTypes,
            XXkb.NameDetailCompat,
        ).forEach { mask ->
            if ((which and mask) != 0) {
                put32le(body, offset, atoms.getOrElse(atomIndex) { 0 })
                atomIndex++
                offset += 4
            }
        }
        return request(XXkb.MajorOpcode, XXkb.SetNames, body)
    }

    private fun setNamesGroupNamesRequest(groupNames: Int): ByteArray {
        val body = ByteArray(24 + Integer.bitCount(groupNames) * 4)
        put16le(body, 0, 0x0100)
        put32le(body, 4, XXkb.NameDetailGroupNames)
        body[16] = groupNames.toByte()
        var offset = 24
        repeat(Integer.bitCount(groupNames)) {
            put32le(body, offset, 1)
            offset += 4
        }
        return request(XXkb.MajorOpcode, XXkb.SetNames, body)
    }

    private fun setNamesBodySize(includeAllDetails: Boolean): Int =
        if (includeAllDetails) 124 else 24

    private fun getGeometryRequest(name: Int): ByteArray {
        val body = ByteArray(8)
        put16le(body, 0, 0x0100)
        put32le(body, 4, name)
        return request(XXkb.MajorOpcode, XXkb.GetGeometry, body)
    }

    private fun setGeometryRequest(name: Int = 0x40, bodySize: Int = setGeometryBodySize()): ByteArray {
        val full = setGeometryBody(name)
        val body = if (bodySize == full.size) full else ByteArray(bodySize).also {
            full.copyInto(it, endIndex = minOf(full.size, bodySize))
        }
        return request(XXkb.MajorOpcode, XXkb.SetGeometry, body)
    }

    private fun setGeometryBody(name: Int = 0x40): ByteArray {
        val full = ByteArray(setGeometryBodySize())
        put16le(full, 0, 0x0100)
        full[2] = 1
        full[3] = 0
        put32le(full, 4, name)
        put16le(full, 8, 320)
        put16le(full, 10, 240)
        put16le(full, 12, 1)
        put16le(full, 14, 2)
        put16le(full, 16, 1)
        put16le(full, 18, 1)
        full[20] = 0
        full[21] = 1

        var offset = 24
        fun writeCounted(value: String) {
            val bytes = value.encodeToByteArray()
            put16le(full, offset, bytes.size)
            bytes.copyInto(full, offset + 2)
            offset += countedGeometryStringSize(value)
        }

        writeCounted("label")
        writeCounted("prop")
        writeCounted("value")
        writeCounted("black")
        writeCounted("white")

        put32le(full, offset, 0x40)
        full[offset + 4] = 1
        full[offset + 5] = 0
        full[offset + 6] = 0
        offset += 8
        full[offset] = 2
        offset += 4
        put16le(full, offset, 0)
        put16le(full, offset + 2, 0)
        put16le(full, offset + 4, 10)
        put16le(full, offset + 6, 10)
        offset += 8

        put32le(full, offset, 0x40)
        full[offset + 4] = 3
        full[offset + 16] = 0
        offset += 20
        writeCounted("text")
        writeCounted("font")

        "REALALIS".encodeToByteArray().copyInto(full, offset)
        offset += 8
        assertEquals(full.size, offset)
        return full
    }

    private fun minimalGeometryBody(
        name: Int,
        nColors: Int = 2,
        nShapes: Int = 1,
        baseColor: Int = 0,
        labelColor: Int = 1,
        shapeName: Int = 0x40,
    ): ByteArray {
        val colors = List(nColors) { index -> if (index == 0) "black" else "white$index" }
        val size = 24 +
            countedGeometryStringSize("label") +
            colors.sumOf(::countedGeometryStringSize) +
            nShapes * (8 + 4 + 8)
        val body = ByteArray(size)
        put16le(body, 0, 0x0100)
        body[2] = nShapes.toByte()
        put32le(body, 4, name)
        put16le(body, 8, 320)
        put16le(body, 10, 240)
        put16le(body, 14, nColors)
        body[20] = baseColor.toByte()
        body[21] = labelColor.toByte()

        var offset = 24
        fun writeCounted(value: String) {
            val bytes = value.encodeToByteArray()
            put16le(body, offset, bytes.size)
            bytes.copyInto(body, offset + 2)
            offset += countedGeometryStringSize(value)
        }

        writeCounted("label")
        colors.forEach(::writeCounted)
        repeat(nShapes) {
            put32le(body, offset, shapeName)
            body[offset + 4] = 1
            offset += 8
            body[offset] = 2
            offset += 4
            put16le(body, offset, 0)
            put16le(body, offset + 2, 0)
            put16le(body, offset + 4, 10)
            put16le(body, offset + 6, 10)
            offset += 8
        }
        assertEquals(body.size, offset)
        return body
    }

    private fun setGeometryBodySize(): Int =
        24 +
            countedGeometryStringSize("label") +
            countedGeometryStringSize("prop") +
            countedGeometryStringSize("value") +
            countedGeometryStringSize("black") +
            countedGeometryStringSize("white") +
            8 + 4 + 8 +
            20 + countedGeometryStringSize("text") + countedGeometryStringSize("font") +
            8

    private fun countedGeometryStringSize(value: String): Int =
        (2 + value.encodeToByteArray().size + 3) and -4

    private fun perClientFlagsRequest(change: Int, value: Int, ctrlsToChange: Int, autoCtrls: Int, autoCtrlsValues: Int): ByteArray {
        val body = ByteArray(24)
        put16le(body, 0, 0x0100)
        put32le(body, 4, change)
        put32le(body, 8, value)
        put32le(body, 12, ctrlsToChange)
        put32le(body, 16, autoCtrls)
        put32le(body, 20, autoCtrlsValues)
        return request(XXkb.MajorOpcode, XXkb.PerClientFlags, body)
    }

    private fun listComponentsRequest(maxNames: Int, trailingPatterns: ByteArray = ByteArray(0)): ByteArray {
        val body = ByteArray(4 + trailingPatterns.size)
        put16le(body, 0, 0x0100)
        put16le(body, 2, maxNames)
        trailingPatterns.copyInto(body, 4)
        return request(XXkb.MajorOpcode, XXkb.ListComponents, body)
    }

    private fun getKbdByNameRequest(need: Int, want: Int, load: Boolean, trailingNames: ByteArray = ByteArray(0)): ByteArray {
        val body = ByteArray(8 + trailingNames.size)
        put16le(body, 0, 0x0100)
        put16le(body, 2, need)
        put16le(body, 4, want)
        body[6] = if (load) 1 else 0
        trailingNames.copyInto(body, 8)
        return request(XXkb.MajorOpcode, XXkb.GetKbdByName, body)
    }

    private fun xkbComponentSpecs(vararg names: String): ByteArray {
        val specs = names.toList() + List(6 - names.size) { "" }
        val size = (specs.sumOf { 1 + it.encodeToByteArray().size } + 3) and -4
        val bytes = ByteArray(size)
        var offset = 0
        specs.forEach { name ->
            val nameBytes = name.encodeToByteArray()
            bytes[offset++] = nameBytes.size.toByte()
            nameBytes.copyInto(bytes, offset)
            offset += nameBytes.size
        }
        return bytes
    }

    private fun xkbListingsByCategory(reply: ByteArray): List<List<Pair<Int, String>>> {
        val counts = listOf(
            u16le(reply, 8),
            u16le(reply, 10),
            u16le(reply, 12),
            u16le(reply, 14),
            u16le(reply, 16),
            u16le(reply, 18),
        )
        var offset = 32
        return counts.map { count ->
            List(count) {
                val flags = u16le(reply, offset)
                val length = u16le(reply, offset + 2)
                val name = reply.copyOfRange(offset + 4, offset + 4 + length).decodeToString()
                offset += (4 + length + 1) and -2
                flags to name
            }.also {
                offset = paddedSize(offset)
            }
        }
    }

    private fun getDeviceInfoRequest(
        wanted: Int,
        allButtons: Boolean,
        firstButton: Int,
        nButtons: Int,
        ledClass: Int = 0,
        ledId: Int = 0,
        deviceSpec: Int = XXkb.DeviceSpecUseCorePointer,
    ): ByteArray {
        val body = ByteArray(12)
        put16le(body, 0, deviceSpec)
        put16le(body, 2, wanted)
        body[4] = if (allButtons) 1 else 0
        body[5] = firstButton.toByte()
        body[6] = nButtons.toByte()
        put16le(body, 8, ledClass)
        put16le(body, 10, ledId)
        return request(XXkb.MajorOpcode, XXkb.GetDeviceInfo, body)
    }

    private fun setDeviceInfoRequest(
        nButtons: Int,
        nDeviceLedFeedbacks: Int,
        change: Int = XXkb.XiFeatureButtonActions or XXkb.XiFeatureIndicatorNames or XXkb.XiFeatureIndicatorMaps,
        firstButton: Int = 1,
        ledClass: Int = XXkb.DfltXIClass,
        ledId: Int = XXkb.DfltXIId,
        ledNamesPresent: Int = 0x0000_0001,
        ledMapsPresent: Int = 0x0000_0001,
        ledPhysIndicators: Int = 0,
        ledState: Int = 0,
        ledNameAtoms: List<Int>? = null,
        ledMaps: List<ByteArray>? = null,
        bodySize: Int = setDeviceInfoBodySize(nButtons, nDeviceLedFeedbacks, change, ledNamesPresent, ledMapsPresent),
        deviceSpec: Int = XXkb.DeviceSpecUseCorePointer,
    ): ByteArray {
        val body = ByteArray(bodySize)
        put16le(body, 0, deviceSpec)
        if (body.size > 2) body[2] = firstButton.toByte()
        if (body.size > 3) body[3] = nButtons.toByte()
        if (body.size >= 6) put16le(body, 4, change)
        if (body.size >= 8) put16le(body, 6, nDeviceLedFeedbacks)
        var offset = 8
        if (change and XXkb.XiFeatureButtonActions != 0) {
            repeat(nButtons) {
                if (offset + 8 <= body.size) {
                    body[offset] = 1
                }
                offset += 8
            }
        }
        if (change and XXkb.XiFeatureIndicators != 0) {
            repeat(nDeviceLedFeedbacks) {
                if (offset + 20 <= body.size) {
                    put16le(body, offset, ledClass)
                    put16le(body, offset + 2, ledId)
                    put32le(body, offset + 4, ledNamesPresent)
                    put32le(body, offset + 8, ledMapsPresent)
                    put32le(body, offset + 12, ledPhysIndicators)
                    put32le(body, offset + 16, ledState)
                }
                offset += 20
                repeat(Integer.bitCount(ledNamesPresent)) { index ->
                    if (offset + 4 <= body.size) put32le(body, offset, ledNameAtoms?.getOrNull(index) ?: (0x40 + index))
                    offset += 4
                }
                repeat(Integer.bitCount(ledMapsPresent)) { index ->
                    if (offset + 12 <= body.size) {
                        val map = ledMaps?.getOrNull(index)
                        if (map == null) {
                            body[offset] = 1
                            body[offset + 1] = 1
                            body[offset + 2] = 1
                            body[offset + 3] = 1
                            body[offset + 4] = 1
                            body[offset + 5] = 1
                            put16le(body, offset + 6, 1)
                            put32le(body, offset + 8, XXkb.BoolCtrlRepeatKeys)
                        } else {
                            map.copyInto(body, offset, 0, minOf(12, map.size))
                        }
                    }
                    offset += 12
                }
            }
        }
        return request(XXkb.MajorOpcode, XXkb.SetDeviceInfo, body)
    }

    private fun setDeviceInfoBodySize(
        nButtons: Int,
        nDeviceLedFeedbacks: Int,
        change: Int,
        ledNamesPresent: Int,
        ledMapsPresent: Int,
    ): Int {
        var size = 8
        if (change and XXkb.XiFeatureButtonActions != 0) size += nButtons * 8
        if (change and XXkb.XiFeatureIndicators != 0) {
            size += nDeviceLedFeedbacks * (20 + Integer.bitCount(ledNamesPresent) * 4 + Integer.bitCount(ledMapsPresent) * 12)
        }
        return size
    }

    private fun setDebuggingFlagsRequest(message: String, affectFlags: Int, flags: Int, affectCtrls: Int, ctrls: Int, extraBytes: Int = 0): ByteArray {
        val messageBytes = message.encodeToByteArray()
        val body = ByteArray(20 + ((messageBytes.size + 3) and -4) + extraBytes)
        put16le(body, 0, messageBytes.size)
        put32le(body, 4, affectFlags)
        put32le(body, 8, flags)
        put32le(body, 12, affectCtrls)
        put32le(body, 16, ctrls)
        messageBytes.copyInto(body, 20)
        return request(XXkb.MajorOpcode, XXkb.SetDebuggingFlags, body)
    }

    private fun changeKeyboardControlRequest(vararg values: Pair<Int, Int>): ByteArray {
        val mask = values.fold(0) { acc, (bit, _) -> acc or bit }
        val body = ByteArray(4 + values.size * 4)
        put32le(body, 0, mask)
        values.forEachIndexed { index, (_, value) ->
            put32le(body, 4 + index * 4, value)
        }
        return request(102, 0, body)
    }

    private fun request(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16le(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun requestBigEndian(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16be(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun paddedSize(size: Int): Int = (size + 3) and -4

    private fun assertError(input: InputStream, error: Int, opcode: Int, badValue: Int, sequence: Int, minorOpcode: Int) {
        val reply = input.readExactly(32)
        assertEquals(0, reply[0].toInt())
        assertEquals(error, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(badValue, u32le(reply, 4))
        assertEquals(minorOpcode, u16le(reply, 8))
        assertEquals(opcode, reply[10].toInt() and 0xff)
    }

    private fun assertGetControls(reply: ByteArray, sequence: Int, enabledControls: Int) {
        assertEquals(1, reply[0].toInt())
        assertEquals(0, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(15, u32le(reply, 4))
        assertEquals(XXkb.DefaultMouseKeysButton, reply[8].toInt() and 0xff)
        assertEquals(XXkb.DefaultGroupCount, reply[9].toInt() and 0xff)
        assertEquals(0, reply[10].toInt() and 0xff)
        assertEquals(0, reply[11].toInt() and 0xff)
        assertEquals(0, reply[12].toInt() and 0xff)
        assertEquals(0, reply[13].toInt() and 0xff)
        assertEquals(0, reply[14].toInt() and 0xff)
        assertEquals(0, reply[15].toInt() and 0xff)
        assertEquals(0, u16le(reply, 16))
        assertEquals(0, u16le(reply, 18))
        assertEquals(XXkb.DefaultRepeatDelay, u16le(reply, 20))
        assertEquals(XXkb.DefaultRepeatInterval, u16le(reply, 22))
        assertEquals(0, u16le(reply, 24))
        assertEquals(0, u16le(reply, 26))
        assertEquals(0, u16le(reply, 28))
        assertEquals(0, u16le(reply, 30))
        assertEquals(0, u16le(reply, 32))
        assertEquals(0, u16le(reply, 34))
        assertEquals(0, u16le(reply, 36))
        assertEquals(0, u16le(reply, 38))
        assertEquals(0, u16le(reply, 40))
        assertEquals(0, u16le(reply, 42))
        assertEquals(0, u16le(reply, 44))
        assertEquals(0, u16le(reply, 46))
        assertEquals(0, u32le(reply, 48))
        assertEquals(0, u32le(reply, 52))
        assertEquals(enabledControls, u32le(reply, 56))
        assertEquals(92, reply.size)
    }

    private fun assertPerClientFlagsReply(reply: ByteArray, sequence: Int, value: Int, autoCtrls: Int = 0, autoCtrlValues: Int = 0) {
        assertEquals(1, reply[0].toInt())
        assertEquals(0, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(0, u32le(reply, 4))
        assertEquals(XXkb.PcfAllFlags, u32le(reply, 8))
        assertEquals(value, u32le(reply, 12))
        assertEquals(autoCtrls, u32le(reply, 16))
        assertEquals(autoCtrlValues, u32le(reply, 20))
        assertEquals(32, reply.size)
    }

    private fun assertControlsNotify(
        event: ByteArray,
        sequence: Int,
        changedControls: Int,
        enabledControls: Int,
        enabledControlChanges: Int,
        requestMinor: Int = XXkb.SetControls,
    ) {
        assertEquals(XXkb.FirstEvent, event[0].toInt() and 0xff)
        assertEquals(XXkb.ControlsNotify, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(0, event[8].toInt() and 0xff)
        assertEquals(XXkb.DefaultGroupCount, event[9].toInt() and 0xff)
        assertEquals(0, u16le(event, 10))
        assertEquals(changedControls, u32le(event, 12))
        assertEquals(enabledControls, u32le(event, 16))
        assertEquals(enabledControlChanges, u32le(event, 20))
        assertEquals(0, event[24].toInt() and 0xff)
        assertEquals(0, event[25].toInt() and 0xff)
        assertEquals(XXkb.MajorOpcode, event[26].toInt() and 0xff)
        assertEquals(requestMinor, event[27].toInt() and 0xff)
        assertEquals(0, u32le(event, 28))
    }

    private fun assertBellNotify(
        event: ByteArray,
        sequence: Int,
        bellClass: Int,
        bellId: Int,
        percent: Int,
        pitch: Int,
        duration: Int,
        name: Int,
        window: Int,
        eventOnly: Boolean,
    ) {
        assertEquals(XXkb.FirstEvent, event[0].toInt() and 0xff)
        assertEquals(XXkb.BellNotify, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(0, event[8].toInt() and 0xff)
        assertEquals(bellClass, event[9].toInt() and 0xff)
        assertEquals(bellId, event[10].toInt() and 0xff)
        assertEquals(percent, event[11].toInt())
        assertEquals(pitch, u16le(event, 12))
        assertEquals(duration, u16le(event, 14))
        assertEquals(name, u32le(event, 16))
        assertEquals(window, u32le(event, 20))
        assertEquals(if (eventOnly) 1 else 0, event[24].toInt() and 0xff)
        assertEquals(0, event[25].toInt() and 0xff)
        assertEquals(0, u16le(event, 26))
        assertEquals(0, u32le(event, 28))
    }

    private fun assertMapNotify(
        event: ByteArray,
        sequence: Int,
        changed: Int,
        firstKeySym: Int,
        nKeySyms: Int,
        firstModMapKey: Int = 0,
        nModMapKeys: Int = 0,
        virtualMods: Int = 0,
    ) {
        assertEquals(XXkb.FirstEvent, event[0].toInt() and 0xff)
        assertEquals(XXkb.MapNotify, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(0, event[8].toInt() and 0xff)
        assertEquals(0, event[9].toInt() and 0xff)
        assertEquals(changed, u16le(event, 10))
        assertEquals(XKeyboard.MinKeycode, event[12].toInt() and 0xff)
        assertEquals(XKeyboard.MaxKeycode, event[13].toInt() and 0xff)
        assertEquals(0, event[14].toInt() and 0xff)
        assertEquals(0, event[15].toInt() and 0xff)
        assertEquals(firstKeySym, event[16].toInt() and 0xff)
        assertEquals(nKeySyms, event[17].toInt() and 0xff)
        for (index in 18 until 24) {
            assertEquals(0, event[index].toInt() and 0xff)
        }
        assertEquals(firstModMapKey, event[24].toInt() and 0xff)
        assertEquals(nModMapKeys, event[25].toInt() and 0xff)
        for (index in 26 until 28) {
            assertEquals(0, event[index].toInt() and 0xff)
        }
        assertEquals(virtualMods, u16le(event, 28))
        assertEquals(0, u16le(event, 30))
    }

    private fun assertMappingNotify(event: ByteArray, sequence: Int, request: Int, firstKeycode: Int, count: Int) {
        assertEquals(34, event[0].toInt() and 0xff)
        assertEquals(0, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(request, event[4].toInt() and 0xff)
        assertEquals(firstKeycode, event[5].toInt() and 0xff)
        assertEquals(count, event[6].toInt() and 0xff)
        for (index in 7 until 32) {
            assertEquals(0, event[index].toInt() and 0xff)
        }
    }

    private fun assertIndicatorMapNotify(event: ByteArray, sequence: Int, state: Int, changed: Int) {
        assertEquals(XXkb.FirstEvent, event[0].toInt() and 0xff)
        assertEquals(XXkb.IndicatorMapNotify, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(0, event[8].toInt() and 0xff)
        assertEquals(0, event[9].toInt() and 0xff)
        assertEquals(0, u16le(event, 10))
        assertEquals(state, u32le(event, 12))
        assertEquals(changed, u32le(event, 16))
        assertEquals(0, u32le(event, 20))
        assertEquals(0, u32le(event, 24))
        assertEquals(0, u32le(event, 28))
    }

    private fun assertIndicatorMaps(reply: ByteArray, maps: List<ByteArray>) {
        assertEquals(maps.size, reply[16].toInt() and 0xff)
        assertEquals(32 + maps.size * 12, reply.size)
        maps.forEachIndexed { index, map ->
            val offset = 32 + index * 12
            assertEquals(map.toList(), reply.copyOfRange(offset, offset + 12).toList())
        }
        assertZero(reply, 17, 32)
    }

    private fun assertIndicatorMapReply(reply: ByteArray, sequence: Int, which: Int, maps: List<ByteArray>) {
        assertEquals(1, reply[0].toInt())
        assertEquals(0, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(maps.size * 3, u32le(reply, 4))
        assertEquals(which, u32le(reply, 8))
        assertEquals(0, u32le(reply, 12))
        assertIndicatorMaps(reply, maps)
    }

    private fun assertCompatMapNotify(event: ByteArray, sequence: Int, changedGroups: Int, firstSI: Int, nSI: Int, nTotalSI: Int) {
        assertEquals(XXkb.FirstEvent, event[0].toInt() and 0xff)
        assertEquals(XXkb.CompatMapNotify, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(0, event[8].toInt() and 0xff)
        assertEquals(changedGroups, event[9].toInt() and 0xff)
        assertEquals(firstSI, u16le(event, 10))
        assertEquals(nSI, u16le(event, 12))
        assertEquals(nTotalSI, u16le(event, 14))
        assertEquals(0, u32le(event, 16))
        assertEquals(0, u32le(event, 20))
        assertEquals(0, u32le(event, 24))
        assertEquals(0, u32le(event, 28))
    }

    private fun assertGeometryReply(reply: ByteArray, sequence: Int, geometryBody: ByteArray, expectedName: Int = u32le(geometryBody, 4)) {
        val payload = geometryBody.copyOfRange(24, geometryBody.size)
        assertEquals(1, reply[0].toInt())
        assertEquals(0, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(payload.size / 4, u32le(reply, 4))
        assertEquals(expectedName, u32le(reply, 8))
        assertEquals(1, reply[12].toInt() and 0xff)
        assertEquals(0, reply[13].toInt() and 0xff)
        assertEquals(u16le(geometryBody, 8), u16le(reply, 14))
        assertEquals(u16le(geometryBody, 10), u16le(reply, 16))
        assertEquals(u16le(geometryBody, 12), u16le(reply, 18))
        assertEquals(u16le(geometryBody, 14), u16le(reply, 20))
        assertEquals(geometryBody[2].toInt() and 0xff, u16le(reply, 22))
        assertEquals(geometryBody[3].toInt() and 0xff, u16le(reply, 24))
        assertEquals(u16le(geometryBody, 16), u16le(reply, 26))
        assertEquals(u16le(geometryBody, 18), u16le(reply, 28))
        assertEquals(geometryBody[20].toInt() and 0xff, reply[30].toInt() and 0xff)
        assertEquals(geometryBody[21].toInt() and 0xff, reply[31].toInt() and 0xff)
        assertEquals(payload.toList(), reply.copyOfRange(32, reply.size).toList())
    }

    private fun assertMapReplyHeader(reply: ByteArray, sequence: Int, present: Int) {
        assertEquals(1, reply[0].toInt())
        assertEquals(0, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(reply.size, 32 + u32le(reply, 4) * 4)
        assertEquals(XKeyboard.MinKeycode, reply[10].toInt() and 0xff)
        assertEquals(XKeyboard.MaxKeycode, reply[11].toInt() and 0xff)
        assertEquals(present, u16le(reply, 12))
    }

    private fun assertCompatMapReply(reply: ByteArray, sequence: Int, groups: Int, groupMaps: List<ByteArray>) {
        assertEquals(1, reply[0].toInt())
        assertEquals(0, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(groupMaps.size, u32le(reply, 4))
        assertEquals(groups, reply[8].toInt() and 0xff)
        assertEquals(0, u16le(reply, 10))
        assertEquals(0, u16le(reply, 12))
        assertEquals(0, u16le(reply, 14))
        groupMaps.forEachIndexed { index, groupMap ->
            assertEquals(groupMap.toList(), reply.copyOfRange(32 + index * 4, 36 + index * 4).toList())
        }
        assertEquals(32 + groupMaps.size * 4, reply.size)
    }

    private fun assertNamesNotify(
        event: ByteArray,
        sequence: Int,
        changed: Int,
        firstType: Int,
        nTypes: Int,
        firstLevelName: Int,
        nLevelNames: Int,
        nRadioGroups: Int,
        nAliases: Int,
        changedGroupNames: Int,
        changedVirtualMods: Int,
        firstKey: Int,
        nKeys: Int,
        changedIndicators: Int,
    ) {
        assertEquals(XXkb.FirstEvent, event[0].toInt() and 0xff)
        assertEquals(XXkb.NamesNotify, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(0, event[8].toInt() and 0xff)
        assertEquals(0, event[9].toInt() and 0xff)
        assertEquals(changed, u16le(event, 10))
        assertEquals(firstType, event[12].toInt() and 0xff)
        assertEquals(nTypes, event[13].toInt() and 0xff)
        assertEquals(firstLevelName, event[14].toInt() and 0xff)
        assertEquals(nLevelNames, event[15].toInt() and 0xff)
        assertEquals(0, event[16].toInt() and 0xff)
        assertEquals(nRadioGroups, event[17].toInt() and 0xff)
        assertEquals(nAliases, event[18].toInt() and 0xff)
        assertEquals(changedGroupNames, event[19].toInt() and 0xff)
        assertEquals(changedVirtualMods, u16le(event, 20))
        assertEquals(firstKey, event[22].toInt() and 0xff)
        assertEquals(nKeys, event[23].toInt() and 0xff)
        assertEquals(changedIndicators, u32le(event, 24))
        assertEquals(0, u32le(event, 28))
    }

    private fun assertIndicatorStateNotify(event: ByteArray, sequence: Int, state: Int, changed: Int) {
        assertEquals(XXkb.FirstEvent, event[0].toInt() and 0xff)
        assertEquals(XXkb.IndicatorStateNotify, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(0, event[8].toInt() and 0xff)
        assertEquals(0, event[9].toInt() and 0xff)
        assertEquals(0, u16le(event, 10))
        assertEquals(state, u32le(event, 12))
        assertEquals(changed, u32le(event, 16))
        assertEquals(0, u32le(event, 20))
        assertEquals(0, u32le(event, 24))
        assertEquals(0, u32le(event, 28))
    }

    private fun assertXkbExtensionDeviceNotify(
        event: ByteArray,
        sequence: Int,
        reason: Int,
        ledClass: Int = 0,
        ledId: Int = 0,
        ledsDefined: Int = 0,
        ledState: Int = 0,
        firstButton: Int = 0,
        nButtons: Int = 0,
        supported: Int = XXkb.XiFeatureAllDeviceFeatures,
        unsupported: Int = 0,
    ) {
        assertEquals(XXkb.FirstEvent, event[0].toInt() and 0xff)
        assertEquals(XXkb.ExtensionDeviceNotify, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(0, event[8].toInt() and 0xff)
        assertEquals(0, event[9].toInt() and 0xff)
        assertEquals(reason, u16le(event, 10))
        assertEquals(ledClass, u16le(event, 12))
        assertEquals(ledId, u16le(event, 14))
        assertEquals(ledsDefined, u32le(event, 16))
        assertEquals(ledState, u32le(event, 20))
        assertEquals(firstButton, event[24].toInt() and 0xff)
        assertEquals(nButtons, event[25].toInt() and 0xff)
        assertEquals(supported, u16le(event, 26))
        assertEquals(unsupported, u16le(event, 28))
        assertEquals(0, u16le(event, 30))
    }

    private fun assertXkbKeySymMap(reply: ByteArray, offset: Int, width: Int, vararg keysyms: Int) {
        assertEquals(if (width > 1) 1 else 0, reply[offset].toInt() and 0xff)
        assertEquals(0, reply[offset + 1].toInt() and 0xff)
        assertEquals(0, reply[offset + 2].toInt() and 0xff)
        assertEquals(0, reply[offset + 3].toInt() and 0xff)
        assertEquals(1, reply[offset + 4].toInt() and 0xff)
        assertEquals(width, reply[offset + 5].toInt() and 0xff)
        assertEquals(keysyms.size, u16le(reply, offset + 6))
        keysyms.forEachIndexed { index, keysym ->
            assertEquals(keysym, u32le(reply, offset + 8 + index * 4))
        }
    }

    private fun assertXkbDefaultKeyTypes(reply: ByteArray, offset: Int) {
        assertEquals(0, reply[offset].toInt() and 0xff)
        assertEquals(0, reply[offset + 1].toInt() and 0xff)
        assertEquals(0, u16le(reply, offset + 2))
        assertEquals(1, reply[offset + 4].toInt() and 0xff)
        assertEquals(0, reply[offset + 5].toInt() and 0xff)
        assertEquals(0, reply[offset + 6].toInt() and 0xff)
        assertEquals(0, reply[offset + 7].toInt() and 0xff)

        var twoLevel = offset + 8
        repeat(3) {
            assertEquals(1, reply[twoLevel].toInt() and 0xff)
            assertEquals(1, reply[twoLevel + 1].toInt() and 0xff)
            assertEquals(0, u16le(reply, twoLevel + 2))
            assertEquals(2, reply[twoLevel + 4].toInt() and 0xff)
            assertEquals(1, reply[twoLevel + 5].toInt() and 0xff)
            assertEquals(0, reply[twoLevel + 6].toInt() and 0xff)
            assertEquals(0, reply[twoLevel + 7].toInt() and 0xff)

            val shiftEntry = twoLevel + 8
            assertEquals(1, reply[shiftEntry].toInt() and 0xff)
            assertEquals(1, reply[shiftEntry + 1].toInt() and 0xff)
            assertEquals(1, reply[shiftEntry + 2].toInt() and 0xff)
            assertEquals(1, reply[shiftEntry + 3].toInt() and 0xff)
            assertEquals(0, u16le(reply, shiftEntry + 4))
            assertEquals(0, u16le(reply, shiftEntry + 6))
            twoLevel += 16
        }
    }

    private fun assertXkbModifierMap(reply: ByteArray, vararg entries: Pair<Int, Int>) {
        val offset = xkbModifierMapOffset(reply)
        entries.forEachIndexed { index, (keycode, modifiers) ->
            assertEquals(keycode, reply[offset + index * 2].toInt() and 0xff)
            assertEquals(modifiers, reply[offset + index * 2 + 1].toInt() and 0xff)
        }
        assertZero(reply, offset + entries.size * 2, 32 + u32le(reply, 4) * 4)
    }

    private fun assertXkbVirtualMods(reply: ByteArray, vararg realModifiers: Int) {
        var offset = xkbKeySymMapBaseOffset(reply)
        if ((u16le(reply, 12) and XXkb.MapPartKeySyms) != 0) {
            repeat(reply[20].toInt() and 0xff) {
                offset += 8 + u16le(reply, offset + 6) * 4
            }
        }
        realModifiers.forEachIndexed { index, modifiers ->
            assertEquals(modifiers, reply[offset + index].toInt() and 0xff)
        }
        assertZero(reply, offset + realModifiers.size, paddedLengthForTest(offset + realModifiers.size))
    }

    private fun xkbKeySymMapOffset(reply: ByteArray, keycode: Int): Int {
        val firstKeySym = reply[17].toInt() and 0xff
        val nKeySyms = reply[20].toInt() and 0xff
        require(keycode in firstKeySym until firstKeySym + nKeySyms)
        var offset = xkbKeySymMapBaseOffset(reply)
        repeat(keycode - firstKeySym) {
            offset += 8 + u16le(reply, offset + 6) * 4
        }
        return offset
    }

    private fun xkbTotalKeySyms(reply: ByteArray): Int {
        val nKeySyms = reply[20].toInt() and 0xff
        var offset = xkbKeySymMapBaseOffset(reply)
        var total = 0
        repeat(nKeySyms) {
            val nSyms = u16le(reply, offset + 6)
            total += nSyms
            offset += 8 + nSyms * 4
        }
        return total
    }

    private fun xkbKeySymMapBaseOffset(reply: ByteArray): Int {
        var offset = 40
        if ((u16le(reply, 12) and XXkb.MapPartKeyTypes) != 0) {
            repeat(reply[15].toInt() and 0xff) {
                val nMapEntries = reply[offset + 5].toInt() and 0xff
                val preserve = reply[offset + 6].toInt() != 0
                offset += 8 + nMapEntries * 8 + if (preserve) nMapEntries * 4 else 0
            }
        }
        return offset
    }

    private fun xkbModifierMapOffset(reply: ByteArray): Int {
        var offset = xkbKeySymMapBaseOffset(reply)
        if ((u16le(reply, 12) and XXkb.MapPartKeySyms) != 0) {
            repeat(reply[20].toInt() and 0xff) {
                offset += 8 + u16le(reply, offset + 6) * 4
            }
        }
        if ((u16le(reply, 12) and XXkb.MapPartVirtualMods) != 0) {
            offset += Integer.bitCount(u16le(reply, 38))
            offset = paddedLengthForTest(offset)
        }
        return offset
    }

    private fun assertZero(bytes: ByteArray, from: Int, until: Int) {
        for (index in from until until) {
            assertEquals(0, bytes[index].toInt() and 0xff)
        }
    }

    private fun paddedLengthForTest(value: Int): Int =
        (value + 3) and -4

    private fun readReply(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        val payload = input.readExactly(u32le(header, 4) * 4)
        return header + payload
    }

    private fun readReplyBigEndian(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        val payload = input.readExactly(u32be(header, 4) * 4)
        return header + payload
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
            check(read >= 0) { "unexpected end of stream" }
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

    private fun put16be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun put32be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

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
}
