package org.jonnyzzz.xserver

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.containers.GenericContainer
import org.testcontainers.DockerClientFactory
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayInputStream
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XvfbContainerTest {
    @Test
    fun `docker baseline can run xdpyinfo against xvfb`() {
        assumeDockerAndImage(REFERENCE_IMAGE)

        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "300")
            .use { container ->
                container.start()
                val result = container.execInContainer(
                    "sh",
                    "-lc",
                    "Xvfb :99 -screen 0 640x480x24 >/tmp/xvfb.log 2>&1 & " +
                        "for i in $(seq 1 50); do DISPLAY=:99 xdpyinfo >/tmp/xdpyinfo.log 2>&1 && break; sleep 0.1; done; " +
                        "cat /tmp/xdpyinfo.log",
                )
                assertEquals(0, result.exitCode, result.stderr)
                assertTrue(result.stdout.contains("dimensions:    640x480 pixels"), result.stdout)
            }
    }

    @Test
    fun `docker x11 tools can query kotlin server`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        val port = 6207
        assumeTrue(isPortAvailable(port), "Port $port is not available")

        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }

            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "300")
                .use { container ->
                    container.start()

                    assertClientSucceeds(container, port, "xdpyinfo")
                    assertClientSucceeds(container, port, "xwininfo -root")
                    assertClientSucceeds(container, port, "xprop -root")
                    assertClientSucceeds(container, port, "xset q")
                    assertClientKeepsRunning(container, port, "xlogo")
                    assertClientKeepsRunning(container, port, "xclock")
                    assertClientKeepsRunning(container, port, "xeyes")
                    assertClientKeepsRunning(container, port, "xcalc")
                    assertClientKeepsRunning(container, port, "xterm")
                }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `xlogo robot screenshot and svg framebuffer roughly match xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runXlogoAgainstXvfb()
        val actual = runXlogoAgainstKotlinServer(port = 6226)

        assertTrue(actual.text.contains("label=\"xlogo\""), actual.text)
        assertTrue(actual.text.contains("- FillPoly:"), actual.text)
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected xlogo SVG export to retain framebuffer images\n${actual.svg}\n${actual.text}")
        assertVisualCaptureClose(reference, actual.robot, label = "Kotlin xlogo Robot screenshot")
        assertComposedSvgClose(
            expected = reference,
            embeddedFramebuffers = actual.embeddedFramebuffers,
            ownerWidth = RealClientCaptureWidth,
            ownerHeight = RealClientCaptureHeight,
            label = "Kotlin xlogo composed SVG framebuffer",
        )
    }

    @Test
    fun `xclock robot screenshot and svg framebuffer roughly match xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runXclockAgainstXvfb()
        val actual = runXclockAgainstKotlinServer(port = 6227)

        assertTrue(actual.text.contains("label=\"xclock\""), actual.text)
        assertTrue(actual.text.contains("- PutImage:"), actual.text)
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected xclock SVG export to retain framebuffer images\n${actual.svg}\n${actual.text}")
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin xclock Robot screenshot",
            coverageTolerance = 0.45,
            distanceThreshold = 60.0,
        )
        assertComposedSvgClose(
            expected = reference,
            embeddedFramebuffers = actual.embeddedFramebuffers,
            ownerWidth = RealClientCaptureWidth,
            ownerHeight = RealClientCaptureHeight,
            label = "Kotlin xclock composed SVG framebuffer",
            coverageTolerance = 0.45,
            distanceThreshold = 60.0,
        )
    }

    @Test
    fun `xcalc robot screenshot and svg framebuffer roughly match xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runXcalcAgainstXvfb()
        val actual = runXcalcAgainstKotlinServer(port = 6228)

        assertTrue(actual.text.contains("label=\"Calculator\""), actual.text)
        assertTrue(
            actual.text.contains("- PolyText8:") ||
                actual.text.contains("- ImageText8:") ||
                actual.text.contains("- PutImage:"),
            actual.text,
        )
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected xcalc SVG export to retain framebuffer images\n${actual.svg}\n${actual.text}")
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin xcalc Robot screenshot",
            coverageTolerance = 0.25,
            distanceThreshold = 300.0,
        )
        assertComposedSvgClose(
            expected = reference,
            embeddedFramebuffers = actual.embeddedFramebuffers,
            ownerWidth = RealClientCaptureWidth,
            ownerHeight = RealClientCaptureHeight,
            label = "Kotlin xcalc composed SVG framebuffer",
            coverageTolerance = 0.25,
            distanceThreshold = 300.0,
        )
    }

    @Test
    fun `xeyes robot screenshot and svg framebuffer roughly match xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runXeyesAgainstXvfb()
        val actual = runXeyesAgainstKotlinServer(port = 6229)

        assertTrue(actual.text.contains("label=\"xeyes\""), actual.text)
        assertTrue(actual.text.contains("- RENDER.Trapezoids:"), actual.text)
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected xeyes SVG export to retain framebuffer images\n${actual.svg}\n${actual.text}")
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin xeyes Robot screenshot",
            coverageTolerance = 0.25,
            distanceThreshold = 120.0,
        )
        assertComposedSvgClose(
            expected = reference,
            embeddedFramebuffers = actual.embeddedFramebuffers,
            ownerWidth = RealClientCaptureWidth,
            ownerHeight = RealClientCaptureHeight,
            label = "Kotlin xeyes composed SVG framebuffer",
            coverageTolerance = 0.25,
            distanceThreshold = 120.0,
        )
    }

    @Test
    fun `xterm robot screenshot and svg framebuffer roughly match xvfb reference`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        assumeDockerAndImage(REFERENCE_IMAGE)
        val reference = runXtermAgainstXvfb()
        val actual = runXtermAgainstKotlinServer(port = 6230)

        assertTrue(actual.text.contains("label=\"xterm-parity\""), actual.text)
        assertTrue(
            actual.text.contains("- PolyText8:") ||
                actual.text.contains("- ImageText8:") ||
                actual.text.contains("- PutImage:"),
            actual.text,
        )
        assertTrue(actual.svg.hasSvgClass("framebuffer-image"), "Expected xterm SVG export to retain framebuffer images\n${actual.svg}\n${actual.text}")
        assertVisualCaptureClose(
            expected = reference,
            actual = actual.robot,
            label = "Kotlin xterm Robot screenshot",
            coverageTolerance = 0.35,
            distanceThreshold = 220.0,
        )
        assertComposedSvgCaptureClose(
            expected = reference,
            embeddedFramebuffers = actual.embeddedFramebuffers,
            captureX = RealClientCaptureX,
            captureY = RealClientCaptureY,
            captureWidth = RealClientCaptureWidth,
            captureHeight = RealClientCaptureHeight,
            backgroundPixel = 0xff00_0000.toInt(),
            label = "Kotlin xterm composed SVG framebuffer",
            coverageTolerance = 0.35,
            distanceThreshold = 220.0,
        )
    }

    @Test
    fun `window manager smoke exposes independent windows and overlap over http`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        val port = 6209
        assumeTrue(isPortAvailable(port), "Port $port is not available")

        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 800, height = 600)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }

            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "300")
                .use { container ->
                    container.start()

                    val display = port - 6000
                    val result = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        export DISPLAY=host.docker.internal:$display
                        twm >/tmp/twm.log 2>&1 &
                        sleep 1
                        timeout 6s xlogo -geometry 180x120+40+40 >/tmp/xlogo.log 2>&1 &
                        timeout 6s xclock -geometry 180x120+110+90 >/tmp/xclock.log 2>&1 &
                        sleep 2
                        curl -fsS http://host.docker.internal:$port/text.txt > /tmp/screen.txt
                        curl -fsS http://host.docker.internal:$port/screen.svg > /tmp/screen.svg
                        cat /tmp/screen.txt
                        printf '\n--- SVG ---\n'
                        cat /tmp/screen.svg
                        """.trimIndent(),
                    )
                    assertEquals(0, result.exitCode, result.stderr + result.stdout)
                    assertTrue(result.stdout.contains("Focus:"), result.stdout)
                    assertTrue(result.stdout.contains("Overlap and focus:"), result.stdout)
                    assertTrue(result.stdout.contains("overlaps"), result.stdout)
                    assertTrue(result.stdout.contains("data-window-id="), result.stdout)
                }

            server.close()
            serverThread.join(1_000)
        }
    }

    private fun assertClientSucceeds(
        container: GenericContainer<*>,
        port: Int,
        command: String,
    ) {
        val display = port - 6000
        val result = container.execInContainer(
            "sh",
            "-lc",
            "DISPLAY=host.docker.internal:$display $command",
        )
        assertEquals(0, result.exitCode, result.stderr + result.stdout)
    }

    private fun assertClientKeepsRunning(
        container: GenericContainer<*>,
        port: Int,
        command: String,
    ) {
        val display = port - 6000
        val result = container.execInContainer(
            "sh",
            "-lc",
            "DISPLAY=host.docker.internal:$display timeout 2s $command",
        )
        assertEquals(124, result.exitCode, result.stderr + result.stdout)
    }

    private fun runXlogoAgainstXvfb(): VisualCapture {
        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "120")
            .use { container ->
                container.start()
                compileRobotCapture(container)
                val result = container.execInContainer(
                    "sh",
                    "-lc",
                    """
                    set -eu
                    Xvfb :99 -screen 0 640x480x24 >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    trap 'kill "${'$'}logo" 2>/dev/null || true; kill "${'$'}xvfb" 2>/dev/null || true' EXIT
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 xlogo -geometry ${RealClientCaptureWidth}x${RealClientCaptureHeight}+${RealClientCaptureX}+${RealClientCaptureY} >/tmp/xlogo.log 2>&1 &
                    logo=${'$'}!
                    DISPLAY=:99 java -cp /tmp XRobotCapture
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                return visualCapture(result.stdout)
            }
    }

    private fun runXlogoAgainstKotlinServer(port: Int): XlogoResult {
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "120")
                .use { container ->
                    container.start()
                    compileRobotCapture(container)
                    val display = port - 6000
                    val startResult = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        DISPLAY=host.docker.internal:$display \
                        xlogo -geometry ${RealClientCaptureWidth}x${RealClientCaptureHeight}+${RealClientCaptureX}+${RealClientCaptureY} >/tmp/xlogo.log 2>&1 &
                        echo ${'$'}! >/tmp/xlogo.pid
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)

                    waitUntil(
                        failureMessage = {
                            val log = container.execInContainer("sh", "-lc", "cat /tmp/xlogo.log 2>/dev/null || true").stdout
                            val text = runCatching { httpGet(port, "/text.txt") }.getOrElse { it.toString() }
                            "xlogo did not become SVG-ready before timeout\nlog:\n$log\ntext:\n$text"
                        },
                    ) {
                        val text = httpGet(port, "/text.txt")
                        text.contains("label=\"xlogo\"") &&
                            httpGet(port, "/screen.svg").hasSvgClass("framebuffer-image")
                    }

                    val capture = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        pid=${'$'}(cat /tmp/xlogo.pid)
                        kill -0 "${'$'}pid"
                        DISPLAY=host.docker.internal:$display java -cp /tmp XRobotCapture
                        """.trimIndent(),
                    )
                    val text = httpGet(port, "/text.txt")
                    val svg = httpGet(port, "/screen.svg")
                    container.execInContainer("sh", "-lc", "kill $(cat /tmp/xlogo.pid) 2>/dev/null || true")
                    server.close()
                    serverThread.join(1_000)
                    assertEquals(0, capture.exitCode, capture.stderr + capture.stdout)
                    return XlogoResult(
                        robot = visualCapture(capture.stdout),
                        text = text,
                        svg = svg,
                        embeddedFramebuffers = svgCompositionLayers(svg),
                    )
                }
        }
    }

    private fun runXclockAgainstXvfb(): VisualCapture {
        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "120")
            .use { container ->
                container.start()
                compileRobotCapture(container)
                val result = container.execInContainer(
                    "sh",
                    "-lc",
                    """
                    set -eu
                    Xvfb :99 -screen 0 640x480x24 >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    trap 'kill "${'$'}clock" 2>/dev/null || true; kill "${'$'}xvfb" 2>/dev/null || true' EXIT
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 ${xclockCommand()} >/tmp/xclock.log 2>&1 &
                    clock=${'$'}!
                    DISPLAY=:99 java -cp /tmp XRobotCapture
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                return visualCapture(result.stdout)
            }
    }

    private fun runXclockAgainstKotlinServer(port: Int): RealClientResult {
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "120")
                .use { container ->
                    container.start()
                    compileRobotCapture(container)
                    val display = port - 6000
                    val startResult = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        DISPLAY=host.docker.internal:$display \
                        ${xclockCommand()} >/tmp/xclock.log 2>&1 &
                        echo ${'$'}! >/tmp/xclock.pid
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)

                    waitUntil(
                        failureMessage = {
                            val log = container.execInContainer("sh", "-lc", "cat /tmp/xclock.log 2>/dev/null || true").stdout
                            val text = runCatching { httpGet(port, "/text.txt") }.getOrElse { it.toString() }
                            "xclock did not become SVG-ready before timeout\nlog:\n$log\ntext:\n$text"
                        },
                    ) {
                        val text = httpGet(port, "/text.txt")
                        text.contains("label=\"xclock\"") &&
                            httpGet(port, "/screen.svg").hasSvgClass("framebuffer-image")
                    }

                    val capture = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        pid=${'$'}(cat /tmp/xclock.pid)
                        kill -0 "${'$'}pid"
                        DISPLAY=host.docker.internal:$display java -cp /tmp XRobotCapture
                        """.trimIndent(),
                    )
                    val text = httpGet(port, "/text.txt")
                    val svg = httpGet(port, "/screen.svg")
                    container.execInContainer("sh", "-lc", "kill $(cat /tmp/xclock.pid) 2>/dev/null || true")
                    server.close()
                    serverThread.join(1_000)
                    assertEquals(0, capture.exitCode, capture.stderr + capture.stdout)
                    return RealClientResult(
                        robot = visualCapture(capture.stdout),
                        text = text,
                        svg = svg,
                        embeddedFramebuffers = svgCompositionLayers(svg),
                    )
                }
        }
    }

    private fun runXcalcAgainstXvfb(): VisualCapture {
        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "120")
            .use { container ->
                container.start()
                compileRobotCapture(container)
                val result = container.execInContainer(
                    "sh",
                    "-lc",
                    """
                    set -eu
                    Xvfb :99 -screen 0 640x480x24 >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    trap 'kill "${'$'}calc" 2>/dev/null || true; kill "${'$'}xvfb" 2>/dev/null || true' EXIT
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 ${xcalcCommand()} >/tmp/xcalc.log 2>&1 &
                    calc=${'$'}!
                    DISPLAY=:99 java -cp /tmp XRobotCapture
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                return visualCapture(result.stdout)
            }
    }

    private fun runXcalcAgainstKotlinServer(port: Int): RealClientResult {
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "120")
                .use { container ->
                    container.start()
                    compileRobotCapture(container)
                    val display = port - 6000
                    val startResult = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        DISPLAY=host.docker.internal:$display \
                        ${xcalcCommand()} >/tmp/xcalc.log 2>&1 &
                        echo ${'$'}! >/tmp/xcalc.pid
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)

                    waitUntil(
                        failureMessage = {
                            val log = container.execInContainer("sh", "-lc", "cat /tmp/xcalc.log 2>/dev/null || true").stdout
                            val text = runCatching { httpGet(port, "/text.txt") }.getOrElse { it.toString() }
                            "xcalc did not become SVG-ready before timeout\nlog:\n$log\ntext:\n$text"
                        },
                    ) {
                        val text = httpGet(port, "/text.txt")
                        text.contains("label=\"Calculator\"") &&
                            text.contains("- PolyText8:")
                    }

                    val capture = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        pid=${'$'}(cat /tmp/xcalc.pid)
                        kill -0 "${'$'}pid"
                        DISPLAY=host.docker.internal:$display java -cp /tmp XRobotCapture
                        """.trimIndent(),
                    )
                    val text = httpGet(port, "/text.txt")
                    val svg = httpGet(port, "/screen.svg")
                    container.execInContainer("sh", "-lc", "kill $(cat /tmp/xcalc.pid) 2>/dev/null || true")
                    server.close()
                    serverThread.join(1_000)
                    assertEquals(0, capture.exitCode, capture.stderr + capture.stdout)
                    return RealClientResult(
                        robot = visualCapture(capture.stdout),
                        text = text,
                        svg = svg,
                        embeddedFramebuffers = svgCompositionLayers(svg),
                    )
                }
        }
    }

    private fun runXeyesAgainstXvfb(): VisualCapture {
        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "120")
            .use { container ->
                container.start()
                compileRobotCapture(container, movePointer = true)
                val result = container.execInContainer(
                    "sh",
                    "-lc",
                    """
                    set -eu
                    Xvfb :99 -screen 0 640x480x24 >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    trap 'kill "${'$'}eyes" 2>/dev/null || true; kill "${'$'}xvfb" 2>/dev/null || true' EXIT
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 ${xeyesCommand()} >/tmp/xeyes.log 2>&1 &
                    eyes=${'$'}!
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xwininfo -name xeyes >/tmp/xeyes-ready.log 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 xwininfo -name xeyes >/tmp/xeyes-ready.log 2>&1
                    DISPLAY=:99 java -cp /tmp XRobotCapture
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                return visualCapture(result.stdout)
            }
    }

    private fun runXeyesAgainstKotlinServer(port: Int): RealClientResult {
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        XServer(
            ServerOptions(
                host = "0.0.0.0",
                port = port,
                width = 640,
                height = 480,
                rootBackgroundPixel = 0x0000_0000,
            ),
        ).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "120")
                .use { container ->
                    container.start()
                    compileRobotCapture(container, movePointer = true)
                    val display = port - 6000
                    val startResult = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        DISPLAY=host.docker.internal:$display \
                        ${xeyesCommand()} >/tmp/xeyes.log 2>&1 &
                        echo ${'$'}! >/tmp/xeyes.pid
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)

                    waitUntil(
                        failureMessage = {
                            val log = container.execInContainer("sh", "-lc", "cat /tmp/xeyes.log 2>/dev/null || true").stdout
                            val text = runCatching { httpGet(port, "/text.txt") }.getOrElse { it.toString() }
                            "xeyes did not become SVG-ready before timeout\nlog:\n$log\ntext:\n$text"
                        },
                    ) {
                        val text = httpGet(port, "/text.txt")
                        text.contains("label=\"xeyes\"") &&
                            httpGet(port, "/screen.svg").hasSvgClass("framebuffer-image")
                    }

                    val capture = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        pid=${'$'}(cat /tmp/xeyes.pid)
                        kill -0 "${'$'}pid"
                        DISPLAY=host.docker.internal:$display java -cp /tmp XRobotCapture
                        """.trimIndent(),
                    )
                    val text = httpGet(port, "/text.txt")
                    val svg = httpGet(port, "/screen.svg")
                    File("build/tmp/xvfb-container-test").also { it.mkdirs() }.let { directory ->
                        File(directory, "xeyes-actual.txt").writeText(text)
                        File(directory, "xeyes-actual.svg").writeText(svg)
                    }
                    container.execInContainer("sh", "-lc", "kill $(cat /tmp/xeyes.pid) 2>/dev/null || true")
                    server.close()
                    serverThread.join(1_000)
                    assertEquals(0, capture.exitCode, capture.stderr + capture.stdout)
                    return RealClientResult(
                        robot = visualCapture(capture.stdout),
                        text = text,
                        svg = svg,
                        embeddedFramebuffers = svgCompositionLayers(svg),
                    )
                }
        }
    }

    private fun runXtermAgainstXvfb(): VisualCapture {
        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "120")
            .use { container ->
                container.start()
                compileRobotCapture(container)
                val result = container.execInContainer(
                    "sh",
                    "-lc",
                    """
                    set -eu
                    Xvfb :99 -screen 0 640x480x24 >/tmp/xvfb.log 2>&1 &
                    xvfb=${'$'}!
                    trap 'kill "${'$'}term" 2>/dev/null || true; kill "${'$'}xvfb" 2>/dev/null || true' EXIT
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 ${xtermCommand()} >/tmp/xterm.log 2>&1 &
                    term=${'$'}!
                    for _ in ${'$'}(seq 1 40); do
                      DISPLAY=:99 xwininfo -name xterm-parity >/tmp/xterm-ready.log 2>&1 && break
                      sleep 0.25
                    done
                    DISPLAY=:99 xwininfo -name xterm-parity >/tmp/xterm-ready.log 2>&1
                    DISPLAY=:99 java -cp /tmp XRobotCapture
                    """.trimIndent(),
                )
                assertEquals(0, result.exitCode, result.stderr + result.stdout)
                return visualCapture(result.stdout)
            }
    }

    private fun runXtermAgainstKotlinServer(port: Int): RealClientResult {
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        XServer(
            ServerOptions(
                host = "0.0.0.0",
                port = port,
                width = 640,
                height = 480,
                rootBackgroundPixel = 0x0000_0000,
            ),
        ).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "120")
                .use { container ->
                    container.start()
                    compileRobotCapture(container)
                    val display = port - 6000
                    val startResult = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        DISPLAY=host.docker.internal:$display \
                        ${xtermCommand()} >/tmp/xterm.log 2>&1 &
                        echo ${'$'}! >/tmp/xterm.pid
                        """.trimIndent(),
                    )
                    assertEquals(0, startResult.exitCode, startResult.stderr + startResult.stdout)

                    waitUntil(
                        failureMessage = {
                            val log = container.execInContainer("sh", "-lc", "cat /tmp/xterm.log 2>/dev/null || true").stdout
                            val text = runCatching { httpGet(port, "/text.txt") }.getOrElse { it.toString() }
                            "xterm did not become SVG-ready before timeout\nlog:\n$log\ntext:\n$text"
                        },
                    ) {
                        val text = httpGet(port, "/text.txt")
                        text.contains("label=\"xterm-parity\"") &&
                            httpGet(port, "/screen.svg").hasSvgClass("framebuffer-image")
                    }

                    val capture = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        pid=${'$'}(cat /tmp/xterm.pid)
                        kill -0 "${'$'}pid"
                        DISPLAY=host.docker.internal:$display java -cp /tmp XRobotCapture
                        """.trimIndent(),
                    )
                    val text = httpGet(port, "/text.txt")
                    val svg = httpGet(port, "/screen.svg")
                    File("build/tmp/xvfb-container-test").also { it.mkdirs() }.let { directory ->
                        File(directory, "xterm-actual.txt").writeText(text)
                        File(directory, "xterm-actual.svg").writeText(svg)
                    }
                    container.execInContainer("sh", "-lc", "kill $(cat /tmp/xterm.pid) 2>/dev/null || true")
                    server.close()
                    serverThread.join(1_000)
                    assertEquals(0, capture.exitCode, capture.stderr + capture.stdout)
                    return RealClientResult(
                        robot = visualCapture(capture.stdout),
                        text = text,
                        svg = svg,
                        embeddedFramebuffers = svgCompositionLayers(svg),
                    )
                }
        }
    }

    private fun compileRobotCapture(container: GenericContainer<*>, movePointer: Boolean = false) {
        val result = container.execInContainer(
            "sh",
            "-lc",
            "cat > /tmp/XRobotCapture.java <<'JAVA'\n${robotCaptureSource(movePointer)}\nJAVA\njavac /tmp/XRobotCapture.java",
        )
        assertEquals(0, result.exitCode, result.stderr + result.stdout)
    }

    private fun assertVisualCaptureClose(
        expected: VisualCapture,
        actual: VisualCapture,
        label: String,
        coverageTolerance: Double = 0.08,
        averageTolerance: Double = 0.04,
        distanceThreshold: Double = 45.0,
    ) {
        dumpVisualCapturePair(label, expected, actual)
        assertEquals(expected.width, actual.width, "$label width should match Xvfb reference")
        assertEquals(expected.height, actual.height, "$label height should match Xvfb reference")
        assertClose(
            expected = expected.nonBackgroundPixels,
            actual = actual.nonBackgroundPixels,
            tolerance = coverageTolerance,
            message = "$label should expose similar non-background coverage to Xvfb; reference=$expected actual=$actual",
        )
        assertClose(
            expected = expected.averageRgb,
            actual = actual.averageRgb,
            tolerance = averageTolerance,
            message = "$label should expose similar average RGB to Xvfb; reference=$expected actual=$actual",
        )
        val distance = imageDistance(expected.image, actual.image)
        assertTrue(
            distance <= distanceThreshold,
            "$label should stay visually close to Xvfb reference; distance=$distance\nreference=$expected\nactual=$actual",
        )
    }

    private fun dumpVisualCapturePair(label: String, expected: VisualCapture, actual: VisualCapture) {
        val safeLabel = label.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
        val directory = File("build/tmp/xvfb-container-test").also { it.mkdirs() }
        ImageIO.write(expected.image, "png", File(directory, "$safeLabel-reference.png"))
        ImageIO.write(actual.image, "png", File(directory, "$safeLabel-actual.png"))
    }

    private fun assertComposedSvgClose(
        expected: VisualCapture,
        embeddedFramebuffers: List<EmbeddedPng>,
        ownerWidth: Int,
        ownerHeight: Int,
        label: String,
        coverageTolerance: Double = 0.08,
        averageTolerance: Double = 0.04,
        distanceThreshold: Double = 45.0,
    ) {
        val owner = embeddedFramebuffers.lastOrNull { it.bytes != null && it.width == ownerWidth && it.height == ownerHeight }
            ?: error("$label did not include an owner framebuffer ${ownerWidth}x$ownerHeight; exported=$embeddedFramebuffers")
        val composed = composeEmbeddedFramebuffers(embeddedFramebuffers)
        val cropped = composed.getSubimage(owner.x, owner.y, expected.width, expected.height)
        assertVisualCaptureClose(
            expected = expected,
            actual = visualCapture(cropped),
            label = label,
            coverageTolerance = coverageTolerance,
            averageTolerance = averageTolerance,
            distanceThreshold = distanceThreshold,
        )
    }

    private fun assertComposedSvgCaptureClose(
        expected: VisualCapture,
        embeddedFramebuffers: List<EmbeddedPng>,
        captureX: Int,
        captureY: Int,
        captureWidth: Int,
        captureHeight: Int,
        backgroundPixel: Int,
        label: String,
        coverageTolerance: Double = 0.08,
        averageTolerance: Double = 0.04,
        distanceThreshold: Double = 45.0,
    ) {
        require(expected.width == captureWidth && expected.height == captureHeight) {
            "$label expected image ${expected.width}x${expected.height} does not match capture ${captureWidth}x$captureHeight"
        }
        val composed = composeEmbeddedFramebuffers(
            embeddedFramebuffers = embeddedFramebuffers,
            canvasWidth = captureX + captureWidth,
            canvasHeight = captureY + captureHeight,
            backgroundPixel = backgroundPixel,
        )
        val cropped = composed.getSubimage(captureX, captureY, captureWidth, captureHeight)
        assertVisualCaptureClose(
            expected = expected,
            actual = visualCapture(cropped),
            label = label,
            coverageTolerance = coverageTolerance,
            averageTolerance = averageTolerance,
            distanceThreshold = distanceThreshold,
        )
    }

    private fun svgCompositionLayers(svg: String): List<EmbeddedPng> {
        val clipRectangles = svgClipRectangles(svg)
        return Regex("""<(?:image|rect)\b[^>]*>""")
            .findAll(svg)
            .mapNotNull { match ->
                val tag = match.value
                val id = Regex("""\bdata-window-id="([^"]+)"""").find(tag)?.groupValues?.get(1)
                val x = svgImageAttribute(tag, "x") ?: return@mapNotNull null
                val y = svgImageAttribute(tag, "y") ?: return@mapNotNull null
                val width = svgImageAttribute(tag, "width") ?: return@mapNotNull null
                val height = svgImageAttribute(tag, "height") ?: return@mapNotNull null
                val encoded = Regex("""\bhref="data:image/png;base64,([A-Za-z0-9+/=]+)"""").find(tag)?.groupValues?.get(1)
                if (encoded == null) {
                    if (!tag.hasSvgClass("window-border")) return@mapNotNull null
                    val fill = Regex("""\bfill="#([0-9a-fA-F]{6})"""").find(tag)?.groupValues?.get(1)?.toInt(16)
                        ?: return@mapNotNull null
                    return@mapNotNull EmbeddedPng(
                        id = id ?: "window-border",
                        bytes = null,
                        fill = 0xff00_0000.toInt() or fill,
                        x = x,
                        y = y,
                        width = width,
                        height = height,
                        clipRectangles = emptyList(),
                    )
                }
                EmbeddedPng(
                    id = id ?: "framebuffer",
                    bytes = Base64.getDecoder().decode(encoded),
                    fill = null,
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    clipRectangles = id?.let { clipRectangles["clip-screen-${it.removePrefix("0x")}"] }.orEmpty(),
                )
            }
            .toList()
    }

    private fun svgClipRectangles(svg: String): Map<String, List<Rectangle>> =
        Regex("""<clipPath\b[^>]*\bid="([^"]+)"[^>]*>(.*?)</clipPath>""", setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(svg)
            .associate { match ->
                val rectangles = Regex("""<rect\b[^>]*>""")
                    .findAll(match.groupValues[2])
                    .mapNotNull { rect ->
                        val tag = rect.value
                        val x = svgImageAttribute(tag, "x") ?: return@mapNotNull null
                        val y = svgImageAttribute(tag, "y") ?: return@mapNotNull null
                        val width = svgImageAttribute(tag, "width") ?: return@mapNotNull null
                        val height = svgImageAttribute(tag, "height") ?: return@mapNotNull null
                        Rectangle(x, y, width, height)
                    }
                    .toList()
                match.groupValues[1] to rectangles
            }

    private fun svgImageAttribute(tag: String, name: String): Int? =
        Regex("""\b$name="(-?\d+)"""").find(tag)?.groupValues?.get(1)?.toInt()

    private fun composeEmbeddedFramebuffers(
        embeddedFramebuffers: List<EmbeddedPng>,
        canvasWidth: Int = 0,
        canvasHeight: Int = 0,
        backgroundPixel: Int? = null,
    ): BufferedImage {
        val width = maxOf(embeddedFramebuffers.maxOfOrNull { it.x + it.width } ?: 0, canvasWidth)
        val height = maxOf(embeddedFramebuffers.maxOfOrNull { it.y + it.height } ?: 0, canvasHeight)
        require(width > 0 && height > 0) { "No framebuffer geometry to compose: $embeddedFramebuffers" }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            if (backgroundPixel != null) {
                graphics.color = java.awt.Color(backgroundPixel, true)
                graphics.fillRect(0, 0, width, height)
            }
            for (embedded in embeddedFramebuffers) {
                if (embedded.bytes != null) {
                    val layer = ImageIO.read(ByteArrayInputStream(embedded.bytes)) ?: continue
                    if (embedded.clipRectangles.isEmpty()) {
                        graphics.drawImage(layer, embedded.x, embedded.y, embedded.width, embedded.height, null)
                    } else {
                        val originalClip = graphics.clip
                        for (clip in embedded.clipRectangles) {
                            graphics.clip = clip
                            graphics.drawImage(layer, embedded.x, embedded.y, embedded.width, embedded.height, null)
                        }
                        graphics.clip = originalClip
                    }
                } else if (embedded.fill != null) {
                    graphics.color = java.awt.Color(embedded.fill, true)
                    graphics.fillRect(embedded.x, embedded.y, embedded.width, embedded.height)
                }
            }
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun visualCapture(stdout: String): VisualCapture {
        val encoded = stdout.lineSequence()
            .firstOrNull { it.startsWith("PNG_BASE64=") }
            ?.removePrefix("PNG_BASE64=")
            ?: error("XRobotCapture did not print PNG_BASE64, stdout:\n$stdout")
        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
            ?: error("XRobotCapture PNG was not readable")
        return visualCapture(image)
    }

    private fun visualCapture(image: BufferedImage): VisualCapture {
        var nonBackground = 0
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                redSum += (argb ushr 16) and 0xff
                greenSum += (argb ushr 8) and 0xff
                blueSum += argb and 0xff
                if (rgbDistance(argb, RealClientBackground) > 8) nonBackground++
            }
        }
        val pixels = image.width * image.height
        return VisualCapture(
            width = image.width,
            height = image.height,
            nonBackgroundPixels = nonBackground,
            averageRgb = (redSum + greenSum + blueSum).toDouble() / (pixels * 3.0 * 255.0),
            image = image,
        )
    }

    private fun assertClose(expected: Int, actual: Int, tolerance: Double, message: String) {
        val allowed = (expected * tolerance).toInt().coerceAtLeast(1)
        assertTrue(abs(expected - actual) <= allowed, message)
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double, message: String) {
        assertTrue(abs(expected - actual) <= tolerance, message)
    }

    private fun imageDistance(reference: BufferedImage, actual: BufferedImage): Double {
        var total = 0L
        var samples = 0
        for (y in 0 until reference.height step 3) {
            for (x in 0 until reference.width step 3) {
                total += rgbDistance(reference.getRGB(x, y), actual.getRGB(x, y))
                samples++
            }
        }
        return total.toDouble() / samples.toDouble()
    }

    private fun rgbDistance(left: Int, right: Int): Int =
        abs(((left ushr 16) and 0xff) - ((right ushr 16) and 0xff)) +
            abs(((left ushr 8) and 0xff) - ((right ushr 8) and 0xff)) +
            abs((left and 0xff) - (right and 0xff))

    private fun String.hasSvgClass(className: String): Boolean =
        Regex("""\bclass="([^"]*)"""")
            .findAll(this)
            .any { match -> match.groupValues[1].split(' ').any { it == className } }

    private fun httpGet(port: Int, path: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().decodeToString().substringAfter("\r\n\r\n")
        }

    private fun waitUntil(
        failureMessage: () -> String = { "Condition did not become true before timeout" },
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 20_000
        var failure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition()) return
            } catch (t: Throwable) {
                failure = t
            }
            Thread.sleep(100)
        }
        failure?.let { throw it }
        throw AssertionError(failureMessage())
    }

    private fun isPortAvailable(port: Int): Boolean =
        runCatching { ServerSocket(port).use { true } }.getOrDefault(false)

    private fun assumeDockerAndImage(image: String) {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
        val imageExists = runCatching {
            DockerClientFactory.instance().client().inspectImageCmd(image).exec()
        }.isSuccess
        assumeTrue(imageExists, "Build $image first with ./gradlew dockerBuildX11Images")
    }

    private companion object {
        const val CLIENT_IMAGE = "jonnyzzz-x/x11-client:latest"
        const val REFERENCE_IMAGE = "jonnyzzz-x/x11-reference:latest"
        const val RealClientCaptureX = 20
        const val RealClientCaptureY = 20
        const val RealClientCaptureWidth = 220
        const val RealClientCaptureHeight = 160
        const val RealClientBackground = 0xffff_ffff.toInt()

        fun xclockCommand(): String =
            "xclock -analog -norender -update 60 -geometry ${RealClientCaptureWidth}x${RealClientCaptureHeight}+${RealClientCaptureX}+${RealClientCaptureY}"

        fun xcalcCommand(): String =
            "xcalc -geometry ${RealClientCaptureWidth}x${RealClientCaptureHeight}+${RealClientCaptureX}+${RealClientCaptureY}"

        fun xeyesCommand(): String =
            "xeyes -geometry ${RealClientCaptureWidth}x${RealClientCaptureHeight}+${RealClientCaptureX}+${RealClientCaptureY}"

        fun xtermCommand(): String =
            "xterm -T xterm-parity -n xterm-parity -geometry 28x8+${RealClientCaptureX}+${RealClientCaptureY} +sb +bc -fn fixed -bg white -fg black -cr black -e sh -lc 'printf \"Kotlin X11\\\\nxterm parity\\\\n0123456789\\\\n\"; sleep 60'"

        fun robotCaptureSource(movePointer: Boolean): String {
            val pointerMove = if (movePointer) {
                "robot.mouseMove(${RealClientCaptureX + RealClientCaptureWidth / 2}, ${RealClientCaptureY + RealClientCaptureHeight / 2});"
            } else {
                ""
            }
            return """
            import java.awt.Rectangle;
            import java.awt.Robot;
            import java.awt.image.BufferedImage;
            import java.io.ByteArrayOutputStream;
            import java.util.Base64;
            import javax.imageio.ImageIO;

            public class XRobotCapture {
              public static void main(String[] args) throws Exception {
                Robot robot = new Robot();
                $pointerMove
                Thread.sleep(1200);
                BufferedImage image = robot.createScreenCapture(
                    new Rectangle($RealClientCaptureX, $RealClientCaptureY, $RealClientCaptureWidth, $RealClientCaptureHeight));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                System.out.println("PNG_BASE64=" + Base64.getEncoder().encodeToString(output.toByteArray()));
              }
            }
            """.trimIndent()
        }
    }

    private data class EmbeddedPng(
        val id: String,
        val bytes: ByteArray?,
        val fill: Int?,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val clipRectangles: List<Rectangle>,
    )

    private data class VisualCapture(
        val width: Int,
        val height: Int,
        val nonBackgroundPixels: Int,
        val averageRgb: Double,
        val image: BufferedImage,
    ) {
        override fun toString(): String =
            "VisualCapture(width=$width, height=$height, nonBackgroundPixels=$nonBackgroundPixels, averageRgb=$averageRgb)"
    }

    private data class XlogoResult(
        val robot: VisualCapture,
        val text: String,
        val svg: String,
        val embeddedFramebuffers: List<EmbeddedPng>,
    )

    private data class RealClientResult(
        val robot: VisualCapture,
        val text: String,
        val svg: String,
        val embeddedFramebuffers: List<EmbeddedPng>,
    )
}
